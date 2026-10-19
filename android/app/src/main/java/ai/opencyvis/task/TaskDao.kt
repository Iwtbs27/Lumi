package ai.opencyvis.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY queueOrder ASC, id ASC")
    abstract fun observeAll(): Flow<List<TaskRecord>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    abstract suspend fun get(id: Long): TaskRecord?

    @Query("SELECT * FROM agent_tasks WHERE status = 'QUEUED' ORDER BY queueOrder ASC, id ASC LIMIT 1")
    abstract suspend fun nextQueued(): TaskRecord?

    @Insert
    protected abstract suspend fun insert(task: TaskRecord): Long

    @Query("SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM agent_tasks")
    protected abstract suspend fun nextQueueOrder(): Long

    @Transaction
    open suspend fun enqueue(request: TaskRequest, now: Long): TaskRecord {
        val task = TaskRecord(
            instruction = request.instruction,
            status = TaskStatus.QUEUED,
            source = request.source,
            remoteChatId = request.remoteChatId,
            routineId = request.routineId,
            conversationId = request.conversationId,
            createdAt = now,
            updatedAt = now,
            queueOrder = nextQueueOrder()
        )
        return task.copy(id = insert(task))
    }

    @Query("""
        UPDATE agent_tasks SET status = :status, error = :error, guiHeld = :guiHeld, updatedAt = :now
        WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
    """)
    abstract suspend fun updateStatus(id: Long, status: String, error: String?, guiHeld: Boolean, now: Long): Int

    @Query("UPDATE agent_tasks SET runId = :runId, updatedAt = :now WHERE id = :id")
    abstract suspend fun attachRun(id: Long, runId: String, now: Long): Int

    @Query("UPDATE agent_tasks SET conversationId = :conversationId, updatedAt = :now WHERE id = :id")
    abstract suspend fun attachConversation(id: Long, conversationId: Long, now: Long): Int

    @Query("""
        UPDATE agent_tasks SET status = 'INTERRUPTED', guiHeld = 0, updatedAt = :now
        WHERE status IN ('RUNNING', 'WAITING_USER') OR (status = 'PAUSED' AND guiHeld = 1)
    """)
    abstract suspend fun recoverInterrupted(now: Long): Int

    @Query("""
        UPDATE agent_tasks SET status = 'QUEUED', error = NULL, guiHeld = 0,
            queueOrder = :queueOrder, updatedAt = :now
        WHERE id = :id AND status IN ('PAUSED', 'INTERRUPTED')
    """)
    protected abstract suspend fun moveToQueue(id: Long, queueOrder: Long, now: Long): Int

    @Transaction
    open suspend fun requeue(id: Long, now: Long): Int = moveToQueue(id, nextQueueOrder(), now)
}
