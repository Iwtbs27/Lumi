package ai.opencyvis.remoteim

import android.util.Log
import ai.opencyvis.AgentService
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImAgentBridge(
    private val manager: ImChannelManager,
    private val config: ConfigRepository,
    private val stringProvider: ImStringProvider
) {
    companion object {
        private const val TAG = "ImAgentBridge"
        private const val DEBOUNCE_MS = 800L
        private const val TYPING_INTERVAL_MS = 4000L // Telegram typing expires after ~5s
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentService: AgentService? = null

    private data class Session(val taskId: Long, val channelId: String, val chatId: String)
    private var activeSession: Session? = null
    private var observerJob: Job? = null
    private var taskObserverJob: Job? = null
    private val runResults = mutableMapOf<Long, String?>()

    private val _state = MutableStateFlow<AgentState?>(null)
    val state: StateFlow<AgentState?> = _state.asStateFlow()

    private var debounceJob: kotlinx.coroutines.Job? = null
    private var typingJob: kotlinx.coroutines.Job? = null

    fun bind(agentService: AgentService) {
        this.agentService = agentService
        observeEngine(agentService)
        observeTaskOutcomes(agentService)
    }

    fun unbind() {
        observerJob?.cancel()
        taskObserverJob?.cancel()
        debounceJob?.cancel()
        activeSession = null
        agentService = null
        stopTypingIndicator()
        scope.launch { _state.value = null }
    }

    fun ownsActiveSession(channelId: String, chatId: String): Boolean =
        activeSession?.let { it.channelId == channelId && it.chatId == chatId &&
            agentService?.activeTaskId?.value == it.taskId } == true

    fun statusText(): String {
        val svc = agentService ?: return stringProvider.statusNoService()
        val st = _state.value

        val statusLine = when (st) {
            is AgentState.Idle -> stringProvider.statusIdle(st.resultMessage)
            is AgentState.Running -> stringProvider.statusRunning(st.step, st.thought)
            is AgentState.WaitingForUser -> stringProvider.statusWaitingUser(st.question)
            is AgentState.WaitingForHandoff -> stringProvider.statusWaitingHandoff(st.reason)
            is AgentState.Error -> stringProvider.statusError(st.message)
            is AgentState.Paused -> stringProvider.statusPaused()
            null -> stringProvider.statusIdle(null)
        }

        val deviceLine = stringProvider.statusDevice(
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE
        )
        val phoneLine = buildPhoneLine(svc)
        val llmLine = stringProvider.statusLlm(config.apiProvider, config.model)

        return listOfNotNull(statusLine, deviceLine, phoneLine, llmLine).joinToString("\n")
    }

    private fun buildPhoneLine(svc: AgentService): String? {
        val vdm = svc.getVirtualDisplayManager() ?: return null
        val tasks = vdm.getRunningTasks(limit = 5)
        val topTask = tasks.firstOrNull { it.displayId == vdm.displayId }
            ?: tasks.firstOrNull()
        val app = topTask?.topPackage ?: topTask?.basePackage ?: return null
        return stringProvider.statusPhone(app)
    }

    fun stop(channelId: String, chatId: String): Boolean {
        if (!ownsActiveSession(channelId, chatId)) return false
        agentService!!.cancelTask(activeSession!!.taskId)
        return true
    }

    suspend fun answerAskUser(text: String, channelId: String, chatId: String): Boolean {
        if (!ownsActiveSession(channelId, chatId)) return false
        return agentService!!.answerTask(activeSession!!.taskId, text)
    }

    suspend fun startTask(instruction: String, channelId: String, chatId: String): Long {
        val service = checkNotNull(agentService) { "Agent service is not connected" }
        return service.enqueueTask(TaskRequest(instruction, source = "im:$channelId", remoteChatId = chatId)).id
    }

    private fun observeEngine(svc: AgentService) {
        observerJob?.cancel()
        observerJob = scope.launch {
            svc.engineFlow.collectLatest { engine ->
                debounceJob?.cancel()
                stopTypingIndicator()
                activeSession = null
                if (engine == null) {
                    _state.value = null
                    return@collectLatest
                }
                val task = svc.activeTaskId.value?.let { svc.getTask(it) }
                val session = task?.takeIf { it.source.startsWith("im:") && it.remoteChatId != null }
                    ?.let { Session(it.id, it.source.removePrefix("im:"), it.remoteChatId!!) }
                activeSession = session
                coroutineScope {
                    launch {
                        engine.state.collect { st ->
                            _state.value = st
                            if (session != null) onStateChanged(st, session)
                        }
                    }
                    if (session != null) launch {
                        engine.stepResults.collect { result -> onStepResult(result, session) }
                    }
                    if (session != null) launch {
                        engine.stepScreenshots.collect { bytes -> sendScreenshot(bytes, session) }
                    }
                }
            }
        }
    }

    private suspend fun onStateChanged(state: AgentState, session: Session) {
        when (state) {
            is AgentState.Running -> startTypingIndicator(session)
            is AgentState.WaitingForUser -> {
                stopTypingIndicator()
                sendForced(stringProvider.notifyAskUser(state.question), session)
            }
            is AgentState.WaitingForHandoff -> {
                stopTypingIndicator()
                sendForced(stringProvider.notifyHandoff(state.reason), session)
            }
            is AgentState.Paused -> stopTypingIndicator()
            is AgentState.Idle -> {
                stopTypingIndicator()
                runResults[session.taskId] = state.resultMessage
            }
            is AgentState.Error -> {
                stopTypingIndicator()
                runResults[session.taskId] = state.message
            }
        }
    }

    private fun observeTaskOutcomes(svc: AgentService) {
        taskObserverJob?.cancel()
        taskObserverJob = scope.launch {
            var previous: Map<Long, String>? = null
            svc.tasks.collect { tasks ->
                val known = previous
                previous = tasks.associate { it.id to it.status }
                if (known != null) for (task in tasks) {
                    if (known[task.id] == task.status || task.status !in TaskStatus.TERMINAL ||
                        !task.source.startsWith("im:") || task.remoteChatId == null) continue
                    val result = runResults.remove(task.id)
                    val text = when (task.status) {
                        TaskStatus.SUCCEEDED -> stringProvider.notifyTaskComplete(result)
                        TaskStatus.CANCELLED -> stringProvider.stopped()
                        else -> stringProvider.notifyError(task.error ?: result.orEmpty())
                    }
                    sendForced(text, Session(task.id, task.source.removePrefix("im:"), task.remoteChatId))
                }
            }
        }
    }

    private fun startTypingIndicator(session: Session) {
        if (typingJob?.isActive == true) return
        val ch = session.channelId
        val chat = session.chatId
        typingJob = scope.launch {
            while (true) {
                try {
                    manager.sendTyping(ch, chat)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "typing indicator failed: ${e.message}")
                }
                delay(TYPING_INTERVAL_MS)
            }
        }
    }

    private fun stopTypingIndicator() {
        typingJob?.cancel()
        typingJob = null
    }

    private fun onStepResult(result: StepResult, session: Session) {
        // Durable task status sends the final outcome after the run has finished cleanup.
        if (result.completed) return

        val thought = result.thought
        if (thought.isBlank()) return

        // debounce: only send the last message within the debounce window
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            sendTextSafe(thought, session)
        }
    }

    private suspend fun sendForced(text: String, session: Session) {
        val ch = session.channelId
        val chat = session.chatId
        try {
            manager.sendText(ch, chat, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "sendForced failed, retrying once", e)
            try {
                delay(1000)
                manager.sendText(ch, chat, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e2: Exception) {
                Log.e(TAG, "sendForced retry failed", e2)
            }
        }
    }

    private suspend fun sendScreenshot(bytes: ByteArray, session: Session) {
        val ch = session.channelId
        val chat = session.chatId
        try {
            manager.sendPhoto(ch, chat, bytes, "")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "sendScreenshot failed: ${e.message}")
        }
    }

    private suspend fun sendTextSafe(text: String, session: Session) {
        val ch = session.channelId
        val chat = session.chatId
        try {
            manager.sendText(ch, chat, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "sendText skipped: ${e.message}")
        }
    }
}
