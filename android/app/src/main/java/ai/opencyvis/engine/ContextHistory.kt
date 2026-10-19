package ai.opencyvis.engine

import org.json.JSONObject

/** Immutable model-view events. TaskState remains the authority for current working state. */
internal class ContextHistory {
    data class Event(val number: Int, val recordedStep: Int, val sourceStep: Int, val kind: String, val text: String) {
        fun message(): Map<String, Any> = mapOf("role" to "user", "content" to
            "[history_event=$number; step=$sourceStep; kind=$kind; raw evidence available via recall_history]\n$text")
    }

    private val events = mutableListOf<Event>()
    private val lastStepViews = mutableMapOf<Int, String>()
    private var recordedUpdates = 0
    private var visibleFrom = 0
    var checkpoint: String? = null
        private set
    val visible: List<Event> get() = events.drop(visibleFrom)

    fun capture(task: TaskState, currentStep: Int, render: (TaskState.Step) -> String) {
        task.steps.values.filter { it.number < currentStep }.forEach { step ->
            val view = render(step)
            val previous = lastStepViews[step.number]
            if (previous != view) {
                events += Event(events.size + 1, if (previous == null) step.number else currentStep - 1,
                    step.number, if (previous == null) "action_result" else "result_update", view)
                lastStepViews[step.number] = view
            }
        }
        task.userUpdates.drop(recordedUpdates).forEachIndexed { index, update ->
            events += Event(events.size + 1, currentStep, currentStep, "user_update",
                JSONObject(update + ("memory_source_index" to recordedUpdates + index + 1)).toString())
        }
        recordedUpdates = task.userUpdates.size
    }

    /** Keep complete recent steps only at an explicit compaction boundary. */
    fun candidates(currentStep: Int, keepRecentSteps: Int = 6): List<Event> =
        visible.takeWhile { it.recordedStep < currentStep - keepRecentSteps }

    fun commit(compacted: List<Event>, newCheckpoint: String) {
        require(compacted.isNotEmpty() && visible.take(compacted.size) == compacted) {
            "Compaction must replace an unchanged history prefix"
        }
        visibleFrom += compacted.size
        checkpoint = newCheckpoint
    }
}
