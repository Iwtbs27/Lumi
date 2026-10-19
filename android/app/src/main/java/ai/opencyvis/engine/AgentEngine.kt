package ai.opencyvis.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.opencyvis.action.Action
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.memory.MemoryManager
import ai.opencyvis.memory.MemoryPacket
import ai.opencyvis.memory.MemorySession
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LLMException
import ai.opencyvis.llm.ModelBudgetExceededException
import ai.opencyvis.llm.ResponseParser
import ai.opencyvis.execution.TaskExecutionRuntime
import ai.opencyvis.execution.TaskGuiAccess
import ai.opencyvis.execution.gui.AccessibilityGuiAccess
import ai.opencyvis.execution.gui.GuiAccess
import ai.opencyvis.execution.gui.GuiActionAttempt
import ai.opencyvis.execution.gui.GuiBlockAction
import ai.opencyvis.execution.gui.GuiBlockExecutor
import ai.opencyvis.execution.gui.GuiBlockRequest
import ai.opencyvis.execution.gui.GuiBlockResult
import ai.opencyvis.execution.gui.GuiOperationEvidence
import ai.opencyvis.execution.gui.GuiPageIdentity
import ai.opencyvis.execution.gui.GuiQueryResult
import ai.opencyvis.execution.gui.GuiSelector
import ai.opencyvis.team.DelegationManager
import ai.opencyvis.team.DelegationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Core agent engine: observe-think-act loop.
 * Ported from cli_demo.py run_instruction() and run_auto().
 */
class AgentEngine(
    private val llmClient: LLMClientInterface,
    private val actionExecutor: ActionExecutor,
    private val maxSteps: Int = 100,
    private val virtualDisplayManager: VirtualDisplayManager? = null,
    private val debugMode: Boolean = false,
    private val memoryManager: MemoryManager? = null,
    private val viewTreeProvider: ((Int, Int, Int) -> String?)? = null,
    private val shouldForwardScreenshot: (() -> Boolean)? = null,
    private val blacklistedPackages: Set<String> = emptySet(),
    private val runStoreFactory: ((String) -> TaskRunStore)? = null,
    private val onRunFinished: (suspend (String, String, Map<String, Any?>) -> Unit)? = null,
    private val onSaveRoutine: (suspend (
        name: String, icon: String, instruction: String,
        scheduleType: String?, scheduleTime: String?, scheduleRepeat: String?,
        scheduleInterval: Int?, scheduleLocation: String?, scheduleOnEnter: Boolean?
    ) -> Unit)? = null,
    private val controlEpoch: AtomicLong = AtomicLong(),
    private val onRunStarted: (suspend (String) -> Unit)? = null,
    private val teamRuntime: TaskTeamRuntime? = null,
    private val executionContext: Context? = null,
) {

    companion object {
        private const val TAG = "AgentEngine"
        val SIDE_EFFECT_ACTIONS = setOf("tap", "swipe", "type_text", "key_event", "open_app", "long_press")
        private const val VD_CAPTURE_MAX_ATTEMPTS = 8
        private const val VD_CAPTURE_RETRY_DELAY_MS = 300L
        private const val VD_IMAGE_READER_TIMEOUT_MS = 600L
        private const val VD_CAPTURE_RECOVERY_THRESHOLD = 3  // Launch home on VD after this many failures

        /**
         * System prompt. Ported from cli_demo.py SYSTEM_PROMPT (lines 286-302).
         */
        val SYSTEM_PROMPT: String get() = LlmPrompts.systemPrompt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var agentJob: Job? = null
    private val lifecycleLock = Any()
    private val actionRepeatGuard = ActionRepeatGuard()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _childAgents = MutableStateFlow<List<DelegationRecord>>(emptyList())
    val childAgents: StateFlow<List<DelegationRecord>> = _childAgents.asStateFlow()
    @Volatile private var activeTeam: DelegationManager? = null

    fun cancelChild(childId: String) {
        val team = activeTeam ?: return
        scope.launch { team.cancel(childId, "Cancelled by the user") }
    }

    private val _stepResults = MutableSharedFlow<StepResult>(replay = 0, extraBufferCapacity = 20)
    val stepResults: SharedFlow<StepResult> = _stepResults.asSharedFlow()

    private val _stepScreenshots = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 5)
    val stepScreenshots: SharedFlow<ByteArray> = _stepScreenshots.asSharedFlow()

    @Volatile
    private var paused = false
    @Volatile private var pausedWaitingState: AgentState? = null
    private val actionMutex = Mutex()
    @Volatile private var handoverContext: Map<String, Any?> = emptyMap()
    @Volatile private var activeTask: TaskState? = null
    @Volatile private var activeStore: TaskRunStore? = null
    @Volatile private var activeExecution: TaskExecutionRuntime? = null
    private var humanControlGranted = false
    val isTaskActive: Boolean get() = agentJob?.isCompleted == false
    @Volatile var lastOutcome: String? = null
        private set

    private var consecutiveBlackFrames = 0
    private var lastCaptureSource = "unknown"
    private var lastNonBlackStep = 0

    @Volatile
    private var userResponseDeferred: CompletableDeferred<String?>? = null

    @Volatile
    private var userHandoffDeferred: CompletableDeferred<String?>? = null

    private val pendingUserSupplements = mutableListOf<String>()
    private var pendingUserAnswer: String? = null

    /**
     * Test-only: jump the engine straight into WaitingForUser state without running the LLM loop.
     * Used by ADB test broadcasts to verify ask_user UI / overlay / heads-up plumbing.
     */
    fun debugSimulateAskUser(question: String) {
        Log.i(TAG, "debugSimulateAskUser: $question")
        _state.value = AgentState.WaitingForUser(question, 1)
    }

    fun debugSimulateHandoff(reason: String) {
        Log.i(TAG, "debugSimulateHandoff: $reason")
        _state.value = AgentState.WaitingForHandoff(reason, 1)
    }

    fun debugSimulateRunning(step: Int = 1, thought: String = "debug running") {
        Log.i(TAG, "debugSimulateRunning: step=$step thought=$thought")
        paused = false
        _state.value = AgentState.Running(step, thought)
    }

    /**
     * Start the agent with the given instruction.
     */
    fun start(instruction: String, resume: TaskCheckpoint? = null): Unit = synchronized(lifecycleLock) {
        require(resume == null || resume.instruction == instruction) { "Resume instruction must match the original task" }
        if (isTaskActive) {
            Log.w(TAG, "Agent already running")
            return@synchronized
        }
        paused = false
        pausedWaitingState = null
        lastOutcome = null
        agentJob = scope.launch(start = CoroutineStart.LAZY) {
            runAgentLoop(instruction, resume)
        }
        agentJob!!.start()
        Unit
    }

    /**
     * Pause the agent loop.
     */
    fun pause() {
        val current = _state.value
        if (current is AgentState.WaitingForUser || current is AgentState.WaitingForHandoff) {
            pausedWaitingState = current
        }
        paused = true
        controlEpoch.incrementAndGet()
        activeExecution?.interruptGui()
        _state.value = AgentState.Paused
        llmClient.cancelRequests()
    }

    /** Drain an input already in progress without classifying this as a human intervention. */
    suspend fun pauseAndAwaitInput() {
        pause()
        activeExecution?.awaitGuiIdle()
        actionMutex.withLock { }
    }

    /** Wait only for input already being injected, never for a pending model request. */
    suspend fun pauseForHuman() {
        val waiting = _state.value as? AgentState.WaitingForHandoff
        pause()
        activeExecution?.awaitGuiIdle()
        actionMutex.withLock {
            synchronized(pendingUserSupplements) {
                if (!humanControlGranted) {
                    activeTask?.let { task ->
                        task.humanInterventionCount++
                        activeStore?.updateHumanIntervention(task.humanInterventionCount)
                    }
                    humanControlGranted = true
                    handoverContext = handoverContext + ("human_intervention_count" to activeTask?.humanInterventionCount)
                }
            }
        }
        if (waiting != null) _state.value = waiting
    }

    fun handoverSnapshot(): Map<String, Any?> = handoverContext

    fun resumeAfterHuman(summary: String) {
        submitUserSupplement(summary)
        userResponseDeferred?.complete("用户已接管操作，请根据最新页面和接管说明继续。")
        completeUserHandoff("human_correction_completed")
        resume()
    }

    private fun showRunning(step: Int, detail: String) {
        if (!paused) _state.value = AgentState.Running(step, detail)
    }

    /**
     * Resume the agent loop.
     */
    fun resume() {
        val waiting = pausedWaitingState
        pausedWaitingState = null
        // Human takeover may have already completed this wait before calling resume.
        when (waiting) {
            is AgentState.WaitingForUser -> if (userResponseDeferred?.isCompleted == false) _state.value = waiting
            is AgentState.WaitingForHandoff -> if (userHandoffDeferred?.isCompleted == false) _state.value = waiting
            else -> Unit
        }
        humanControlGranted = false
        paused = false
        // State will be updated in the loop
    }

    /**
     * Submit user response when engine is in WaitingForUser state.
     */
    fun submitUserResponse(response: String) {
        synchronized(pendingUserSupplements) {
            pendingUserAnswer = response
            activeStore?.updatePendingInputs(pendingUserSupplements, pendingUserAnswer)
        }
        userResponseDeferred?.complete(response)
    }

    fun completeUserHandoff(source: String = "user_returned_control") {
        userHandoffDeferred?.complete(source)
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(pendingUserSupplements) {
            pendingUserSupplements.add(trimmed)
            activeStore?.updatePendingInputs(pendingUserSupplements, pendingUserAnswer)
        }
        Log.i(TAG, "submitUserSupplement queued (${trimmed.length} chars)")
    }

    /**
     * Stop the agent.
     */
    fun stop() {
        paused = true
        pausedWaitingState = null
        controlEpoch.incrementAndGet()
        activeExecution?.interruptGui()
        userResponseDeferred?.complete(null)
        userResponseDeferred = null
        userHandoffDeferred?.complete(null)
        userHandoffDeferred = null
        synchronized(lifecycleLock) { agentJob?.cancel() }
        llmClient.cancelRequests()
        _state.value = AgentState.Idle()
    }

    /** Includes archive/memory finalization and the suspending onRunFinished callback. */
    suspend fun awaitStopped() { synchronized(lifecycleLock) { agentJob }?.join() }

    suspend fun stopAndJoin() { stop(); awaitStopped() }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        llmClient.shutdown()
    }

    /**
     * Main observe-think-act loop. Ported from cli_demo.py run_instruction().
     */
    private suspend fun runAgentLoop(instruction: String, resume: TaskCheckpoint?) {
        consecutiveBlackFrames = 0
        lastNonBlackStep = 0

        val task = resume?.let(TaskState::restore) ?: TaskState(instruction)
        activeTask = task
        var store: TaskRunStore? = null
        var memory: MemorySession? = null
        var hybrid: TaskExecutionRuntime? = null
        var executionMemory = MemoryPacket()
        var team: TeamSession? = null
        var teamObserver: Job? = null
        var currentStep = task.runStartStep - 1
        var outcome = "interrupted"
        val humanInterventionsByStep = mutableMapOf<Int, Int>()
        suspend fun refreshTeamState() {
            team?.let { session ->
                task.teamContext = session.context() + ("budget" to requireNotNull(teamRuntime).budget.snapshot().toJson())
            }
        }
        suspend fun refreshExecutionState() {
            hybrid?.let { runtime ->
                task.executionContext = runtime.context(executionMemory.items + listOfNotNull(memory?.currentProcedure()))
            }
        }
        fun publishHandover(): Map<String, Any?> {
            val recent = task.steps.values.toList().takeLast(8)
            val memoryReferences = recent.flatMap { record ->
                (record.decision["memory_use"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
            }.mapNotNull { memoryRef ->
                val id = memoryRef["id"] as? String ?: return@mapNotNull null
                val revision = memoryRef["revision"] as? Number ?: return@mapNotNull null
                mapOf("id" to id, "revision" to revision.toInt())
            } + listOfNotNull(task.procedureExecution.takeIf { it["id"] != null }?.let {
                mapOf("id" to it["id"], "revision" to it["revision"])
            })
            val data = mapOf(
                "run_id" to store?.runId, "task" to instruction,
                "current_state" to task.snapshot(), "human_intervention_count" to task.humanInterventionCount,
                "memory_references" to memoryReferences.distinct(),
                "last_confirmed_step" to task.steps.values.lastOrNull { it.effect?.get("status") == "confirmed_success" }?.number,
                "failing_steps" to recent.filter { it.effect?.get("status") == "confirmed_failure" ||
                    it.execution["execution_status"] in setOf("execution_failed", "blocked") }.map { it.number },
                "recent_steps" to recent.map { record -> mapOf(
                    "step" to record.number,
                    "source_run" to (task.stepSources[record.number] ?: store?.runId),
                    "thought" to (record.decision["thought"] as? String)?.take(1200),
                    "action" to record.decision.filterKeys { it !in TaskState.RESPONSE_METADATA },
                    "execution" to record.execution, "effect" to record.effect,
                    "memory_use" to record.decision["memory_use"],
                    "procedure_update" to record.decision["procedure_update"]
                ) }
            )
            val frozen = JSONObject(data)
            return frozen.keys().asSequence().associateWith { frozen.get(it) }.also {
                if (activeTask === task) handoverContext = it
            }
        }
        var pendingActionFeedback: String? = resume?.let {
            "Resuming an interrupted task. This is a NEW observation: check pending_results against the current screen before repeating any uncertain operation. " +
                "Previously issued actions are not replayed. Previous wait: ${JSONObject(it.waitingContext)}. ${it.pendingFeedback.orEmpty()}"
        }
        var waitingContext = resume?.waitingContext.orEmpty()
        fun saveCheckpoint() {
            val archive = store ?: return
            synchronized(pendingUserSupplements) {
                archive.saveCheckpoint(task.exportCheckpoint(archive.runId,
                    maxOf(currentStep + 1, (task.steps.keys.maxOrNull() ?: 0) + 1),
                    pendingUserSupplements.toList(), pendingUserAnswer, pendingActionFeedback,
                    if (humanControlGranted) waitingContext + ("human_control_granted" to true) else waitingContext))
            }
        }
        fun execution(step: Int, status: String, detail: String, trace: Map<String, Any?> = emptyMap()) {
            val record = task.steps.getValue(step)
            val data = trace + mapOf(
                "action_type" to record.decision["action_type"],
                "executed" to if (status == "issued") null else (status == "executed"),
                "execution_status" to status, "feedback" to detail
            )
            record.execution = data
            if (status in TaskState.DISPATCHED && record.decision["action_type"] in SIDE_EFFECT_ACTIONS) {
                record.effect = mapOf("status" to "unknown", "evidence" to
                    if (status == "issued") "Action is being dispatched; completion must be checked after interruption"
                    else "Input accepted; effect awaits a later observation")
            }
            store?.recordEvent(step, if (status == "issued") "operation_issued" else "execution", data)
            saveCheckpoint()
            publishHandover()
        }
        var prevViewTree: String? = null
        var prevActionType: String? = null
        var prevScreenFingerprint: ScreenFingerprint? = null
        var consecutiveUnchangedScreens = 0

        try {
            store = runStoreFactory?.invoke(instruction)
            synchronized(pendingUserSupplements) {
                resume?.let {
                    pendingUserSupplements.addAll(0, it.pendingUserInputs)
                    if (pendingUserAnswer == null) pendingUserAnswer = it.pendingUserAnswer
                }
                saveCheckpoint()
                activeStore = store
            }
            store?.let { onRunStarted?.invoke(it.runId) }
            memory = store?.let { memoryManager?.session(task, it) }
            memory?.start()
            hybrid = executionContext?.let { appContext ->
                TaskExecutionRuntime(appContext, requireNotNull(store) { "Hybrid execution requires a task archive" },
                    taskGuiAccess { currentStep }, memoryManager?.repository)
            }
            activeExecution = hybrid
            refreshExecutionState()
            team = store?.let { archive -> teamRuntime?.session(archive, { task.goalRevision }, scope, execution = hybrid) {
                listOf(task.instruction) + task.userUpdates.map { it.getValue("text").toString() }
            } }
            team?.let { session ->
                session.initialize()
                activeTeam = session.manager
                teamObserver = scope.launch { session.manager.records.collect { _childAgents.value = it } }
            }
            publishHandover()
            val context = ContextManager(task, store, llmClient)
            // Wait for VD to stabilize after creation. Android may briefly route
            // the calling app's activity to a new VD; the keyguard dismiss thread
            // in PrivilegedService also needs ~600ms. The mirror cache drain
            // (800ms after creation) clears stale frames.
            if (virtualDisplayManager != null) {
                delay(1200)
            }

            for (step in task.runStartStep until task.runStartStep + maxSteps) {
                currentStep = step
                // Check for pause
                while (paused) {
                    delay(200)
                    if (!scope.isActive) return
                }
                val observedEpoch = controlEpoch.get()

                // === BLACKLIST GATE ===
                if (blacklistedPackages.isNotEmpty()) {
                    val displayId = virtualDisplayManager?.displayId ?: 0
                    val topPkg = VdAccessibilityService.getTopPackageOnDisplay(displayId)
                    if (topPkg != null && topPkg in blacklistedPackages) {
                        Log.i(TAG, "Blacklist gate: protected app $topPkg active at step $step")
                        val reason = "Protected app ($topPkg) is active. Returning control to user."
                        val deferred = CompletableDeferred<String?>()
                        userHandoffDeferred = deferred
                        waitingContext = mapOf("kind" to "handoff_user", "reason" to reason, "handoff_kind" to "protected_app", "step" to step)
                        saveCheckpoint()
                        _state.value = AgentState.WaitingForHandoff(reason, step, "protected_app")
                        _stepResults.emit(
                            StepResult(step, "blacklist_handoff", "Protected app detected",
                                true, reason, 0, false)
                        )
                        val handoffSource = deferred.await()
                        userHandoffDeferred = null
                        if (handoffSource == null) {
                            _state.value = AgentState.Idle()
                            return
                        }
                        pendingActionFeedback = "User returned control after protected app."
                        waitingContext = emptyMap()
                        saveCheckpoint()
                        continue
                    }
                }

                val stepStartTime = System.currentTimeMillis()
                var captureDuration: Long? = null
                var llmDuration: Long? = null
                var actionDuration: Long? = null
                try {
                    showRunning(step, "Capturing screenshot...")

                    // === OBSERVE ===
                    val t0 = System.currentTimeMillis()
                    val vdm = virtualDisplayManager
                    val screenshotBase64: String? = if (vdm != null) {
                        captureVirtualDisplay(vdm, step, observedEpoch)
                    } else {
                        Log.w(TAG, "No VirtualDisplayManager — capturing physical screen")
                        lastCaptureSource = "physical_backend:${ScreenCapture.backend.javaClass.simpleName}"
                        ScreenCapture.captureBase64()
                    }
                    val captureMs = System.currentTimeMillis() - t0
                    captureDuration = captureMs

                    if (shouldForwardScreenshot?.invoke() == true && screenshotBase64 != null) {
                        val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                        _stepScreenshots.tryEmit(bytes)
                    }

                    if (paused || controlEpoch.get() != observedEpoch) continue
                    if (screenshotBase64 == null) {
                        outcome = "failed"
                        store?.recordEvent(step, "error", mapOf("stage" to "capture", "message" to "Failed to capture screenshot"))
                        _state.value = AgentState.Error("Failed to capture screenshot")
                        _stepResults.emit(
                            StepResult(step, "error", "Screenshot failed", false,
                                "Failed to capture screenshot", 0, true)
                        )
                        return
                    }
                    val screenFingerprint = ScreenFingerprint.fromBase64(screenshotBase64)

                    // FLAG_SECURE detection: consecutive strictly-black frames indicate a
                    // privacy-protected screen. Use strict check (all sampled pixels == 0x000000)
                    // to avoid false positives from app transition animations which have
                    // status bar / navigation bar colors.
                    if (isBitmapStrictlyBlack(screenshotBase64)) {
                        consecutiveBlackFrames++
                        Log.d(TAG, "Strictly black frame detected ($consecutiveBlackFrames consecutive, lastNonBlack=$lastNonBlackStep)")
                    } else {
                        consecutiveBlackFrames = 0
                        lastNonBlackStep = step
                    }

                    // Trigger handoff if:
                    // - 3+ consecutive strictly-black frames (rules out transition animations)
                    // - Had a non-black frame before (not screen-off from start)
                    // - Not already on step 1 (rules out initial state issues)
                    if (consecutiveBlackFrames >= 3 && lastNonBlackStep > 0) {
                        Log.i(TAG, "FLAG_SECURE handoff triggered at step $step")
                        consecutiveBlackFrames = 0  // reset to prevent re-trigger if user resumes

                        val reason = "I may not be able to see this screen — it appears to be privacy-protected. Please take over to handle this part."
                        val deferred = CompletableDeferred<String?>()
                        userHandoffDeferred = deferred
                        _state.value = AgentState.WaitingForHandoff(reason, step, "protected_screen")
                        _stepResults.emit(
                            StepResult(step, "flag_secure_handoff", "Privacy-protected screen detected",
                                true, reason, System.currentTimeMillis() - stepStartTime, false)
                        )

                        val handoffSource = deferred.await()
                        userHandoffDeferred = null

                        if (handoffSource == null) {
                            _state.value = AgentState.Idle()
                            return
                        }

                        // After user returns control, check if still black
                        pendingActionFeedback = "User returned control after privacy-protected screen."
                        continue  // take fresh screenshot
                    }

                    // === VIEW TREE ===
                    val viewTree: String? = if (vdm != null && viewTreeProvider != null) {
                        try {
                            viewTreeProvider.invoke(vdm.displayId, vdm.width, vdm.height)
                        } catch (e: Exception) {
                            Log.w(TAG, "ViewTree capture failed", e)
                            null
                        }
                    } else null
                    if (viewTree != null) {
                        Log.i(TAG, "Step $step ViewTree: ${viewTree.length} chars, ${viewTree.lines().size} nodes")
                    }

                    store?.recordObservation(step, screenshotBase64, viewTree, mapOf(
                        "display_id" to (vdm?.displayId ?: 0),
                        "display_width" to vdm?.width, "display_height" to vdm?.height,
                        "source" to lastCaptureSource,
                        "capture_started_at" to t0, "capture_finished_at" to (t0 + captureMs),
                        "tree_finished_at" to System.currentTimeMillis(),
                        "frame_timestamp" to null, "frame_timestamp_status" to "unknown; capture may use a cached frame"
                    ))
                    task.steps[step] = TaskState.Step(step, if (store == null) screenshotBase64 else null, if (store == null) viewTree else null)
                    store?.let { task.stepSources[step] = it.runId }
                    humanInterventionsByStep[step] = task.humanInterventionCount
                    saveCheckpoint()

                    // Detect unchanged screen after side-effect action (viewTree-based)
                    if (step > 1 && prevActionType in SIDE_EFFECT_ACTIONS &&
                        prevViewTree != null && viewTree != null &&
                        prevViewTree == viewTree && pendingActionFeedback == null) {
                        pendingActionFeedback = String.format(
                            LlmPrompts.agentFeedback("screen_unchanged"), prevActionType
                        )
                        Log.w(TAG, "Step $step: screen unchanged after $prevActionType action (viewTree)")
                    }

                    // Detect unchanged screen via ScreenFingerprint (works without accessibility service)
                    if (step > 1 && prevActionType in SIDE_EFFECT_ACTIONS && screenFingerprint != null) {
                        val prev = prevScreenFingerprint
                        if (prev != null && screenFingerprint.isSimilarTo(prev)) {
                            consecutiveUnchangedScreens++
                        } else {
                            consecutiveUnchangedScreens = 0
                        }
                        if (consecutiveUnchangedScreens >= 3 && consecutiveUnchangedScreens % 3 == 0
                            && pendingActionFeedback == null) {
                            pendingActionFeedback = LlmPrompts.agentFeedback("screen_stuck")
                            Log.w(TAG, "Step $step: screen stuck for $consecutiveUnchangedScreens steps (fingerprint)")
                        }
                    }
                    prevScreenFingerprint = screenFingerprint

                    // Build user message — on step 1, hint that the VD may be blank
                    val t1 = System.currentTimeMillis()
                    synchronized(pendingUserSupplements) {
                        pendingUserAnswer?.let { task.addUserUpdate(step, "answer", it) }
                        pendingUserSupplements.forEach { task.addUserUpdate(step, "supplement", it) }
                        pendingUserAnswer = null
                        pendingUserSupplements.clear()
                        saveCheckpoint()
                    }
                    refreshTeamState()
                    val memoryPacket = memory?.prepare(step, VdAccessibilityService.getTopPackageOnDisplay(vdm?.displayId ?: 0))
                        ?: MemoryPacket()
                    executionMemory = memoryPacket
                    refreshExecutionState()
                    val feedback = pendingActionFeedback
                    val firstStepHint = if (step == 1 && vdm != null) LlmPrompts.agentFeedback("vd_blank_hint") else null
                    val contextInput = ContextInput(step, screenshotBase64, viewTree, memoryPacket, feedback, firstStepHint)
                    store?.recordEvent(step, "user_updates", mapOf("updates" to task.userUpdates))
                    pendingActionFeedback = null
                    val encodeMs = System.currentTimeMillis() - t1

                    // === THINK ===
                    showRunning(step, "Thinking...")
                    val t2 = System.currentTimeMillis()

                    lateinit var resultData: Map<String, Any?>
                    try {
                        resultData = context.decide(contextInput) {
                            !paused && controlEpoch.get() == observedEpoch
                        }
                    } catch (cancelled: CancellationException) {
                        // Pausing cancels this HTTP round, not the task's coroutine.
                        if (kotlin.coroutines.coroutineContext.isActive &&
                            (paused || controlEpoch.get() != observedEpoch)) continue
                        throw cancelled
                    } catch (exhausted: ModelBudgetExceededException) {
                        if (paused || controlEpoch.get() != observedEpoch) continue
                        outcome = "failed"
                        team?.manager?.close("budget_exhausted")
                        refreshTeamState()
                        val detail = teamRuntime?.let {
                            TeamFinalization.summarize(llmClient, task, it, store, step)
                        } ?: exhausted.message.orEmpty()
                        store?.recordEvent(step, "budget_exhausted", mapOf("message" to detail))
                        _state.value = AgentState.Error(detail)
                        _stepResults.emit(StepResult(step, "budget_exhausted", detail, false,
                            detail, System.currentTimeMillis() - stepStartTime, true))
                        return
                    } catch (e: LLMException) {
                        if (paused || controlEpoch.get() != observedEpoch) continue
                        outcome = "failed"
                        store?.recordEvent(step, "error", mapOf("stage" to "llm", "message" to e.message))
                        val llmMs = System.currentTimeMillis() - t2
                        llmDuration = llmMs
                        Log.w(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms (ERROR)")
                        _state.value = AgentState.Error("LLM error: ${e.message}")
                        _stepResults.emit(
                            StepResult(step, "error", e.message ?: "LLM error", false,
                                "LLM API error: ${e.message}", llmMs, true)
                        )
                        return
                    }
                    val llmMs = System.currentTimeMillis() - t2
                    llmDuration = llmMs

                    if (paused || controlEpoch.get() != observedEpoch) {
                        store?.recordEvent(step, "discarded_decision", mapOf("reason" to "Human took control after this observation"))
                        continue
                    }

                    task.recalledSteps = emptyList()
                    task.recalledTextPage = null
                    task.steps.getValue(step).decision = resultData
                    store?.recordEvent(step, "proposed", resultData)
                    try {
                        task.applyResponse(resultData, step)
                    } catch (e: IllegalArgumentException) {
                        pendingActionFeedback = "Invalid tool response: ${e.message}. Correct the fields; no phone action was executed."
                        execution(step, "blocked", pendingActionFeedback!!)
                        continue
                    }
                    (resultData["previous_action_result"] as? Map<*, *>)?.let {
                        store?.recordEvent(step, "effect", mapOf("target_step" to it["step"], "status" to it["status"], "evidence" to it["evidence"], "evidence_step" to step))
                    }

                    memory?.respond(step, resultData)
                    refreshTeamState()
                    val noteValue = resultData["note"] as? String
                    if (!noteValue.isNullOrBlank()) {
                        val (key, value) = parseNote(noteValue)
                        val updatedKeys = (resultData["memory_update"] as List<*>).map { (it as Map<*, *>)["key"] }
                        if (key !in updatedKeys) task.memories[key] = mapOf("value" to value, "source_step" to step, "recorded_step" to step)
                    }
                    store?.recordEvent(step, "state", task.snapshot())
                    saveCheckpoint()
                    publishHandover()

                    Log.i(TAG, "Step $step LLM response: thought=${resultData["thought"]}, action_type=${resultData["action_type"]}, reason=${resultData["reason"]}, question=${resultData["question"]}")
                    val debugInfo = if (debugMode) formatDebugInfo(resultData) else null

                    val thought = resultData["thought"] as String
                    val actionType = resultData["action_type"] as String

                    if (paused || controlEpoch.get() != observedEpoch) continue
                    showRunning(step, thought)

                    if (actionType in TaskExecutionRuntime.ACTIONS) {
                        val started = System.currentTimeMillis()
                        // Programs acquire the existing GUI mutex only inside each SDK call;
                        // keeping it here would block their Binder callbacks from re-entering.
                        val result = try {
                            requireNotNull(hybrid) { "Hybrid execution is unavailable for this run" }
                                .tool(resultData, step, observedEpoch, memory)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            JSONObject().put("status", "failed").put("reason", failure.message ?: failure.javaClass.simpleName)
                        }
                        refreshExecutionState()
                        val success = (!result.has("status") || result.optString("status") in setOf("completed", "succeeded")) &&
                            result.isNull("reason")
                        val detail = result.toString()
                        execution(step, if (success) "tool" else "blocked", detail,
                            mapOf("hybrid_result" to ResponseParser.jsonObjectToMap(result)))
                        pendingActionFeedback = detail
                        actionDuration = System.currentTimeMillis() - started
                        _stepResults.emit(StepResult(step, actionType, thought, success, detail,
                            System.currentTimeMillis() - stepStartTime, false, debugInfo))
                        continue
                    }

                    if (actionType in TeamSession.ACTIONS) {
                        val result = try {
                            val session = requireNotNull(team) { "资料协作未启用；可在设置中为新任务配置团队额度。" }
                            true to session.tool(resultData, step)
                        } catch (invalid: IllegalArgumentException) {
                            false to "Delegation tool: ${invalid.message}"
                        }
                        refreshTeamState()
                        execution(step, if (result.first) "tool" else "blocked", result.second)
                        pendingActionFeedback = result.second
                        _stepResults.emit(StepResult(step, actionType, thought, result.first, result.second,
                            System.currentTimeMillis() - stepStartTime, false, debugInfo))
                        continue
                    }

                    if (actionType in setOf("search_memory", "read_memory", "remember")) {
                        val result = memory?.tool(step, resultData) ?: (false to "Persistent memory is unavailable for this run")
                        execution(step, if (result.first) "tool" else "blocked", result.second)
                        pendingActionFeedback = result.second
                        _stepResults.emit(StepResult(step, actionType, thought, result.first, result.second,
                            System.currentTimeMillis() - stepStartTime, false, debugInfo))
                        continue
                    }

                    if (actionType == "update_plan" || actionType == "recall_history") {
                        val result = try {
                            true to if (actionType == "update_plan") {
                                "Plan updated: ${task.plan.size} subgoals; current=${task.currentGoalId}"
                            } else context.recall(resultData, step)
                        } catch (e: IllegalArgumentException) {
                            false to "History recall failed: ${e.message}"
                        }
                        execution(step, if (result.first) "tool" else "blocked", result.second)
                        pendingActionFeedback = result.second
                        _stepResults.emit(StepResult(step, actionType, thought, result.first, result.second,
                            System.currentTimeMillis() - stepStartTime, false, debugInfo))
                        continue
                    }

                    // Check for terminal actions
                    if (actionType == "finish" || actionType == "fail") {
                        outcome = if (actionType == "finish") "completed" else "failed"
                        execution(step, "tool", outcome)
                        val detail = if (actionType == "finish") {
                            "Task finished: $thought"
                        } else {
                            "Task failed: ${resultData["reason"] ?: thought}"
                        }
                        val totalMs = System.currentTimeMillis() - stepStartTime
                        Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=0ms total=${totalMs}ms [$actionType]")
                        Log.i(TAG, "Task completed at step $step [$actionType]: ${detail.take(100)}")
                        val sugName = resultData["suggested_routine_name"] as? String
                        val sugIcon = resultData["suggested_routine_icon"] as? String
                        _stepResults.emit(
                            StepResult(step, actionType, thought, actionType == "finish",
                                detail, totalMs, true, debugInfo,
                                suggestedRoutineName = sugName,
                                suggestedRoutineIcon = sugIcon)
                        )
                        _state.value = if (actionType == "finish") {
                            AgentState.Idle(resultMessage = thought)
                        } else {
                            AgentState.Error(resultData["reason"] as? String ?: thought)
                        }
                        return
                    }

                    // Handle ask_user: suspend until user answers
                    if (actionType == "ask_user") {
                        execution(step, "tool", "Waiting for user answer")
                        val question = resultData["question"] as? String ?: thought
                        waitingContext = mapOf("kind" to "ask_user", "question" to question, "step" to step)
                        saveCheckpoint()
                        val totalMsAsk = System.currentTimeMillis() - stepStartTime
                        Log.i(TAG, "WaitingForUser(question=$question, step=$step)")
                        val deferred = CompletableDeferred<String?>()
                        userResponseDeferred = deferred
                        _state.value = AgentState.WaitingForUser(question, step)
                        _stepResults.emit(StepResult(step, "ask_user", thought, true, "Asking: $question", totalMsAsk, false))

                        val userAnswer = deferred.await()
                        userResponseDeferred = null

                        if (userAnswer == null) {
                            _state.value = AgentState.Idle()
                            return
                        }

                        // Store answer to be merged into the next user message (with screenshot).
                        // Avoids two consecutive user messages which confuses the LLM.
                        waitingContext = emptyMap()
                        saveCheckpoint()
                        continue  // go to next loop iteration to take screenshot
                    }

                    // Handle handoff_user: suspend while the user operates the device.
                    if (actionType == "handoff_user") {
                        execution(step, "tool", "Waiting for user handoff")
                        val reason = resultData["handoff_reason"] as? String
                            ?: resultData["reason"] as? String
                            ?: thought.ifBlank { LlmPrompts.agentFeedback("handoff_default_reason") }
                        val totalMsHandoff = System.currentTimeMillis() - stepStartTime
                        val kind = (resultData["handoff_kind"] as? String)?.takeIf {
                            it in setOf("gui_difficulty", "captcha", "account_access", "other")
                        } ?: "unknown"
                        waitingContext = mapOf("kind" to "handoff_user", "reason" to reason, "handoff_kind" to kind, "step" to step)
                        saveCheckpoint()
                        Log.i(TAG, "WaitingForHandoff(reason=$reason, step=$step, kind=$kind)")
                        val deferred = CompletableDeferred<String?>()
                        userHandoffDeferred = deferred
                        _state.value = AgentState.WaitingForHandoff(reason, step, kind)
                        _stepResults.emit(
                            StepResult(
                                step,
                                "handoff_user",
                                thought,
                                true,
                                "Handing off to user: $reason",
                                totalMsHandoff,
                                false,
                                debugInfo
                            )
                        )

                        val handoffSource = deferred.await()
                        userHandoffDeferred = null

                        if (handoffSource == null) {
                            _state.value = AgentState.Idle()
                            return
                        }

                        pendingActionFeedback = String.format(LlmPrompts.agentFeedback("handoff_completed"), handoffSource)
                        waitingContext = emptyMap()
                        saveCheckpoint()
                        continue
                    }

                    // Handle standalone note action: record and continue to next observe cycle
                    if (actionType == "note") {
                        execution(step, "tool", "Task note recorded")
                        val totalMsNote = System.currentTimeMillis() - stepStartTime
                        Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms [note]")
                        _stepResults.emit(StepResult(step, "note", thought, true,
                            "Note: ${noteValue ?: thought}", totalMsNote, false, debugInfo))
                        delay(200) // brief delay before next observe
                        continue
                    }

                    if (actionType == "save_routine") {
                        val routineName = (resultData["routine_name"] as? String) ?: ""
                        val routineIcon = (resultData["routine_icon"] as? String) ?: "⚡"
                        val routineInstruction = (resultData["routine_instruction"] as? String) ?: instruction
                        val scheduleType = resultData["schedule_type"] as? String
                        val scheduleTime = resultData["schedule_time"] as? String
                        val scheduleRepeat = resultData["schedule_repeat"] as? String
                        val scheduleInterval = (resultData["schedule_interval"] as? Number)?.toInt()
                        val scheduleLocation = resultData["schedule_location"] as? String
                        val scheduleOnEnter = resultData["schedule_on_enter"] as? Boolean

                        onSaveRoutine?.invoke(
                            routineName, routineIcon, routineInstruction,
                            scheduleType, scheduleTime, scheduleRepeat,
                            scheduleInterval, scheduleLocation, scheduleOnEnter
                        )
                        execution(step, "tool", "Routine saved")
                        outcome = "completed"

                        val totalMs = System.currentTimeMillis() - stepStartTime
                        val scheduleDesc = when (scheduleType) {
                            "time" -> "@ $scheduleTime ${scheduleRepeat ?: "daily"}"
                            "interval" -> "every ${scheduleInterval}min"
                            "geofence" -> "${if (scheduleOnEnter != false) "arrive" else "leave"} $scheduleLocation"
                            else -> ""
                        }
                        val desc = if (scheduleDesc.isNotEmpty()) "$routineName ($scheduleDesc)" else routineName
                        Log.i(TAG, "Step $step: save_routine: $desc")
                        _stepResults.emit(
                            StepResult(step, actionType, thought, true,
                                "Routine created: $desc", totalMs, true, debugInfo)
                        )

                        _state.value = AgentState.Idle(resultMessage = "Routine created: $desc")
                        return
                    }

                    // === ACT ===
                    val t3 = System.currentTimeMillis()
                    val action = Action.fromMap(resultData)
                    showRunning(step, "Executing: $actionType")

                    val unresolved = task.unresolvedResumedStep(resultData)
                    if (unresolved != null) {
                        pendingActionFeedback = "Step $unresolved may already have changed the app. Read back its result in a fresh observation before retrying the same operation; keep unknown if it cannot be determined."
                        execution(step, "blocked", pendingActionFeedback!!)
                        _stepResults.emit(StepResult(step, actionType, thought, false, pendingActionFeedback!!,
                            System.currentTimeMillis() - stepStartTime, false, debugInfo))
                        continue
                    }

                    when (val repeatDecision = actionRepeatGuard.evaluate(action, screenFingerprint)) {
                        is ActionRepeatGuard.Decision.Block -> {
                            val totalMs = System.currentTimeMillis() - stepStartTime
                            pendingActionFeedback = repeatDecision.feedback
                            execution(step, "blocked", repeatDecision.feedback)
                            Log.w(TAG, "Step $step action blocked by repeat guard: ${repeatDecision.feedback}")
                            _stepResults.emit(
                                StepResult(
                                    step = step,
                                    actionType = action.typeName,
                                    thought = thought,
                                    success = false,
                                    detail = repeatDecision.feedback,
                                    durationMs = totalMs,
                                    completed = false,
                                    debugInfo = debugInfo
                                )
                            )
                            delay(300)
                            continue
                        }
                        ActionRepeatGuard.Decision.Allow -> Unit
                    }

                    val stepResult = actionMutex.withLock {
                        if (paused || controlEpoch.get() != observedEpoch) null
                        else {
                            if (actionType in SIDE_EFFECT_ACTIONS) execution(step, "issued", "Dispatching $actionType")
                            actionExecutor.execute(action, step).also { result ->
                                execution(step, if (result.success) "executed" else "execution_failed", result.detail, actionExecutor.lastTouchTrace)
                                if (result.success) actionRepeatGuard.recordExecuted(action, screenFingerprint)
                            }
                        }
                    }
                    if (stepResult == null) {
                        execution(step, "discarded", "Human took control before this action")
                        continue
                    }

                    // Feed action failure back to LLM in the next iteration
                    if (!stepResult.success) {
                        pendingActionFeedback = String.format(LlmPrompts.agentFeedback("action_failed"), stepResult.detail)
                        Log.w(TAG, "Step $step action failed, will feed back to LLM: ${stepResult.detail}")
                    } else if (actionType == "list_apps") {
                        pendingActionFeedback = stepResult.detail
                        Log.i(TAG, "Step $step list_apps result: ${stepResult.detail.take(200)}")
                    }

                    val actionMs = System.currentTimeMillis() - t3
                    actionDuration = actionMs

                    val totalMs = System.currentTimeMillis() - stepStartTime

                    val finalResult = stepResult.copy(
                        completed = false,
                        durationMs = totalMs,
                        debugInfo = debugInfo
                    )
                    _stepResults.emit(finalResult)

                    Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=${actionMs}ms total=${totalMs}ms [$actionType]")

                    // Wait for screen update before next step
                    prevViewTree = viewTree
                    prevActionType = actionType
                    delay(if (actionType == "open_app") 2000 else 1000)

                } finally {
                    withContext(NonCancellable) { memory?.checkpoint(step) }
                    saveCheckpoint()
                    store?.recordEvent(step, "timing", mapOf(
                        "capture_ms" to captureDuration, "llm_ms" to llmDuration,
                        "action_ms" to actionDuration, "total_ms" to (System.currentTimeMillis() - stepStartTime)
                    ))
                }
            }

            // Max steps reached
            outcome = "max_steps"
            Log.w(TAG, "max_steps reached at step $maxSteps, stopping agent")
            _state.value = AgentState.Idle(resultMessage = String.format(LlmPrompts.agentFeedback("max_steps_reached"), maxSteps))
            _stepResults.emit(
                StepResult(currentStep, "max_steps", "Reached max steps ($maxSteps)",
                    false, "Max steps reached", 0, true)
            )

        } catch (e: CancellationException) {
            Log.i(TAG, "Agent loop cancelled")
            _state.value = AgentState.Idle()
        } catch (e: Exception) {
            outcome = "failed"
            store?.recordEvent(currentStep, "error", mapOf("stage" to "engine", "message" to e.message))
            Log.e(TAG, "Agent loop error", e)
            _state.value = AgentState.Error(e.message ?: "Unknown error")
            _stepResults.emit(StepResult(currentStep, "error", e.message ?: "Agent error", false, e.message ?: "Agent error", 0, true))
        } finally {
            withContext(NonCancellable) {
                try {
                    hybrid?.interruptGui()
                    hybrid?.awaitGuiIdle()
                } catch (failure: Exception) {
                    outcome = "failed"
                    Log.e(TAG, "GUI program finalization failed", failure)
                }
                try {
                    team?.manager?.close(outcome)
                    refreshTeamState()
                }
                catch (failure: Exception) {
                    outcome = "failed"
                    Log.e(TAG, "Child task finalization failed", failure)
                }
                finally {
                    teamObserver?.cancel()
                    teamObserver?.join()
                    team?.let { _childAgents.value = it.manager.records.value }
                    activeTeam = null
                }
                try { refreshExecutionState() }
                catch (failure: Exception) { Log.e(TAG, "Execution context finalization failed", failure) }
                activeExecution = null
                try { memory?.finish(currentStep, outcome) }
                catch (e: Exception) {
                    Log.e(TAG, "Memory finalization failed", e)
                }
                try { saveCheckpoint() }
                catch (e: Exception) { Log.e(TAG, "Task checkpoint finalization failed", e) }
                val finishedStore = store
                if (finishedStore != null) {
                    try { finishedStore.finish(outcome) }
                    catch (e: Exception) { Log.e(TAG, "Task archive finalization failed", e) }
                    try {
                        val finalContext = publishHandover() + ("continuation_steps" to task.steps.values
                            .filter { it.number >= task.runStartStep }.map { record ->
                            mapOf(
                                "step" to record.number,
                                "source_run" to finishedStore.runId,
                                "action" to record.decision.filterKeys { it !in TaskState.RESPONSE_METADATA },
                                "execution" to record.execution,
                                "effect" to record.effect,
                                "user_updates" to task.userUpdates.filter { it["step"] == record.number },
                                "human_intervention_count" to humanInterventionsByStep[record.number]
                            )
                        })
                        onRunFinished?.invoke(finishedStore.runId, outcome, finalContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Task review finalization failed", e)
                    }
                }
                lastOutcome = outcome
                if (activeTask === task) {
                    activeTask = null
                    synchronized(pendingUserSupplements) { activeStore = null }
                }
            }
        }
    }

    /** The task's VD is shared with ordinary actions; no second executor or GUI lease is created. */
    private fun taskGuiAccess(step: () -> Int): TaskGuiAccess? {
        val display = virtualDisplayManager ?: return null
        val native = AccessibilityGuiAccess { target ->
            require(display.width > 0 && display.height > 0) { "Task display dimensions are unavailable" }
            val bounds = target.bounds
            val x = ((bounds.left.toFloat() + bounds.right) * 500 / display.width).toInt().coerceIn(0, 1000)
            val y = ((bounds.top.toFloat() + bounds.bottom) * 500 / display.height).toInt().coerceIn(0, 1000)
            actionExecutor.execute(Action.Tap(x, y), step()).success
        }
        fun blocked(selector: GuiSelector): String? {
            if (selector.displayId != display.displayId) return "task_display_changed"
            if (selector.packageName in blacklistedPackages) return "package_blacklisted: ${selector.packageName}"
            if (blacklistedPackages.isNotEmpty()) {
                val current = VdAccessibilityService.getTopPackageOnDisplay(display.displayId)
                if (current in blacklistedPackages) return "package_blacklisted: $current"
            }
            return null
        }
        val guarded = object : GuiAccess {
            override suspend fun query(selector: GuiSelector): GuiQueryResult {
                blocked(selector)?.let { return GuiQueryResult(null, emptyList(), it) }
                return native.query(selector)
            }

            override suspend fun perform(action: GuiBlockAction, expectedPage: GuiPageIdentity,
                isCurrent: () -> Boolean): GuiActionAttempt {
                blocked(action.selector)?.let { return GuiActionAttempt(false, null, it) }
                return native.perform(action, expectedPage, isCurrent)
            }
        }
        val blocks = GuiBlockExecutor(guarded) { controlEpoch.get() }
        return object : TaskGuiAccess {
            override val displayId: Int get() = display.displayId

            override suspend fun query(selector: GuiSelector, epoch: Long): GuiQueryResult = actionMutex.withLock {
                if (paused || controlEpoch.get() != epoch) return@withLock GuiQueryResult(null, emptyList(), "control_epoch_changed")
                guarded.query(selector)
            }

            override suspend fun execute(request: GuiBlockRequest, epoch: Long,
                onOperation: (Int, String, GuiOperationEvidence) -> Unit): GuiBlockResult = actionMutex.withLock {
                if (paused || controlEpoch.get() != epoch) return@withLock GuiBlockResult(
                    "needs_agent", emptyList(), null, "control_epoch_changed")
                blocks.execute(request, epoch, onOperation)
            }
        }
    }

    /**
     * Capture a frame from the virtual display.
     *
     * Strategy:
     * 1. ScreenCapture API (SurfaceFlinger, API 36+) — fast, works for most windows
     * 2. If ScreenCapture returns null (API <36) or all-black (FLAG_SECURE),
     *    fall back to ImageReader capture which reads directly from the VD's
     *    own surface and works on all API levels.
     *
     * Recovery: After 3 consecutive failures, launches home on the VD to ensure
     * an Activity is rendered (fixes empty VD state after failed app launch).
     */
    private suspend fun captureVirtualDisplay(vdm: VirtualDisplayManager, step: Int, observedEpoch: Long): String? {
        for (attempt in 1..VD_CAPTURE_MAX_ATTEMPTS) {
            val frame = withContext(Dispatchers.IO) {
                vdm.captureFrame(timeoutMs = VD_IMAGE_READER_TIMEOUT_MS, maxWidth = 540)
            }
            if (frame != null) {
                lastCaptureSource = frame.source
                return ScreenCapture.captureBase64(virtualDisplayBitmap = frame.bitmap)
            }
            if (paused || controlEpoch.get() != observedEpoch) return null

            // Recovery: after 3 failed attempts, launch home on VD to ensure content renders
            if (attempt == VD_CAPTURE_RECOVERY_THRESHOLD) {
                val topTask = vdm.getTopTaskIdOnDisplay(vdm.displayId)
                if (topTask == null || topTask <= 0) {
                    Log.w(TAG, "VD empty after $attempt capture failures, launching home")
                    actionMutex.withLock {
                        if (!paused && controlEpoch.get() == observedEpoch) withContext(Dispatchers.IO) {
                            ScreenCapture.backend.ensureVdHasContent(vdm.displayId)
                        }
                    }
                    delay(1000)  // Wait for home to render a frame
                } else {
                    Log.w(TAG, "VD has task $topTask but no new frame — using cached frame")
                }
            } else if (attempt < VD_CAPTURE_MAX_ATTEMPTS) {
                Log.d(TAG, "VD capture failed at step $step (attempt $attempt/$VD_CAPTURE_MAX_ATTEMPTS), waiting ${VD_CAPTURE_RETRY_DELAY_MS}ms...")
                delay(VD_CAPTURE_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "VD capture failed at step $step after $VD_CAPTURE_MAX_ATTEMPTS attempts")
        return null
    }

    private fun formatDebugInfo(resultData: Map<String, Any?>): String {
        return try {
            JSONObject(resultData).toString(2).take(2000)
        } catch (_: Exception) {
            resultData.toString().take(2000)
        }
    }

    /**
     * Quick check if a bitmap is all-black (or nearly so).
     * Samples a grid of pixels rather than checking every pixel.
     */
    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return true
        val sampleCount = 20
        for (i in 0 until sampleCount) {
            val x = (w * (i + 1)) / (sampleCount + 1)
            val y = (h * (i + 1)) / (sampleCount + 1)
            val pixel = bitmap.getPixel(x, y)
            // Check if pixel has any non-black color (ignore alpha)
            if ((pixel and 0x00FFFFFF) != 0) return false
        }
        return true
    }

    /**
     * Strict black check: ALL sampled pixels must be near-black (< 5 per channel).
     * FLAG_SECURE returns pure black; transition animations have status/nav bar colors.
     * Small threshold accounts for JPEG compression artifacts on pure black input.
     */
    private fun isBitmapStrictlyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w == 0 || h == 0) return true
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r >= 5 || g >= 5 || b >= 5) return false
                }
                true
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Decode a base64 JPEG and check if the image is mostly black (90%+ dark pixels).
     * Used to detect FLAG_SECURE screens where screenshots return black frames.
     */
    private fun isBitmapLikelyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                // Sample 20 pixels across the image to check if it's mostly black
                val w = bitmap.width
                val h = bitmap.height
                var darkPixels = 0
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r < 15 && g < 15 && b < 15) darkPixels++
                }
                darkPixels >= sampleCount * 0.9  // 90%+ dark pixels = likely black
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Parse a note string into key-value pair.
     * Format: "key: value" → Pair(key, value). If no colon, uses the full string as both.
     */
    private fun parseNote(note: String): Pair<String, String> {
        val colonIndex = note.indexOf(':')
        return if (colonIndex > 0) {
            val key = note.substring(0, colonIndex).trim()
            val value = note.substring(colonIndex + 1).trim()
            key to value
        } else {
            note.trim() to note.trim()
        }
    }

}
