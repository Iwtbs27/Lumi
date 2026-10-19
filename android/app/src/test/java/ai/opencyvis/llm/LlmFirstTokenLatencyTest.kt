package ai.opencyvis.llm

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LlmFirstTokenLatencyTest {
    @Test fun streamingClientsRecordTextToolAndThinkingDeltas() = runBlocking {
        for (provider in 0..1) for (kind in listOf("text", "tool", "thinking")) {
            withClient(provider) { server, client ->
                server.enqueue(stream(delta(provider, kind, "partial")))
                runCatching { client.chatWithSchema(messages, LlmRequestSchema.phoneAction(1)) }
                val latency = client.lastCallInfo?.firstTokenLatencyMs
                assertNotNull("provider=$provider kind=$kind", latency)
                assertTrue(requireNotNull(latency) >= 0)
            }
        }
    }

    @Test fun usageRoleAndEmptyDeltasDoNotInventFirstTokenLatency() = runBlocking {
        for (provider in 0..1) withClient(provider) { server, client ->
            val usage = if (provider == 0) JSONObject().put("choices", JSONArray())
                .put("usage", JSONObject().put("prompt_tokens", 12).put("completion_tokens", 0))
            else JSONObject().put("type", "message_delta").put("usage", JSONObject().put("output_tokens", 0))
            val role = JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("role", "assistant").put("content", JSONObject.NULL))))
            server.enqueue(stream(role, delta(provider, "text", ""), delta(provider, "tool", ""), usage))
            runCatching { client.chatWithSchema(messages, LlmRequestSchema.phoneAction(1)) }
            assertNotNull(client.lastCallInfo)
            assertNull(client.lastCallInfo?.firstTokenLatencyMs)
        }
    }

    @Test fun latencyBelongsToTheAttemptThatActuallyReceivedADelta() = runBlocking {
        for (provider in 0..1) withClient(provider) { server, client ->
            server.enqueue(stream())
            server.enqueue(stream(delta(provider, "tool", "{\"action_type\":\"finish\"}")))
            client.chatWithSchema(messages, LlmRequestSchema.phoneAction(2))
            assertEquals(2, client.callAttempts.size)
            assertNull(client.callAttempts[0].firstTokenLatencyMs)
            assertNotNull(client.callAttempts[1].firstTokenLatencyMs)
        }
    }

    @Test fun nonStreamingOllamaResponseDoesNotClaimFirstTokenLatency() = runBlocking {
        withClient(2) { server, client ->
            val function = JSONObject().put("name", "phone_action")
                .put("arguments", JSONObject().put("action_type", "finish"))
            val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
            server.enqueue(MockResponse().setBody(JSONObject().put("message", message).toString()))
            client.chatWithSchema(messages, LlmRequestSchema.phoneAction(1))
            assertNotNull(client.lastCallInfo)
            assertNull(client.lastCallInfo?.firstTokenLatencyMs)
        }
    }

    private suspend fun withClient(provider: Int, block: suspend (MockWebServer, LLMClientInterface) -> Unit) {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString().trimEnd('/')
        val client: LLMClientInterface = when (provider) {
            0 -> LLMClient("test-key", "test-model", url)
            1 -> AnthropicClient("test-key", "test-model", url)
            else -> OllamaClient("test-model", url)
        }
        try { block(server, client) } finally { client.shutdown(); server.shutdown() }
    }

    private fun delta(provider: Int, kind: String, text: String): JSONObject {
        if (provider == 1) {
            val (type, field) = when (kind) {
                "tool" -> "input_json_delta" to "partial_json"
                "thinking" -> "thinking_delta" to "thinking"
                else -> "text_delta" to "text"
            }
            return JSONObject().put("type", "content_block_delta")
                .put("delta", JSONObject().put("type", type).put(field, text))
        }
        val delta = when (kind) {
            "tool" -> JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                .put("function", JSONObject().put("arguments", text))))
            "thinking" -> JSONObject().put("reasoning_content", text)
            else -> JSONObject().put("content", text)
        }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
    }

    private fun stream(vararg events: JSONObject): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "test"))
}
