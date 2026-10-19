package ai.opencyvis.memory

import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState
import org.json.JSONArray
import org.json.JSONObject

/** Event-driven retrieval, bounded cards and one-turn historical evidence. */
internal class MemoryRetrieval(
    private val manager: MemoryManager, private val task: TaskState, private val store: TaskRunStore
) {
    private val repository get() = manager.repository
    private var cacheKey: List<Any?> = emptyList()
    private var candidates = emptyList<MemoryItem>()
    private var confirmedRevisions = emptyMap<String, Int>()
    private var refreshVersion = 0
    private var explicitQuery: String? = null
    private var recall = emptyList<MemoryRecall>()
    private val excluded = mutableSetOf<String>()

    fun invalidate() { refreshVersion++ }
    fun consumeRecall() { recall = emptyList() }
    fun exclude(id: String) { excluded += id; invalidate() }

    suspend fun prepare(step: Int, observedPackage: String?, procedure: ProcedureExecution): MemoryPacket {
        val started = System.currentTimeMillis()
        val goal = task.plan.firstOrNull { it["id"] == task.currentGoalId }?.get("goal") as? String
        val context = task.memoryContext
        val stale = context["possibly_stale"] == true
        val app = if (context["app_uncertain"] == true) null else context["app"] as? String
        val page = if (stale) null else context["page"] as? String
        val scope = app?.takeIf(String::isNotBlank) ?: observedPackage
        val query = explicitQuery ?: listOfNotNull(goal,
            (context["query_terms"] as? List<*>)?.joinToString(" "), scope, page,
            task.userUpdates.lastOrNull()?.get("text") as? String,
            task.instruction.substringBefore("[procedure_reference]")).joinToString(" ")
        val key = listOf(task.currentGoalId, task.userUpdates.size, scope, observedPackage, page,
            context["query_terms"], repository.revisionToken(), refreshVersion)
        if (key != cacheKey) {
            confirmedRevisions = repository.confirmedRevisions()
            if (cacheKey.take(5) != key.take(5)) excluded.clear()
            candidates = repository.search(query, scope)
            val reference = task.instruction.substringAfter("[procedure_reference]", "").substringBefore("[/procedure_reference]").trim()
            if (reference.isNotEmpty()) {
                val id = JSONObject(reference).getString("id")
                repository.get(id)?.takeIf { it.status == "active" }?.let { candidates = listOf(it) + candidates }
            }
            candidates = candidates.distinctBy { it.id }
            explicitQuery = null
            cacheKey = key
            candidates.forEach { item -> record(item, step, "retrieved") }
            store.recordEvent(step, "memory_retrieval", mapOf("query" to query, "app_scope" to scope,
                "observed_package" to observedPackage, "candidate_ids" to candidates.map { it.id },
                "retrieval_ms" to System.currentTimeMillis() - started, "refresh_reason" to "context_or_memory_changed"))
        }
        val active = procedure.activeItem()
        val ordered = (listOfNotNull(active) + candidates).distinctBy { it.id }.filter { it.id !in excluded }
        val selected = mutableListOf<MemoryItem>()
        val text = StringBuilder()
        val details = procedure.details()
        if (active != null && details.isNotBlank()) {
            text.append("[current_method]\n").append(methodContext(active, details)).append('\n')
        }
        for (item in ordered) {
            val card = card(item)
            if (selected.size == 8 || text.length + card.length > 5000) continue
            text.append(card).append('\n')
            selected += item
            record(item, step, "injected")
        }
        store.recordEvent(step, "memory_context", mapOf("chars" to text.length,
            "injected_ids" to selected.map { it.id }, "historical_images" to recall.count { it.image != null }))
        return MemoryPacket(text.toString().ifBlank { "No relevant long-term memory." }, selected, recall)
    }

    suspend fun search(data: Map<String, Any?>): String {
        val query = data["memory_query"] as String
        if (data["memory_scope"] == "episodes") {
            val hits = repository.searchEpisodes(query).filter { it.runId != store.runId }.take(5)
            recall = hits.map { episode -> MemoryRecall("episode=${episode.id}; recorded_at=${episode.createdAt}; HISTORICAL",
                JSONObject().put("instruction", episode.instruction).put("summary", episode.summary.take(1000))
                    .put("source_type", episode.sourceType)
                    .put("run_status", episode.status).put("result_status", episode.resultStatus).toString()) }
            return "Historical search returned ${hits.size} episodes; read_memory with scope=episodes expands a result."
        }
        explicitQuery = query
        invalidate()
        return "Memory search queued for the next observation."
    }

    suspend fun read(data: Map<String, Any?>): String {
        val id = data["memory_id"] as String
        val details = data["memory_detail"] as? String ?: "details"
        val episode = data["memory_scope"] == "episodes"
        val label: String
        val body: String
        val evidence: List<MemoryEvidence>
        if (episode) {
            val record = requireNotNull(repository.getEpisode(id)) { "Episode not found" }
            label = "episode=$id; recorded_at=${record.createdAt}; HISTORICAL"
            body = JSONObject().put("instruction", record.instruction).put("summary", record.summary)
                .put("source_type", record.sourceType)
                .put("run_status", record.status).put("result_status", record.resultStatus)
                .apply { if (details != "summary") put("segments", JSONArray(record.segmentsJson)) }.toString()
            evidence = repository.episodeEvidence(id).distinctBy { it.runId to it.step }.sortedByDescending { it.step }.take(2)
        } else {
            val record = requireNotNull(repository.get(id)) { "Memory not found" }
            label = "memory=$id; revision=${record.revision}; HISTORICAL KNOWLEDGE"
            body = if (details == "summary") card(record) else MemoryBodies.snapshot(record)
            evidence = repository.evidence(id).filter { it.revision == record.revision }
                .distinctBy { it.runId to it.step }.sortedByDescending { it.observedAt }.take(2)
            if (record.status == "active") candidates = (listOf(record) + candidates).distinctBy { it.id }
        }
        val offset = (data["memory_offset"] as? Number)?.toInt() ?: 0
        require(offset in 0..body.length) { "memory_offset is outside this record" }
        val end = minOf(body.length, offset + 4800)
        val excerpt = if (offset == 0 && end == body.length) body else
            "[text_fragment offset=$offset; next_offset=${if (end < body.length) end else "end"}; total_chars=${body.length}]\n" + body.substring(offset, end)
        val result = mutableListOf(MemoryRecall(label, excerpt))
        if (details == "evidence") {
            evidence.forEach { entry ->
                result += MemoryRecall("run=${entry.runId}; step=${entry.step}; revision=${entry.revision}; actor=${entry.actor}; source=${entry.sourceType}; scope=${entry.supportScopeJson}; observed_at=${entry.observedAt}; HISTORICAL",
                    entry.text.take(1800), manager.evidence.readImage(entry))
            }
        }
        recall = result
        return "Read $label; details arrive with the next observation."
    }

    private fun card(item: MemoryItem): String {
        val body = JSONObject(item.bodyJson)
        val conditions = body.optJSONArray(if (item.type == "procedure") "preconditions" else "conditions")
        val shortConditions = JSONArray()
        for (i in 0 until minOf(conditions?.length() ?: 0, 4)) shortConditions.put(conditions!!.optString(i).take(100))
        return JSONObject().put("id", item.id).put("revision", item.revision).put("type", item.type)
            .put("title", item.title.take(100)).put("summary", item.summary.take(300))
            .put("app_scope", item.appScope).put("conditions", shortConditions)
            .put("evidence_level", item.evidenceLevel).put("last_verified_at", item.lastVerifiedAt)
            .put("origin", item.origin)
            .put("user_confirmed", confirmedRevisions[item.id] == item.revision)
            .apply { if (item.type == "procedure") put("completion", body.optString("completion").take(200)) }.toString()
    }

    /** Reserve most of the 5k context for the active node; cards use the remaining space. */
    private fun methodContext(item: MemoryItem, details: String): String {
        if (details.length <= 3500) return details
        val full = JSONObject(details)
        val node = full.getJSONObject("current_node")
        val dependencies = full.getJSONArray("data_dependencies")
        val core = JSONObject().put("id", item.id).put("revision", item.revision)
            .put("current_node", node).put("data_dependencies", dependencies)
            .put("partial", true).put("omitted_fields", JSONArray(listOf("preconditions", "completion")))
            .put("read_memory", "Read this memory's details for the omitted conditions and completion before relying on them.")
        if (core.toString().length <= 3500) return core.toString()
        // Huge individual fields remain explicitly incomplete, with enough room to name both sections.
        val nodeText = node.toString()
        val dependencyText = dependencies.toString()
        return """
            id=${item.id}; revision=${item.revision}; partial=true
            [current_node_preview chars=${minOf(900, nodeText.length)}/${nodeText.length}]
            ${nodeText.take(900)}
            [data_dependencies_preview count=${dependencies.length()}; chars=${minOf(1400, dependencyText.length)}/${dependencyText.length}]
            ${dependencyText.take(1400)}
            read_memory required: use this id, memory_scope=memories and memory_detail=details; follow next_offset until this node's dependencies and conditions are complete before acting.
        """.trimIndent()
    }

    private suspend fun record(item: MemoryItem, step: Int, event: String) = repository.recordUsage(MemoryUsage(
        id = "${store.runId}:$step:${item.id}:$event", memoryId = item.id, revision = item.revision,
        runId = store.runId, step = step, event = event, goalId = task.currentGoalId.ifEmpty { null }))
}
