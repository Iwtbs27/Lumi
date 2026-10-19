package ai.opencyvis.engine

/** State belonging to one run. Observations and execution facts come from the engine. */
internal class TaskState(val instruction: String) {
    companion object {
        val RESPONSE_METADATA = setOf("thought", "memory_update", "previous_action_result", "plan",
            "current_goal_id", "plan_reason", "memory_context", "memory_use", "memory_feedback", "procedure_update")
        val DISPATCHED = setOf("issued", "executed")

        @Suppress("UNCHECKED_CAST")
        fun restore(checkpoint: TaskCheckpoint): TaskState = TaskState(checkpoint.instruction).apply {
            val state = checkpoint.state
            plan = state.getValue("plan") as List<Map<String, Any?>>
            currentGoalId = state.getValue("current_goal_id") as String
            planVersion = (state.getValue("plan_version") as Number).toInt()
            planReason = state.getValue("plan_reason") as String
            memories.putAll(state.getValue("task_memory") as Map<String, Map<String, Any?>>)
            memoryContext = (state.getValue("memory_context") as Map<String, Any?>) +
                mapOf("possibly_stale" to true, "app_uncertain" to true)
            procedureExecution = state.getValue("procedure_execution") as Map<String, Any?>
            humanInterventionCount = (state.getValue("human_intervention_count") as Number).toInt()
            userUpdates.addAll(checkpoint.userUpdates)
            checkpoint.steps.forEach { entry ->
                val number = (entry.getValue("number") as Number).toInt()
                steps[number] = Step(number, null, null, entry.getValue("decision") as Map<String, Any?>,
                    entry.getValue("execution") as Map<String, Any?>, entry["effect"] as? Map<String, Any?>)
            }
            require(checkpoint.nextStep > (steps.keys.maxOrNull() ?: 0)) { "Checkpoint would reuse an existing step" }
            stepSources.putAll(checkpoint.stepSources)
            sourceRuns.addAll((checkpoint.sourceRuns + checkpoint.sourceRunId).distinct())
            runStartStep = checkpoint.nextStep
        }
    }
    data class Step(
        val number: Int,
        val image: String?,
        val tree: String?,
        var decision: Map<String, Any?> = emptyMap(),
        var execution: Map<String, Any?> = emptyMap(),
        var effect: Map<String, Any?>? = null
    )

    val steps = linkedMapOf<Int, Step>()
    /** Step numbers continue across runs; old evidence remains owned by its original run. */
    val stepSources = linkedMapOf<Int, String>()
    val sourceRuns = mutableListOf<String>()
    var runStartStep = 1
        private set
    val userUpdates = mutableListOf<Map<String, Any?>>()
    val memories = linkedMapOf<String, Map<String, Any?>>()
    var plan: List<Map<String, Any?>> = emptyList()
        private set
    var currentGoalId = ""
        private set
    private var planVersion = 0
    val goalRevision: String get() = "$planVersion:${userUpdates.size}"
    var teamContext: Map<String, Any?> = mapOf("enabled" to false)
    var executionContext: Map<String, Any?> = mapOf("enabled" to false)
    private var planReason = ""
    var recalledSteps = emptyList<Int>()
    var recallContent = "both"
    var recallOffset = 0
    var recallLimit = 4800
    var recalledTextPage: Map<String, Any?>? = null
    var historyFromStep = 1
    var memoryContext: Map<String, Any?> = emptyMap()
    var procedureExecution: Map<String, Any?> = emptyMap()
    @Volatile var humanInterventionCount = 0

    fun addUserUpdate(step: Int, kind: String, text: String) {
        userUpdates += mapOf("step" to step, "kind" to kind, "text" to text)
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "goal_revision" to goalRevision,
        "team" to teamContext,
        "execution" to executionContext,
        "plan" to plan,
        "current_goal_id" to currentGoalId,
        "plan_version" to planVersion, "plan_reason" to planReason,
        "task_memory" to memories,
        "memory_context" to memoryContext,
        "procedure_execution" to procedureExecution,
        "human_intervention_count" to humanInterventionCount,
        "pending_results" to steps.values.filter {
            it.execution["execution_status"] in DISPATCHED &&
                it.decision["action_type"] in AgentEngine.SIDE_EFFECT_ACTIONS &&
                it.effect?.get("status") !in setOf("confirmed_success", "confirmed_failure")
        }.map { mapOf("step" to it.number, "action" to it.decision.filterKeys { key -> key !in RESPONSE_METADATA }, "effect" to it.effect) }
    )

    fun exportCheckpoint(runId: String, nextStep: Int, pendingUserInputs: List<String> = emptyList(),
                         pendingUserAnswer: String? = null, pendingFeedback: String? = null,
                         waitingContext: Map<String, Any?> = emptyMap()): TaskCheckpoint {
        val checkpoint = TaskCheckpoint(instruction, runId, nextStep, snapshot(),
            steps.values.map { mapOf("number" to it.number, "decision" to it.decision,
                "execution" to it.execution, "effect" to it.effect) }, userUpdates.toList(),
            (sourceRuns + runId).distinct(), steps.keys.associateWith { stepSources[it] ?: runId },
            pendingUserInputs, pendingUserAnswer, pendingFeedback, waitingContext)
        // JSON freezes nested facts and maps while retaining their original values.
        return TaskCheckpoint.fromJson(checkpoint.toJson())
    }

    /** A continuation may inspect/navigate, but cannot retry an unresolved old write verbatim. */
    fun unresolvedResumedStep(action: Map<String, Any?>): Int? {
        if (action["action_type"] !in AgentEngine.SIDE_EFFECT_ACTIONS) return null
        val proposed = ai.opencyvis.action.Action.fromMap(action - "thought")
        return steps.values.firstOrNull {
            it.number < runStartStep && it.execution["execution_status"] in DISPATCHED &&
                it.effect?.get("status") !in setOf("confirmed_success", "confirmed_failure") &&
                ai.opencyvis.action.Action.fromMap(it.decision - "thought") == proposed
        }?.number
    }

    /** Validate protocol fields and sources before applying any state changes. */
    fun applyResponse(data: Map<String, Any?>, step: Int) {
        require(data["thought"] is String) { "thought must be a string" }
        require(data["action_type"] is String && (data["action_type"] as String).isNotBlank()) { "action_type is required" }
        validateAction(data)
        val updates = (data["memory_update"] as? List<*>)
            ?: throw IllegalArgumentException("memory_update is required; use [] when nothing changed")
        val checked = updates.map { raw ->
            val update = raw as? Map<*, *> ?: throw IllegalArgumentException("memory_update entries must be objects")
            val key = update["key"] as? String
            require(!key.isNullOrBlank()) { "memory_update.key is required" }
            when (update["op"]) {
                "upsert" -> {
                    require(update["value"] is String && (update["value"] as String).isNotBlank()) { "upsert requires value" }
                    val source = (update["source_step"] as? Number)?.toInt()
                    require(source in steps) { "upsert requires an existing source_step" }
                }
                "delete" -> Unit
                else -> throw IllegalArgumentException("memory_update.op must be upsert or delete")
            }
            update
        }
        require(data["previous_action_result"] == null || data["previous_action_result"] is Map<*, *>) { "previous_action_result must be an object" }
        val effect = data["previous_action_result"] as? Map<*, *>
        val target = effect?.let {
            val targetStep = (it["step"] as? Number)?.toInt()
            val previous = steps[targetStep]
            require(targetStep != step && previous?.execution?.get("execution_status") in DISPATCHED) {
                "previous_action_result must refer to a previously executed action"
            }
            require(it["status"] in setOf("confirmed_success", "confirmed_failure", "unknown")) { "Invalid effect status" }
            require(it["evidence"] is String && (it["evidence"] as String).isNotBlank()) { "Effect requires observed evidence, or explain why it is unknown" }
            previous
        }

        val newPlan = if (data["action_type"] == "update_plan") parsePlan(data, step) else null
        checked.forEach { update ->
            val key = update["key"] as String
            if (update["op"] == "delete") memories.remove(key)
            else memories[key] = mapOf("value" to update["value"], "source_step" to update["source_step"], "recorded_step" to step)
        }
        if (effect != null && target != null) target.effect = mapOf(
            "status" to effect["status"], "evidence" to effect["evidence"], "evidence_step" to step
        )
        if (newPlan != null) {
            plan = newPlan
            currentGoalId = data["current_goal_id"] as String
            planVersion++
            planReason = data["plan_reason"] as? String ?: ""
        }
    }

    private fun validateAction(data: Map<String, Any?>) {
        if (data["action_type"] in ai.opencyvis.execution.ExecutionToolSchema.actions) {
            ai.opencyvis.execution.ExecutionToolSchema.validate(data)
            return
        }
        fun field(key: String) {
            require(data[key] is String && (data[key] as String).isNotBlank()) { "$key is required" }
        }
        when (data["action_type"]) {
            "tap", "long_press" -> ai.opencyvis.action.Action.fromMap(data)
            "open_app" -> field("app_name")
            "type_text" -> field("text")
            "swipe" -> require(data["direction"] in setOf("up", "down", "left", "right")) { "Invalid swipe direction" }
            "key_event" -> require(data["key"] in setOf("back", "home", "enter", "recent")) { "Invalid key" }
            "ask_user" -> field("question")
            "handoff_user" -> field("handoff_reason")
            "fail" -> field("reason")
            "note" -> field("note")
            "remember" -> { field("memory_key"); field("memory_value") }
            "search_memory" -> field("memory_query")
            "read_memory" -> field("memory_id")
            "save_routine" -> field("routine_name")
            "delegate_task" -> field("child_goal")
            "read_child" -> field("child_id")
            "review_child" -> {
                field("child_id"); field("reason")
                require(data["child_accept"] is Boolean) { "child_accept must be a boolean" }
            }
            "cancel_child" -> { field("child_id"); field("reason") }
            "recall_history" -> {
                val ids = data["recall_steps"]
                require(ids == null || ids is List<*> && ids.all { it is Number && it.toInt() in steps }) { "recall_steps must refer to existing observations" }
                require((ids is List<*> && ids.isNotEmpty()) || (data["recall_query"] as? String)?.isNotBlank() == true ||
                    (data["artifact_ref"] as? String)?.isNotBlank() == true) { "recall_history requires recall_steps, recall_query or artifact_ref" }
                require(data["recall_content"] == null || data["recall_content"] in setOf("text", "image", "both")) { "Invalid recall_content" }
                require(data["recall_offset"] == null || data["recall_offset"] is Number && (data["recall_offset"] as Number).toInt() >= 0) { "recall_offset must be nonnegative" }
                require(data["recall_limit"] == null || data["recall_limit"] is Number && (data["recall_limit"] as Number).toInt() in 1..4800) { "recall_limit must be 1..4800" }
            }
            "update_plan", "finish", "wait", "list_apps" -> Unit
            else -> throw IllegalArgumentException("Unknown action_type")
        }
    }

    private fun parsePlan(data: Map<String, Any?>, step: Int): List<Map<String, Any?>> {
        val goals = data["plan"] as? List<*> ?: throw IllegalArgumentException("update_plan requires plan")
        require(goals.isNotEmpty()) { "Plan must contain at least one subgoal" }
        val parsed = goals.map { raw ->
            val goal = raw as? Map<*, *> ?: throw IllegalArgumentException("Plan entries must be objects")
            for (key in listOf("id", "goal", "done_when")) {
                require(goal[key] is String && (goal[key] as String).isNotBlank()) { "Plan requires $key" }
            }
            require(goal["status"] in setOf("pending", "active", "done", "blocked", "skipped")) { "Invalid plan status" }
            if (goal["status"] == "done") {
                val evidence = (goal["evidence_step"] as? Number)?.toInt()
                require(evidence in steps && evidence!! <= step) { "Completed subgoals require an observed evidence_step" }
            }
            goal.entries.associate { it.key.toString() to it.value }
        }
        require(parsed.map { it["id"] }.distinct().size == parsed.size) { "Plan IDs must be unique" }
        val current = data["current_goal_id"] as? String ?: throw IllegalArgumentException("current_goal_id is required; use empty string when all goals are done")
        require(current.isEmpty() || parsed.any { it["id"] == current }) { "current_goal_id must identify a subgoal" }
        val active = parsed.filter { it["status"] == "active" }
        require(active.size <= 1 && (active.isEmpty() || active.single()["id"] == current)) { "Only the current subgoal can be active" }
        require(current.isEmpty() || parsed.first { it["id"] == current }["status"] !in setOf("done", "skipped")) { "Current subgoal is already finished" }
        return parsed
    }
}
