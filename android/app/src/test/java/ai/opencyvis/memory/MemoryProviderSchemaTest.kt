package ai.opencyvis.memory

import ai.opencyvis.llm.AnthropicClient
import ai.opencyvis.llm.LLMClient
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.OllamaClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryProviderSchemaTest {
    @Test fun allProviderClientsSendIndependentConsolidationToolAndParseItsResult() = runBlocking {
        val server = MockWebServer()
        server.start()
        val clients = mutableListOf<LLMClientInterface>()
        try {
            val base = server.url("/").toString().trimEnd('/')
            clients += LLMClient("test-key", "test-model", base)
            clients += AnthropicClient("test-key", "test-model", base)
            clients += OllamaClient("test-model", base)
            val result = JSONObject().put("episode_summary", "已保存历史经历")
                .put("proposals", JSONArray()).put("conflicts", JSONArray())
            val functionArgs = JSONObject().put("name", "consolidate_memory").put("arguments", result.toString())
            val toolDelta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", functionArgs)))
            val openAiEvent = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", toolDelta)))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: $openAiEvent\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n"))
            val anthropicEvent = JSONObject().put("type", "content_block_delta").put("delta", JSONObject()
                .put("type", "input_json_delta").put("partial_json", result.toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: $anthropicEvent\n\ndata: {\"type\":\"message_delta\",\"usage\":{\"input_tokens\":31,\"output_tokens\":5}}\n\ndata: {\"type\":\"message_stop\"}\n\n"))
            val nativeFunction = JSONObject().put("name", "consolidate_memory").put("arguments", result)
            val nativeMessage = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", nativeFunction)))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
                .put("message", nativeMessage).put("prompt_eval_count", 31).put("eval_count", 5).toString()))

            for ((index, client) in clients.withIndex()) {
                val response = client.chatWithSchema(listOf(mapOf("role" to "user", "content" to "整理本次经历")),
                    MemoryConsolidationSchema.request())
                assertEquals("已保存历史经历", response["episode_summary"])
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val payload = JSONObject(request.body.readUtf8())
                val tool = payload.getJSONArray("tools").getJSONObject(0)
                val function = if (index == 1) tool else tool.getJSONObject("function")
                assertEquals("consolidate_memory", function.getString("name"))
                assertFalse(payload.toString().contains("phone_action"))
                val schema = function.getJSONObject(if (index == 1) "input_schema" else "parameters")
                assertTrue(schema.getJSONObject("properties").has("proposals"))
                assertEquals(31L, client.lastCallInfo?.usage?.inputTokens)
                assertEquals(5L, client.lastCallInfo?.usage?.outputTokens)
                assertEquals(1, client.callAttempts.size)
            }
            assertEquals(3, server.requestCount)
        } finally {
            clients.forEach { it.shutdown() }
            server.shutdown()
        }
    }
}
