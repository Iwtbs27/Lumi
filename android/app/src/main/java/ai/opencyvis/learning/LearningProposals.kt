package ai.opencyvis.learning

import ai.opencyvis.memory.MemoryBodies
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryProposal
import ai.opencyvis.memory.MemoryEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The editable draft is the single source used for preview and the eventual atomic commit. */
internal object LearningProposals {
    fun decode(sessionId: String, raw: String): List<MemoryProposal> {
        val rows = JSONArray(raw)
        require(rows.length() <= 8) { "一次最多保存 8 条学习结果" }
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val type = row.getString("type")
            val title = row.getString("title").trim()
            val summary = row.getString("summary").trim()
            val reason = row.getString("reason").trim()
            require(title.isNotBlank() && summary.isNotBlank() && reason.isNotBlank()) { "第 ${index + 1} 条缺少标题、摘要或依据" }
            val body = row.getJSONObject("body")
            MemoryBodies.validate(type, body)
            val target = row.optString("target_id").takeIf(String::isNotBlank)
            val evidence = row.getJSONArray("source_evidence_ids")
            val ids = (0 until evidence.length()).map(evidence::getString).distinct()
            require(ids.isNotEmpty()) { "第 ${index + 1} 条没有示范证据" }
            MemoryProposal(
                item = MemoryItem(id = target ?: row.getString("memory_id"), type = type, title = title,
                    summary = summary, appScope = row.optString("app_scope").takeIf(String::isNotBlank),
                    taskFamily = row.optString("task_family").takeIf(String::isNotBlank),
                    origin = "learned", status = "candidate", evidenceLevel = "unverified", bodyJson = body.toString()),
                expectedRevision = target?.let { row.getInt("target_revision") }, reason = reason,
                sourceRunId = sessionId, sourceEvidenceIds = ids,
                supportScopeJson = row.getJSONObject("support_scope").toString(),
                sourceSupportScopes = row.optJSONObject("source_support_scopes")?.let { scopes ->
                    scopes.keys().asSequence().associateWith { scopes.getJSONObject(it).toString() }
                }.orEmpty()
            )
        }
    }

    /** Explicit edits are new human evidence, not something the original demonstration showed. */
    fun recordEdits(session: LearningSession, rows: JSONArray, firstStep: Int): List<MemoryEvidence> {
        val previous = JSONArray(session.proposalsJson)
        require(rows.length() == previous.length()) { "提案已变化，请重新打开" }
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val old = previous.getJSONObject(i)
                require(row.getString("memory_id") == old.getString("memory_id")) { "编辑不能更换记忆编号" }
                if (MemoryBodies.sameJson(row.toString(), old.toString())) continue
                val body = row.getJSONObject("body")
                val before = old.getJSONObject("body")
                val scope = JSONObject(row.getJSONObject("support_scope").toString())
                fun same(a: Any?, b: Any?) = MemoryBodies.sameJson(
                    JSONObject().put("v", a ?: JSONObject.NULL).toString(), JSONObject().put("v", b ?: JSONObject.NULL).toString())
                val fields = scope.getJSONArray("fields")
                val fieldNames = (0 until fields.length()).map(fields::getString).toMutableSet()
                for (key in (before.keys().asSequence() + body.keys().asSequence()).toSet()) {
                    if (key != "nodes" && !same(before.opt(key), body.opt(key))) fieldNames += key
                }
                val nodes = scope.getJSONArray("nodes")
                val nodeIds = (0 until nodes.length()).map(nodes::getString).toMutableSet()
                val oldNodes = before.optJSONArray("nodes") ?: JSONArray()
                val newNodes = body.optJSONArray("nodes") ?: JSONArray()
                val oldById = (0 until oldNodes.length()).map(oldNodes::getJSONObject).associateBy { it.getString("node_id") }
                for (n in 0 until newNodes.length()) {
                    val node = newNodes.getJSONObject(n)
                    val nodeId = node.getString("node_id")
                    if (!same(oldById[nodeId], node)) nodeIds += nodeId
                }
                scope.put("fields", JSONArray(fieldNames.toList())).put("nodes", JSONArray(nodeIds.toList()))
                row.put("support_scope", scope)
                val refs = row.getJSONArray("source_evidence_ids")
                val scopes = JSONObject()
                for (r in 0 until refs.length()) scopes.put(refs.getString(r), JSONObject()
                    .put("context_only", true).put("whole_procedure", false))
                val id = "${session.id}:edit:${UUID.randomUUID()}"
                refs.put(id)
                scopes.put(id, scope)
                row.put("source_support_scopes", scopes)
                add(MemoryEvidence(id = id, episodeId = session.id, runId = session.id, step = firstStep + size,
                    kind = "user_explanation", text = JSONObject().put("action", "user_edited_learning_draft")
                        .put("title", row.getString("title")).put("body", body).put("scope", scope).toString(),
                    author = "user", actor = "human", sourceType = session.sourceType, observationType = "user_stated"))
            }
        }
    }

    fun assignIds(rows: JSONArray): JSONArray {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            row.put("memory_id", row.optString("target_id").takeIf(String::isNotBlank)
                ?: row.optString("memory_id").takeIf(String::isNotBlank) ?: UUID.randomUUID().toString())
        }
        return rows
    }
}
