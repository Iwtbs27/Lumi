package ai.opencyvis.engine

import ai.opencyvis.config.TeamConfig
import ai.opencyvis.execution.TaskExecutionRuntime
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.ResponseParser
import ai.opencyvis.team.CodingTaskSpec
import ai.opencyvis.team.DelegationManager
import ai.opencyvis.team.DelegationRecord
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.DelegationStatus
import ai.opencyvis.team.MaterialPage
import ai.opencyvis.team.MaterialRef
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Connects research and coding children to the parent task's versioned materials and execution host. */
class TeamSession(
    directory: File,
    private val store: TaskRunStore,
    config: TeamConfig,
    scope: CoroutineScope,
    private val goalRevision: () -> String,
    private val userConstraints: () -> List<String>,
    private val execution: TaskExecutionRuntime? = null,
    createClient: (DelegationRequest) -> LLMClientInterface,
) {
    val manager = DelegationManager(directory, requireNotNull(store.taskId), store.runId, scope,
        config.maxActiveChildren, config.childMaxSteps, goalRevision, createClient, readMaterial = { _, reference, offset, limit ->
        if (TaskExecutionRuntime.isArtifactReference(reference.reference)) {
            requireNotNull(execution) { "File artifacts are unavailable in this task" }.readMaterial(reference, offset, limit)
        } else {
            val page = store.readContextArtifact(reference.reference, offset, limit)
            MaterialPage(reference, page.text, page.offset, page.nextOffset, page.totalChars,
                if (reference.kind == "evidence" && offset == 0) reference.sourceStep?.let(store::readImage) else null)
        }
    }, codingWorker = execution?.codingWorker)

    suspend fun initialize() = manager.initialize()

    suspend fun context(): Map<String, Any?> {
        manager.refreshApplicability()
        return mapOf("enabled" to true, "children" to manager.records.value.map(::summary))
    }

    suspend fun tool(action: Map<String, Any?>, step: Int): String {
        val result: Map<String, Any?> = when (action["action_type"]) {
            "delegate_task", "delegate_coding" -> {
                val coding = action["action_type"] == "delegate_coding"
                val materials = materials(action, step, required = !coding)
                val extra = strings(action, "child_constraints")
                val spec = if (coding) codingSpec(action) else null
                val request = DelegationRequest(UUID.randomUUID().toString(), requireNotNull(store.taskId),
                    store.runId, goalRevision(), action["child_goal"] as String,
                    (userConstraints() + extra).distinct(), materials,
                    role = if (coding) "coding" else "research", coding = spec, sourceStep = step)
                summary(manager.delegate(request))
            }
            "read_child" -> {
                val childId = action["child_id"] as String
                requireNotNull(manager.get(childId)) { "Child does not belong to this task" }
                val wait = (action["child_wait_ms"] as? Number)?.toLong() ?: 0
                require(wait in 0..30_000) { "child_wait_ms must be 0..30000" }
                if (wait > 0) withTimeoutOrNull(wait) {
                    manager.records.first { records ->
                        records.any { it.request.childId == childId && it.status !in DelegationStatus.IN_FLIGHT }
                    }
                }
                val offset = (action["recall_offset"] as? Number)?.toInt() ?: 0
                val limit = (action["recall_limit"] as? Number)?.toInt() ?: 4800
                summary(requireNotNull(manager.get(childId))) +
                    ("result_page" to manager.readResult(childId, offset, limit).toMap())
            }
            "review_child" -> summary(manager.review(action["child_id"] as String,
                action["child_accept"] as Boolean, action["reason"] as String))
            "cancel_child" -> summary(manager.cancel(action["child_id"] as String, action["reason"] as String))
            else -> error("Not a team action")
        }
        store.recordEvent(step, "delegation", mapOf("action_type" to action["action_type"], "result" to result))
        return JSONObject(result).toString()
    }

    private fun materials(action: Map<String, Any?>, step: Int, required: Boolean): List<MaterialRef> {
        val rawSteps = action["evidence_steps"]
        require(rawSteps == null || rawSteps is List<*> && rawSteps.all { it is Number }) {
            "evidence_steps must be an array of recorded step numbers"
        }
        val evidence = (rawSteps as? List<*>).orEmpty().map { (it as Number).toInt() }.distinct().map { sourceStep ->
            require(sourceStep in 1 until step) { "Delegate only already completed observation steps" }
            val source = store.sourceRunForStep(sourceStep)
            val text = JSONObject(mapOf("source_run" to source, "source_step" to sourceStep,
                "view_tree" to store.readTree(sourceStep), "step_record" to store.readStepDetails(sourceStep))).toString()
            val ref = store.saveContextArtifact(text, sourceStep, "delegation_evidence")
            MaterialRef("evidence", ref.artifactRef, ref.version, source, sourceStep, "Observation $sourceStep")
        }
        val artifacts = strings(action, "artifact_refs").distinct().map { reference ->
            if (TaskExecutionRuntime.isArtifactReference(reference)) {
                requireNotNull(execution) { "File artifacts are unavailable in this task" }.material(reference, step)
            } else {
                val ref = store.contextArtifactReference(reference)
                MaterialRef("artifact", reference, ref.version, reference.substringBefore(":context:"), ref.sourceStep, ref.label)
            }
        }
        require(!required || evidence.isNotEmpty() || artifacts.isNotEmpty()) { "Delegate existing evidence_steps or artifact_refs" }
        return evidence + artifacts
    }

    private fun codingSpec(action: Map<String, Any?>): CodingTaskSpec {
        requireNotNull(execution) { "Coding execution is unavailable in this task" }
        val mode = action["coding_mode"] as? String ?: error("coding_mode is required")
        val params = action["params"] as? Map<*, *> ?: error("params must be an explicit object, including {} when empty")
        val maxExecutions = integer(action, "max_executions")
        val timeout = integer(action, "execution_timeout_ms").toLong()
        val source = action["source_program"]?.let {
            require(it is Map<*, *>) { "source_program must contain program_id and revision" }
            ResponseParser.jsonObjectToMap(JSONObject(it))
        }
        return CodingTaskSpec(mode, ResponseParser.jsonObjectToMap(JSONObject(params)),
            strings(action, "completion_conditions"), maxExecutions, timeout, source)
    }

    private fun integer(action: Map<String, Any?>, key: String): Int {
        val number = action[key]
        require(number is Number && number.toDouble() == number.toInt().toDouble()) { "$key must be an explicit integer" }
        return number.toInt()
    }

    private fun strings(action: Map<String, Any?>, key: String): List<String> {
        val value = action[key] ?: return emptyList()
        require(value is List<*> && value.all { it is String }) { "$key must be an array of strings" }
        return value.map { it as String }
    }

    private fun summary(record: DelegationRecord): Map<String, Any?> = mapOf(
        "child_id" to record.request.childId, "goal" to record.request.goal,
        "role" to record.request.role, "coding_mode" to record.request.coding?.mode,
        "goal_revision" to record.request.goalRevision, "status" to record.status,
        "stale" to record.stale, "decision" to record.decision, "review_reason" to record.reviewReason,
        "result_ref" to record.resultRef, "error" to record.error,
        "calls" to record.usage.calls,
        "known_input_tokens" to record.usage.knownInputTokens,
        "known_output_tokens" to record.usage.knownOutputTokens,
        "unknown_usage_attempts" to record.usage.unknownUsageAttempts)

    companion object {
        val ACTIONS = setOf("delegate_task", "delegate_coding", "read_child", "review_child", "cancel_child")
    }
}
