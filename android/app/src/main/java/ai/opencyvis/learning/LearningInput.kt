package ai.opencyvis.learning

import android.util.Base64
import ai.opencyvis.memory.MemoryEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object LearningInput {
    data class Batch(val records: List<JSONObject>, val frames: List<LearningFrame>)

    /** Bound each batch without dropping the tail of a long demonstration. */
    fun batches(events: List<JSONObject>, frames: List<LearningFrame>): List<Batch> {
        val rows = (events.map { event ->
            JSONObject(event.toString()).apply {
                val details = getJSONObject("details").toString()
                if (details.length > 1800) { remove("details"); put("details_excerpt", details.take(1800)) }
            }
        } + frames.map { frame -> frame.json().apply {
            remove("image_path"); remove("tree_path"); put("kind", "frame_index")
        } }).sortedBy { it.getInt("sequence") }
        val batches = mutableListOf<Batch>()
        var current = mutableListOf<JSONObject>()
        var chars = 0
        fun flush() {
            if (current.isEmpty()) return
            val min = current.first().getInt("sequence")
            val max = current.last().getInt("sequence")
            // A preceding frame keeps a gesture at a batch boundary understandable.
            val selected = listOfNotNull(frames.lastOrNull { it.sequence < min }) + frames.filter { it.sequence in min..max }
            batches += Batch(current.toList(), selected.distinctBy { it.id })
            current = mutableListOf(); chars = 0
        }
        for (row in rows) {
            if (current.size >= 60 || chars + row.toString().length > 18000) flush()
            current += row; chars += row.toString().length
        }
        flush()
        return batches
    }

    fun initialFrames(frames: List<LearningFrame>): List<LearningFrame> {
        if (frames.size <= 6) return frames
        return (0..5).map { frames[it * (frames.size - 1) / 5] }.distinctBy { it.id }
    }

    fun text(value: String): Map<String, Any> = mapOf("type" to "text", "text" to value)

    fun answerIds(session: LearningSession): Set<String> {
        val answers = JSONArray(session.answersJson)
        return (0 until answers.length()).map { answers.getJSONObject(it).getString("evidence_id") }.toSet()
    }

    fun evidenceContent(evidence: List<MemoryEvidence>, imageIds: Set<String>): List<Map<String, Any>> = buildList {
        for (entry in evidence) {
            val tree = entry.treePath?.let(::File)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            add(text(JSONObject().put("evidence_id", entry.id).put("kind", entry.kind)
                .put("actor", entry.actor).put("observation_type", entry.observationType)
                .put("text", entry.text.take(8000)).put("text_truncated", entry.text.length > 8000)
                .put("tree", tree?.take(10000)).put("tree_truncated", tree != null && tree.length > 10000).toString()))
            if (entry.id in imageIds) {
                val image = entry.imagePath?.let(::File)?.takeIf { it.isFile }
                if (image == null) add(text("该证据图片当前不可用；不能据索引断言画面内容。"))
                else add(mapOf("type" to "image_url", "image_url" to mapOf("url" to
                    "data:image/jpeg;base64,${Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)}")))
            }
        }
    }

    fun sessionHeader(session: LearningSession): JSONObject = JSONObject()
        .put("session_id", session.id).put("mode", session.mode).put("goal", session.goal)
        .put("goal_source", session.goalSource).put("note", session.note)
        .put("answers", JSONArray(session.answersJson)).put("capture_display_id", session.displayId)
        .put("correction_context", TaskReviewInput.context(session))
}
