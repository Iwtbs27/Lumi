package ai.opencyvis.engine

import ai.opencyvis.memory.MemoryPacket
import org.json.JSONObject

/** Frozen prefix and append-only history, followed by this round's replaceable state/observation. */
internal class ContextBuilder(private val task: TaskState, private val store: TaskRunStore?) {
    val history = ContextHistory()
    private val fixedPrefix = listOf(
        mapOf("role" to "system", "content" to AgentEngine.SYSTEM_PROMPT),
        mapOf("role" to "user", "content" to "[task]\n${task.instruction}")
    )
    private fun image(step: Int): String? = store?.readImage(step) ?: task.steps[step]?.image
    private fun tree(step: Int): String? = store?.readTree(step) ?: task.steps[step]?.tree
    private fun text(value: String): Map<String, Any> = mapOf("type" to "text", "text" to value)
    private fun picture(base64: String): Map<String, Any> = mapOf(
        "type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64")
    )
    private fun json(value: Map<String, Any?>): String = JSONObject(value).toString()

    fun previewCheckpoint(compacted: List<ContextHistory.Event>, checkpoint: String,
                          tail: Map<String, Any>): List<Map<String, Any>> = fixedPrefix +
        mapOf("role" to "user", "content" to "[checkpoint; historical summary, not current UI]\n$checkpoint") +
        history.visible.drop(compacted.size).map { it.message() } + tail

    fun build(
        step: Int,
        screenshot: String,
        viewTree: String?,
        memory: MemoryPacket,
        feedback: String?,
        firstStepHint: String?,
        reduceRecall: Boolean = false
    ): List<Map<String, Any>> {
        history.capture(task, step, ::summary)
        val messages = fixedPrefix.toMutableList<Map<String, Any>>()
        history.checkpoint?.let { messages += mapOf("role" to "user", "content" to "[checkpoint; historical summary, not current UI]\n$it") }
        messages += history.visible.map { it.message() }
        val content = mutableListOf<Map<String, Any>>()
        content += text(buildString {
            firstStepHint?.let { append('\n').append(it) }
            append("\n[user_updates: chronological; latest correction takes precedence]\n")
            task.userUpdates.forEach { append(json(it)).append('\n') }
            append("\n[long_term_memory: current task instructions take precedence]\n")
            append(if (reduceRecall) memory.text.take(2000) + "\n[Recall reduced for context budget; read_memory can retrieve details.]" else memory.text).append('\n')
            append("\n[cur_state]\n").append(json(promptState(step)))
        })
        var historyImages = 0
        // These images were explicitly requested. Keep the shared quota during compaction too.
        val imageLimit = 2
        memory.recalled.forEach { recalled ->
            content += text("[recalled_history ${recalled.label}]\n${excerpt(recalled.text, step,
                "memory_recall.${recalled.label}", if (reduceRecall) 1200 else 4800)}")
            if (historyImages < imageLimit) recalled.image?.let { content += picture(it); historyImages++ }
        }
        task.recalledSteps.forEach { recalled ->
            content += text("[recalled_history step=$recalled; historical evidence]\n${task.steps[recalled]?.let(::summary).orEmpty()}")
            if (task.recallContent != "image") {
                content += text(excerpt(tree(recalled) ?: "No accessibility tree was available for this observation.", recalled,
                    "accessibility_tree", if (reduceRecall) 1200 else 4800))
                content += text(store?.readStepDetailsPage(recalled, offset = task.recallOffset,
                    limit = minOf(task.recallLimit, if (reduceRecall) 1200 else 4800))?.let { json(it.toMap()) }
                    ?: task.steps[recalled]?.let(::summary).orEmpty())
            }
            if (task.recallContent != "text" && historyImages < imageLimit) image(recalled)?.let { content += picture(it); historyImages++ }
        }
        task.recalledTextPage?.let { content += text("[requested exact text page]\n${json(it)}") }
        content += text("[current_step=$step; CURRENT observation]\n${excerpt(feedback.orEmpty(), step, "tool_feedback", 4800)}\n[accessibility_tree]\n${viewTree ?: "Unavailable. Use the current screenshot."}")
        content += picture(screenshot)
        messages += mapOf("role" to "user", "content" to content)
        return messages
    }

    private fun summary(record: TaskState.Step): String = json(mapOf(
        "step" to record.number,
        "proposed_action" to promptValue(record.decision.filterKeys {
            it !in TaskState.RESPONSE_METADATA
        }, record.number, "action"),
        "execution" to promptValue(record.execution, record.number, "execution"),
        "observed_effect" to promptValue(record.effect, record.number, "effect")
    ))

    private fun promptState(step: Int): Map<String, Any?> {
        val state = task.snapshot().toMutableMap()
        val facts = json(task.memories)
        state["task_memory"] = if (store != null && facts.length > 8000) mapOf(
            "all_facts" to store.saveContextArtifact(facts, 0, "task_memory").toMap(),
            "count" to task.memories.size,
            "recent" to promptValue(task.memories.entries.toList().takeLast(8).associate { it.toPair() }, step, "recent_facts")
        ) else promptValue(task.memories, step, "task_memory")
        for (key in listOf("pending_results", "procedure_execution")) {
            state[key] = promptValue(state[key], step, key)
        }
        return state
    }

    private fun promptValue(value: Any?, step: Int, label: String): Any? = when (value) {
        is String -> if (store != null && value.length > 2000) mapOf(
            "exact_text" to store.saveContextArtifact(value, step, label).toMap(), "preview" to value.take(300)
        ) else value
        is Map<*, *> -> {
            val source = ((value["recorded_step"] ?: value["source_step"] ?: value["step"]) as? Number)?.toInt() ?: step
            value.entries.associate { (key, child) -> key.toString() to promptValue(child, source, "$label.$key") }
        }
        is List<*> -> value.mapIndexed { i, child -> promptValue(child, step, "$label.$i") }
        else -> value
    }

    private fun excerpt(value: String, step: Int, label: String, limit: Int): String {
        if (store == null || value.length <= limit) return value
        val ref = store.saveContextArtifact(value, step, label)
        val page = ContextTextPage.from(ref.artifactRef, value, 0, limit)
        return json(ref.toMap()) + "\n" + page.text + page.nextOffset?.let {
            "\n[Partial text; use recall_history with artifact_ref and recall_offset=$it for remaining text.]"
        }.orEmpty()
    }
}
