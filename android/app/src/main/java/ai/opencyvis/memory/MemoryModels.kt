package ai.opencyvis.memory

import org.json.JSONArray
import org.json.JSONObject

data class MemoryProposal(
    val item: MemoryItem,
    val expectedRevision: Int? = null,
    val reason: String,
    val sourceRunId: String,
    val sourceSteps: List<Int> = emptyList(),
    val sourceEvidenceIds: List<String> = emptyList(),
    val supportScopeJson: String = "{}",
    val sourceSupportScopes: Map<String, String> = emptyMap()
)

data class MemoryWriteResult(val id: String, val status: String, val reason: String = "")

/** JSON contracts are shared by the runtime, editor and consolidation model. */
object MemoryBodies {
    fun fact(value: String, domain: String = "user_preference", sourceRef: String? = null): String =
        JSONObject().put("domain", domain).put("value", value)
            .put("conditions", JSONArray()).apply {
                if (sourceRef != null) put("source_ref", sourceRef)
            }.toString()

    // Procedure: goal, parameters[{name,description,required}],
    // preconditions[string], nodes[{node_id,intent,expected,next,branches[{condition,next}]}],
    // completion, failure_notes[string], aliases[string], maturity,
    // data_dependencies[{source,transform,target_node,target_field}] (optional).
    fun snapshot(item: MemoryItem): String = JSONObject()
        .put("title", item.title).put("summary", item.summary).put("body", JSONObject(item.bodyJson))
        .put("type", item.type).put("app_scope", item.appScope).put("task_family", item.taskFamily)
        .put("status", item.status).put("origin", item.origin).put("evidence_level", item.evidenceLevel)
        .put("last_verified_at", item.lastVerifiedAt).toString()

    fun identity(item: MemoryItem): String = listOf(item.type, item.appScope.orEmpty(),
        item.taskFamily.orEmpty(), item.title.trim().lowercase()).joinToString("\u001f")

    fun sameContent(first: String, second: String): Boolean =
        structure(JSONObject(first), setOf("maturity", "aliases")) == structure(JSONObject(second), setOf("maturity", "aliases"))

    fun sameJson(first: String, second: String): Boolean = structure(JSONObject(first)) == structure(JSONObject(second))

    fun sameReviewedContent(first: String, second: String): Boolean =
        structure(JSONObject(first), setOf("maturity")) == structure(JSONObject(second), setOf("maturity"))

    private fun structure(value: Any?, ignored: Set<String> = emptySet()): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().filter { it !in ignored }.sorted()
            .associateWith { structure(value.get(it), ignored) }
        is JSONArray -> (0 until value.length()).map { structure(value.get(it), ignored) }
        else -> value
    }

    fun validate(type: String, body: JSONObject) {
        require(type in setOf("fact", "procedure")) { "未知记忆类型：$type" }
        if (type == "fact") {
            require(body.getString("domain") in setOf("user_preference", "app_knowledge")) { "Fact domain 无效" }
            require(body.getString("value").isNotBlank()) { "Fact value 不能为空" }
            body.getJSONArray("conditions")
            return
        }
        require(body.getString("goal").isNotBlank() && body.getString("completion").isNotBlank()) {
            "方法缺少目标或完成标准"
        }
        val parameters = body.getJSONArray("parameters")
        val names = (0 until parameters.length()).map {
            val parameter = parameters.getJSONObject(it)
            parameter.getBoolean("required")
            parameter.getString("description")
            parameter.getString("name")
        }
        require(names.all { it.isNotBlank() } && names.toSet().size == names.size) { "方法参数名称必须唯一且非空" }
        body.getJSONArray("preconditions")
        val nodes = body.getJSONArray("nodes")
        require(nodes.length() > 0) { "方法至少需要一个节点" }
        val ids = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("node_id") }
        require(ids.all { it.isNotBlank() } && ids.toSet().size == ids.size) { "方法节点 id 必须唯一且非空" }
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            require(node.getString("intent").isNotBlank() && node.getString("expected").isNotBlank()) {
                "方法节点缺少操作意图或预期结果"
            }
            val next = node.optString("next")
            require(next.isEmpty() || next in ids) { "方法后续节点不存在" }
            val branches = node.optJSONArray("branches") ?: JSONArray()
            for (j in 0 until branches.length()) require(branches.getJSONObject(j).getString("next") in ids) {
                "方法分支节点不存在"
            }
        }
        if (!body.has("data_dependencies")) return
        val dependencies = body.getJSONArray("data_dependencies")
        for (i in 0 until dependencies.length()) {
            val dependency = dependencies.getJSONObject(i)
            require(dependency.getString("source").isNotBlank() && dependency.getString("target_field").isNotBlank()) {
                "数据依赖需要来源和目标字段"
            }
            require(dependency.getString("target_node") in ids) { "数据依赖的目标节点不存在" }
            dependency.getString("transform")
        }
    }
}
