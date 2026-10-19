package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.TaskRunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ConversationDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var historyRepo: ChatHistoryRepository
    private var conversationId: Long = -1
    private var conversationStatus: String = ""
    private var taskId: Long? = null
    private var linkedTask: TaskRecord? = null
    private var lastQuestion: String? = null

    // AgentService binding for active conversations
    private var agentService: AgentService? = null
    private var bound = false
    private var stateJob: Job? = null

    // Active controls
    private lateinit var activeControls: LinearLayout
    private lateinit var activeActionButtons: LinearLayout
    private lateinit var editActiveInput: EditText
    private lateinit var btnActiveSend: Button
    private lateinit var btnActiveView: Button
    private lateinit var btnActiveStop: Button
    private lateinit var chatAdapter: ChatAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            agentService = (binder as? AgentService.AgentBinder)?.getService()
            bound = true
            setupActiveControls()
            observeActiveState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            stateJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_detail)

        historyRepo = ChatHistoryRepository(this)
        conversationId = intent.getLongExtra("conversation_id", -1)
        taskId = intent.getLongExtra("task_id", -1).takeIf { it > 0 }
        val title = intent.getStringExtra("conversation_title") ?: ""
        conversationStatus = intent.getStringExtra("conversation_status") ?: ""
        val createdAt = intent.getLongExtra("conversation_created_at", 0)

        findViewById<TextView>(R.id.text_title).text = title
        findViewById<TextView>(R.id.text_subtitle).text = buildSubtitle(createdAt, conversationStatus)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btn_export_run).setOnClickListener { exportRunRecords() }

        // Chat messages
        chatAdapter = ChatAdapter()
        findViewById<RecyclerView>(R.id.recycler_messages).apply {
            layoutManager = LinearLayoutManager(this@ConversationDetailActivity)
            adapter = chatAdapter
        }

        // Active controls
        activeControls = findViewById(R.id.active_controls)
        activeActionButtons = findViewById(R.id.active_action_buttons)
        editActiveInput = findViewById(R.id.edit_active_input)
        btnActiveSend = findViewById(R.id.btn_active_send)
        btnActiveView = findViewById(R.id.btn_active_view)
        btnActiveStop = findViewById(R.id.btn_active_stop)

        // Load messages
        scope.launch {
            val messages = historyRepo.getMessages(conversationId)
            messages.forEach { entity ->
                val type = try { MessageType.valueOf(entity.type) } catch (_: Exception) { MessageType.SYSTEM }
                chatAdapter.addMessage(ChatMessage(type, entity.text, entity.timestamp))
            }
        }

        // Resolve the persisted task, including suspended and interrupted conversations.
        tryBindAgentService()
    }

    private fun tryBindAgentService() {
        bindService(
            Intent(this, AgentService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun setupActiveControls() {
        btnActiveSend.setOnClickListener {
            val svc = agentService ?: return@setOnClickListener
            val task = linkedTask ?: return@setOnClickListener
            val text = editActiveInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (!isConversationTaskActive(task, conversationId, svc.activeTaskId.value, svc.currentConversationId)) {
                showTaskChanged()
                return@setOnClickListener
            }
            val answering = svc.stateFlow?.value is AgentState.WaitingForUser
            val accepted = if (answering) svc.answerTask(task.id, text) else svc.supplementTask(task.id, text)
            if (!accepted) {
                showTaskChanged()
                return@setOnClickListener
            }
            chatAdapter.addMessage(ChatMessage(if (answering) MessageType.USER_ANSWER else MessageType.USER_SUPPLEMENT, text))
            editActiveInput.text.clear()
            scrollChatToBottom()
        }

        btnActiveView.setOnClickListener {
            val svc = agentService ?: return@setOnClickListener
            val task = linkedTask ?: return@setOnClickListener
            when {
                task.status == TaskStatus.INTERRUPTED || (task.status == TaskStatus.PAUSED && !task.guiHeld) ->
                    svc.recoverTask(task.id)
                !isConversationTaskActive(task, conversationId, svc.activeTaskId.value, svc.currentConversationId) ->
                    showTaskChanged()
                task.status == TaskStatus.PAUSED -> svc.resumeTask(task.id)
                else -> svc.enterViewMode()
            }
        }

        btnActiveStop.setOnClickListener {
            val task = linkedTask ?: return@setOnClickListener
            agentService?.cancelTask(task.id)
        }
    }

    private fun observeActiveState() {
        stateJob?.cancel()
        val svc = agentService ?: return
        stateJob = scope.launch {
            combine(svc.tasks, svc.activeTaskId, svc.engineFlow) { tasks, _, engine -> tasks to engine }
                .collectLatest { (tasks, engine) ->
                    linkedTask = if (taskId != null) {
                        tasks.find { it.id == taskId && it.conversationId == conversationId }
                    } else {
                        tasks.lastOrNull { it.conversationId == conversationId }?.also { taskId = it.id }
                    }
                    if (engine == null) renderTaskControls(null)
                    else engine.state.collect { renderTaskControls(it) }
                }
        }
    }

    private fun renderTaskControls(state: AgentState?) {
        val svc = agentService
        val task = linkedTask
        if (task == null || svc == null || task.status in TaskStatus.TERMINAL) {
            activeControls.visibility = View.GONE
            return
        }
        val active = isConversationTaskActive(task, conversationId, svc.activeTaskId.value, svc.currentConversationId)
        val recoverable = task.status == TaskStatus.INTERRUPTED || (task.status == TaskStatus.PAUSED && !task.guiHeld)
        activeControls.visibility = View.VISIBLE
        activeActionButtons.visibility = View.VISIBLE
        btnActiveStop.setText(if (task.status == TaskStatus.QUEUED) R.string.task_queue_cancel_queued else R.string.task_queue_cancel)
        btnActiveView.visibility = if (active || recoverable) View.VISIBLE else View.GONE
        btnActiveView.setText(when {
            recoverable -> R.string.task_queue_requeue
            task.status == TaskStatus.PAUSED -> R.string.hs_resume
            else -> R.string.hs_view
        })
        (editActiveInput.parent as View).visibility = if (active) View.VISIBLE else View.GONE
        editActiveInput.setHint(if (state is AgentState.WaitingForUser) R.string.hs_input_answer else R.string.hs_input_supplement)
        if (active && state is AgentState.WaitingForUser && lastQuestion != state.question) {
            lastQuestion = state.question
            chatAdapter.addMessage(ChatMessage(MessageType.AGENT_QUESTION, state.question))
            scrollChatToBottom()
        }
        findViewById<TextView>(R.id.text_subtitle).setText(taskStatusLabel(task))
    }

    private fun showTaskChanged() {
        Toast.makeText(this, R.string.task_queue_not_active, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildSubtitle(createdAt: Long, status: String): String {
        val time = DateUtils.getRelativeTimeSpanString(
            createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val statusLabel = conversationStatusLabel(this, status)
        return "$time · $statusLabel"
    }

    private fun confirmDelete() {
        MemoryUi.deleteConversation(this) { deleteMemories ->
            scope.launch {
                try {
                    val task = TaskRepository(this@ConversationDetailActivity).tasks.first()
                        .lastOrNull { it.conversationId == conversationId && (taskId == null || it.id == taskId) }
                    if (task != null && task.status !in TaskStatus.TERMINAL) {
                        val service = requireNotNull(agentService) { getString(R.string.task_queue_connecting) }
                        service.cancelTaskAndWait(task.id)
                    }
                    historyRepo.deleteConversation(conversationId, deleteMemories)
                    finish()
                } catch (error: Exception) { MemoryUi.error(this@ConversationDetailActivity, error) }
            }
        }
    }

    private fun exportRunRecords() {
        val button = findViewById<Button>(R.id.btn_export_run)
        button.isEnabled = false
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    TaskRunStore.exportConversation(this@ConversationDetailActivity, conversationId)
                }
                if (file == null) {
                    Toast.makeText(this@ConversationDetailActivity, R.string.run_export_empty, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val uri = FileProvider.getUriForFile(this@ConversationDetailActivity, "$packageName.run_exports", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(getString(R.string.run_export_title), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.run_export_title)))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(
                    this@ConversationDetailActivity,
                    getString(R.string.run_export_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun scrollChatToBottom() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_messages)
        recycler.post {
            val count = chatAdapter.itemCount
            if (count > 0) recycler.smoothScrollToPosition(count - 1)
        }
    }
}

/** Controls on a historical page must never follow a newer task into another conversation. */
internal fun isConversationTaskActive(
    task: TaskRecord?,
    conversationId: Long,
    activeTaskId: Long?,
    activeConversationId: Long?
): Boolean = task != null && task.id == activeTaskId && task.conversationId == conversationId &&
    activeConversationId == conversationId && task.guiHeld && task.status in TaskStatus.ACTIVE
