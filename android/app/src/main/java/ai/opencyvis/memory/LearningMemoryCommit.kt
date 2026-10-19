package ai.opencyvis.memory

import org.json.JSONArray
import org.json.JSONObject

/** Runs inside the caller's transaction after the user accepts a learning draft. */
internal class LearningMemoryCommit(
    private val dao: MemoryDao,
    private val save: (MemoryItem, MemoryItem?, String) -> Unit
) {
    fun apply(proposal: MemoryProposal, runId: String, enable: Boolean): MemoryWriteResult {
        val proposed = proposal.item
        require(proposal.sourceRunId == runId) { "学习提案来源与会话不一致" }
        require(proposed.title.isNotBlank() && proposed.summary.isNotBlank()) { "记忆标题和摘要不能为空" }
        MemoryBodies.validate(proposed.type, JSONObject(proposed.bodyJson))
        check(dao.blocked(MemoryBodies.identity(proposed)) == 0) { "该记忆已删除，请重新整理" }
        val old = dao.get(proposed.id)
        require(old == null || old.type == proposed.type) { "学习更新不能改变记忆类型" }
        check(if (old == null) proposal.expectedRevision == null else old.revision == proposal.expectedRevision) {
            "记忆版本已变化，请重新整理后确认"
        }
        if (old == null) check(dao.sameIdentity(proposed.title, proposed.type,
            proposed.appScope.orEmpty(), proposed.taskFamily.orEmpty()).isEmpty()) {
            "已有同名记忆，请重新整理并合并到当前版本"
        }
        val raw = dao.runEvidence(runId).filter { it.memoryId == null }
        val source = if (proposal.sourceEvidenceIds.isNotEmpty()) {
            proposal.sourceEvidenceIds.distinct().map { id ->
                requireNotNull(raw.firstOrNull { it.id == id }) { "学习依据已不存在：$id" }
            }
        } else proposal.sourceSteps.distinct().map { step ->
            requireNotNull(raw.firstOrNull { it.step == step }) { "学习依据已不存在：$step" }
        }
        require(source.isNotEmpty()) { "学习提案需要来源依据" }
        require(source.all { it.sourceType in HUMAN_SOURCES }) { "学习提案需要本会话的示范依据" }
        val scope = JSONObject(proposal.supportScopeJson)
        validateScope(old, proposed, scope)
        require(proposal.sourceSupportScopes.keys.all { id -> source.any { it.id == id } }) { "来源范围引用了未提交的证据" }
        val unchanged = old != null && old.title == proposed.title && old.summary == proposed.summary &&
            old.appScope == proposed.appScope && old.taskFamily == proposed.taskFamily &&
            MemoryBodies.sameContent(old.bodyJson, proposed.bodyJson)
        val body = JSONObject(proposed.bodyJson)
        if (proposed.type == "procedure") body.put("maturity",
            if (unchanged) JSONObject(old!!.bodyJson).optString("maturity", "observed") else "observed")
        val status = if (enable) "active" else "candidate"
        val sameVersion = unchanged && MemoryBodies.sameJson(old!!.bodyJson, body.toString())
        val item = proposed.copy(id = old?.id ?: proposed.id,
            revision = if (sameVersion && old!!.status == status) old.revision else (old?.revision ?: 0) + 1,
            status = status, origin = old?.origin?.takeIf { it in setOf("explicit_user", "user_edit") } ?: "learned",
            evidenceLevel = if (unchanged) old!!.evidenceLevel else "unverified",
            lastVerifiedAt = if (unchanged) old!!.lastVerifiedAt else null,
            bodyJson = body.toString(), createdAt = old?.createdAt ?: proposed.createdAt,
            updatedAt = System.currentTimeMillis())
        save(item, old?.takeIf { it.revision != item.revision }, proposal.reason)
        if (old != null && !unchanged) carryUnchangedSupport(old, item)
        source.forEach { evidence ->
            val attached = evidence.copy(memoryId = item.id, revision = item.revision,
                supportScopeJson = proposal.sourceSupportScopes[evidence.id]?.also { JSONObject(it) } ?: scope.toString())
            dao.putEvidence(attached.copy(id = memoryEvidenceVersionId(item, attached)))
        }
        val review = MemoryEvidence(memoryId = item.id, revision = item.revision,
            episodeId = runId, runId = runId, step = 0, kind = "user_confirmation",
            text = "User accepted this learning proposal and its scope.", author = "user_review",
            actor = "human", sourceType = "user_confirmation")
        dao.putEvidence(review.copy(id = memoryEvidenceVersionId(item, review)))
        return MemoryWriteResult(item.id, if (old == null) "created" else if (unchanged) "supported" else "updated")
    }

    private fun carryUnchangedSupport(old: MemoryItem, item: MemoryItem) {
        val before = JSONObject(old.bodyJson)
        val after = JSONObject(item.bodyJson)
        fun node(body: JSONObject, id: String): JSONObject? {
            val nodes = body.optJSONArray("nodes") ?: return null
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }.firstOrNull { it.optString("node_id") == id }
        }
        for (evidence in dao.evidence(old.id).filter { it.revision == old.revision && it.kind != "user_confirmation" }) {
            val scope = JSONObject(evidence.supportScopeJson)
            val nodes = scope.optJSONArray("nodes") ?: JSONArray()
            val retainedNodes = (0 until nodes.length()).map { nodes.getString(it) }.filter { id ->
                val previous = node(before, id)
                val current = node(after, id)
                previous != null && current != null && MemoryBodies.sameContent(previous.toString(), current.toString())
            }
            val fields = scope.optJSONArray("fields") ?: JSONArray()
            val retainedFields = (0 until fields.length()).map { fields.getString(it) }.filter { field ->
                before.has(field) && after.has(field) && MemoryBodies.sameContent(
                    JSONObject().put("value", before.get(field)).toString(), JSONObject().put("value", after.get(field)).toString())
            }
            if (retainedNodes.isEmpty() && retainedFields.isEmpty()) continue
            val retainedScope = JSONObject().put("nodes", JSONArray(retainedNodes))
                .put("fields", JSONArray(retainedFields)).put("whole_procedure", false)
            dao.putEvidence(evidence.copy(id = memoryEvidenceVersionId(item, evidence), revision = item.revision,
                supportScopeJson = retainedScope.toString()))
        }
    }

    companion object {
        private val HUMAN_SOURCES = setOf("human_demo", "human_correction", "human_observation")

        /** Also used while compiling, so out-of-scope rewrites are corrected before user review. */
        fun validateScope(old: MemoryItem?, item: MemoryItem, scope: JSONObject) {
            val before = old?.let { JSONObject(it.bodyJson) }
            val after = JSONObject(item.bodyJson)
            fun nodes(body: JSONObject?): Map<String, JSONObject> {
                val rows = body?.optJSONArray("nodes") ?: JSONArray()
                return (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("node_id") }
            }
            fun strings(key: String): Set<String> = scope.optJSONArray(key)?.let { rows ->
                (0 until rows.length()).map { rows.getString(it) }.toSet()
            }.orEmpty()
            fun same(first: Any?, second: Any?): Boolean = MemoryBodies.sameJson(
                JSONObject().put("value", first ?: JSONObject.NULL).toString(),
                JSONObject().put("value", second ?: JSONObject.NULL).toString())
            val previousNodes = nodes(before)
            val currentNodes = nodes(after)
            val citedNodes = strings("nodes")
            val citedFields = strings("fields")
            val knownNodes = previousNodes.keys + currentNodes.keys
            val knownFields = before?.keys()?.asSequence()?.toSet().orEmpty() + after.keys().asSequence().toSet()
            require(citedNodes.all { it in knownNodes }) { "证据支持范围引用了不存在的节点" }
            require(citedFields.all { it in knownFields }) { "证据支持范围引用了不存在的字段" }
            if (old == null || scope.optBoolean("whole_procedure")) return
            require(old.appScope == item.appScope && old.taskFamily == item.taskFamily) { "局部更新不能改变方法的App或任务范围" }
            for (field in knownFields - setOf("nodes", "maturity")) {
                require(field in citedFields || same(before?.opt(field), after.opt(field))) { "修改超出证据范围：$field" }
            }
            // Listing the top-level nodes field never authorizes edits to unlisted nodes.
            for (id in knownNodes) require(id in citedNodes || same(previousNodes[id], currentNodes[id])) {
                "修改超出证据范围：节点$id"
            }
            require(previousNodes.keys.filter { it !in citedNodes } == currentNodes.keys.filter { it !in citedNodes }) {
                "局部更新不能重排未涉及的节点"
            }
            val previousEntry = previousNodes.keys.firstOrNull()
            val currentEntry = currentNodes.keys.firstOrNull()
            require(previousEntry == currentEntry || previousEntry in citedNodes && currentEntry in citedNodes) {
                "修改入口节点需要将原入口和新入口列入证据范围"
            }
        }
    }
}

internal fun memoryEvidenceVersionId(item: MemoryItem, evidence: MemoryEvidence): String =
    "${item.id}:${item.revision}:${evidence.runId}:${evidence.step}:${evidence.kind}:${evidence.author}:${evidence.actor}:${evidence.sourceType}"
