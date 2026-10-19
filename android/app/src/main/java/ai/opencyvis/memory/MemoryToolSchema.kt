package ai.opencyvis.memory

import ai.opencyvis.engine.LlmPrompts
import org.json.JSONArray
import org.json.JSONObject

/** Optional annotations share the existing phone_action call; retrieval remains an explicit action. */
object MemoryToolSchema {
    fun addTo(properties: JSONObject) {
        fun text(zh: String, en: String) = if (LlmPrompts.isChinese()) zh else en
        properties.put("memory_query", string(text("search_memory 的查询，可换同义表达重查", "Query for search_memory; rephrase if needed")))
        properties.put("memory_scope", string(text("查询 memories 或 episodes", "Search memories or episodes"), "memories", "episodes"))
        properties.put("memory_id", string(text("read_memory 的记忆或经历 ID", "Memory or episode ID for read_memory")))
        properties.put("memory_detail", string(text("summary/details/evidence；证据按需含历史图", "summary/details/evidence; images are historical"), "summary", "details", "evidence"))
        properties.put("memory_offset", integer(text("长详情的字符位置；按上次返回的 next_offset 继续读取", "Character offset for long details; use the returned next_offset")))
        properties.put("memory_app", string(text("适用 App 名称，空串表示不限定", "Applicable app name; empty means unspecified")))
        properties.put("memory_source_index", integer(text("明确长期指示的用户消息：0=原任务，1起=user_updates 顺序", "User message with explicit persistent instruction: 0=task, 1+=user_updates order")))
        properties.put("memory_source_quote", string(text("remember：原用户消息中的原文片段", "remember: exact excerpt from that user message")))
        properties.put("memory_context", obj(
            "app" to string(text("当前截图中识别的 App 名；未知省略", "App name observed in current screenshot; omit if unknown")),
            "page" to string(text("页面类别，如商品详情或笔记编辑", "Page kind, e.g. product detail or note editor")),
            "query_terms" to array(string(text("当前目标的检索词", "Search term for the current goal")))
        ).put("description", text("仅在状态变化或查询需要时附带；描述动作前当前图", "Optional on state changes or query needs; describes this observation before the action")))
        properties.put("memory_use", array(obj(
            "id" to string("Memory ID"), "revision" to integer("Memory revision"),
            "node_id" to string(text("流程节点，普通知识可省略", "Procedure node; omit for facts"))
        ).required("id", "revision")).put("maxItems", 2)
            .put("description", text("可选的本轮采用声明；也可观察结果后直接填 memory_feedback", "Optional adoption on this action; memory_feedback can also report usage after observing the result")))
        properties.put("memory_feedback", array(obj(
            "id" to string("Memory ID"), "revision" to integer("Memory revision"),
            "applied_step" to integer(text("此前实际使用它的动作 step", "Earlier action step where this memory was applied")),
            "outcome" to string("Local result", "success", "failure", "inapplicable", "unknown"),
            "cause" to string("Failure attribution", "memory", "environment", "unknown"),
            "evidence_step" to integer(text("实际观察结果的 step", "Step where the result was observed")),
            "evidence" to string(text("具体观察；不能预报本轮动作结果", "Concrete observation; never predict this action's outcome"))
        ).required("id", "revision", "applied_step", "outcome", "evidence_step", "evidence")).put("maxItems", 2)
            .put("description", text("反馈实际使用过的记忆及后续观察，不要求此前填写 memory_use；动作成功仅支持对应做法", "Report a used memory and a later observation; prior memory_use is not required. Action success supports only the relevant claim")))
        properties.put("procedure_update", obj(
            "op" to string("Procedure transition", "start", "rebind", "advance", "branch", "complete", "abort"),
            "id" to string("Procedure ID (start)"), "revision" to integer("Procedure revision (start)"),
            "node_id" to string("Entry or next node ID"), "goal_id" to string("Related plan goal ID, if any"),
            "bindings" to array(obj(
                "name" to string("Parameter name"), "value" to string("Value from this task"),
                "task_memory_key" to string("Source task-memory key, or omit for user input"),
                "user_source_index" to integer("Source user message: 0=task, 1+=updates")
            ).required("name", "value")),
            "evidence_step" to integer("Observed result step"),
            "evidence" to string("Observed result or reason for exit")
        ).required("op"))
    }

    private fun string(description: String, vararg values: String) = JSONObject()
        .put("type", "string").put("description", description).apply {
            if (values.isNotEmpty()) put("enum", JSONArray(values.toList()))
        }
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun array(item: JSONObject) = JSONObject().put("type", "array").put("items", item)
    private fun obj(vararg fields: Pair<String, JSONObject>) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { fields.forEach { (name, schema) -> put(name, schema) } })
    private fun JSONObject.required(vararg names: String) = put("required", JSONArray(names.toList()))
}
