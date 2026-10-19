package ai.opencyvis.learning

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.memory.MemoryJob
import ai.opencyvis.memory.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Screens out routine access and task changes before paying for full demonstration parsing. */
internal class TaskReview(private val learning: LearningRepository, private val memory: MemoryRepository) {
    suspend fun run(session: LearningSession, job: MemoryJob, checkpoint: JSONObject,
        client: LLMClientInterface): TaskReviewDecision {
        val source = JSONObject(session.sourceJson)
        require(source.has("task_completion")) { "任务尚未结束，不能开始接管复盘" }
        val events = learning.events(session.id)
        TaskReviewPolicy.localSkip(source, events)?.let { return it }

        val context = TaskReviewInput.context(session)
        val prior = context.optJSONArray("recent_steps") ?: JSONArray()
        val beforeIds = (0 until prior.length()).map { index ->
            val row = prior.getJSONObject(index)
            "${session.id}:before:${row.getInt("step")}".also { row.put("evidence_id", it) }
        }.toSet()
        val after = context.getJSONObject("task_completion").getJSONObject("continuation").getJSONArray("provided_steps")
        val afterIds = (0 until after.length()).map { index ->
            val row = after.getJSONObject(index)
            "${session.id}:after:${row.getInt("step")}".also { row.put("evidence_id", it) }
        }.toSet()
        val selectedEvents = TaskReviewInput.sample(events.filter(TaskReviewPolicy::isHumanAction), 24)
        val actionIds = selectedEvents.map { it.getString("evidence_id") }.toSet()
        val evidence = memory.getEvidence(session.id).filter { it.memoryId == null }.associateBy { it.id }
        val frames = TaskReviewInput.sample(learning.frames(session.id).filter { File(it.imagePath).isFile }, 4)
        val imageIds = frames.map { it.evidenceId }.filter { it in evidence }.toSet()
        val header = JSONObject().put("goal", TaskReviewInput.excerpt(session.goal, 2400))
            .put("context", context).put("human_event_count", events.count(TaskReviewPolicy::isHumanAction))
            .put("provided_human_events", JSONArray(selectedEvents.map { event ->
                JSONObject().put("evidence_id", event.getString("evidence_id")).put("kind", event.getString("kind"))
                    .put("sequence", event.getInt("sequence")).put("package_name", event.opt("package_name"))
                    .put("details", TaskReviewInput.excerpt(event.getJSONObject("details").toString(), 900))
            })).put("frame_count", session.frameCount).put("provided_image_ids", JSONArray(imageIds.toList()))
        val content = mutableListOf(LearningInput.text(header.toString()))
        content += LearningInput.evidenceContent(imageIds.mapNotNull(evidence::get), imageIds)
        return LearningModelCall(memory).validated(job.id, "review", checkpoint, client,
            listOf(mapOf("role" to "system", "content" to LearningSchema.reviewPrompt),
                mapOf("role" to "user", "content" to content)), LearningSchema.reviewer()) {
            TaskReviewDecision.parse(it, beforeIds + afterIds + actionIds + imageIds, actionIds, beforeIds)
        }
    }
}
