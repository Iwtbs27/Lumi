package ai.opencyvis.memory

import ai.opencyvis.engine.TaskState
import ai.opencyvis.execution.OperationJournal
import org.json.JSONArray
import org.json.JSONObject

/** Per-run cursor over a saved method. It never sends phone actions or maintains a second plan. */
internal class ProcedureExecution(private val task: TaskState) {
    var item: MemoryItem? = null
        private set
    private var nodeId = ""
    private var goalId = ""
    private var status = "idle"
    private var reason = ""
    private var userRevision = 0
    private val bindings = linkedMapOf<String, Map<String, Any?>>()
    private val visited = mutableListOf<Map<String, Any?>>()
    private var appliedStep: Int? = null
    private var startedAtIntervention = 0
    private var nodeStartedAtIntervention = 0
    private var entryNodeId = ""

    /** Restore only the saved revision; a fresh observation still decides the next action. */
    fun restore(current: MemoryItem?) {
        val saved = task.procedureExecution
        if (saved.isEmpty()) return
        if (current == null || current.status != "active" || current.id != saved["id"] ||
            current.revision != (saved["revision"] as Number).toInt()) {
            task.procedureExecution = saved + mapOf("status" to "aborted",
                "reason" to "The saved method changed or was removed while this task was interrupted")
            return
        }
        item = current
        nodeId = saved.getValue("node_id") as String
        goalId = saved.getValue("goal_id") as String
        status = saved.getValue("status") as String
        reason = saved.getValue("reason") as String
        userRevision = (saved.getValue("user_revision") as Number).toInt()
        entryNodeId = saved.getValue("entry_node_id") as String
        startedAtIntervention = (saved.getValue("started_at_intervention") as Number).toInt()
        nodeStartedAtIntervention = (saved.getValue("node_started_at_intervention") as Number).toInt()
        appliedStep = (saved["pending_action_step"] as? Number)?.toInt()
        @Suppress("UNCHECKED_CAST")
        bindings.putAll(saved.getValue("bindings") as Map<String, Map<String, Any?>>)
        @Suppress("UNCHECKED_CAST")
        visited.addAll(saved.getValue("completed_nodes") as List<Map<String, Any?>>)
        refresh(current)
    }

    fun apply(update: Map<*, *>, step: Int, selected: MemoryItem? = null) {
        when (update["op"]) {
            "start" -> {
                val memory = requireNotNull(selected) { "Procedure not found" }
                require(memory.type == "procedure" && memory.status == "active") { "Procedure is not active" }
                require((update["revision"] as? Number)?.toInt() == memory.revision) { "Read the current procedure revision first" }
                val nodes = nodes(memory)
                val entry = (update["node_id"] as? String)?.takeIf(String::isNotBlank) ?: nodes.first().getString("node_id")
                require(nodes.any { it.getString("node_id") == entry }) { "Unknown procedure node" }
                val newBindings = readBindings(update, memory)
                val newGoal = update["goal_id"] as? String ?: ""
                require(newGoal.isEmpty() || task.plan.any { it["id"] == newGoal }) { "Unknown plan goal" }
                item = memory
                nodeId = entry
                goalId = newGoal
                bindings.clear()
                bindings.putAll(newBindings)
                visited.clear()
                appliedStep = null
                startedAtIntervention = task.humanInterventionCount
                nodeStartedAtIntervention = task.humanInterventionCount
                entryNodeId = entry
                status = "ready"
                userRevision = task.userUpdates.size
                reason = ""
            }
            "rebind" -> {
                val memory = requireActive()
                val changed = readBindings(update, memory, bindings)
                bindings.clear()
                bindings.putAll(changed)
                userRevision = task.userUpdates.size
                status = "ready"
                reason = ""
            }
            "advance", "branch", "complete" -> {
                val memory = requireActive()
                require(status != "needs_rebind") { "Update changed parameter bindings before continuing" }
                val sourceStep = (update["evidence_step"] as? Number)?.toInt()
                require(sourceStep != null && sourceStep in task.steps && sourceStep <= step) { "Transition requires an observed evidence_step" }
                val evidence = update["evidence"] as? String
                require(!evidence.isNullOrBlank()) { "Transition requires observed evidence" }
                require(appliedStep == null || sourceStep > appliedStep!!) { "Observe the action result before advancing" }
                val current = nodes(memory).first { it.getString("node_id") == nodeId }
                val next = update["node_id"] as? String ?: ""
                if (update["op"] == "complete") {
                    require(current.optString("next").isBlank()) { "Complete remaining procedure nodes first" }
                } else {
                    val allowed = if (update["op"] == "branch") {
                        current.optJSONArray("branches")?.let { rows ->
                            (0 until rows.length()).map { rows.getJSONObject(it).getString("next") }
                        }.orEmpty()
                    } else listOf(current.optString("next"))
                    require(next.isNotBlank() && next in allowed && nodes(memory).any { it.getString("node_id") == next }) {
                        "Next node must be a declared transition; abort to use another route"
                    }
                }
                visited += mapOf("node_id" to nodeId, "evidence_step" to sourceStep, "evidence" to evidence,
                    "applied_step" to appliedStep,
                    "agent_executed" to (appliedStep != null && nodeStartedAtIntervention == task.humanInterventionCount))
                appliedStep = null
                nodeStartedAtIntervention = task.humanInterventionCount
                status = if (update["op"] == "complete") "completed" else "ready"
                if (status == "ready") nodeId = next
            }
            "abort" -> {
                requireActive()
                status = "aborted"
                reason = update["evidence"] as? String ?: "Method no longer applies"
            }
            else -> error("Unknown procedure_update.op")
        }
        publish()
    }

    fun refresh(current: MemoryItem?) {
        val memory = item ?: return
        if (status in setOf("completed", "aborted")) return
        when {
            current == null || current.status != "active" || current.revision != memory.revision -> {
                status = "aborted"
                reason = "The saved method was changed, disabled or deleted; retrieve its current version"
            }
            goalId.isNotEmpty() && task.plan.none { it["id"] == goalId && it["status"] !in setOf("done", "skipped") } -> {
                status = "aborted"
                reason = "The associated plan goal is no longer active"
            }
            userRevision != task.userUpdates.size || bindings.values.any { binding ->
                val key = binding["task_memory_key"] as? String
                key != null && task.memories[key]?.get("recorded_step") != binding["recorded_step"]
            } -> {
                status = "needs_rebind"
                reason = "Task input changed; rebind affected parameters or abort this method"
            }
        }
        publish()
    }

    fun executed(step: Int, memoryId: String) {
        if (item?.id == memoryId && status in setOf("ready", "awaiting_result")) {
            // The program already advanced its checked nodes inside this model step.
            // Its outer action must not mark the following, unexecuted node as pending.
            if (visited.lastOrNull()?.let {
                it["source"] == "program_operation" && (it["applied_step"] as? Number)?.toInt() == step
            } == true) return
            appliedStep = step
            status = "awaiting_result"
            publish()
        }
    }

    fun activeItem(): MemoryItem? = item?.takeIf { status in setOf("ready", "awaiting_result") }

    /** Consume checked program operations without weakening the later-observation rule for model updates. */
    fun applyVerifiedOperations(
        step: Int, runId: String, executionId: String,
        mappings: List<ProcedureOperationMapping>, journal: OperationJournal,
    ): List<VerifiedProcedureNode> {
        // Re-presenting an already checkpointed prefix, including after restart, adds no experience.
        val pending = mappings.filterNot { mapping -> visited.any {
            it["node_id"] == mapping.nodeId && it["operation_ids"] == mapping.operationIds
        } }
        if (pending.isEmpty()) return emptyList()
        val memory = requireActive()
        require(status != "needs_rebind") { "Update changed parameter bindings before continuing" }
        require(step >= task.runStartStep && step in task.steps) { "Operations require a current-run model step" }
        val declared = nodes(memory).associateBy { it.getString("node_id") }
        val prepared = mutableListOf<Triple<ProcedureOperationMapping, List<JSONObject>, String>>()
        val used = visited.flatMap { (it["operation_ids"] as? List<*>)?.filterIsInstance<String>().orEmpty() }.toMutableSet()
        var expectedNode = nodeId
        var previousFinished = (visited.lastOrNull { it["run_id"] == runId }?.get("finished_sequence") as? Number)?.toLong()
        for ((index, mapping) in pending.withIndex()) {
            require(mapping.nodeId == expectedNode) { "Program operations must follow the current procedure node" }
            require(mapping.operationIds.isNotEmpty() && mapping.operationIds.all { used.add(it) }) {
                "Each procedure operation must have one node assignment"
            }
            val operations = mapping.operationIds.map(journal::get)
            require(operations.all {
                it.getString("run_id") == runId && it.getInt("step") == step && it.optString("execution_id") == executionId
            }) { "Procedure evidence must belong to this run, model step and execution" }
            if (operations.any { it.getString("status") != "succeeded" ||
                    it.optJSONObject("result")?.optBoolean("verified") != true }) break
            for (operation in operations) {
                val issued = operation.getLong("issued_sequence")
                val finished = operation.getLong("finished_sequence")
                require(issued > 0 && finished > issued && (previousFinished == null || issued > previousFinished!!)) {
                    "Observe each operation before issuing the next procedure operation"
                }
                previousFinished = finished
            }
            val current = declared.getValue(expectedNode)
            val following = pending.getOrNull(index + 1)?.nodeId
            val defaultNext = current.optString("next")
            val next = if (following != null) {
                val branches = current.optJSONArray("branches")
                val allowed = listOf(defaultNext) + (0 until (branches?.length() ?: 0))
                    .map { branches!!.getJSONObject(it).getString("next") }
                require(following in allowed && following in declared) { "Program nodes must use a declared transition" }
                following
            } else defaultNext
            prepared += Triple(mapping, operations, next)
            expectedNode = next
        }
        // Validate the complete checked prefix before changing the cursor, so an invalid order cannot partially advance it.
        return prepared.map { (mapping, operations, next) ->
            val independent = nodeStartedAtIntervention == task.humanInterventionCount
            visited += mapOf("node_id" to mapping.nodeId, "evidence_step" to step, "applied_step" to step,
                "evidence" to "Verified host operations: ${mapping.operationIds.joinToString()}",
                "source" to "program_operation", "run_id" to runId, "execution_id" to executionId,
                "operation_ids" to mapping.operationIds.toList(),
                "issued_sequence" to operations.first().getLong("issued_sequence"),
                "finished_sequence" to operations.last().getLong("finished_sequence"), "agent_executed" to independent)
            appliedStep = null
            nodeStartedAtIntervention = task.humanInterventionCount
            status = if (next.isBlank()) "completed" else "ready"
            if (status == "ready") nodeId = next
            publish()
            VerifiedProcedureNode(mapping.nodeId, operations, independent, supportScope())
        }
    }

    private fun executedInThisRun(node: Map<String, Any?>): Boolean =
        node["agent_executed"] == true &&
            (node["applied_step"] as? Number)?.toInt()?.let { it >= task.runStartStep } == true

    fun supportScope(): JSONObject = JSONObject().put("nodes", JSONArray(visited.filter(::executedInThisRun).map { it["node_id"] }))
        .put("whole_procedure", status == "completed" && item?.let {
            entryNodeId == nodes(it).first().getString("node_id") && visited.isNotEmpty() &&
                visited.all(::executedInThisRun) &&
                startedAtIntervention == task.humanInterventionCount
        } == true)

    fun details(): String = item?.let { memory ->
        val body = JSONObject(memory.bodyJson)
        val dependencies = body.optJSONArray("data_dependencies")
        val currentDependencies = (0 until (dependencies?.length() ?: 0))
            .map { dependencies!!.getJSONObject(it) }.filter { it.optString("target_node") == nodeId }
        JSONObject().put("id", memory.id).put("revision", memory.revision)
            .put("preconditions", body.optJSONArray("preconditions"))
            .put("current_node", nodes(memory).firstOrNull { it.getString("node_id") == nodeId })
            .put("data_dependencies", JSONArray(currentDependencies))
            .put("completion", body.optString("completion")).toString()
    }.orEmpty()

    private fun requireActive(): MemoryItem {
        require(status !in setOf("idle", "completed", "aborted")) { "No active procedure; start one first" }
        return requireNotNull(item)
    }

    private fun nodes(memory: MemoryItem): List<JSONObject> {
        val rows = JSONObject(memory.bodyJson).getJSONArray("nodes")
        require(rows.length() > 0) { "Procedure has no nodes" }
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun readBindings(
        update: Map<*, *>, memory: MemoryItem, previous: Map<String, Map<String, Any?>> = emptyMap()
    ): Map<String, Map<String, Any?>> {
        val result = previous.toMutableMap()
        val parameters = JSONObject(memory.bodyJson).optJSONArray("parameters")
        val names = (0 until (parameters?.length() ?: 0)).map { parameters!!.getJSONObject(it).getString("name") }
        (update["bindings"] as? List<*>).orEmpty().forEach { raw ->
            val binding = raw as? Map<*, *> ?: error("Binding must be an object")
            val name = binding["name"] as? String ?: error("Binding name required")
            val value = binding["value"] as? String ?: error("Binding value required")
            require(name in names) { "Unknown parameter: $name" }
            val key = binding["task_memory_key"] as? String
            result[name] = if (!key.isNullOrBlank()) {
                val fact = task.memories[key] ?: error("Unknown task-memory source: $key")
                require(fact["value"] == value) { "Bind the current task-memory value for $key" }
                mapOf("value" to value, "task_memory_key" to key, "recorded_step" to fact["recorded_step"])
            } else {
                val index = (binding["user_source_index"] as? Number)?.toInt() ?: error("Parameter needs a source")
                val source = userText(task, index)
                val reference = task.instruction.substringAfter("[procedure_reference]", "")
                    .substringBefore("[/procedure_reference]").trim()
                val referenceMatches = if (index == 0 && reference.isNotEmpty()) {
                    runCatching { JSONObject(reference).optJSONObject("parameters")?.optString(name) == value }.getOrDefault(false)
                } else false
                require(value.isNotBlank() && (source.contains(value) || referenceMatches)) { "Parameter value is not in the cited user input" }
                mapOf("value" to value, "user_source_index" to index)
            }
        }
        for (index in 0 until (parameters?.length() ?: 0)) {
            val parameter = parameters!!.getJSONObject(index)
            require(!parameter.optBoolean("required", true) || parameter.getString("name") in result) {
                "Missing required parameter: ${parameter.getString("name")}"
            }
        }
        result.values.forEach { binding ->
            val key = binding["task_memory_key"] as? String
            if (key != null) {
                val current = task.memories[key]
                require(current != null && current["recorded_step"] == binding["recorded_step"] && current["value"] == binding["value"]) {
                    "Binding for $key is stale; include its current value in rebind"
                }
            }
        }
        return result
    }

    private fun publish() {
        task.procedureExecution = item?.let {
            mapOf("id" to it.id, "revision" to it.revision, "node_id" to nodeId, "goal_id" to goalId,
                "status" to status, "bindings" to bindings.toMap(), "pending_action_step" to appliedStep,
                "completed_nodes" to visited.toList(), "reason" to reason,
                "user_revision" to userRevision, "entry_node_id" to entryNodeId,
                "started_at_intervention" to startedAtIntervention,
                "node_started_at_intervention" to nodeStartedAtIntervention,
                "independent_execution" to supportScope().optBoolean("whole_procedure"))
        }.orEmpty()
    }

    companion object {
        fun userText(task: TaskState, index: Int): String {
            require(index in 0..task.userUpdates.size) { "Unknown user message index" }
            return if (index == 0) task.instruction else task.userUpdates[index - 1]["text"] as String
        }
    }
}
