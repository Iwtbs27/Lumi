package ai.opencyvis.llm

import ai.opencyvis.config.ContextBudgetConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.TimeUnit
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextOutputLimitTest {
    @Test fun phoneToolsStayFixedForTheClientWhenLocaleChanges() = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            for (provider in 0..2) {
                Locale.setDefault(Locale.ENGLISH)
                val server = MockWebServer()
                server.start()
                val client = client(provider, server.url("/").toString().trimEnd('/'), ContextBudgetConfig.preset(128_000))
                try {
                    server.enqueue(successResponse(provider))
                    client.chatWithTools(messages)
                    val first = JSONObject(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
                    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
                    server.enqueue(successResponse(provider))
                    client.chatWithTools(messages)
                    val second = JSONObject(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
                    assertEquals(first.getJSONArray("tools").toString(), second.getJSONArray("tools").toString())
                } finally { client.shutdown(); server.shutdown() }
            }
        } finally { Locale.setDefault(originalLocale) }
    }

    @Test fun everyProviderKeepsActualRetryRequestsInsideReservedOutput() = runBlocking {
        for (provider in 0..2) {
            val server = MockWebServer()
            server.start()
            val budget = ContextBudgetConfig(128_000, 5_000)
            val client = client(provider, server.url("/").toString().trimEnd('/'), budget)
            try {
                repeat(3) { server.enqueue(emptyResponse(provider)) }
                val schema = LlmRequestSchema.phoneAction(3, budget.outputReserveTokens)
                assertTrue(runCatching { client.chatWithSchema(messages, schema) }.isFailure)
                val limits = (0..2).map {
                    val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    val payload = JSONObject(request.body.readUtf8())
                    if (provider == 2) {
                        assertEquals(128_000, payload.getJSONObject("options").getInt("num_ctx"))
                        payload.getJSONObject("options").getInt("num_predict")
                    } else payload.getInt("max_tokens")
                }
                assertEquals(if (provider == 2) listOf(4096, 4096, 4096) else listOf(4096, 5000, 5000), limits)
                assertEquals(3, client.callAttempts.size)
            } finally { client.shutdown(); server.shutdown() }
        }
    }

    @Test fun customSchemaAboveReserveFailsBeforeNetworkCall() = runBlocking {
        for (provider in 0..2) {
            val server = MockWebServer()
            server.start()
            val client = client(provider, server.url("/").toString().trimEnd('/'), ContextBudgetConfig(128_000, 2_000))
            try {
                val schema = LlmRequestSchema(ToolSchema.phoneActionTool(), maxOutputTokens = 3_000)
                val failure = runCatching { client.chatWithSchema(messages, schema) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals(0, server.requestCount)
            } finally { client.shutdown(); server.shutdown() }
        }
    }

    @Test fun phoneActionStartsBelowSmallReserveAndSchemaLimitCanBeLower() {
        val schema = LlmRequestSchema.phoneAction(3, 2_000)
        assertEquals(2_000, schema.maxOutputTokens)
        assertEquals(2_000, schema.outputTokenLimit(ContextBudgetConfig(128_000, 8_000)))
        assertEquals(2_000, schema.nextOutputTokens(2_000, 2_000))
    }

    private fun client(provider: Int, url: String, budget: ContextBudgetConfig): LLMClientInterface = when (provider) {
        0 -> LLMClient("test-key", "test-model", url, contextBudget = budget)
        1 -> AnthropicClient("test-key", "test-model", url, contextBudget = budget)
        else -> OllamaClient("test-model", url, contextBudget = budget)
    }

    private fun emptyResponse(provider: Int): MockResponse = if (provider == 2)
        MockResponse().setBody("")
    else MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n")

    private fun successResponse(provider: Int): MockResponse {
        val args = JSONObject().put("action_type", "finish").put("thought", "done")
        val function = JSONObject().put("name", "phone_action")
            .put("arguments", if (provider == 2) args else args.toString())
        val message = JSONObject().put("tool_calls", org.json.JSONArray().put(JSONObject().put("function", function)))
        if (provider == 2) return MockResponse().setBody(JSONObject().put("message", message).toString())
        val event = if (provider == 1) JSONObject().put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args.toString()))
        else JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", message)))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n")
    }

    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "test"))
}
