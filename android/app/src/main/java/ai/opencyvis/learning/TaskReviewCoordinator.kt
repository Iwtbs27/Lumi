package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** A recorded handoff waits for its parent task, then joins the existing durable learning queue. */
class TaskReviewCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val learning = LearningRepository(appContext)
    private val memory = MemoryRepository(appContext)

    suspend fun completeRun(runId: String, outcome: String, finalContext: Map<String, Any?>) {
        val pending = learning.all().filter { it.parentRunId == runId && it.status == "awaiting_task" && TaskReviewPolicy.isAutomatic(it) }
        if (pending.isEmpty()) return
        val parent = memory.getEpisode(runId)
        val supplied = JSONObject(finalContext)
        val suppliedSteps = supplied.optJSONArray("continuation_steps")
        val steps = suppliedSteps ?: parent?.let { JSONArray(it.segmentsJson) } ?: JSONArray()
        for (session in pending) {
            val source = JSONObject(session.sourceJson)
            val recent = source.optJSONArray("recent_steps") ?: JSONArray()
            val handoffStep = if (source.has("handoff_step")) source.getInt("handoff_step") else
                (0 until recent.length()).maxOfOrNull { recent.getJSONObject(it).optInt("step") } ?: 0
            source.put("task_completion", JSONObject().put("outcome", outcome)
                .put("finished_at", System.currentTimeMillis())
                .put("summary", TaskReviewInput.excerpt(parent?.summary.orEmpty(), 1600))
                .put("final_state", TaskReviewInput.excerpt(supplied.optJSONObject("current_state")?.toString().orEmpty(), 2400))
                .put("continuation_source", when {
                    suppliedSteps != null -> "final_context"
                    parent != null -> "memory_episode"
                    else -> "unavailable"
                })
                .put("continuation", TaskReviewInput.continuation(steps, handoffStep,
                    if (source.isNull("human_intervention_count")) null else source.getInt("human_intervention_count"))))
            learning.put(session.copy(sourceJson = source.toString(), status = "queued", error = null,
                updatedAt = System.currentTimeMillis()))
            LearningJobs(appContext).enqueue(session.id)
        }
    }

    /** Process-local task identity prevents opening the learning page from completing a live run. */
    internal suspend fun recover() {
        learning.all().filter { it.status == "awaiting_task" && TaskReviewPolicy.isAutomatic(it) }
            .mapNotNull { it.parentRunId }.distinct().forEach { runId ->
                if (!TaskRunStore.isRunActive(runId)) {
                    val parent = memory.getEpisode(runId)
                    val recorded = TaskRunStore.readRunOutcome(appContext, runId) ?: parent?.status
                    val outcome = if (recorded == "running") "interrupted" else recorded ?: "unknown"
                    completeRun(runId, outcome, emptyMap())
                }
            }
    }
}
