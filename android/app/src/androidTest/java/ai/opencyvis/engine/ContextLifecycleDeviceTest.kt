package ai.opencyvis.engine

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.LlmUsage
import ai.opencyvis.llm.ResponseParser
import ai.opencyvis.llm.ToolSchema
import ai.opencyvis.memory.MemoryPacket
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Android JSON, image and archive integration; no network or real-model quality assertions. */
@RunWith(AndroidJUnit4::class)
class ContextLifecycleDeviceTest {
    @Test fun longTaskCompactsWithoutLosingExactEvidenceOrUnknownResults() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val files = File(app.cacheDir, "context_lifecycle_${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = files }
        val task = TaskState("Compare recorded items and preserve exact prices")
        val store = TaskRunStore(context, instruction = task.instruction)
        try {
            val exactText = (1..80).joinToString("\n") {
                "row=$it; exact_price=12.3500; label=蓝色; code=000$it; note=A/B"
            }
            val screenshot = screenshot()
            for (step in 1..100) {
                val pending = step == 1 || step >= 95
                val action = if (pending) mapOf("action_type" to "tap", "x" to 500, "y" to 500)
                    else mapOf("action_type" to "note", "note" to "Compared item $step")
                val execution = mapOf("execution_status" to if (pending) "executed" else "tool",
                    "detail" to "item-$step " + "evidence ".repeat(120))
                val effect = if (pending) mapOf("status" to "unknown", "evidence" to "No confirmation yet") else null
                task.steps[step] = TaskState.Step(step, null, "tree-$step", action, execution, effect)
                store.recordEvent(step, "proposed", action)
                store.recordEvent(step, "execution", execution)
                effect?.let { store.recordEvent(step, "effect", it) }
            }
            task.memories["catalog"] = mapOf("value" to exactText, "source_step" to 1)
            task.addUserUpdate(101, "supplement", "Compare blue items only")
            store.recordEvent(1, "proposed", mapOf("memory_update" to listOf(mapOf(
                "op" to "upsert", "key" to "catalog", "value" to exactText, "source_step" to 1))))
            store.recordObservation(1, screenshot, "original exact catalog", emptyMap())
            store.recordObservation(101, screenshot, "current comparison screen", emptyMap())
            val originalStep = requireNotNull(store.readStepDetails(1))
            val client = ContractClient()
            val manager = ContextManager(task, store, client)
            val input = ContextInput(101, screenshot, "current comparison screen", MemoryPacket(), null, null)

            val action = manager.decide(input) { true }
            task.applyResponse(action, 101)

            assertEquals("wait", action["action_type"])
            assertTrue("The long history must actually trigger compaction", client.summaryCalls > 0)
            assertNotNull(manager.builder.history.checkpoint)
            val visible = manager.builder.history.visible.map { it.sourceStep }.toSet()
            assertFalse(1 in visible)
            assertTrue((95..100).all { it in visible })
            assertEquals(100, task.steps.size)
            assertEquals(exactText, task.memories.getValue("catalog")["value"])
            val content = client.actionMessages.last()["content"] as List<*>
            val stateBlock = (content.first() as Map<*, *>)["text"] as String
            assertTrue(stateBlock.contains("Compare blue items only"))
            val state = JSONObject(stateBlock.substringAfter("[cur_state]\n"))
            val pending = state.getJSONArray("pending_results")
            assertEquals(setOf(1, 95, 96, 97, 98, 99, 100), (0 until pending.length()).map {
                val result = pending.getJSONObject(it)
                assertEquals("unknown", result.getJSONObject("effect").getString("status"))
                result.getInt("step")
            }.toSet())
            val artifact = state.getJSONObject("task_memory").getJSONObject("catalog")
                .getJSONObject("value").getJSONObject("exact_text")
            assertEquals(exactText.length, artifact.getInt("char_length"))
            assertEquals(exactText, recallAll(manager, task, artifact.getString("artifact_ref"), 101))
            val stepPage = requireNotNull(store.readStepDetailsPage(1, limit = 257))
            assertNotNull(stepPage.nextOffset)
            assertEquals(originalStep, recallAll(manager, task, stepPage.reference, 101))
            assertEquals(originalStep, store.readStepDetails(1))
            assertEquals(screenshot, store.readImage(1))
            val recalledInput = manager.builder.build(101, screenshot, input.viewTree, MemoryPacket(), null, null)
            assertTrue(recalledInput.last().toString().contains("[requested exact text page]"))

            val folder = File(files, "agent_runs/${store.runId}/steps/101")
            val events = File(folder, "events.jsonl").readLines().map { JSONObject(it) }
            assertTrue(events.any { it.getString("type") == "context_checkpoint" })
            val usages = events.filter { it.getString("type") == "usage" }.map { it.getJSONObject("data") }
            assertEquals(client.summaryCalls + 1, usages.size)
            assertEquals(client.summaryCalls, usages.count { it.getString("purpose") == "context_summary" })
            assertEquals(1, usages.count { it.getString("purpose") == "action" })
            usages.forEachIndexed { index, usage ->
                val summary = usage.getString("purpose") == "context_summary"
                assertEquals(index + 1, usage.getInt("attempt"))
                assertEquals(if (summary) 100L else 200L, usage.getLong("input_tokens"))
                assertEquals(if (summary) 20L else 30L, usage.getLong("output_tokens"))
                val request = JSONObject(File(folder, "request_${index + 1}.json").readText())
                assertEquals(if (summary) "context_summary" else "phone_action",
                    request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
            }
        } finally {
            store.finish("completed")
            files.deleteRecursively()
        }
    }

    private fun recallAll(manager: ContextManager, task: TaskState, reference: String, step: Int): String {
        val result = StringBuilder()
        var offset = 0
        var pages = 0
        do {
            val action = ResponseParser.jsonObjectToMap(JSONObject().put("thought", "Read exact evidence")
                .put("action_type", "recall_history").put("memory_update", JSONArray())
                .put("artifact_ref", reference).put("recall_offset", offset).put("recall_limit", 257))
            task.applyResponse(action, step)
            manager.recall(action, step)
            val page = requireNotNull(task.recalledTextPage)
            assertEquals(reference, page["reference"])
            assertEquals(offset, page["offset"])
            result.append(page["text"] as String)
            pages++
            val next = page["next_offset"] as? Int
            if (next == null) {
                assertEquals(page["total_chars"], result.length)
                break
            }
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertTrue("Exact evidence must span multiple pages", pages > 1)
        return result.toString()
    }

    private fun screenshot(): String {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes))
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    private class ContractClient : LLMClientInterface {
        override val contextBudget = ContextBudgetConfig(128_000, 16_000, workingInputLimitTokens = 32_000)
        override val actionTool = ToolSchema.phoneActionTool()
        override var lastCallInfo: LlmCallInfo? = null
        var summaryCalls = 0
        var actionMessages = emptyList<Map<String, Any>>()

        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            assertEquals("context_summary", schema.tool.getJSONObject("function").getString("name"))
            summaryCalls++
            val events = JSONObject(messages.last()["content"] as String).getJSONArray("events")
            val sources = (0 until events.length()).map { events.getJSONObject(it).getInt("source_step") }.distinct()
            return reply(messages, schema.tool, JSONObject().put("summary", "Compared recorded items; retrieve source steps for exact prices.")
                .put("evidence_steps", JSONArray(sources)).put("confirmed_steps", JSONArray())
                .put("open_questions", JSONArray(listOf("Tap results are still unconfirmed"))), LlmUsage(100, 20))
        }

        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            actionMessages = messages
            return reply(messages, actionTool, JSONObject().put("thought", "Wait for confirmation")
                .put("action_type", "wait").put("memory_update", JSONArray()), LlmUsage(200, 30))
        }

        private fun reply(messages: List<Map<String, Any>>, tool: JSONObject, arguments: JSONObject, usage: LlmUsage): Map<String, Any?> {
            val name = tool.getJSONObject("function").getString("name")
            lastCallInfo = LlmCallInfo(mapOf("model" to "local-contract-fake", "messages" to messages,
                "tools" to listOf(tool), "tool_choice" to mapOf("type" to "function", "function" to mapOf("name" to name))), usage, 1)
            val call = JSONObject().put("id", "local-call").put("type", "function").put("function",
                JSONObject().put("name", name).put("arguments", arguments.toString()))
            val message = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call))
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
            return requireNotNull(ResponseParser.parse(response))
        }

        override fun shutdown() = Unit
    }
}
