package ai.opencyvis.learning

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.memory.MemoryBodies
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.memory.LearningMemoryCommit
import org.json.JSONArray
import org.json.JSONObject

internal class LearningCompiler(private val learning: LearningRepository, private val memory: MemoryRepository) {
    suspend fun advance(session: LearningSession, job: MemoryJob, checkpoint: JSONObject,
        client: LLMClientInterface): Boolean {
        val batches = checkpoint.getJSONArray("parse_batches")
        val index = checkpoint.optInt("compile_index")
        if (index >= batches.length()) return true
        val part = batches.getJSONObject(index)
        val draft = checkpoint.optJSONArray("working_draft") ?: JSONArray()
        val related = related(session, part)
        val knownTargets = related.associateBy { it.id }
        val evidence = memory.getEvidence(session.id).filter { it.memoryId == null }.associateBy { it.id }
        val supported = LearningInput.answerIds(session).toMutableSet()
        val segments = part.getJSONArray("segments")
        for (i in 0 until segments.length()) {
            val refs = segments.getJSONObject(i).getJSONArray("evidence_ids")
            for (j in 0 until refs.length()) supported += refs.getString(j)
        }
        for (i in 0 until draft.length()) {
            val refs = draft.getJSONObject(i).getJSONArray("source_evidence_ids")
            for (j in 0 until refs.length()) supported += refs.getString(j)
        }
        val input = LearningInput.sessionHeader(session).put("batch", index + 1).put("batch_count", batches.length())
            .put("analysis", part).put("working_draft", draft)
            .put("related_memories", JSONArray(related.map {
                JSONObject(MemoryBodies.snapshot(it)).put("id", it.id).put("revision", it.revision)
            })).put("questions", JSONArray(session.questionsJson))
        val messages = listOf(mapOf("role" to "system", "content" to LearningSchema.compilerPrompt),
            mapOf("role" to "user", "content" to input.toString()))
        val value = LearningModelCall(memory).validated(job.id, "compile:$index", checkpoint,
            client, messages, LearningSchema.compiler()) { output ->
            output.getString("summary")
            val rows = LearningProposals.assignIds(output.getJSONArray("proposals"))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val refs = row.getJSONArray("source_evidence_ids")
                val scopes = JSONObject()
                for (d in 0 until draft.length()) {
                    val prior = draft.getJSONObject(d).optJSONObject("source_support_scopes") ?: continue
                    for (r in 0 until refs.length()) {
                        val id = refs.getString(r)
                        if (prior.has(id)) scopes.put(id, prior.getJSONObject(id))
                    }
                }
                row.put("source_support_scopes", scopes)
            }
            val proposals = LearningProposals.decode(session.id, rows.toString())
            for (proposal in proposals) {
                require(proposal.sourceEvidenceIds.all { it in evidence && it in supported }) { "提案只能引用已提供的解析、草稿或用户说明中的原始证据" }
                if (proposal.expectedRevision != null) require(knownTargets[proposal.item.id]?.revision == proposal.expectedRevision) {
                    "提案引用了未提供的记忆版本"
                }
                val scope = JSONObject(proposal.supportScopeJson)
                scope.getJSONArray("nodes"); scope.getJSONArray("fields")
                scope.getBoolean("whole_procedure")
                LearningMemoryCommit.validateScope(knownTargets[proposal.item.id], proposal.item, scope)
            }
            LearningSchema.validateQuestions(output.getJSONArray("questions"))
            output
        }
        checkpoint.put("compile_index", index + 1).put("working_draft", value.getJSONArray("proposals"))
            .put("learning_summary", value.getString("summary")).put("questions", value.getJSONArray("questions"))
        val finished = index + 1 == batches.length()
        val analysis = JSONObject(session.analysisJson).put("learning_summary", value.getString("summary"))
        learning.put(session.copy(status = if (finished) {
            if (value.getJSONArray("questions").length() > 0) "awaiting_input" else "ready"
        } else "compiling", analysisJson = analysis.toString(), proposalsJson = value.getJSONArray("proposals").toString(),
            questionsJson = value.getJSONArray("questions").toString(), error = null, updatedAt = System.currentTimeMillis()))
        return finished
    }

    private suspend fun related(session: LearningSession, part: JSONObject): List<MemoryItem> {
        val source = JSONObject(session.sourceJson)
        val references = source.optJSONArray("memory_references") ?: JSONArray()
        val explicit = (0 until references.length()).mapNotNull {
            memory.get(references.getJSONObject(it).getString("id"))
        }
        val draft = JSONArray(session.proposalsJson)
        val targets = (0 until draft.length()).mapNotNull { draft.getJSONObject(it).optString("target_id").takeIf(String::isNotBlank) }
        val existing = targets.mapNotNull { memory.get(it) }
        val query = "${session.goal} ${part.optString("goal")} ${part.optString("summary")}"
        val matches = memory.searchForConsolidation(query, session.appScope, 12)
        // Exact correction targets and carried drafts must remain available in full.
        val selected = (explicit + existing).distinctBy { it.id }.toMutableList()
        var chars = selected.sumOf { MemoryBodies.snapshot(it).length }
        for (item in matches.distinctBy { it.id }.filter { candidate -> selected.none { it.id == candidate.id } }) {
            val size = MemoryBodies.snapshot(item).length
            if (chars + size > 14000) continue
            selected += item; chars += size
        }
        return selected
    }

}
