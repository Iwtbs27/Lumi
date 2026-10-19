package ai.opencyvis.memory

import org.json.JSONArray
import org.json.JSONObject

/** A compact model view; the complete episode and source evidence remain unchanged in Room. */
internal object MemoryConsolidationInput {
    /** Only persisted, accepted outcomes enter this view; raw model feedback is not proof. */
    fun feedbackReviews(segments: JSONArray, usages: List<MemoryUsage>,
        snapshots: Map<Pair<String, Int>, JSONObject>): JSONArray {
        val byStep = (0 until segments.length()).map { segments.getJSONObject(it) }
            .filter { !it.has("kind") }.associateBy { it.getInt("step") }
        val result = JSONArray()
        usages.filter { it.event == "outcome" }.associateBy { it.id }.values
            .groupBy { it.memoryId to it.revision }.forEach { (key, outcomes) ->
                val snapshot = snapshots[key] ?: return@forEach
                // Keep the preceding action too: e.g. a save click before Back changes the conclusion.
                val steps = outcomes.flatMap { listOfNotNull(it.step - 1, it.step, it.evidenceStep) }.distinct().sorted()
                val feedback = outcomes.map { usage -> JSONObject()
                    .put("applied_step", usage.step).put("evidence_step", usage.evidenceStep)
                    .put("outcome", usage.outcome).put("details", JSONObject(usage.detailsJson)) }
                result.put(JSONObject().put("kind", "memory_feedback_review")
                    .put("memory", JSONObject(snapshot.toString()).put("id", key.first).put("revision", key.second))
                    .put("accepted_feedback", JSONArray(feedback))
                    .put("observations", JSONArray(steps.mapNotNull { byStep[it]?.let { source -> compact(source, byStep) } })))
            }
        return result
    }

    fun batches(segments: JSONArray, instruction: String): List<JSONArray> {
        val budget = 20_000 - MemoryConsolidationSchema.prompt.length -
            JSONObject.quote(instruction.take(2400)).length - 4000 - 500
        val result = mutableListOf<JSONArray>()
        var current = JSONArray()
        var size = 2
        fun append(value: JSONObject) {
            val length = value.toString().length + 1
            if (size + length > budget && current.length() > 0) {
                result.add(current)
                current = JSONArray()
                size = 2
            }
            current.put(value)
            size += length
        }
        val byStep = (0 until segments.length()).map { segments.getJSONObject(it) }
            .filter { !it.has("kind") }.associateBy { it.optInt("step") }
        for (i in 0 until segments.length()) {
            val segment = compact(segments.getJSONObject(i), byStep)
            val text = segment.toString()
            if (text.length + 2 <= budget) append(segment) else {
                val pieces = text.chunked(3000)
                pieces.forEachIndexed { part, content -> append(JSONObject()
                    .put("step", segment.optInt("step")).put("kind", "fragment")
                    .put("part", part + 1).put("parts", pieces.size).put("text", content).apply {
                        segment.optJSONObject("memory")?.let { memory -> put("memory_reference", JSONObject()
                            .put("id", memory.getString("id")).put("revision", memory.getInt("revision"))
                            .put("current_revision", memory.getInt("current_revision"))) }
                    }) }
            }
        }
        if (current.length() > 0) result.add(current)
        return result.ifEmpty { listOf(JSONArray()) }
    }

    private fun compact(source: JSONObject, byStep: Map<Int, JSONObject>): JSONObject {
        val result = clean(source) as JSONObject
        if (source.has("kind")) return result
        val action = result.optJSONObject("action")
        action?.let {
            for (key in listOf("x", "y", "x1", "y1", "x2", "y2", "element_id")) it.remove(key)
            val thought = it.optString("thought")
            if (thought.length > 200) it.put("thought", thought.take(200)).put("thought_excerpt", true)
            val text = it.optString("text")
            if (text.length > 300) {
                it.remove("text")
                it.put("text_excerpt", text.take(240)).put("text_chars", text.length)
                    .put("text_source_step", source.getInt("step"))
            }
        }
        val execution = source.optJSONObject("execution")
        if (execution != null) {
            val status = execution.optString("execution_status")
            result.put("execution", JSONObject().put("execution_status", status))
            if (status != "executed" || action?.optString("action_type") == "list_apps") {
                val feedback = execution.optString("feedback")
                if (feedback.isNotBlank()) result.getJSONObject("execution").put("feedback", feedback)
            }
        }
        result.optJSONObject("memory_context")?.let { context ->
            val observation = JSONObject()
            for (key in listOf("app", "page")) {
                if (context.optString(key).isNotBlank()) observation.put(key, context.getString(key))
            }
            for (key in listOf("possibly_stale", "app_uncertain")) {
                if (context.optBoolean(key)) observation.put(key, true)
            }
            if (observation.length() > 0) result.put("memory_context", observation) else result.remove("memory_context")
        }
        val previous = source.optJSONObject("previous_action_result")
        val earlier = previous?.let { byStep[it.optInt("step")]?.optJSONObject("effect") }
        if (earlier != null && earlier.optInt("evidence_step") == source.optInt("step") &&
            earlier.optString("status") == previous.optString("status") &&
            earlier.optString("evidence") == previous.optString("evidence")) {
            result.remove("previous_action_result")
        }
        return result
    }

    private fun clean(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { result ->
            for (key in value.keys()) {
                val raw = value.get(key)
                if (raw == JSONObject.NULL) continue
                val item = clean(raw)
                if (item == "" || item is JSONObject && item.length() == 0 || item is JSONArray && item.length() == 0) continue
                result.put(key, item)
            }
        }
        is JSONArray -> JSONArray().also { result ->
            for (i in 0 until value.length()) if (!value.isNull(i)) result.put(clean(value.get(i)))
        }
        else -> value
    }
}
