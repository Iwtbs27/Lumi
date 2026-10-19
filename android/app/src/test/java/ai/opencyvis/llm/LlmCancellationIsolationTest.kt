package ai.opencyvis.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
class LlmCancellationIsolationTest {
    @Test fun openAiCancelledRoundDoesNotRetryAndNextRoundCanRun() = runBlocking { cancelledRound(0) }
    @Test fun anthropicCancelledRoundDoesNotRetryAndNextRoundCanRun() = runBlocking { cancelledRound(1) }
    @Test fun ollamaCancelledRoundDoesNotRetryAndNextRoundCanRun() = runBlocking { cancelledRound(2) }

    private suspend fun cancelledRound(provider: Int): Unit = coroutineScope {
        val server = MockWebServer().apply { start() }
        val client = client(provider, server)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(success(provider))
        try {
            val pending = async { runCatching { client.chatWithSchema(messages, LlmRequestSchema.phoneAction(2)) } }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull("The actual first HTTP request must be in flight", request)
            client.cancelRequests()
            val failure = withTimeout(5_000) { pending.await() }.exceptionOrNull()
            assertTrue("Cancelling a round must propagate cancellation, not retry or return success: $failure",
                failure is CancellationException)
            assertEquals("The cancelled round must not send a retry", 1, server.requestCount)
            assertEquals("finish", client.chatWithSchema(messages, LlmRequestSchema.phoneAction(1))["action_type"])
            assertEquals(2, server.requestCount)
        } finally {
            client.cancelRequests()
            client.shutdown()
            server.shutdown()
        }
    }

    @Test fun cancellingOneClientLeavesItsSiblingRequestRunning(): Unit = runBlocking {
        val firstServer = MockWebServer().apply { start() }
        val secondServer = MockWebServer().apply { start() }
        val first = client(0, firstServer)
        val second = client(0, secondServer)
        firstServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        secondServer.enqueue(success(0).setBodyDelay(300, TimeUnit.MILLISECONDS))
        try {
            val cancelled = async { runCatching { first.chatWithSchema(messages, LlmRequestSchema.phoneAction(1)) } }
            val sibling = async { second.chatWithSchema(messages, LlmRequestSchema.phoneAction(1)) }
            withContext(Dispatchers.IO) {
                assertNotNull(firstServer.takeRequest(3, TimeUnit.SECONDS))
                assertNotNull(secondServer.takeRequest(3, TimeUnit.SECONDS))
            }
            first.cancelRequests()
            assertTrue(withTimeout(5_000) { cancelled.await() }.exceptionOrNull() is CancellationException)
            assertEquals("finish", withTimeout(5_000) { sibling.await() }["action_type"])
            assertEquals(1, secondServer.requestCount)
        } finally {
            first.cancelRequests(); second.cancelRequests()
            first.shutdown(); second.shutdown()
            firstServer.shutdown(); secondServer.shutdown()
        }
    }

    private fun client(provider: Int, server: MockWebServer): LLMClientInterface {
        val url = server.url("/").toString().trimEnd('/')
        return when (provider) {
            0 -> LLMClient("test-key", "test-model", url)
            1 -> AnthropicClient("test-key", "test-model", url)
            else -> OllamaClient("test-model", url)
        }
    }

    private fun success(provider: Int): MockResponse {
        val arguments = JSONObject().put("action_type", "finish")
        val function = JSONObject().put("name", "phone_action")
            .put("arguments", if (provider == 2) arguments else arguments.toString())
        val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
        if (provider == 2) return MockResponse().setBody(JSONObject().put("message", message).toString())
        val event = if (provider == 1) JSONObject().put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", arguments.toString()))
        else JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", message)))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n")
    }

    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "test"))
}
