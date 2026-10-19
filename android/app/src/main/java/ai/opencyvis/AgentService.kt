package ai.opencyvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.action.Action
import ai.opencyvis.learning.LearningRecorder
import ai.opencyvis.learning.LearningSessionController
import ai.opencyvis.learning.TaskHandoffRecording
import ai.opencyvis.learning.TaskReviewCoordinator
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.backend.BackendDetector
import ai.opencyvis.backend.ConnectionState
import ai.opencyvis.backend.DetectionResult
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.RemoteBackend
import ai.opencyvis.backend.SetupStateDetector
import ai.opencyvis.backend.SystemBackend
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.memory.MemoryManager
import ai.opencyvis.display.TaskDisplayGuard
import ai.opencyvis.display.TaskSnapshot
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.engine.ActionRepeatGuard
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.HandoffUiState
import ai.opencyvis.engine.HandoffVisualDetector
import ai.opencyvis.engine.ScreenFingerprint
import ai.opencyvis.engine.StepResult
import ai.opencyvis.engine.TaskCheckpoint
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.task.TaskCoordinator
import ai.opencyvis.task.TaskInterruptedException
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskRunner
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmClientFactory
import ai.opencyvis.engine.TaskTeamRuntime
import ai.opencyvis.overlay.OverlayStatePolicy
import ai.opencyvis.schedule.ScheduleManager
import ai.opencyvis.ui.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that hosts the AgentEngine.
 * Manages display state transitions (CHAT/VIEW/TAKEOVER) via task reparenting.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "opencyvis_agent"
        private const val CHANNEL_ASK = "opencyvis_ask_user"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ASK_ID = 1002
        private const val NOTIFICATION_COMPLETED_ID = 1003
        private const val HANDOFF_TIMEOUT_MS = 30_000L
        private const val HANDOFF_COUNTDOWN_SECONDS = 10
        private const val HANDOFF_SAMPLE_INTERVAL_MS = 1_000L
        const val EXTRA_FOCUS_ASK = "focus_ask_user"
        const val EXTRA_SHOW_HANDOFF = "show_handoff"
    }

    // ── Display state management ────────────────────────────────────────

    enum class DisplayState { CHAT, VIEW, TAKEOVER }

    private val _displayStateFlow = MutableStateFlow(DisplayState.CHAT)
    val displayStateFlow: StateFlow<DisplayState> = _displayStateFlow.asStateFlow()
    var displayState = DisplayState.CHAT
        private set(value) { field = value; _displayStateFlow.value = value }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: AgentEngine? = null
    private var agentStartupJob: Job? = null
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskCoordinator: TaskCoordinator
    private val queueReady = CompletableDeferred<Unit>()
    private val controlEpoch = AtomicLong()
    @Volatile private var shuttingDown = false
    @Volatile private var finishingLearning = false
    @Volatile private var activeRunTaskId: Long? = null
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeRunEngine: AgentEngine? = null
    @Volatile private var activeRunOwnsDisplay = false
    private var pausedTaskId: Long? = null
    private var startupResumeGate: CompletableDeferred<Unit>? = null
    @Volatile private var requestedStopTaskId: Long? = null
    private var runStateObserverJob: Job? = null
    private var historyQuestionJob: Job? = null
    private var historyTerminalWritten: CompletableDeferred<Unit>? = null
    private val historyWrites = Mutex()

    val activeTaskId: StateFlow<Long?> get() = taskCoordinator.activeTaskId
    val tasks: Flow<List<TaskRecord>> get() = taskCoordinator.tasks

    /**
     * Multi-subscriber flow that emits whenever the active AgentEngine changes
     * (or is cleared). Replaces the previous single-listener `onEngineCreated`
     * var, which silently overwrote prior listeners when both
     * `OverlayService` and `ControlPanelActivity` registered against it.
     */
    private val _engineFlow = MutableStateFlow<AgentEngine?>(null)
    val engineFlow: StateFlow<AgentEngine?> = _engineFlow.asStateFlow()
    private val _handoffUiState = MutableStateFlow<HandoffUiState>(HandoffUiState.Idle)
    val handoffUiState: StateFlow<HandoffUiState> = _handoffUiState.asStateFlow()
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private val learning by lazy { LearningSessionController(this) }
    private val taskRecording by lazy { TaskHandoffRecording(this, learning) }
    private val taskControl = Mutex()
    private val operationStartLock = Any()
    @Volatile private var startingOperation: String? = null
    val isAgentBusy: Boolean get() = startingOperation == "agent" ||
        (::taskCoordinator.isInitialized && activeTaskId.value != null) || engine?.isTaskActive == true
    val isLearningStarting: Boolean get() = startingOperation in setOf("teaching", "observation")
    val learningSessionId: String? get() = learning.sessionId
    val isLearning: Boolean get() = learningSessionId != null
    val isTaskReviewRecording: Boolean get() = taskRecording.isRecording
    val isLearningPaused: Boolean get() = learning.paused
    val learningCaptureError: String? get() = learning.captureError

    private fun reserveOperationStart(kind: String) = synchronized(operationStartLock) {
        check(startingOperation == null && !finishingLearning) { "正在启动任务或结束录制，请稍候" }
        check(LearningRecorder.get(this).active.value == null) { "请先结束当前教学或观察" }
        if (kind == "teaching" || kind == "observation") {
            check(!isAgentBusy) { "请先结束当前任务；任务中的人工接管会自动记录" }
        }
        startingOperation = kind
    }

    private fun releaseOperationStart(kind: String) = synchronized(operationStartLock) {
        if (startingOperation == kind) startingOperation = null
    }

    fun reserveObservationStart() = reserveOperationStart("observation")
    fun releaseObservationStart() = releaseOperationStart("observation")
    private var taskDisplayGuard: TaskDisplayGuard? = null
    private var taskRescueInProgress = false
    private var agentWakeLock: PowerManager.WakeLock? = null
    private lateinit var config: ConfigRepository
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var memoryManager: MemoryManager
    var currentConversationId: Long? = null
        private set
    var currentInstruction: String = ""
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backendDeferred = CompletableDeferred<PrivilegeBackend>()
    private var backend: PrivilegeBackend? = null

    /** The name of the currently active backend (e.g. "system", "shizuku", "adb-direct"), or null. */
    val activeBackendName: String?
        get() = backend?.capabilities?.name

    /** Disconnect the privilege backend. The user can reconnect later from the setup flow. */
    fun revokeBackend() {
        backend?.destroy()
        backend = null
        Log.i(TAG, "Backend revoked by user")
    }

    /** Update the backend after pairing/reconnection (called by AdbPairingService). */
    fun updateBackend(newBackend: PrivilegeBackend) {
        val previousBackend = backend
        backend = newBackend
        ScreenCapture.backend = newBackend
        observeConnectorState(newBackend)
        if (previousBackend !== newBackend) {
            previousBackend?.destroy()
        }
        Log.i(TAG, "Backend updated: ${newBackend.capabilities.name}")
    }
    private var stateObserverJob: Job? = null
    private var historyObserverJob: Job? = null
    private var handoffObserverJob: Job? = null
    private var handoffMonitorJob: Job? = null
    private var activeHandoffStep: Int? = null
    private var activeHandoffReason: String = ""
    private var disconnectTimestamp: Long? = null
    private var disconnectObserverJob: Job? = null

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        App.agentService = this
        config = ConfigRepository(this)
        // Auto-apply active profile to ensure config values match saved profile
        val activeProfile = config.activeProfileName
        if (activeProfile.isNotEmpty()) {
            val profileRepo = ai.opencyvis.config.ProfileRepository(config, this)
            profileRepo.switchTo(activeProfile)
        }
        historyRepo = ChatHistoryRepository(this)
        memoryManager = MemoryManager(this)
        taskRepository = TaskRepository(this)
        taskCoordinator = TaskCoordinator(scope, taskRepository, object : TaskRunner {
            override suspend fun run(task: TaskRecord): String = runQueuedTask(task)
            override suspend fun pause() {
                pauseCurrentRun()
                updateNotification("Agent paused")
            }
            override suspend fun resume() {
                resumeCurrentRun()
                updateNotification("Agent running")
            }
            override suspend fun stop(suspendTask: Boolean) = stopCurrentRun(suspendTask)
            override suspend fun finished(task: TaskRecord, status: String) = finishQueuedTask(task, status)
        })
        createNotificationChannel()
        ensureAccessibilityServiceEnabled()
        // Recovery must complete before a submitted task can become a new running conversation.
        scope.launch {
            try {
                historyRepo.markStaleRunningAsStopped()
                ai.opencyvis.memory.MemoryJobs.recover(this@AgentService)
                taskCoordinator.initialize()
                queueReady.complete(Unit)
            } catch (failure: Exception) {
                queueReady.completeExceptionally(failure)
                Log.e(TAG, "Unable to initialize task queue", failure)
            }
        }
        // Detect privilege backend (async for Shizuku, instant for system app)
        scope.launch {
            when (val result = BackendDetector.detect(this@AgentService)) {
                is DetectionResult.Ready -> {
                    backend = result.backend
                    ScreenCapture.backend = result.backend
                    backendDeferred.complete(result.backend)
                    observeConnectorState(result.backend)
                    Log.i(TAG, "Backend ready: ${result.backend.capabilities.name}")
                }
                is DetectionResult.SetupRequired -> {
                    // Don't fall back to SystemBackend on standard flavor — it won't work
                    backendDeferred.complete(SystemBackend()) // unblock startAgent (will fail gracefully)
                    Log.w(TAG, "Backend setup required — user needs to complete setup wizard")
                }
                is DetectionResult.NoneAvailable -> {
                    backendDeferred.complete(SystemBackend()) // unblock startAgent
                    Log.w(TAG, "No backend available — wireless debugging not enabled?")
                }
            }
        }
        Log.i(TAG, "AgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.lumi_notification_ready)))
        if (intent?.hasExtra("task_id") == true) {
            taskCommand { taskCoordinator.kick() }
            return START_STICKY
        }
        val instruction = intent?.getStringExtra("instruction")
        if (!instruction.isNullOrBlank()) {
            val scheduled = intent.action == "ai.opencyvis.START_SCHEDULED"
            val routineId = if (intent.hasExtra("routine_id")) intent.getIntExtra("routine_id", -1)
                .takeIf { it >= 0 }?.toLong() else null
            taskCommand {
                enqueueTask(TaskRequest(instruction, source = if (scheduled) "scheduled" else "intent",
                    routineId = routineId))
            }
        } else {
            taskCommand { taskCoordinator.kick() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        App.agentService = null
        disconnectObserverJob?.cancel()
        scope.launch {
            try {
                // close stops dispatch and awaits the active run's NonCancellable finalization.
                taskCoordinator.close()
                taskControl.withLock {
                    displayState = DisplayState.VIEW
                    finishTaskRecording("service_stopped")
                    if (isLearning) {
                        try { finishLearning("服务已停止；演示结果尚未确认") }
                        catch (failure: Exception) { Log.e(TAG, "Unable to save stopped demonstration", failure) }
                    }
                    learning.destroy()
                }
                engine?.stopAndJoin()
                engine?.destroy()
                engine = null
                _engineFlow.value = null
                stopRunObservers()
                taskDisplayGuard?.stop()
                taskDisplayGuard = null
                virtualDisplayManager?.destroy()
                virtualDisplayManager = null
                backend?.destroy()
                backend = null
                releaseAgentWakeLock()
            } finally {
                scope.cancel()
            }
        }
        Log.i(TAG, "AgentService stopping; queued tasks remain persisted")
        super.onDestroy()
    }

    private fun observeConnectorState(b: PrivilegeBackend) {
        disconnectObserverJob?.cancel()
        if (b !is RemoteBackend) return

        disconnectObserverJob = scope.launch {
            b.connector.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        val isEngineRunning = engine?.state?.value is AgentState.Running
                        if (isEngineRunning) {
                            Log.w(TAG, "Backend disconnected during active task, pausing")
                            engine?.pause()
                            disconnectTimestamp = System.currentTimeMillis()
                            updateNotification("⏸ Paused — reconnecting...")
                        } else {
                            Log.w(TAG, "Backend disconnected while idle")
                        }

                        // Shizuku is independent of wireless debugging. Only the direct
                        // ADB connector should use adb_wifi_enabled as a retry condition.
                        val wirelessOn = SetupStateDetector.isWirelessDebuggingEnabled(this@AgentService)
                        val canRetry = b.connector.name == "shizuku" || wirelessOn
                        if (canRetry) {
                            Log.i(TAG, "Attempting ${b.connector.name} backend reconnect...")
                            var reconnected = false
                            for (attempt in 1..5) {
                                delay(3000L * attempt)
                                if (retryBackendDetection()) {
                                    Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                                    reconnected = true
                                    break
                                }
                            }
                            if (!reconnected) {
                                Log.w(TAG, "Auto-reconnect failed after 5 attempts")
                                backend = null
                                ScreenCapture.backend = SystemBackend()
                            }
                        } else {
                            Log.i(TAG, "Wireless debugging is off for ADB backend, clearing backend")
                            backend = null
                            ScreenCapture.backend = SystemBackend()
                        }
                    }
                    is ConnectionState.Connected -> {
                        val wasDisconnected = disconnectTimestamp != null
                        val isEnginePaused = engine?.state?.value is AgentState.Paused
                        if (wasDisconnected && isEnginePaused && !isLearning && displayState != DisplayState.TAKEOVER) {
                            val elapsed = System.currentTimeMillis() - disconnectTimestamp!!
                            disconnectTimestamp = null

                            if (elapsed > 10_000) {
                                Log.i(TAG, "Long disconnect (${elapsed}ms), resuming with fresh state")
                            }
                            engine?.resume()
                            updateNotification("Running: ${currentInstruction.take(30)}...")
                            Log.i(TAG, "Backend reconnected after ${elapsed}ms, resumed agent")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun ensureAccessibilityServiceEnabled() {
        try {
            val componentName = "${packageName}/${packageName}.accessibility.VdAccessibilityService"
            val existing = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (componentName in existing) return

            val updated = if (existing.isBlank()) componentName else "$existing:$componentName"
            android.provider.Settings.Secure.putString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
            )
            android.provider.Settings.Secure.putInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            Log.i(TAG, "Auto-enabled VdAccessibilityService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-enable accessibility service (requires system app)", e)
        }
    }

    // ── Agent lifecycle ─────────────────────────────────────────────────

    /** Legacy callers submit a distinct task; only the coordinator starts an engine. */
    fun startAgent(instruction: String) {
        taskCommand { enqueueTask(TaskRequest(instruction)) }
    }

    suspend fun enqueueTask(request: TaskRequest): TaskRecord {
        queueReady.await()
        check(!shuttingDown) { "任务服务正在停止" }
        requireNotNull(config.contextBudget) { "请先在设置中配置当前模型的上下文窗口和输出预留。" }
        return taskCoordinator.enqueue(request)
    }

    suspend fun getTask(taskId: Long): TaskRecord? = taskRepository.get(taskId)
    fun cancelTask(taskId: Long) = taskCommand { taskCoordinator.cancel(taskId) }
    suspend fun cancelTaskAndWait(taskId: Long) {
        queueReady.await()
        taskCoordinator.cancel(taskId)
        activeTaskId.first { it != taskId }
    }
    fun pauseTask(taskId: Long) = taskCommand { taskCoordinator.pause(taskId) }
    fun resumeTask(taskId: Long) = taskCommand { taskCoordinator.resume(taskId) }
    fun suspendTaskAndRunNext(taskId: Long) = taskCommand { taskCoordinator.suspendAndRunNext(taskId) }
    fun recoverTask(taskId: Long) = taskCommand { taskCoordinator.recover(taskId) }

    private fun taskCommand(command: suspend () -> Unit) {
        scope.launch {
            try {
                queueReady.await()
                command()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Task command failed", failure)
                mainHandler.post { Toast.makeText(this@AgentService, failure.message, Toast.LENGTH_LONG).show() }
                updateNotification(failure.message ?: "任务操作失败")
            }
        }
    }

    internal suspend fun runQueuedTask(task: TaskRecord): String = coroutineScope {
        activeRunTaskId = task.id
        activeRunId = null
        activeRunEngine = null
        activeRunOwnsDisplay = false
        if (requestedStopTaskId != task.id) requestedStopTaskId = null
        val execution = async(start = CoroutineStart.LAZY) {
            if (shuttingDown || requestedStopTaskId == task.id) return@async "interrupted"
            while (finishingLearning || isLearning || isLearningStarting || LearningRecorder.get(this@AgentService).active.value != null) {
                delay(100)
            }
            if (shuttingDown || requestedStopTaskId == task.id) return@async "interrupted"
            reserveOperationStart("agent")
            try {
                activateTask(task)
            } finally {
                releaseOperationStart("agent")
            }
        }
        agentStartupJob = execution
        try {
            execution.start()
            execution.await()
        } catch (cancelled: CancellationException) {
            if (requestedStopTaskId != task.id && !shuttingDown) throw cancelled
            "interrupted"
        } finally {
            withContext(NonCancellable) {
                val current = activeRunEngine
                try {
                    if (current?.isTaskActive == true) current.stopAndJoin()
                    if (activeRunOwnsDisplay) {
                        try {
                            settleRunHistory(task.id, current)
                        } finally {
                            stopRunObservers()
                            taskControl.withLock {
                                if (activeRunId?.let(taskRecording::belongsTo) == true) {
                                    displayState = DisplayState.VIEW
                                    finishTaskRecording("task_run_exited")
                                }
                            }
                            taskDisplayGuard?.stop()
                            taskDisplayGuard = null
                            releaseAgentWakeLock()
                            if (requestedStopTaskId == task.id || shuttingDown) stopAgentAfterRecording()
                        }
                    }
                } finally {
                    activeRunTaskId = null
                    activeRunId = null
                    activeRunEngine = null
                    activeRunOwnsDisplay = false
                    agentStartupJob = null
                    historyTerminalWritten = null
                }
            }
        }
    }

    private suspend fun activateTask(task: TaskRecord): String {
        try {
            requireNotNull(config.contextBudget) { "请先在设置中配置当前模型的上下文窗口和输出预留。" }
        } catch (invalid: IllegalArgumentException) {
            throw TaskInterruptedException(invalid.message ?: "上下文预算配置无效", invalid)
        }
        val clients = LlmClientFactory.capture(config)
        check(engine?.isTaskActive != true) { "上一个任务尚未退出" }
        activeRunOwnsDisplay = true
        engine?.destroy()
        stopRunObservers()
        controlEpoch.incrementAndGet()
        taskRescueInProgress = false
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        cancelCompletionNotification()
        displayState = DisplayState.CHAT
        val resume = task.runId?.let { runId ->
            try {
                requireNotNull(TaskRunStore.readCheckpoint(this, runId)) { "找不到任务恢复检查点：$runId" }
            } catch (failure: Exception) {
                throw TaskInterruptedException("无法恢复任务进度：${failure.message}", failure)
            }
        }
        disconnectTimestamp = null
        val b = backend ?: run {
            backendDeferred.await()
            backend ?: throw TaskInterruptedException("请先连接无线调试或系统后端，再继续任务。")
        }
        if (requestedStopTaskId == task.id || shuttingDown) return "interrupted"
        var vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            vdm?.destroy()
            vdm = VirtualDisplayManager(this, b)
            val dm = resources.displayMetrics
            val id = vdm.create(width = dm.widthPixels, height = dm.heightPixels, dpi = dm.densityDpi)
            if (id == -1) {
                vdm.destroy()
                virtualDisplayManager = null
                error("无法创建任务显示屏")
            }
            virtualDisplayManager = vdm
        }
        val displayId = vdm.displayId
        val displaySize = Point(vdm.width, vdm.height)
        acquireAgentWakeLock()
        val team = try {
            TaskTeamRuntime.open(this, task.id, resume != null, config.teamConfig, clients)
        } catch (invalid: IllegalArgumentException) {
            throw TaskInterruptedException("资料协作配置无效：${invalid.message}", invalid)
        }
        val llmClient = team?.parentClient() ?: clients(null)
        val convId = task.conversationId ?: historyRepo.startConversation(task.instruction).also {
            taskRepository.attachConversation(task.id, it)
            if (task.source.startsWith("im:") && task.remoteChatId != null) {
                historyRepo.appendInbound(it, task.instruction, task.source, task.remoteChatId)
            } else {
                historyRepo.addMessage(it, MessageType.USER_INPUT, task.instruction)
            }
        }
        currentConversationId = convId
        currentInstruction = task.instruction
        val actionExecutor = ActionExecutor(this, displayId, displaySize,
            onOpenAppSuccess = { launchedPkg ->
                val epoch = controlEpoch.get()
                if (activeRunTaskId == task.id) taskDisplayGuard?.trackLaunch(launchedPkg)
                mainHandler.postDelayed({
                    if (activeRunTaskId == task.id && controlEpoch.get() == epoch) {
                        refreshControlledTasksFromVd("open_app_success")
                    }
                }, 250)
            }, backend = b, blacklistedPackages = config.blacklistedPackages)
        val newEngine = AgentEngine(
            llmClient, actionExecutor, config.maxSteps, vdm, config.debugMode, memoryManager,
            viewTreeProvider = { id, w, h -> VdAccessibilityService.captureViewTree(id, w, h) },
            shouldForwardScreenshot = { config.imSendStepScreenshots },
            blacklistedPackages = config.blacklistedPackages,
            teamRuntime = team,
            executionContext = applicationContext,
            controlEpoch = controlEpoch,
            runStoreFactory = { instruction -> TaskRunStore(this, convId, instruction, taskId = task.id, resume = resume) },
            onRunStarted = { runId ->
                withContext(NonCancellable) {
                    activeRunId = runId
                    taskRepository.attachRun(task.id, runId)
                }
            },
            onRunFinished = ::onTaskRunFinished,
            onSaveRoutine = { name, icon, instr, schedType, schedTime, schedRepeat,
                              schedInterval, schedLocation, schedOnEnter ->
                withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getInstance(this@AgentService).routineDao()
                    val routine = ai.opencyvis.db.RoutineEntity(
                        name = name,
                        icon = icon,
                        instruction = instr,
                        description = null,
                        category = "custom",
                        isPinned = false,
                        useCount = 0,
                        lastUsedAt = null,
                        createdAt = System.currentTimeMillis(),
                        sortOrder = 100,
                        scheduleEnabled = schedType != null,
                        triggerType = schedType,
                        scheduleHour = schedTime?.split(":")?.getOrNull(0)?.toIntOrNull(),
                        scheduleMinute = schedTime?.split(":")?.getOrNull(1)?.toIntOrNull(),
                        scheduleRepeatDays = when (schedRepeat) {
                            "daily", null -> null
                            "weekdays" -> "1,2,3,4,5"
                            else -> schedRepeat
                        },
                        intervalMinutes = schedInterval,
                        geoLocationName = schedLocation,
                        geoTriggerOnEnter = schedOnEnter
                    )
                    val id = dao.insertRoutine(routine).toInt()
                    if (schedType != null) {
                        ScheduleManager.register(this@AgentService, routine.copy(id = id))
                    }
                    Log.i(TAG, "Saved routine '$name' (id=$id, schedule=$schedType)")
                }
            }
        )
        try {
            val started = withStartupPermission(task.id) {
                engine = newEngine
                activeRunEngine = newEngine
                _engineFlow.value = newEngine
                observeHistoryWrites()
                observeAskUserNotifications()
                observeHandoffRequests()
                observeQueuedRunState(task.id, newEngine)
                startTaskDisplayGuard(vdm)
                startEngineRun(newEngine, task.instruction, resume, convId)
            }
            if (!started) return "interrupted"
        } finally {
            if (activeRunEngine !== newEngine) newEngine.destroy()
        }
        releaseOperationStart("agent")
        observeConnectorState(b)
        updateNotification("Running: ${task.instruction.take(30)}...")
        return awaitExecutionResult(newEngine)
    }

    internal suspend fun startEngineRun(current: AgentEngine, instruction: String,
        resume: TaskCheckpoint?, conversationId: Long) {
        current.start(instruction, resume)
        historyRepo.updateStatus(conversationId, "running")
    }

    internal suspend fun awaitExecutionResult(current: AgentEngine): String {
        current.awaitStopped()
        val outcome = current.lastOutcome ?: "interrupted"
        if (outcome == "failed" || outcome == "max_steps") {
            val detail = when (val state = current.state.value) {
                is AgentState.Error -> state.message
                is AgentState.Idle -> state.resultMessage
                else -> null
            }
            // Coordinator persists this message after runQueuedTask finishes its cleanup.
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() }
                ?: if (outcome == "max_steps") "任务达到最大执行步数" else "任务执行失败")
        }
        return outcome
    }

    private fun observeQueuedRunState(taskId: Long, current: AgentEngine) {
        runStateObserverJob?.cancel()
        runStateObserverJob = scope.launch {
            current.state.collect { state ->
                val status = when (state) {
                    is AgentState.Running -> TaskStatus.RUNNING
                    is AgentState.WaitingForUser, is AgentState.WaitingForHandoff -> TaskStatus.WAITING_USER
                    is AgentState.Paused -> TaskStatus.PAUSED
                    else -> null
                }
                if (status != null) taskCoordinator.reportState(taskId, status)
            }
        }
    }

    internal suspend fun settleRunHistory(taskId: Long, current: AgentEngine?) {
        // Cancellation can happen after outcome is set but before the terminal event is emitted.
        if (requestedStopTaskId != taskId && !shuttingDown &&
            current?.lastOutcome in setOf("completed", "failed", "max_steps")) {
            historyTerminalWritten?.await()
        }
        historyWrites.withLock { /* Drain a history write already in progress. */ }
    }

    internal suspend fun pauseCurrentRun() {
        val taskId = activeTaskId.value ?: return
        taskControl.withLock {
            pausedTaskId = taskId
            if (activeRunEngine == null) {
                if (startupResumeGate == null) startupResumeGate = CompletableDeferred()
            } else activeRunEngine?.pauseAndAwaitInput()
        }
    }

    internal suspend fun resumeCurrentRun() {
        val taskId = activeTaskId.value ?: return
        val current = taskControl.withLock {
            if (pausedTaskId == taskId) {
                pausedTaskId = null
                startupResumeGate?.complete(Unit)
                startupResumeGate = null
            }
            activeRunEngine
        } ?: return
        if (displayState == DisplayState.TAKEOVER || isTaskReviewRecording) {
            returnTaskControl("resume_task")
        } else if (!isLearning) current.resume()
    }

    /** A pause received before engine construction must hold the actual start as well. */
    internal suspend fun withStartupPermission(taskId: Long, start: suspend () -> Unit): Boolean {
        while (true) {
            val gate = taskControl.withLock {
                if (shuttingDown || requestedStopTaskId == taskId) return false
                if (pausedTaskId == taskId) {
                    startupResumeGate ?: CompletableDeferred<Unit>().also { startupResumeGate = it }
                } else {
                    start()
                    return true
                }
            }
            gate.await()
        }
    }

    internal suspend fun stopCurrentRun(suspendTask: Boolean) {
        val taskId = activeTaskId.value ?: return
        requestedStopTaskId = taskId
        val current = taskControl.withLock {
            if (activeRunTaskId != taskId || !activeRunOwnsDisplay) return@withLock null
            displayState = DisplayState.VIEW
            handoffMonitorJob?.cancel()
            activeRunEngine?.also { it.pauseAndAwaitInput() }
        }
        taskControl.withLock {
            if (activeRunId?.let(taskRecording::belongsTo) == true) {
                finishTaskRecording(if (suspendTask) "task_suspended" else "task_stopped")
            }
        }
        // onRunFinished takes taskControl itself: never hold it while joining the engine.
        if (current != null) current.stopAndJoin() else agentStartupJob?.cancelAndJoin()
    }

    private suspend fun stopRunObservers() {
        listOf(stateObserverJob, runStateObserverJob, historyObserverJob, historyQuestionJob,
            askObserverJob, handoffObserverJob, handoffMonitorJob).forEach { it?.cancelAndJoin() }
        stateObserverJob = null
        runStateObserverJob = null
        historyObserverJob = null
        historyQuestionJob = null
        askObserverJob = null
        handoffObserverJob = null
        handoffMonitorJob = null
        cancelAskNotification()
    }

    private suspend fun finishQueuedTask(task: TaskRecord, status: String) {
        // The coordinator calls this only after both run and stop have returned.
        if (requestedStopTaskId == task.id) requestedStopTaskId = null
        taskControl.withLock {
            if (pausedTaskId == task.id) {
                pausedTaskId = null
                startupResumeGate?.complete(Unit)
                startupResumeGate = null
            }
        }
        val record = taskRepository.get(task.id) ?: task
        record.conversationId?.let { conversationId ->
            val historyStatus = when (status) {
                TaskStatus.SUCCEEDED -> "completed"
                TaskStatus.FAILED -> "failed"
                TaskStatus.CANCELLED -> "stopped"
                TaskStatus.PAUSED -> "paused"
                else -> "interrupted"
            }
            historyRepo.updateStatus(conversationId, historyStatus)
        }
        if (status in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            TaskRunStore.releaseTaskRetention(this, task.id)
        }
        if (!shuttingDown) {
            val message = when (status) {
                TaskStatus.SUCCEEDED -> getString(R.string.notif_task_completed)
                TaskStatus.FAILED -> getString(R.string.notif_task_failed_title)
                TaskStatus.PAUSED -> "任务已挂起"
                TaskStatus.CANCELLED -> "任务已取消"
                else -> "任务已中断，可继续"
            }
            updateNotification(message)
            val inBackground = !androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (inBackground && status in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED)) {
                postCompletionNotification(message, record.instruction)
            }
        }
    }

    private class DebugLLMClient : LLMClientInterface {
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
            mapOf(
                "action_type" to "wait",
                "thought" to "debug wait",
                "memory_update" to emptyList<Any>(),
            )

        override fun shutdown() {}
    }

    internal fun debugStartRunningAgent() {
        check(!isAgentBusy) { "请先结束当前任务再运行调试" }
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        engine?.destroy()
        stateObserverJob?.cancel()
        historyObserverJob?.cancel()
        askObserverJob?.cancel()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        releaseAgentWakeLock()

        val b = backend ?: SystemBackend()
        var vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            vdm?.destroy()
            vdm = VirtualDisplayManager(this, b)
            val dm = resources.displayMetrics
            val displayId = vdm.create(
                width = dm.widthPixels,
                height = dm.heightPixels,
                dpi = dm.densityDpi
            )
            if (displayId == -1) {
                Log.e(TAG, "debugStartRunningAgent: failed to create VD")
                return
            }
            virtualDisplayManager = vdm
        }

        acquireAgentWakeLock()

        val displaySize = Point(vdm.width, vdm.height)
        val newEngine = AgentEngine(
            DebugLLMClient(),
            ActionExecutor(
                this,
                vdm.displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("debug_open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = config.blacklistedPackages
            ),
            config.maxSteps,
            vdm,
            debugMode = true,
            memoryManager = memoryManager,
            viewTreeProvider = { displayId, w, h ->
                VdAccessibilityService.captureViewTree(displayId, w, h)
            },
            blacklistedPackages = config.blacklistedPackages
        )
        engine = newEngine
        currentInstruction = "debug takeover verification"
        currentConversationId = null
        displayState = DisplayState.CHAT
        _engineFlow.value = newEngine
        newEngine.debugSimulateRunning()
        updateNotification("Debug agent running")
        observeStateForVdLifecycle()
        observeHandoffRequests()
        startTaskDisplayGuard(vdm)
        Log.i(TAG, "Debug running agent ready on display ${vdm.displayId}")
    }

    internal fun debugRepeatGuardTypeText() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x0f0f0f0f0f0f0f0fL)
        guard.recordExecuted(ai.opencyvis.action.Action.TypeText("京东"), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.TypeText("京东"), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard type_text decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard type_text decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapBlocked() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(ai.opencyvis.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.Tap(520, 590), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapAllowed() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(ai.opencyvis.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.Tap(520, 590), ScreenFingerprint(-1L))) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=ALLOW")
        }
    }

    private var askObserverJob: Job? = null

    /**
     * Watch for WaitingForUser state and post a high-priority heads-up notification
     * so the user sees the prompt even when OpenCyvis is in the background.
     */
    private fun observeAskUserNotifications() {
        askObserverJob?.cancel()
        val flow = engine?.state ?: return
        askObserverJob = scope.launch {
            var wasAsking = false
            flow.collect { state ->
                val asking = state is AgentState.WaitingForUser
                if (asking && !wasAsking) {
                    val q = (state as AgentState.WaitingForUser).question
                    postAskNotification(q)
                } else if (!asking && wasAsking) {
                    cancelAskNotification()
                }
                wasAsking = asking
            }
        }
    }

    private fun postAskNotification(question: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: ai.opencyvis.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_FOCUS_ASK, true)
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(getString(R.string.notif_needs_answer))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_open_app), pi
                ).build()
            )
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ASK_ID, notif)
    }

    private fun cancelAskNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ASK_ID)
    }

    private fun postCompletionNotification(title: String, body: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: ai.opencyvis.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pi = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_COMPLETED_ID, notif)
    }

    private fun cancelCompletionNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_COMPLETED_ID)
    }

    private fun observeHandoffRequests() {
        handoffObserverJob?.cancel()
        val flow = engine?.state ?: return
        handoffObserverJob = scope.launch {
            flow.collect { state ->
                if (state is AgentState.WaitingForHandoff) {
                    if (activeHandoffStep != state.step) {
                        activeHandoffStep = state.step
                        activeHandoffReason = state.reason
                        startUserHandoff(state.reason)
                    }
                } else if (activeHandoffStep != null && state !is AgentState.Paused) {
                    activeHandoffStep = null
                    activeHandoffReason = ""
                    handoffMonitorJob?.cancel()
                    _handoffUiState.value = HandoffUiState.Idle
                }
            }
        }
    }

    private fun startUserHandoff(reason: String) {
        Log.i(TAG, "Starting user handoff: $reason")
        _handoffUiState.value = HandoffUiState.Active(reason)
        enterViewMode(showHandoff = true)
        enterTakeoverMode()
        updateNotification("等待人工接管")
    }

    private fun startHandoffVisualMonitor(reason: String) {
        handoffMonitorJob?.cancel()
        if (isLearning) return
        val vdm = virtualDisplayManager ?: return
        val detector = HandoffVisualDetector()
        handoffMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var baseline: HandoffVisualDetector.Fingerprint? = null
            var stableTransitionCount = 0

            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                _handoffUiState.value = HandoffUiState.Active(reason, elapsed)

                val fingerprint = withContext(Dispatchers.IO) {
                    val bitmap = vdm.captureFrame(timeoutMs = 250)?.bitmap
                    try {
                        if (bitmap != null) detector.fingerprint(bitmap) else null
                    } finally {
                        bitmap?.recycle()
                    }
                }

                if (fingerprint != null) {
                    val base = baseline
                    if (base == null || base.width != fingerprint.width || base.height != fingerprint.height) {
                        baseline = fingerprint
                        stableTransitionCount = 0
                    } else if (detector.isHighConfidenceTransition(base, fingerprint)) {
                        stableTransitionCount++
                        if (stableTransitionCount >= 2) {
                            Log.i(TAG, "Handoff visual monitor detected high-confidence transition")
                            _handoffUiState.value = HandoffUiState.PendingReturn(
                                reason = getString(R.string.handoff_page_changed),
                                countdownSeconds = 0,
                                source = "visual_change_stable"
                            )
                            delay(900L)
                            completeUserHandoff("visual_change_stable")
                            return@launch
                        }
                    } else {
                        stableTransitionCount = 0
                    }
                }

                if (elapsed >= HANDOFF_TIMEOUT_MS) {
                    showPendingHandoffReturn(reason, "timeout")
                    return@launch
                }

                delay(HANDOFF_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun showPendingHandoffReturn(reason: String, source: String) {
        handoffMonitorJob?.cancel()
        handoffMonitorJob = scope.launch {
            for (remaining in HANDOFF_COUNTDOWN_SECONDS downTo 1) {
                _handoffUiState.value = HandoffUiState.PendingReturn(reason, remaining, source)
                delay(1_000L)
            }
            completeUserHandoff("${source}_countdown_elapsed")
        }
    }

    fun completeUserHandoff(source: String = "manual_return_control") {
        val owner = engine
        scope.launch {
            if (engine === owner) returnTaskControl(source)
        }
    }

    private suspend fun returnTaskControl(source: String, discard: Boolean = false) = taskControl.withLock {
        if (isLearning && !isTaskReviewRecording) return@withLock
        val current = engine ?: return@withLock
        if (activeRunTaskId != null && requestedStopTaskId == activeRunTaskId) return@withLock
        if (displayState != DisplayState.TAKEOVER && current.state.value !is AgentState.WaitingForHandoff) return@withLock
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        // Stop accepting touches before draining the recorder and giving the Agent control.
        displayState = DisplayState.VIEW
        val summary = finishTaskRecording(source, discard)
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        if (current.isTaskActive) {
            if (summary != null) current.resumeAfterHuman(
                "人工接管已结束，请重新观察当前页面后继续。以下是实际操作记录，不能单独证明此前 Agent 动作成功。\n$summary"
            ) else {
                current.completeUserHandoff(source)
                current.resume()
            }
        }
        updateNotification(if (current.isTaskActive) "Agent running" else getString(R.string.notif_task_completed))
    }

    private suspend fun finishTaskRecording(source: String, discard: Boolean = false): String? {
        return try { withContext(NonCancellable) { taskRecording.finish(source, discard) } }
        catch (failure: Exception) {
            Log.e(TAG, "Unable to save takeover recording", failure)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AgentService, "接管记录保存失败：${failure.message}", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    private suspend fun onTaskRunFinished(runId: String, outcome: String, context: Map<String, Any?>) {
        taskControl.withLock {
            if (taskRecording.belongsTo(runId)) {
                displayState = DisplayState.VIEW
                finishTaskRecording("task_$outcome")
            }
        }
        TaskReviewCoordinator(this).completeRun(runId, outcome, context)
    }

    fun cancelPendingHandoffReturn() {
        val reason = activeHandoffReason.ifBlank { getString(R.string.handoff_reason_default) }
        Log.i(TAG, "Cancelling pending handoff return")
        startHandoffVisualMonitor(reason)
    }

    /**
     * Observe step results and state changes to persist chat history.
     */
    private fun observeHistoryWrites() {
        historyObserverJob?.cancel()
        historyQuestionJob?.cancel()
        val eng = engine ?: return
        val convId = currentConversationId ?: return
        val ownerTaskId = activeRunTaskId
        val debug = config.debugMode
        var lastHistoryStatus: String? = null
        val terminalWritten = CompletableDeferred<Unit>()
        historyTerminalWritten = terminalWritten
        // Subscribe before engine.start, including runs that fail before the first observation.
        historyObserverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eng.stepResults.collect { result ->
                try {
                    historyWrites.withLock {
                        if (activeRunTaskId == ownerTaskId && result.success && !result.completed &&
                            shouldRefreshControlledTasks(result)) {
                            refreshControlledTasksFromVd("step_${result.step}_${result.actionType}")
                        }
                        if (debug) {
                            val prefix = if (result.success) "" else "[FAIL] "
                            historyRepo.addMessage(convId, MessageType.AGENT_STATUS,
                                "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}")
                            if (!result.debugInfo.isNullOrBlank()) {
                                historyRepo.addMessage(convId, MessageType.AGENT_DEBUG, "[LLM]\n${result.debugInfo}")
                            }
                        } else if (!result.completed) {
                            val userMsg = result.thought.ifBlank { result.detail }
                            if (userMsg != lastHistoryStatus) {
                                if (lastHistoryStatus == null) historyRepo.addMessage(convId, MessageType.AGENT_STATUS, userMsg)
                                else historyRepo.updateLastAgentStatus(convId, userMsg)
                                lastHistoryStatus = userMsg
                            }
                        }
                        if (result.completed) {
                            val summary = when (result.actionType) {
                                "finish" -> "✅ ${getString(R.string.task_complete, result.thought)}"
                                "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                                "max_steps" -> "⚠️ ${getString(R.string.task_max_steps, result.step)}"
                                "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                                else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                            }
                            if (!debug && lastHistoryStatus != null) {
                                historyRepo.updateLastAgentStatus(convId, summary)
                                historyRepo.updateLastAgentStatusToResult(convId)
                            } else historyRepo.addMessage(convId, MessageType.AGENT_RESULT, summary)
                            lastHistoryStatus = null
                        }
                    }
                    if (result.completed) terminalWritten.complete(Unit)
                } catch (failure: Exception) {
                    terminalWritten.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        historyQuestionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eng.state.collect { state ->
                historyWrites.withLock {
                    if (state is AgentState.WaitingForUser) {
                        if (!debug) lastHistoryStatus = null
                        historyRepo.addMessage(convId, MessageType.AGENT_QUESTION, state.question)
                    } else if (state is AgentState.WaitingForHandoff) {
                        if (!debug) lastHistoryStatus = null
                        historyRepo.addMessage(convId, MessageType.AGENT_STATUS,
                            getString(R.string.handoff_sensitive_input, state.reason))
                    }
                }
            }
        }
    }

    /**
     * Watch for agent completion. Keep VD alive for user inspection.
     */
    private fun observeStateForVdLifecycle() {
        stateObserverJob?.cancel()
        val flow = engine?.state ?: return

        stateObserverJob = scope.launch {
            var wasRunning = false
            flow.collect { state ->
                if (state is AgentState.Running) {
                    wasRunning = true
                }
                if (wasRunning && (state is AgentState.Idle || state is AgentState.Error)) {
                    Log.i(TAG, "Task completed, keeping virtual display for user inspection")
                    taskDisplayGuard?.stop()
                    taskDisplayGuard = null
                    releaseAgentWakeLock()

                    // Update foreground notification + post completion notification if in background
                    val isBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                        .lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.STARTED)
                    when (state) {
                        is AgentState.Idle -> {
                            updateNotification(getString(R.string.notif_task_completed))
                            if (isBackground) {
                                val body = state.resultMessage ?: currentInstruction
                                postCompletionNotification(
                                    getString(R.string.notif_task_completed), body)
                            }
                        }
                        is AgentState.Error -> {
                            updateNotification(getString(R.string.notif_task_failed_title))
                            if (isBackground) {
                                postCompletionNotification(
                                    getString(R.string.notif_task_failed_title), state.message)
                            }
                        }
                        else -> {}
                    }

                    stateObserverJob?.cancel()
                }
            }
        }
    }

    fun pauseAgent() {
        val taskId = activeTaskId.value
        if (taskId != null) pauseTask(taskId) else {
            engine?.pause()
            updateNotification("Agent paused")
        }
    }

    fun resumeAgent() {
        val taskId = activeTaskId.value
        if (taskId != null) resumeTask(taskId) else {
            if (displayState == DisplayState.TAKEOVER || isTaskReviewRecording) {
                completeUserHandoff("resume_task")
            } else if (!isLearning) engine?.resume()
        }
    }

    suspend fun retryBackendDetection(): Boolean {
        val result = BackendDetector.detect(this@AgentService)
        if (result is DetectionResult.Ready) {
            backend = result.backend
            ScreenCapture.backend = result.backend
            observeConnectorState(result.backend)
            // New PrivilegedService won't have the old VD's ImageReader — destroy stale VDM
            virtualDisplayManager?.destroy()
            virtualDisplayManager = null
            Log.i(TAG, "Backend re-detected: ${result.backend.capabilities.name}")
            return true
        }
        Log.w(TAG, "Backend re-detection failed: $result")
        return false
    }

    fun stopAgent() {
        val taskId = activeTaskId.value
        if (taskId != null) {
            cancelTask(taskId)
            return
        }
        scope.launch {
            taskControl.withLock {
                displayState = DisplayState.VIEW
                if (isTaskReviewRecording) finishTaskRecording("task_stopped")
                else if (isLearning) {
                    try { finishLearning("任务已停止，演示结果尚未确认") }
                    catch (failure: Exception) { Log.e(TAG, "Unable to save stopped demonstration", failure) }
                }
            }
            engine?.stopAndJoin()
            stopRunObservers()
            stopAgentAfterRecording()
        }
    }

    private suspend fun stopAgentAfterRecording() {
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        taskRescueInProgress = false
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        handoffObserverJob?.cancel()
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()
        engine?.stop()
        cancelAskNotification()
        currentConversationId = null
        currentInstruction = ""

        val vdm = virtualDisplayManager
        val rescueTaskId = vdm?.takeIf { it.isCreated }?.getTopTaskIdOnDisplay(vdm.displayId)
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null
        if (rescueTaskId != null) {
            val moved = CompletableDeferred<Unit>()
            Handler(Looper.getMainLooper()).post {
                try {
                    bringOpenCyvisUiToFront()
                    vdm?.moveTaskToDisplay(rescueTaskId, 0)
                    moved.complete(Unit)
                } catch (failure: Exception) {
                    moved.completeExceptionally(failure)
                }
            }
            // An old deferred move must finish before the next task can acquire the GUI.
            moved.await()
        }
        displayState = DisplayState.CHAT
        Log.i(TAG, "Agent stopped, display handover complete")
    }

    @Suppress("DEPRECATION")
    private fun acquireAgentWakeLock() {
        val current = agentWakeLock
        if (current?.isHeld == true) return

        try {
            val powerManager = getSystemService(PowerManager::class.java)
            agentWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:Agent"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "Agent wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire agent wake lock: ${e.message}")
            agentWakeLock = null
        }
    }

    private fun releaseAgentWakeLock() {
        val wakeLock = agentWakeLock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Agent wake lock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release agent wake lock: ${e.message}")
        } finally {
            agentWakeLock = null
        }
    }

    fun answerTask(taskId: Long, text: String): Boolean {
        if (activeTaskId.value != taskId || requestedStopTaskId == taskId) return false
        val current = activeRunEngine ?: return false
        if (current.state.value !is AgentState.WaitingForUser) return false
        recordUserInput(MessageType.USER_ANSWER, text)
        current.submitUserResponse(text)
        updateNotification("Agent running")
        return true
    }

    fun supplementTask(taskId: Long, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || activeTaskId.value != taskId || requestedStopTaskId == taskId) return false
        val current = activeRunEngine ?: return false
        if (!current.isTaskActive) return false
        recordUserInput(MessageType.USER_SUPPLEMENT, trimmed)
        current.submitUserSupplement(trimmed)
        return true
    }

    private fun recordUserInput(type: MessageType, text: String) {
        val conversationId = currentConversationId ?: return
        // Join the existing write queue before input can resume and finish the engine.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            historyWrites.withLock { historyRepo.addMessage(conversationId, type, text) }
        }
    }

    fun submitUserResponse(answer: String) {
        val taskId = activeTaskId.value
        if (taskId != null) answerTask(taskId, answer)
        else engine?.submitUserResponse(answer) // Standalone debug session.
    }

    fun submitUserSupplement(text: String) {
        val taskId = activeTaskId.value
        if (taskId != null) supplementTask(taskId, text)
        else engine?.submitUserSupplement(text) // Standalone debug session.
    }

    suspend fun startTeaching(goal: String, appName: String): String = withContext(Dispatchers.Default) {
        reserveOperationStart("teaching")
        try {
            require(goal.isNotBlank() && appName.isNotBlank()) { "请填写教学目标并选择 App" }
            backendDeferred.await()
            val inputBackend = checkNotNull(backend) { "请先连接无线调试或系统后端" }
            val vdm = virtualDisplayManager?.takeIf { it.isCreated } ?: run {
                val metrics = resources.displayMetrics
                VirtualDisplayManager(this@AgentService, inputBackend).also {
                    check(it.create(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) > 0) { "无法创建教学显示屏" }
                    virtualDisplayManager = it
                }
            }
            startTaskDisplayGuard(vdm)
            val opened = ActionExecutor(this@AgentService, vdm.displayId, Point(vdm.width, vdm.height),
                onOpenAppSuccess = { taskDisplayGuard?.trackLaunch(it) }, backend = inputBackend)
                .execute(Action.OpenApp(appName), 0)
            check(opened.success) { opened.detail }
            delay(700)
            refreshControlledTasksFromVd("teaching_open_app")
            val id = learning.start("demonstration", goal, vdm, JSONObject().put("initial_app", appName), appName)
            acquireAgentWakeLock()
            withContext(Dispatchers.Main) { enterViewMode(); displayState = DisplayState.TAKEOVER }
            updateNotification("正在录制教学 · 请在演示结束后保存")
            id
        } catch (failure: Exception) {
            cleanupLearningStart(failure)
            taskDisplayGuard?.stop()
            taskDisplayGuard = null
            releaseAgentWakeLock()
            throw failure
        } finally { releaseOperationStart("teaching") }
    }

    private suspend fun cleanupLearningStart(failure: Exception) = withContext(NonCancellable) {
        if (isLearning) {
            try { learning.finish("学习启动中断，演示尚未完成", false) }
            catch (saveFailure: Exception) { failure.addSuppressed(saveFailure) }
        }
    }

    suspend fun pauseLearning(paused: Boolean) = withContext(Dispatchers.IO) { learning.pause(paused) }

    suspend fun openLearningApp(appName: String) = withContext(Dispatchers.Default) {
        learning.input {
            check(isLearning) { "当前没有正在录制的演示或接管" }
            val vdm = checkNotNull(virtualDisplayManager)
            learning.prepareAppChange()
            val result = ActionExecutor(this@AgentService, vdm.displayId, Point(vdm.width, vdm.height),
                onOpenAppSuccess = { taskDisplayGuard?.trackLaunch(it) },
                backend = checkNotNull(backend)).execute(Action.OpenApp(appName), 0)
            learning.openedApp(appName, result.success)
            check(result.success) { result.detail }
            refreshControlledTasksFromVd("learning_open_app")
        }
    }

    suspend fun finishLearning(note: String = "", discard: Boolean = false): String? = withContext(NonCancellable + Dispatchers.IO) {
        val id = learningSessionId ?: return@withContext null
        if (isTaskReviewRecording) {
            returnTaskControl(if (discard) "recording_discarded" else "learning_page_return", discard)
            return@withContext id
        }
        finishingLearning = true
        displayState = DisplayState.VIEW
        try {
            learning.finish(note, discard)
            id
        } finally {
            try {
                displayState = DisplayState.VIEW
                taskDisplayGuard?.stop()
                taskDisplayGuard = null
                releaseAgentWakeLock()
                updateNotification("演示已结束")
            } finally {
                finishingLearning = false
            }
        }
    }

    fun recordLearningTouch(event: android.view.MotionEvent, accepted: Boolean, previewSize: Point) =
        learning.touch(event, accepted, previewSize)

    fun recordLearningKey(event: android.view.KeyEvent, accepted: Boolean) = learning.key(event, accepted)

    fun recordLearningText(inserted: String, deleted: Int, accepted: Boolean) = learning.text(inserted, deleted, accepted)

    suspend fun performHumanInput(block: suspend () -> Unit) = learning.input {
        if (displayState == DisplayState.TAKEOVER) block()
    }

    /**
     * Enter VIEW mode: launch ViewActivity with SurfaceView rendering VD content.
     * Agent keeps running — user can watch the agent work in real-time.
     * App stays on VD (no task reparenting).
     */
    fun enterViewMode(showHandoff: Boolean = false): Boolean {
        val vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            mainHandler.post {
                Toast.makeText(this, "No virtual display", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Prevent infinite recursion: if VD shows our own app, move it off the VD.
        // Without this, SurfaceView → VD → OpenCyvis UI → SurfaceView → ... (套娃)
        // Use backend (shell uid) for task queries — main process can't access hidden APIs.
        // getTopTaskIdOnDisplay returns -1 if only OpenCyvis tasks are on VD.
        // In that case we just proceed — the VD will show whatever was last rendered.

        // Launch ViewActivity — SurfaceView will connect to VD via setSurface()
        val intent = Intent(this, ai.opencyvis.ui.ViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (showHandoff) {
                putExtra(EXTRA_SHOW_HANDOFF, true)
                putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        startActivity(intent)

        displayState = DisplayState.VIEW
        updateNotification("Viewing agent operation")
        Log.i(TAG, "Entered VIEW mode (agent keeps running)")
        return true
    }

    /**
     * Enter TAKEOVER mode: pause agent, let user interact via touch forwarding.
     * Called by ViewActivity when user taps "Takeover".
     */
    fun enterTakeoverMode(onReady: (() -> Unit)? = null) {
        if (displayState != DisplayState.VIEW) return
        val owner = engine
        scope.launch {
            taskControl.withLock {
                if (engine !== owner || (activeRunTaskId != null && requestedStopTaskId == activeRunTaskId)) return@withLock
                val current = engine ?: return@withLock
                val display = virtualDisplayManager?.takeIf { it.isCreated } ?: return@withLock
                if (displayState != DisplayState.VIEW) return@withLock
                val handoff = current.state.value as? AgentState.WaitingForHandoff
                if (current.isTaskActive) {
                    current.pauseForHuman()
                    try {
                        taskRecording.start(display, current.handoverSnapshot(),
                            if (handoff == null) "manual_takeover" else "agent_handoff",
                            handoff?.kind ?: "unknown", handoff?.reason ?: "用户主动接管", handoff?.step)
                    } catch (failure: Exception) {
                        Log.e(TAG, "Unable to start takeover recording", failure)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AgentService, "接管录制未启动：${failure.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                displayState = DisplayState.TAKEOVER
                if (isTaskReviewRecording) {
                    handoffMonitorJob?.cancel()
                    updateNotification("正在记录接管操作 · 交还后继续任务")
                } else {
                    if (handoff != null) startHandoffVisualMonitor(handoff.reason)
                    updateNotification("Takeover mode")
                }
            }
            withContext(Dispatchers.Main) { onReady?.invoke() }
        }
    }

    /**
     * Exit TAKEOVER mode back to VIEW mode: resume agent.
     * Called by ViewActivity when user taps "Return Control".
     */
    fun exitTakeoverMode() {
        completeUserHandoff("manual_return_control")
    }

    /**
     * Return to CHAT mode: close ViewActivity, bring ControlPanelActivity to front.
     * Agent keeps running on VD.
     */
    fun returnToChat() {
        if (isLearning && !isTaskReviewRecording) return
        val owner = engine
        scope.launch {
            if (engine !== owner) return@launch
            returnTaskControl("return_to_chat")
            withContext(Dispatchers.Main) {
                if (engine !== owner || (activeRunTaskId != null && requestedStopTaskId == activeRunTaskId)) return@withContext
                val intent = Intent(this@AgentService, ai.opencyvis.ui.ControlPanelActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
                displayState = DisplayState.CHAT
                updateNotification(getString(R.string.lumi_notification_running))
                Log.i(TAG, "Returned to CHAT mode")
            }
        }
    }

    // ── VD task escape guard ─────────────────────────────────────────────

    private fun startTaskDisplayGuard(vdm: VirtualDisplayManager) {
        taskDisplayGuard?.stop()
        taskDisplayGuard = TaskDisplayGuard(
            context = this,
            vdm = vdm,
            scope = scope,
            onEscape = { task -> handleControlledTaskEscape(task) }
        ).also { it.start() }
    }

    private fun refreshControlledTasksFromVd(reason: String) {
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        // Use backend (shell uid) for task queries — main process can't access hidden APIs on real devices
        val b = backend ?: return
        val taskId = b.getTopTaskIdOnDisplay(vdm.displayId, packageName)
        if (taskId <= 0) {
            Log.i(TAG, "No controlled VD task found while refreshing ($reason)")
            return
        }
        taskDisplayGuard?.addControlledTaskId(taskId)
        Log.d(TAG, "Refreshed controlled VD task ($reason): #$taskId")
    }

    private fun TaskSnapshot.isHomeTask(): Boolean {
        val packages = setOfNotNull(basePackage, topPackage)
        if (packages.isEmpty()) return false
        return packages.any { it in homePackages() }
    }

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return setOfNotNull(packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName)
    }

    private fun shouldRefreshControlledTasks(result: StepResult): Boolean {
        if (result.actionType == "open_app") return true
        return taskDisplayGuard?.controlledTaskIdsSnapshot()?.isNotEmpty() == true
    }

    private fun handleControlledTaskEscape(task: TaskSnapshot) {
        if (taskRescueInProgress) return
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        if (task.containsPackage(packageName)) return

        taskRescueInProgress = true
        try {
            // TaskDisplayGuard already did moveTaskToDisplay; just bring our UI back to front
            Log.i(TAG, "Escape callback for task ${task.taskId}, bringing UI to front on Display 0")
            bringOpenCyvisUiToFront()
        } finally {
            taskRescueInProgress = false
        }
    }

    private fun bringOpenCyvisUiToFrontAfterRescue() {
        bringOpenCyvisUiToFront()
    }

    private fun bringOpenCyvisUiToFront() {
        val cls = when (displayState) {
            DisplayState.VIEW, DisplayState.TAKEOVER -> ai.opencyvis.ui.ViewActivity::class.java
            DisplayState.CHAT -> ai.opencyvis.ui.ControlPanelActivity::class.java
        }
        Log.i(TAG, "bringOpenCyvisUiToFront: ${cls.simpleName} on Display 0")
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (cls == ai.opencyvis.ui.ViewActivity::class.java) {
                putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        val options = android.app.ActivityOptions.makeBasic()
        try {
            options.setLaunchDisplayId(0)
        } catch (_: Exception) {}
        startActivity(intent, options.toBundle())
    }

    // ── Public accessors ────────────────────────────────────────────────

    val stateFlow: StateFlow<AgentState>?
        get() = engine?.state

    /** Non-null StateFlow for ImAgentBridge subscription (null value = no engine) */
    val decisionFlow: StateFlow<AgentState?>
        get() = engine?.state ?: MutableStateFlow(null)

    val stepResultFlow: SharedFlow<StepResult>?
        get() = engine?.stepResults

    fun getEngine(): AgentEngine? = engine

    fun getVirtualDisplayManager(): VirtualDisplayManager? = virtualDisplayManager

    /** Manual takeover must use the same authorized backend as agent actions. */
    fun getInputBackend(): PrivilegeBackend? = backend

    fun isOverlayActiveState(state: AgentState?): Boolean {
        return OverlayStatePolicy.isActive(state, displayState)
    }

    /**
     * Launch the secondary home/launcher on the given display so it's not blank.
     * Uses SECONDARY_HOME category — Android's designated launcher for non-default displays.
     */
    private fun launchHomeOnDisplay(displayId: Int) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_SECONDARY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val b = backend
            if (b is RemoteBackend) {
                val intentUri = homeIntent.toUri(Intent.URI_INTENT_SCHEME)
                b.startActivityOnDisplay(intentUri, displayId)
                Log.i(TAG, "Launched secondary home on display $displayId via backend")
            } else {
                val options = android.app.ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                startActivity(homeIntent, options.toBundle())
                Log.i(TAG, "Launched secondary home on display $displayId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch home on display $displayId: ${e.message}")
            // Fallback: try regular home
            try {
                val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                val b = backend
                if (b is RemoteBackend) {
                    val intentUri = fallbackIntent.toUri(Intent.URI_INTENT_SCHEME)
                    b.startActivityOnDisplay(intentUri, displayId)
                    Log.i(TAG, "Launched home (fallback) on display $displayId via backend")
                } else {
                    val opts = android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId)
                    startActivity(fallbackIntent, opts.toBundle())
                    Log.i(TAG, "Launched home (fallback) on display $displayId")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to launch home fallback on display $displayId: ${e2.message}")
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.lumi_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AI phone control agent service" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASK,
                getString(R.string.notif_channel_ask),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_ask_desc)
                enableVibration(true)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ai.opencyvis.ui.ControlPanelActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
