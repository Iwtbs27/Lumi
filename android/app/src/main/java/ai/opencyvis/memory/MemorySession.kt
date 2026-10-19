package ai.opencyvis.memory

import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState
import ai.opencyvis.execution.OperationJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Coordinates the memory modules at the observe/respond/execute boundaries of one run. */
internal class MemorySession(
    private val manager: MemoryManager, private val task: TaskState, private val store: TaskRunStore
) {
    private val repository get() = manager.repository
    private val retrieval = MemoryRetrieval(manager, task, store)
    private val procedure = ProcedureExecution(task)
    private val recorder = MemoryEpisodeRecorder(manager, task, store)
    private val selections = linkedMapOf<Int, List<MemoryItem>>()
    private val selectedNodes = mutableMapOf<Pair<Int, String>, String>()
    private data class SuppliedMemory(val item: MemoryItem, val firstStep: Int)
    private val supplied = linkedMapOf<Pair<String, Int>, SuppliedMemory>()
    private val reportedOutcomes = mutableSetOf<Pair<Int, String>>()
    private val warnings = mutableListOf<String>()

    fun currentProcedure(): MemoryItem? = procedure.activeItem()

    suspend fun start() {
        MemoryJobs.enterForeground(store.runId)
        attempt(task.runStartStep, "procedure_restore") {
            val id = task.procedureExecution["id"] as? String
            if (id != null) procedure.restore(repository.get(id))
        }
        attempt(0, "episode_start") { recorder.checkpoint(0) }
    }

    suspend fun prepare(step: Int, observedPackage: String?): MemoryPacket {
        recorder.observe(step)
        attempt(step, "procedure_refresh") { procedure.refresh(procedure.item?.let { repository.get(it.id) }) }
        var packet = MemoryPacket()
        attempt(step, "retrieval") { packet = retrieval.prepare(step, observedPackage, procedure) }
        packet.items.forEach { item -> supplied.putIfAbsent(item.id to item.revision, SuppliedMemory(item, step)) }
        if (warnings.isNotEmpty()) {
            packet = packet.copy(text = packet.text + "\n[memory_feedback]\n" + warnings.joinToString("\n"))
            warnings.clear()
        }
        return packet
    }

    suspend fun respond(step: Int, data: Map<String, Any?>) {
        retrieval.consumeRecall()
        attempt(step, "procedure_refresh") { procedure.refresh(procedure.item?.let { repository.get(it.id) }) }
        for (field in listOf("memory_context", "procedure_update")) {
            if (data[field] != null && data[field] !is Map<*, *>) attempt(step, field) { error("$field must be an object") }
        }
        for (field in listOf("memory_use", "memory_feedback")) {
            if (data[field] != null && data[field] !is List<*>) attempt(step, field) { error("$field must be an array") }
        }
        if (data.containsKey("memory_context")) attempt(step, "observation_annotation") {
            val raw = data["memory_context"] as? Map<*, *> ?: error("memory_context must be an object")
            val fields = linkedMapOf<String, Any?>("observed_step" to step, "source" to "model_current_screenshot",
                "possibly_stale" to false, "app_uncertain" to false)
            for (key in listOf("app", "page")) {
                val value = raw[key]
                require(value == null || value is String) { "$key must be text" }
                (value as? String)?.takeIf(String::isNotBlank)?.let { fields[key] = it.take(200) }
            }
            (raw["query_terms"] as? List<*>)?.let { terms ->
                require(terms.all { it is String }) { "query_terms must be text" }
                fields["query_terms"] = terms.take(8).map { (it as String).take(100) }
            }
            task.memoryContext = fields
        }
        val previous = data["previous_action_result"] as? Map<*, *>
        if (previous != null) attempt(step, "observed_usage") {
            val target = (previous["step"] as Number).toInt()
            selections[target].orEmpty().forEach { item ->
                val outcome = when (previous["status"]) {
                    "confirmed_success" -> "success"; "confirmed_failure" -> "failure"; else -> "unknown"
                }
                recordOutcome(item, target, step, outcome, previous["evidence"].toString(), "unknown", "action", "previous_action_result")
            }
            if (previous["status"] == "confirmed_failure") retrieval.invalidate()
        }
        (data["memory_feedback"] as? List<*>)?.forEach { raw -> attempt(step, "usage_feedback") {
            val feedback = raw as? Map<*, *> ?: error("memory_feedback entries must be objects")
            val target = (feedback["applied_step"] as? Number)?.toInt() ?: error("applied_step required")
            val id = feedback["id"] as? String ?: error("Memory id required")
            val revision = (feedback["revision"] as? Number)?.toInt() ?: error("Memory revision required")
            val item = selections[target].orEmpty().firstOrNull {
                it.id == id && it.revision == revision
            } ?: supplied[id to revision]?.takeIf { it.firstStep <= target }?.item
                ?: error("Feedback must refer to a memory revision supplied before the action")
            val evidenceStep = (feedback["evidence_step"] as? Number)?.toInt() ?: error("evidence_step required")
            val evidence = feedback["evidence"] as? String ?: error("Observed evidence required")
            val outcome = feedback["outcome"] as? String ?: error("Outcome required")
            require(evidenceStep in task.steps && evidenceStep <= step && evidenceStep > target && evidence.isNotBlank()) {
                "Feedback requires a later observed result of the earlier action"
            }
            require(outcome in setOf("success", "failure", "inapplicable", "unknown")) { "Invalid local outcome" }
            val cause = feedback["cause"] as? String ?: "unknown"
            recordOutcome(item, target, evidenceStep, outcome, evidence, cause, "action", "memory_feedback")
            if (selections[target].orEmpty().none { it.id == item.id && it.revision == item.revision }) {
                usage(item, target, "applied", mapOf("source" to "memory_feedback", "reported_step" to step))
                selections[target] = selections[target].orEmpty() + item
            }
            if (outcome == "inapplicable") retrieval.exclude(item.id)
            if (outcome == "failure" && cause == "memory") {
                repository.markNeedsReview(item.id, item.revision, evidence)
                retrieval.invalidate()
            }
        } }
        (data["procedure_update"] as? Map<*, *>)?.let { update -> attempt(step, "procedure_update") {
            val selected = if (update["op"] == "start") {
                repository.get(update["id"] as? String ?: error("Procedure id required"))
            } else null
            procedure.apply(update, step, selected)
            if (update["op"] == "complete") {
                val item = requireNotNull(procedure.item)
                val applied = selections.entries.lastOrNull { (_, items) -> items.any { it.id == item.id } }?.key
                if (applied != null) recordOutcome(item, applied, (update["evidence_step"] as Number).toInt(),
                    "success", update["evidence"].toString(), "unknown", "procedure", "procedure_completion")
            }
        } }
        val selected = mutableListOf<MemoryItem>()
        (data["memory_use"] as? List<*>)?.take(2)?.forEach { raw -> attempt(step, "memory_selection") {
            val use = raw as? Map<*, *> ?: error("memory_use entries must be objects")
            val item = requireNotNull(repository.get(use["id"] as? String ?: error("Memory id required"))) { "Memory not found" }
            require(item.status == "active" && item.revision == (use["revision"] as? Number)?.toInt()) { "Memory changed or is not active" }
            require(procedure.item?.id != item.id || procedure.activeItem() != null) { "Procedure needs rebind or restart before use" }
            (use["node_id"] as? String)?.takeIf(String::isNotBlank)?.let { node ->
                val nodes = JSONObject(item.bodyJson).optJSONArray("nodes") ?: error("Memory has no nodes")
                require((0 until nodes.length()).any { nodes.getJSONObject(it).optString("node_id") == node }) { "Unknown memory node" }
                selectedNodes[step to item.id] = node
            }
            selected += item
        } }
        if (data["action_type"] in AgentEngine.SIDE_EFFECT_ACTIONS) procedure.activeItem()?.let { selected += it }
        selections[step] = selected.distinctBy { it.id }.take(2)
        selections[step].orEmpty().forEach { item -> attempt(step, "selection_record") { usage(item, step, "selected") } }
        if (data["action_type"] == "update_plan") retrieval.invalidate()
    }

    suspend fun tool(step: Int, data: Map<String, Any?>): Pair<Boolean, String>? {
        if (data["action_type"] !in setOf("search_memory", "read_memory", "remember")) return null
        return try {
            val result = when (data["action_type"]) {
                "search_memory" -> retrieval.search(data)
                "read_memory" -> retrieval.read(data)
                else -> remember(step, data)
            }
            true to result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            store.recordEvent(step, "memory_error", mapOf("stage" to "tool", "message" to e.message))
            false to "Memory operation failed: ${e.message}"
        }
    }

    suspend fun checkpoint(step: Int) {
        val record = task.steps[step] ?: return
        if (record.execution["execution_status"] == "executed") {
            selections[step].orEmpty().forEach { item -> attempt(step, "applied_record") {
                usage(item, step, "applied")
                procedure.executed(step, item.id)
            } }
            val action = record.decision["action_type"]
            if (action in setOf("tap", "open_app", "key_event")) {
                task.memoryContext = task.memoryContext + mapOf("possibly_stale" to true,
                    "app_uncertain" to (action == "open_app" || action == "key_event"))
            }
        }
        attempt(step, "episode_checkpoint") { recorder.checkpoint(step) }
    }

    /** Called only by the host after resolving the saved program's explicit node mapping. */
    suspend fun applyVerifiedOperations(
        step: Int, executionId: String, mappings: List<ProcedureOperationMapping>, journal: OperationJournal,
    ) {
        procedure.refresh(procedure.item?.let { repository.get(it.id) })
        val accepted = procedure.applyVerifiedOperations(step, store.runId, executionId, mappings, journal)
        if (accepted.isEmpty()) return
        val item = requireNotNull(procedure.item)
        // The external effects already happened. Cancellation must not discard their checked evidence.
        withContext(NonCancellable) {
            for (node in accepted) for (operation in node.operations) {
                val operationId = operation.getString("operation_id")
                val identity = "${store.runId}:${item.id}:${item.revision}:operation:$operationId"
                val actor = if (node.independentExecution) "agent" else "mixed"
                val details = JSONObject().put("source", "program_operation").put("evidence_author", "host_verified")
                    .put("evidence", operation.getJSONObject("result").toString()).put("cause", "unknown")
                    .put("scope", "operation").put("node_id", node.nodeId).put("actor", actor)
                    .put("source_type", "agent_run").put("independent_execution", node.independentExecution)
                    .put("support_scope", node.supportScope).put("operation_id", operationId)
                    .put("execution_id", executionId).put("issued_sequence", operation.getLong("issued_sequence"))
                    .put("finished_sequence", operation.getLong("finished_sequence")).put("operation_evidence", operation)
                repository.saveEvidence(MemoryEvidence(id = identity, memoryId = item.id, revision = item.revision,
                    episodeId = store.runId, runId = store.runId, step = step, kind = "operation",
                    text = operation.toString(), author = "host_verified", actor = actor,
                    supportScopeJson = node.supportScope.toString()))
                repository.recordUsage(MemoryUsage(id = "$identity:outcome", memoryId = item.id, revision = item.revision,
                    runId = store.runId, step = step, event = "outcome", outcome = "success", evidenceStep = step,
                    goalId = task.currentGoalId.ifEmpty { null }, detailsJson = details.toString()))
            }
            store.recordEvent(step, "procedure_operations_verified", mapOf("memory_id" to item.id,
                "revision" to item.revision, "execution_id" to executionId,
                "nodes" to accepted.map { it.nodeId },
                "operation_ids" to accepted.flatMap { it.operations.map { operation -> operation.getString("operation_id") } }))
        }
    }

    suspend fun finish(step: Int, outcome: String) {
        try {
            recorder.checkpoint(step, outcome)
            manager.jobs.enqueue(store.runId)
            store.recordEvent(step, "memory_consolidation", mapOf("status" to "queued", "run_id" to store.runId))
        } finally {
            MemoryJobs.leaveForeground(store.runId)
            manager.jobs.resumePending()
        }
    }

    private suspend fun remember(step: Int, data: Map<String, Any?>): String {
        val index = (data["memory_source_index"] as? Number)?.toInt() ?: error("remember requires memory_source_index")
        val quote = data["memory_source_quote"] as? String ?: error("remember requires the user's original memory_source_quote")
        require(quote.isNotBlank() && ProcedureExecution.userText(task, index).contains(quote)) { "Quote must come from the cited user message" }
        val source = JSONObject().put("run_id", store.runId).put("step", step).put("user_message_index", index).put("quote", quote)
        val evidence = manager.evidence.save(store.runId, step, source.toString(), author = "user_stated",
            actor = "human", sourceType = "user_statement")
        val item = repository.saveExplicit(data["memory_key"] as String, data["memory_value"] as String,
            (data["memory_app"] as? String)?.takeIf(String::isNotBlank), source.toString())
        repository.saveEvidence(evidence.copy(id = UUID.randomUUID().toString(), memoryId = item.id, revision = item.revision))
        retrieval.invalidate()
        return "Saved explicit user memory: ${item.title} (id=${item.id}, revision=${item.revision})"
    }

    private suspend fun usage(item: MemoryItem, step: Int, event: String,
        details: Map<String, Any?> = task.procedureExecution.takeIf { it["id"] == item.id }.orEmpty()) {
        val value = JSONObject(details)
        selectedNodes[step to item.id]?.let { value.put("node_id", it) }
        repository.recordUsage(MemoryUsage(id = "${store.runId}:$step:${item.id}:$event",
            memoryId = item.id, revision = item.revision, runId = store.runId, step = step,
            event = event, goalId = task.currentGoalId.ifEmpty { null }, detailsJson = value.toString()))
    }

    private suspend fun recordOutcome(item: MemoryItem, applied: Int, evidenceStep: Int, outcome: String,
        evidence: String, cause: String, scope: String, source: String) {
        require(task.steps[applied]?.execution?.get("execution_status") == "executed") { "Memory was not applied by an executed action" }
        if (source == "previous_action_result" && (applied to item.id) in reportedOutcomes) return
        val node = repository.runUsages(store.runId).lastOrNull {
            it.memoryId == item.id && it.step == applied && it.event in setOf("selected", "applied")
        }?.let { JSONObject(it.detailsJson).optString("node_id") }?.takeIf(String::isNotBlank)
        val support = if (scope == "procedure") procedure.supportScope() else JSONObject()
            .put("nodes", JSONArray(listOfNotNull(node))).put("whole_procedure", false)
        val independent = recorder.independentBetween(applied, evidenceStep) &&
            (scope != "procedure" || support.optBoolean("whole_procedure"))
        repository.recordUsage(MemoryUsage(id = "${store.runId}:$applied:${item.id}:outcome:$scope",
            memoryId = item.id, revision = item.revision, runId = store.runId, step = applied,
            event = "outcome", outcome = outcome, evidenceStep = evidenceStep, goalId = task.currentGoalId.ifEmpty { null },
            detailsJson = JSONObject().put("evidence", evidence).put("cause", cause).put("scope", scope)
                .put("source", source).put("evidence_author", "model_observed")
                .put("actor", if (independent) "agent" else "mixed").put("source_type", "agent_run")
                .put("independent_execution", independent).put("support_scope", support).toString()))
        if (source == "memory_feedback") reportedOutcomes += applied to item.id
        // A later report can refer to an observation already archived by the recorder.
        if (outcome in setOf("success", "failure")) repository.getEvidence(store.runId, evidenceStep)
            .filter { it.memoryId == null && it.author == "model_report" }
            .forEach { repository.saveEvidence(it.copy(author = "model_observed")) }
    }

    private suspend fun attempt(step: Int, stage: String, operation: suspend () -> Unit) {
        try { operation() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = "$stage: ${e.message}"
            warnings += message
            store.recordEvent(step, "memory_error", mapOf("stage" to stage, "message" to e.message))
        }
    }
}
