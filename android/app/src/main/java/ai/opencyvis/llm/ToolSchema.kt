package ai.opencyvis.llm

import ai.opencyvis.engine.LlmPrompts
import org.json.JSONArray
import org.json.JSONObject

object ToolSchema {

    fun phoneActionTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", "phone_action")
            .put("description", LlmPrompts.toolDescription())
            .put("parameters", parameters()))

    private fun parameters(): JSONObject {
        val properties = JSONObject()
        for (key in listOf(
            "thought", "app_name", "keyword", "text", "reason", "question", "handoff_reason",
            "note", "memory_key", "memory_value", "memory_category", "routine_name", "routine_icon",
            "routine_instruction", "schedule_time", "schedule_repeat", "schedule_location",
            "suggested_routine_name", "suggested_routine_icon", "current_goal_id", "plan_reason",
            "recall_query"
        )) {
            properties.put(key, field("string", key))
        }
        for (key in listOf("x", "y", "schedule_interval")) {
            properties.put(key, field("integer", key))
        }
        properties.put("schedule_on_enter", field("boolean", "schedule_on_enter"))
        properties.put("action_type", field("string", "action_type", listOf(
            "tap", "long_press", "open_app", "list_apps", "swipe", "key_event", "type_text",
            "wait", "finish", "fail", "ask_user", "handoff_user", "note", "remember", "save_routine",
            "update_plan", "recall_history", "search_memory", "read_memory",
            "delegate_task", "read_child", "review_child", "cancel_child"
        ) + ai.opencyvis.execution.ExecutionToolSchema.actions))
        properties.put("direction", field("string", "direction", listOf("up", "down", "left", "right")))
        properties.put("handoff_kind", field("string", "handoff_kind", listOf(
            "gui_difficulty", "captcha", "account_access", "other"
        )))
        properties.put("key", field("string", "key", listOf("back", "home", "enter", "recent")))
        properties.put("schedule_type", field("string", "schedule_type", listOf("time", "interval", "geofence")))

        properties.put("memory_update", field("array", "memory_update").put("items", objectSchema(JSONObject()
            .put("op", field("string", "memory_op", listOf("upsert", "delete")))
            .put("key", field("string", "task_memory_key"))
            .put("value", field("string", "task_memory_value"))
            .put("source_step", field("integer", "source_step")), "op", "key")))

        properties.put("previous_action_result", objectSchema(JSONObject()
            .put("step", field("integer", "result_step"))
            .put("status", field("string", "result_status", listOf(
                "confirmed_success", "confirmed_failure", "unknown"
            )))
            .put("evidence", field("string", "result_evidence")), "step", "status", "evidence")
            .put("description", LlmPrompts.paramDescription("previous_action_result")))

        properties.put("plan", field("array", "plan").put("items", objectSchema(JSONObject()
            .put("id", field("string", "goal_id"))
            .put("goal", field("string", "goal"))
            .put("done_when", field("string", "done_when"))
            .put("status", field("string", "goal_status", listOf(
                "pending", "active", "done", "blocked", "skipped"
            )))
            .put("evidence_step", field("integer", "evidence_step")),
            "id", "goal", "done_when", "status")))
        properties.put("recall_steps", field("array", "recall_steps")
            .put("items", JSONObject().put("type", "integer")))
        properties.put("recall_content", field("string", "recall_content", listOf("text", "image", "both")))
        properties.put("artifact_ref", JSONObject().put("type", "string").put("description",
            "Exact text reference returned in context; use with recall_history to retrieve a page from this run"))
        properties.put("recall_offset", JSONObject().put("type", "integer").put("minimum", 0).put("description",
            "Text page start; first page uses 0, following pages use returned next_offset"))
        properties.put("recall_limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 4800)
            .put("description", "Maximum text characters in a recalled page, up to 4800"))

        ai.opencyvis.memory.MemoryToolSchema.addTo(properties)
        properties.put("child_goal", JSONObject().put("type", "string").put("description",
            "A bounded goal for delegate_task (research) or delegate_coding (prepare and verify a program)"))
        properties.put("child_constraints", JSONObject().put("type", "array")
            .put("items", JSONObject().put("type", "string")))
        properties.put("evidence_steps", JSONObject().put("type", "array").put("description",
            "Previously observed steps to freeze and share with the child")
            .put("items", JSONObject().put("type", "integer")))
        properties.put("artifact_refs", JSONObject().put("type", "array").put("description",
            "Exact immutable evidence or file artifact references already returned by this task")
            .put("items", JSONObject().put("type", "string")))
        properties.put("child_id", JSONObject().put("type", "string").put("description",
            "Child identity returned by delegation; used by read_child, review_child, cancel_child"))
        properties.put("child_accept", JSONObject().put("type", "boolean").put("description",
            "Explicit parent decision after checking a child result; review_child also requires reason"))
        properties.put("child_wait_ms", JSONObject().put("type", "integer").put("minimum", 0)
            .put("maximum", 30000).put("description",
                "read_child may wait up to this duration for the child, without a model request; default 0"))
        ai.opencyvis.execution.ExecutionToolSchema.addTo(properties)
        return objectSchema(properties, "thought", "action_type", "memory_update")
    }

    private fun field(type: String, key: String, values: List<String>? = null): JSONObject =
        JSONObject().put("type", type).put("description", LlmPrompts.paramDescription(key)).apply {
            if (values != null) put("enum", JSONArray(values))
        }

    private fun objectSchema(properties: JSONObject, vararg required: String): JSONObject =
        JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(required.toList()))

    fun toolsArray(): JSONArray = JSONArray().put(phoneActionTool())

    fun anthropicPhoneActionTool(): JSONObject = JSONObject()
        .put("name", "phone_action")
        .put("description", LlmPrompts.toolDescription())
        .put("input_schema", parameters())

    fun anthropicToolsArray(): JSONArray = JSONArray().put(anthropicPhoneActionTool())
}
