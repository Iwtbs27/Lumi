package ai.opencyvis.learning

import org.json.JSONArray
import org.json.JSONObject

/** Bounded review excerpts keep the first resumed actions as well as the eventual outcome. */
internal object TaskReviewInput {
    fun excerpt(text: String, limit: Int): String = if (text.length <= limit) text else text.take(limit) + "…[后文未提供]"

    fun <T> sample(rows: List<T>, limit: Int): List<T> {
        if (rows.size <= limit) return rows
        // Preserve the first few actions where a correction takes effect, and cover the full remainder.
        val head = minOf(4, limit / 3)
        val indices = (0 until head).toList() + (0 until limit - head).map {
            head + it * (rows.size - 1 - head) / (limit - head - 1)
        }
        return indices.distinct().map(rows::get)
    }

    fun continuation(steps: JSONArray, handoffStep: Int, interventionCount: Int? = null): JSONObject {
        val following = (0 until steps.length()).map { steps.getJSONObject(it) }
            .filter { it.optInt("step") > handoffStep && it.optString("kind") != "task_state" }
            .distinctBy { it.getInt("step") }.sortedBy { it.getInt("step") }
        val rows = following.takeWhile { interventionCount == null || it.isNull("human_intervention_count") ||
            it.getInt("human_intervention_count") <= interventionCount }
        val selected = sample(rows, 24).map(::step)
        return JSONObject().put("total_steps", rows.size).put("provided_steps", JSONArray(selected))
            .put("omitted_steps", rows.size - selected.size)
            .put("stopped_at_next_handoff", rows.size != following.size)
    }

    fun step(row: JSONObject): JSONObject {
        val action = row.optJSONObject("action")?.let { JSONObject(it.toString()) }
        val thought = row.optString("thought", action?.optString("thought").orEmpty())
        action?.remove("thought")
        return JSONObject().put("step", row.getInt("step"))
            .put("human_intervention_count", row.opt("human_intervention_count"))
            .put("thought", excerpt(thought, 1200))
            .put("action", excerpt(action?.toString().orEmpty(), 700))
            .put("execution", excerpt(row.optJSONObject("execution")?.toString().orEmpty(), 500))
            .put("effect", excerpt(row.optJSONObject("effect")?.toString().orEmpty(), 700))
            .put("user_updates", excerpt(row.opt("user_updates")?.toString().orEmpty(), 500))
    }

    fun context(session: LearningSession): JSONObject {
        val source = JSONObject(session.sourceJson)
        if (!TaskReviewPolicy.isAutomatic(session)) return source
        // The original frozen snapshot stays on disk; repeated parse/compile calls receive bounded context.
        source.put("current_state", excerpt(source.opt("current_state")?.toString().orEmpty(), 2400))
        source.put("return_context", excerpt(source.opt("return_context")?.toString().orEmpty(), 1200))
        val recent = source.optJSONArray("recent_steps") ?: JSONArray()
        source.put("recent_steps", JSONArray((0 until recent.length()).map { recent.getJSONObject(it) }.takeLast(8).map(::step)))
        return source
    }
}
