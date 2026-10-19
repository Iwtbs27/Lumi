package ai.opencyvis.task

import android.content.Context
import ai.opencyvis.db.AppDatabase
import kotlinx.coroutines.flow.Flow

interface TaskQueueStore {
    val tasks: Flow<List<TaskRecord>>
    suspend fun enqueue(request: TaskRequest): TaskRecord
    suspend fun get(id: Long): TaskRecord?
    suspend fun nextQueued(): TaskRecord?
    suspend fun updateStatus(
        id: Long,
        status: String,
        error: String? = null,
        guiHeld: Boolean = status in TaskStatus.ACTIVE
    ): Int
    suspend fun attachRun(id: Long, runId: String): Int
    suspend fun attachConversation(id: Long, conversationId: Long): Int
    suspend fun recoverInterrupted(): Int
    suspend fun requeue(id: Long): Int
}

class TaskRepository(
    private val dao: TaskDao,
    private val clock: () -> Long = System::currentTimeMillis
) : TaskQueueStore {
    constructor(context: Context) : this(AppDatabase.getInstance(context).taskDao())

    override val tasks: Flow<List<TaskRecord>> = dao.observeAll()

    override suspend fun enqueue(request: TaskRequest): TaskRecord {
        require(request.instruction.isNotBlank()) { "任务内容不能为空" }
        require(request.source.isNotBlank()) { "任务来源不能为空" }
        return dao.enqueue(request, clock())
    }

    override suspend fun get(id: Long): TaskRecord? = dao.get(id)

    override suspend fun nextQueued(): TaskRecord? = dao.nextQueued()

    override suspend fun updateStatus(id: Long, status: String, error: String?, guiHeld: Boolean): Int {
        require(status in TaskStatus.ALL) { "未知任务状态：$status" }
        return dao.updateStatus(id, status, error, guiHeld, clock())
    }

    override suspend fun attachRun(id: Long, runId: String): Int = dao.attachRun(id, runId, clock())

    override suspend fun attachConversation(id: Long, conversationId: Long): Int =
        dao.attachConversation(id, conversationId, clock())

    override suspend fun recoverInterrupted(): Int = dao.recoverInterrupted(clock())

    override suspend fun requeue(id: Long): Int = dao.requeue(id, clock())
}
