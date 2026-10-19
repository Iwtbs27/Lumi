package ai.opencyvis.engine

import ai.opencyvis.llm.ResponseParser
import org.json.JSONObject

/** Durable authority for a continuation; observations and model-view caches are recreated. */
data class TaskCheckpoint(
    val instruction: String,
    val sourceRunId: String,
    val nextStep: Int,
    val state: Map<String, Any?>,
    val steps: List<Map<String, Any?>>,
    val userUpdates: List<Map<String, Any?>>,
    val sourceRuns: List<String>,
    val stepSources: Map<Int, String>,
    val pendingUserInputs: List<String> = emptyList(),
    val pendingUserAnswer: String? = null,
    val pendingFeedback: String? = null,
    val waitingContext: Map<String, Any?> = emptyMap(),
    val traceStep: Int = 0,
    val traceEventCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject(mapOf(
        "schema_version" to 1, "instruction" to instruction, "source_run_id" to sourceRunId,
        "next_step" to nextStep, "state" to state, "steps" to steps, "user_updates" to userUpdates,
        "source_runs" to sourceRuns, "step_sources" to stepSources.mapKeys { it.key.toString() },
        "pending_user_inputs" to pendingUserInputs, "pending_user_answer" to pendingUserAnswer,
        "pending_feedback" to pendingFeedback, "waiting_context" to waitingContext,
        "trace_step" to traceStep, "trace_event_count" to traceEventCount
    ))

    companion object {
        fun fromJson(json: JSONObject): TaskCheckpoint {
            require(json.getInt("schema_version") == 1) { "Unsupported task checkpoint version" }
            val value = ResponseParser.jsonObjectToMap(json)
            @Suppress("UNCHECKED_CAST")
            fun rows(key: String) = value[key] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            fun record(key: String) = value[key] as Map<String, Any?>
            val nextStep = json.getInt("next_step")
            require(nextStep > 0) { "Checkpoint next step must be positive" }
            return TaskCheckpoint(json.getString("instruction"), json.getString("source_run_id"), nextStep,
                record("state"), rows("steps"), rows("user_updates"),
                (value["source_runs"] as List<*>).map { it as String },
                record("step_sources").entries.associate { it.key.toInt() to it.value as String },
                (value["pending_user_inputs"] as List<*>).map { it as String },
                value["pending_user_answer"] as? String, value["pending_feedback"] as? String,
                record("waiting_context"), json.getInt("trace_step"), json.getInt("trace_event_count"))
        }
    }
}
