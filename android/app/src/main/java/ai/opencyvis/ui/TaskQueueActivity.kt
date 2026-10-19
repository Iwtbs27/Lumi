package ai.opencyvis.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import ai.opencyvis.AgentService
import ai.opencyvis.App
import ai.opencyvis.R
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.team.DelegationRecord
import ai.opencyvis.team.DelegationStatus
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal enum class TaskQueueAction(val label: Int) {
    PAUSE(R.string.task_queue_pause), RESUME(R.string.task_queue_resume),
    PARK(R.string.task_queue_park), REQUEUE(R.string.task_queue_requeue),
    CANCEL(R.string.task_queue_cancel), REPLY(R.string.task_queue_reply),
    HANDOFF(R.string.task_queue_handoff), OPEN(R.string.task_queue_open),
    TEAM(R.string.team_task_title)
}

internal fun taskQueueActions(
    task: TaskRecord, activeId: Long?, state: AgentState? = null, hasChildren: Boolean = false
): List<TaskQueueAction> = buildList {
    val active = task.id == activeId
    when {
        active && task.status == TaskStatus.WAITING_USER -> {
            when (state) {
                is AgentState.WaitingForUser -> add(TaskQueueAction.REPLY)
                is AgentState.WaitingForHandoff -> add(TaskQueueAction.HANDOFF)
                else -> Unit
            }
            add(TaskQueueAction.PAUSE); add(TaskQueueAction.PARK)
        }
        active && task.status == TaskStatus.RUNNING -> {
            add(TaskQueueAction.PAUSE); add(TaskQueueAction.PARK)
        }
        active && task.status == TaskStatus.PAUSED && task.guiHeld -> {
            add(TaskQueueAction.RESUME); add(TaskQueueAction.PARK)
        }
        task.status == TaskStatus.INTERRUPTED || (task.status == TaskStatus.PAUSED && !task.guiHeld) ->
            add(TaskQueueAction.REQUEUE)
    }
    if (task.status !in TaskStatus.TERMINAL) add(TaskQueueAction.CANCEL)
    if (task.conversationId != null) add(TaskQueueAction.OPEN)
    if (active && task.status in TaskStatus.ACTIVE && hasChildren) add(TaskQueueAction.TEAM)
}

internal fun taskStatusLabel(task: TaskRecord): Int = when (task.status) {
    TaskStatus.QUEUED -> R.string.task_queue_queued
    TaskStatus.RUNNING -> R.string.task_queue_running
    TaskStatus.WAITING_USER -> R.string.task_queue_waiting
    TaskStatus.PAUSED -> if (task.guiHeld) R.string.task_queue_paused else R.string.task_queue_parked
    TaskStatus.INTERRUPTED -> R.string.task_queue_interrupted
    TaskStatus.SUCCEEDED -> R.string.task_queue_succeeded
    TaskStatus.FAILED -> R.string.task_queue_failed
    TaskStatus.CANCELLED -> R.string.task_queue_cancelled
    else -> error("Unknown task status: ${task.status}")
}

class TaskQueueActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: TaskRepository
    private lateinit var adapter: TaskQueueAdapter
    private lateinit var input: EditText
    private var service: AgentService? = null
    private var bindingRegistered = false
    private var activeJob: Job? = null
    private var activeId: Long? = null
    private var activeState: AgentState? = null
    private var activeEngine: AgentEngine? = null
    private var children = emptyList<DelegationRecord>()
    private var teamDialog: TeamTaskDialog? = null
    private var records = emptyList<TaskRecord>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            observeService((binder as? AgentService.AgentBinder)?.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) = observeService(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_queue)
        findViewById<MaterialToolbar>(R.id.task_queue_toolbar).setNavigationOnClickListener { finish() }
        repository = TaskRepository(this)
        input = findViewById(R.id.task_queue_input)
        adapter = TaskQueueAdapter(::performAction)
        findViewById<RecyclerView>(R.id.task_queue_list).apply {
            layoutManager = LinearLayoutManager(this@TaskQueueActivity)
            adapter = this@TaskQueueActivity.adapter
        }
        findViewById<MaterialButton>(R.id.task_queue_add).setOnClickListener { addTask() }
        scope.launch {
            repository.tasks.collect {
                records = it
                render()
            }
        }
        observeService(App.agentService)
        try {
            startAndBindService()
        } catch (error: Exception) {
            notice(R.string.task_queue_start_failed)
        }
    }

    private fun startAndBindService(taskId: Long? = null) {
        val intent = Intent(this, AgentService::class.java)
        if (taskId != null) intent.putExtra("task_id", taskId)
        startForegroundService(intent)
        if (!bindingRegistered) bindingRegistered = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeService(value: AgentService?) {
        activeJob?.cancel()
        closeTeamDialog()
        service = value
        activeId = value?.activeTaskId?.value
        activeEngine = value?.engineFlow?.value
        activeState = value?.stateFlow?.value
        children = activeEngine?.childAgents?.value.orEmpty().filter { it.request.rootTaskId == activeId }
        render()
        if (value != null) activeJob = scope.launch {
            combine(value.activeTaskId, value.engineFlow) { id, engine -> id to engine }.collectLatest { (id, engine) ->
                if (activeId != id || activeEngine !== engine) closeTeamDialog()
                activeId = id
                activeEngine = engine
                activeState = engine?.state?.value
                children = engine?.childAgents?.value.orEmpty().filter { it.request.rootTaskId == id }
                render()
                if (engine != null) {
                    combine(engine.state, engine.childAgents) { state, helpers -> state to helpers }.collect { (state, helpers) ->
                        activeState = state
                        children = helpers.filter { it.request.rootTaskId == id }
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.task_queue_connection).setText(
            if (service == null) R.string.task_queue_connecting else R.string.task_queue_help
        )
        findViewById<TextView>(R.id.task_queue_empty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        val ordered = records.sortedBy {
            when {
                it.id == activeId -> 0
                it.status == TaskStatus.QUEUED -> 1
                it.status !in TaskStatus.TERMINAL -> 2
                else -> 3
            }
        }
        adapter.submit(ordered, activeId, activeState, children, service != null)
        if (records.none { it.id == activeId && it.status in TaskStatus.ACTIVE } || children.isEmpty()) closeTeamDialog()
        else teamDialog?.update(children)
    }

    private fun addTask() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            input.error = getString(R.string.task_queue_input_required)
            return
        }
        val button = findViewById<MaterialButton>(R.id.task_queue_add)
        button.isEnabled = false
        scope.launch {
            try {
                // Persist first: a delayed bind or failed service start must not lose the user's task.
                val task = repository.enqueue(TaskRequest(text))
                input.text.clear()
                try {
                    startAndBindService(task.id)
                } catch (error: Exception) {
                    notice(R.string.task_queue_saved_start_failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Snackbar.make(input, getString(R.string.task_queue_error, error.message), Snackbar.LENGTH_LONG).show()
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun performAction(task: TaskRecord, action: TaskQueueAction) {
        if (action == TaskQueueAction.OPEN) {
            val conversationId = task.conversationId ?: return
            startActivity(Intent(this, ConversationDetailActivity::class.java)
                .putExtra("task_id", task.id).putExtra("conversation_id", conversationId)
                .putExtra("conversation_title", task.instruction)
                .putExtra("conversation_created_at", task.createdAt))
            return
        }
        val current = service
        if (current == null) {
            notice(R.string.task_queue_connecting)
            return
        }
        when (action) {
            TaskQueueAction.PAUSE -> current.pauseTask(task.id)
            TaskQueueAction.RESUME -> current.resumeTask(task.id)
            TaskQueueAction.REQUEUE -> current.recoverTask(task.id)
            TaskQueueAction.CANCEL -> current.cancelTask(task.id)
            TaskQueueAction.PARK -> AlertDialog.Builder(this)
                .setTitle(R.string.task_queue_park).setMessage(R.string.task_queue_park_explanation)
                .setPositiveButton(R.string.task_queue_park) { _, _ -> current.suspendTaskAndRunNext(task.id) }
                .setNegativeButton(android.R.string.cancel, null).show()
            TaskQueueAction.REPLY -> reply(task.id, current)
            TaskQueueAction.HANDOFF -> {
                if (current.activeTaskId.value == task.id && current.stateFlow?.value is AgentState.WaitingForHandoff) {
                    current.enterViewMode(showHandoff = true)
                } else notice(R.string.task_queue_not_active)
            }
            TaskQueueAction.TEAM -> showTeam(task.id, current)
            TaskQueueAction.OPEN -> Unit
        }
    }

    private fun showTeam(taskId: Long, current: AgentService) {
        val engine = current.getEngine()
        if (current.activeTaskId.value != taskId || engine == null || engine !== activeEngine) {
            notice(R.string.task_queue_not_active)
            return
        }
        val helpers = engine.childAgents.value.filter { it.request.rootTaskId == taskId }
        if (helpers.isEmpty()) return
        closeTeamDialog()
        teamDialog = TeamTaskDialog(this) { childId ->
            // A detail view may outlive its run. Never route its button into a replacement engine.
            if (service === current && current.activeTaskId.value == taskId && current.getEngine() === engine) {
                current.getEngine()?.cancelChild(childId)
            } else notice(R.string.task_queue_not_active)
        }.also { it.show(helpers) }
    }

    private fun closeTeamDialog() {
        teamDialog?.dismiss()
        teamDialog = null
    }

    private fun reply(taskId: Long, current: AgentService) {
        val waiting = (current.stateFlow?.value as? AgentState.WaitingForUser)
            ?.takeIf { current.activeTaskId.value == taskId }
        if (waiting == null) {
            notice(R.string.task_queue_not_active)
            return
        }
        val answer = EditText(this).apply { setHint(R.string.task_queue_reply_hint); minLines = 2 }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.task_queue_reply).setView(answer)
            .setMessage(waiting.question)
            .setPositiveButton(R.string.hs_send, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = answer.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (current.answerTask(taskId, text)) dialog.dismiss()
                    else notice(R.string.task_queue_not_active)
                }
            }
        }
        dialog.show()
    }

    private fun notice(message: Int) = Snackbar.make(input, message, Snackbar.LENGTH_LONG).show()

    override fun onDestroy() {
        closeTeamDialog()
        if (bindingRegistered) unbindService(connection)
        scope.cancel()
        super.onDestroy()
    }
}

private class TaskQueueAdapter(
    private val action: (TaskRecord, TaskQueueAction) -> Unit
) : RecyclerView.Adapter<TaskQueueAdapter.Holder>() {
    private var records = emptyList<TaskRecord>()
    private var activeId: Long? = null
    private var activeState: AgentState? = null
    private var children = emptyList<DelegationRecord>()
    private var connected = false

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    fun submit(value: List<TaskRecord>, active: Long?, state: AgentState?, helpers: List<DelegationRecord>, serviceConnected: Boolean) {
        records = value
        activeId = active
        activeState = state
        children = helpers
        connected = serviceConnected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_task_queue, parent, false)
    )

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val task = records[position]
        val view = holder.itemView
        val context = view.context
        view.findViewById<TextView>(R.id.task_queue_instruction).text = task.instruction
        val statusLabel = if (task.id == activeId && activeState is AgentState.WaitingForHandoff)
            R.string.execution_handoff else taskStatusLabel(task)
        val taskChildren = if (task.id == activeId && task.status in TaskStatus.ACTIVE)
            children.filter { it.request.rootTaskId == task.id } else emptyList()
        view.findViewById<TextView>(R.id.task_queue_status).text = context.getString(statusLabel).let {
            if (task.id == activeId) context.getString(R.string.task_queue_current, it) else it
        }.let { status ->
            if (taskChildren.isEmpty()) status else status + "\n" + context.getString(
                R.string.team_task_summary,
                taskChildren.count { it.status in DelegationStatus.IN_FLIGHT },
                taskChildren.count { it.status == DelegationStatus.COMPLETED }
            )
        }
        view.findViewById<TextView>(R.id.task_queue_error).apply {
            text = task.error
            visibility = if (task.error.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val actions = taskQueueActions(task, activeId, activeState, taskChildren.isNotEmpty())
        view.findViewById<View>(R.id.task_queue_actions).visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<MaterialButton>(R.id.task_queue_primary).apply {
            val first = actions.firstOrNull()
            if (first != null) setText(if (task.status == TaskStatus.QUEUED && first == TaskQueueAction.CANCEL)
                R.string.task_queue_cancel_queued else first.label)
            isEnabled = connected || first == TaskQueueAction.OPEN
            setOnClickListener { first?.let { action(task, it) } }
        }
        view.findViewById<MaterialButton>(R.id.task_queue_more).apply {
            visibility = if (actions.size > 1) View.VISIBLE else View.GONE
            contentDescription = context.getString(R.string.task_queue_more_for, task.instruction)
            setOnClickListener {
                PopupMenu(context, this).apply {
                    actions.drop(1).forEachIndexed { index, item ->
                        menu.add(0, index, index, item.label).isEnabled = connected || item == TaskQueueAction.OPEN
                    }
                    setOnMenuItemClickListener { item -> action(task, actions[item.itemId + 1]); true }
                    show()
                }
            }
        }
    }
}
