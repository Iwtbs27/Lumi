package ai.opencyvis.learning

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

internal class DemonstrationParser(private val learning: LearningRepository, private val memory: MemoryRepository) {
    /** One persisted model round; returns true when all capture batches have been parsed. */
    suspend fun advance(session: LearningSession, job: MemoryJob, checkpoint: JSONObject,
        client: LLMClientInterface): Boolean {
        val batches = LearningInput.batches(learning.events(session.id), learning.frames(session.id))
        require(batches.isNotEmpty()) { "没有可解析的记录" }
        val parsed = checkpoint.optJSONArray("parse_batches") ?: JSONArray()
        val index = parsed.length()
        if (index >= batches.size) return true
        val batch = batches[index]
        val evidence = memory.getEvidence(session.id).filter { it.memoryId == null }
        val byId = evidence.associateBy { it.id }
        val initial = LearningInput.initialFrames(batch.frames).map { it.evidenceId }.toSet()
        val recalled = checkpoint.optJSONArray("recalled_ids") ?: JSONArray()
        val recalledIds = (0 until recalled.length()).map(recalled::getString).toSet()
        val imageIds = initial + recalledIds.filter { byId[it]?.imagePath != null }
        val seenIds = initial + recalledIds + batch.records.filter { it.optString("kind") != "frame_index" }
            .map { it.getString("evidence_id") } + LearningInput.answerIds(session)
        val round = checkpoint.optInt("parse_round")
        val header = LearningInput.sessionHeader(session).put("batch", index + 1).put("batch_count", batches.size)
            .put("records", JSONArray(batch.records)).put("previous_summary", parsed.optJSONObject(index - 1)?.optString("summary"))
            .put("remaining_evidence_rounds", 2 - round).put("remaining_image_budget", 12 - imageIds.size)
        val content = mutableListOf(LearningInput.text(header.toString()))
        content += LearningInput.evidenceContent((initial + recalledIds).mapNotNull(byId::get), imageIds)
        val messages = listOf(mapOf("role" to "system", "content" to LearningSchema.parserPrompt),
            mapOf("role" to "user", "content" to content))
        val output = LearningModelCall(memory).validated(job.id, "parse:$index:$round", checkpoint,
            client, messages, LearningSchema.parser()) { value ->
            value.getString("reason")
            when (value.getString("action")) {
                "read_learning_evidence" -> {
                    val requested = value.getJSONArray("evidence_ids")
                    require(round < 2 && requested.length() in 1..6) { "回看预算已用完，请提交解析并保留未知项" }
                    val ids = (0 until requested.length()).map(requested::getString)
                    require(ids.all { it in byId }) { "回看引用了不存在的证据" }
                    require((imageIds + ids.filter { byId[it]?.imagePath != null }).size <= 12) { "本批最多查看12张不同图片" }
                }
                "submit_analysis" -> {
                    val analysis = value.getJSONObject("analysis")
                    analysis.getString("summary"); analysis.getString("goal")
                    require(analysis.getString("goal_source") in setOf("user", "task", "inferred", "unknown")) { "目标来源不合法" }
                    require(analysis.getString("outcome") in setOf("observed_success", "user_reported", "partial", "unknown")) { "结果状态不合法" }
                    analysis.getJSONArray("unknowns")
                    val segments = analysis.getJSONArray("segments")
                    for (i in 0 until segments.length()) {
                        val segment = segments.getJSONObject(i)
                        segment.getString("goal"); segment.getString("before"); segment.getString("action"); segment.getString("after")
                        val refs = segment.getJSONArray("evidence_ids")
                        require(refs.length() > 0 && (0 until refs.length()).all { refs.getString(it) in seenIds }) {
                            "解析片段只能引用已提供内容的证据；未查看的图片请先回看"
                        }
                    }
                    LearningSchema.validateQuestions(value.getJSONArray("questions"))
                }
                else -> throw IllegalArgumentException("未知学习解析操作")
            }
            value
        }
        if (output.getString("action") == "read_learning_evidence") {
            val ids = output.getJSONArray("evidence_ids")
            checkpoint.put("recalled_ids", JSONArray((recalledIds + (0 until ids.length()).map(ids::getString)).toList()))
                .put("parse_round", round + 1).put("last_evidence_question", output.getString("reason"))
            return false
        }
        val analysis = output.getJSONObject("analysis").put("questions", output.getJSONArray("questions"))
        parsed.put(analysis)
        checkpoint.put("parse_batches", parsed).put("parse_round", 0).put("recalled_ids", JSONArray())
            .put("batch_count", batches.size)
        val allSegments = JSONArray()
        val unknowns = JSONArray()
        val summaries = mutableListOf<String>()
        for (i in 0 until parsed.length()) {
            val part = parsed.getJSONObject(i)
            summaries += part.getString("summary")
            val segments = part.getJSONArray("segments")
            for (j in 0 until segments.length()) allSegments.put(segments.getJSONObject(j).put("batch", i + 1))
            val unknown = part.optJSONArray("unknowns") ?: JSONArray()
            for (j in 0 until unknown.length()) unknowns.put(unknown.getString(j))
        }
        val merged = JSONObject().put("summary", summaries.joinToString("\n")).put("goal", analysis.getString("goal"))
            .put("goal_source", analysis.getString("goal_source")).put("outcome", analysis.getString("outcome"))
            .put("segments", allSegments).put("unknowns", unknowns)
        learning.put(session.copy(status = "parsing", analysisJson = merged.toString(),
            questionsJson = output.getJSONArray("questions").toString(), updatedAt = System.currentTimeMillis()))
        return parsed.length() == batches.size
    }
}
