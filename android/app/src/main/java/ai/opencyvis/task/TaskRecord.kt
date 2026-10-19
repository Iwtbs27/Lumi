package ai.opencyvis.task

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object TaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_USER = "WAITING_USER"
    const val PAUSED = "PAUSED"
    const val INTERRUPTED = "INTERRUPTED"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    val ACTIVE = setOf(RUNNING, WAITING_USER, PAUSED)
    val TERMINAL = setOf(SUCCEEDED, FAILED, CANCELLED)
    val ALL = ACTIVE + TERMINAL + setOf(QUEUED, INTERRUPTED)
}

data class TaskRequest(
    val instruction: String,
    val source: String = "local",
    val remoteChatId: String? = null,
    val routineId: Long? = null,
    val conversationId: Long? = null
)

@Entity(tableName = "agent_tasks", indices = [Index(value = ["status", "queueOrder"])])
data class TaskRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instruction: String,
    val status: String,
    val source: String = "local",
    val remoteChatId: String? = null,
    val routineId: Long? = null,
    val conversationId: Long? = null,
    val runId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String? = null,
    val guiHeld: Boolean = false,
    val queueOrder: Long = 0
)
