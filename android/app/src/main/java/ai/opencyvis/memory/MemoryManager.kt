package ai.opencyvis.memory

import android.content.Context
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState

/** Application services are shared; all cursors, selections and caches belong to one run. */
class MemoryManager(context: Context) {
    internal val repository = MemoryRepository(context)
    internal val evidence = MemoryEvidenceStore(context)
    internal val jobs = MemoryJobs(context)

    internal fun session(task: TaskState, store: TaskRunStore): MemorySession = MemorySession(this, task, store)
}

internal data class MemoryRecall(val label: String, val text: String, val image: String? = null)
internal data class MemoryPacket(
    val text: String = "No relevant long-term memory.",
    val items: List<MemoryItem> = emptyList(),
    val recalled: List<MemoryRecall> = emptyList()
)
