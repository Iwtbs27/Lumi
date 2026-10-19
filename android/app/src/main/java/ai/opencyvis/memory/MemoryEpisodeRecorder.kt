package ai.opencyvis.memory

import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState
import org.json.JSONArray
import org.json.JSONObject

/** Copies compact observations before the original run archive can be pruned. */
internal class MemoryEpisodeRecorder(
    private val manager: MemoryManager, private val task: TaskState, private val store: TaskRunStore
) {
    private val createdAt = System.currentTimeMillis()
    private val observations = linkedMapOf<Int, Map<String, Any?>>()
    private val interventions = mutableMapOf<Int, Int>()

    fun observe(step: Int) { interventions[step] = task.humanInterventionCount }

    fun independentBetween(actionStep: Int, observationStep: Int): Boolean =
        interventions[actionStep] != null && interventions[actionStep] == interventions[observationStep]

    suspend fun checkpoint(step: Int, status: String = "running") {
        val record = task.steps[step]?.takeIf { step >= task.runStartStep }
        if (record != null) observations[step] = task.memoryContext.toMap()
        val records = task.steps.values.filter { it.number >= task.runStartStep }.map { entry ->
            mapOf("step" to entry.number,
                "human_intervention_count" to interventions[entry.number],
                "action" to entry.decision.filterKeys { it !in TaskState.RESPONSE_METADATA || it == "thought" },
                "execution" to entry.execution, "effect" to entry.effect,
                "previous_action_result" to entry.decision["previous_action_result"],
                "memory_context" to observations[entry.number], "memory_update" to entry.decision["memory_update"],
                "memory_use" to entry.decision["memory_use"], "memory_feedback" to entry.decision["memory_feedback"],
                "procedure_update" to entry.decision["procedure_update"],
                "plan" to entry.decision["plan"],
                "user_updates" to task.userUpdates.filter { it["step"] == entry.number })
        }
        val finalState = mapOf("kind" to "task_state", "step" to step,
            "task_memory" to task.memories, "plan" to task.plan, "user_updates" to task.userUpdates,
            "procedure_execution" to task.procedureExecution)
        val statusValue = when (status) { "running", "completed" -> status; "failed", "max_steps" -> "failed"; else -> "interrupted" }
        val summary = if (status == "running") "" else record?.decision?.let {
            (it["reason"] ?: it["thought"]) as? String
        }.orEmpty()
        manager.repository.saveEpisode(MemoryEpisode(id = store.runId, conversationId = store.conversationId,
            instruction = task.instruction, summary = summary, status = statusValue,
            resultStatus = if (records.any { (it["effect"] as? Map<*, *>)?.get("status") == "confirmed_success" }) "partial" else "unknown",
            segmentsJson = JSONArray(records + finalState).toString(),
            appScope = task.memoryContext["app"] as? String, createdAt = createdAt, updatedAt = System.currentTimeMillis()))
        if (record != null) {
            val effect = record.decision["previous_action_result"] as? Map<*, *>
            val acceptedFeedback = manager.repository.runUsages(store.runId).any {
                it.event == "outcome" && it.evidenceStep == step && it.outcome in setOf("success", "failure")
            }
            val observed = effect?.get("status") in setOf("confirmed_success", "confirmed_failure") ||
                (record.decision["memory_update"] as? List<*>)?.isNotEmpty() == true || acceptedFeedback
            val important = observed || record.decision["action_type"] in setOf("finish", "fail", "update_plan", "open_app") ||
                task.userUpdates.any { it["step"] == step }
            manager.evidence.save(store.runId, step, JSONObject(records.last()).toString(),
                imageBase64 = if (important) store.readImage(step) else null,
                tree = if (important) store.readTree(step) else null,
                author = if (observed) "model_observed" else "model_report",
                actor = if (effect != null && !independentBetween((effect["step"] as Number).toInt(), step)) "mixed" else "agent")
        }
    }
}
