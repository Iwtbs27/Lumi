package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.memory.MemoryEpisode
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** A durable review/parse/compile state machine. Human acceptance stays outside the Worker. */
internal class LearningProcessor(context: Context) {
    private val learning = LearningRepository(context)
    private val memory = MemoryRepository(context)

    suspend fun advance(job: MemoryJob, client: LLMClientInterface): Boolean {
        val session = learning.get(job.runId) ?: return true
        if (session.status in setOf("discarded", "saved")) return true
        if (session.status == "skipped") {
            memory.updateJob(job.copy(status = "completed"))
            return true
        }
        val checkpoint = JSONObject(job.checkpointJson)
        if (job.kind == "learning_review") {
            learning.put(session.copy(status = "reviewing", error = null))
            val decision = TaskReview(learning, memory).run(session, job, checkpoint, client)
            val current = memory.getJob(job.id) ?: return true
            if (current.status == "cancelled") return true
            val skipped = decision.decision == "skip"
            checkpoint.put("review_completed", true).put("task_review", decision.json())
            learning.put(session.copy(status = if (skipped) "skipped" else "parsing", error = null,
                sourceJson = JSONObject(session.sourceJson).put("task_review", decision.json()).toString(),
                updatedAt = System.currentTimeMillis()))
            memory.updateJob(current.copy(kind = if (skipped) job.kind else "learning_parse",
                checkpointJson = checkpoint.toString(), status = if (skipped) "completed" else "pending"))
            return skipped
        }
        val parsing = job.kind == "learning_parse"
        learning.put(session.copy(status = if (parsing) "parsing" else "compiling", error = null))
        val done = if (parsing) DemonstrationParser(learning, memory).advance(session, job, checkpoint, client)
            else LearningCompiler(learning, memory).advance(session, job, checkpoint, client)
        val current = memory.getJob(job.id) ?: return true
        if (current.status == "cancelled") return true
        if (parsing && done) checkpoint.put("parse_completed", true)
        if (!parsing && done) {
            val result = learning.get(session.id) ?: return true
            if (result.status == "discarded") return true
            publishEpisode(result)
        }
        memory.updateJob(current.copy(kind = if (parsing && done) "learning_compile" else job.kind,
            checkpointJson = checkpoint.toString(), status = if (!parsing && done) "completed" else "pending"))
        return !parsing && done
    }

    private suspend fun publishEpisode(session: LearningSession) {
        val analysis = JSONObject(session.analysisJson)
        val evidence = memory.getEvidence(session.id).filter { it.memoryId == null }
        val parent = session.parentRunId?.let { memory.getEpisode(it) }
        memory.saveEpisode(MemoryEpisode(id = session.id, instruction = session.goal.ifBlank { analysis.optString("goal") },
            conversationId = parent?.conversationId, summary = analysis.optString("learning_summary"), status = "completed",
            // A human demonstration never becomes an agent success just because parsing finished.
            resultStatus = analysis.optString("outcome", "unknown"),
            segmentsJson = analysis.getJSONArray("segments").toString(),
            evidenceRefsJson = JSONArray(evidence.map { it.id }).toString(), appScope = session.appScope,
            createdAt = session.createdAt, sourceType = session.sourceType))
    }
}
