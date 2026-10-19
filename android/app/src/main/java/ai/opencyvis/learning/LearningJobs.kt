package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryJobs
import ai.opencyvis.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** Uses the same WorkManager worker and foreground lease as ordinary memory consolidation. */
internal class LearningJobs(context: Context) {
    private val appContext = context.applicationContext
    private val memory = MemoryRepository(appContext)
    private val learning = LearningRepository(appContext)

    suspend fun enqueue(sessionId: String, recompile: Boolean = false) {
        val session = requireNotNull(learning.get(sessionId))
        val id = jobId(sessionId)
        val old = memory.getJob(id)
        require(old?.status != "cancelled") { "学习来源已丢弃" }
        val checkpoint = old?.checkpointJson?.let(::JSONObject) ?: JSONObject()
        val restartCompile = recompile || old?.status == "completed"
        if (old?.status == "failed" || restartCompile) {
            listOf("response_key", "last_response", "repair_for", "invalid_response", "validation_error").forEach(checkpoint::remove)
            checkpoint.put("network_failures", 0)
        }
        val compiled = checkpoint.optBoolean("parse_completed")
        if (restartCompile && compiled) checkpoint.put("compile_index", 0)
            .put("working_draft", JSONArray(session.proposalsJson))
        val review = TaskReviewPolicy.isAutomatic(session) && !checkpoint.optBoolean("review_completed")
        val job = (old ?: MemoryJob(id = id, runId = sessionId)).copy(status = "pending", error = null,
            kind = if (review) "learning_review" else if (compiled) "learning_compile" else "learning_parse",
            payloadJson = JSONObject().put("session_id", sessionId).toString(), checkpointJson = checkpoint.toString())
        if (old == null) memory.enqueueJob(job) else memory.updateJob(job)
        MemoryJobs(appContext).schedule(id, replace = old != null)
    }

    suspend fun recover() {
        val active = LearningRecorder.get(appContext).active.value?.id
        for (session in learning.all()) {
            if (session.id == active) continue
            when (session.status) {
                "recording", "paused" -> learning.put(session.copy(status = if (TaskReviewPolicy.isAutomatic(session)) "awaiting_task" else "interrupted",
                    finishedAt = System.currentTimeMillis(), error = "录制已中断；已有素材保留，可继续整理。"))
                "queued", "reviewing", "parsing", "compiling" -> {
                    val job = memory.getJob(jobId(session.id))
                    if (job == null) enqueue(session.id)
                    else if (job.status in setOf("pending", "running")) MemoryJobs(appContext).schedule(job.id)
                    else if (job.status == "failed") learning.put(session.copy(status = "failed", error = job.error))
                    else if (job.status == "completed") {
                        val review = JSONObject(job.checkpointJson).optJSONObject("task_review")
                        learning.put(session.copy(status = when {
                            review?.optString("decision") == "skip" -> "skipped"
                            JSONArray(session.questionsJson).length() > 0 -> "awaiting_input"
                            else -> "ready"
                        }, sourceJson = if (review == null) session.sourceJson else
                            JSONObject(session.sourceJson).put("task_review", review).toString()))
                    }
                }
            }
        }
        TaskReviewCoordinator(appContext).recover()
    }

    companion object { fun jobId(sessionId: String) = "$sessionId:learn:v1" }
}
