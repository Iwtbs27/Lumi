package ai.opencyvis.llm

import ai.opencyvis.engine.ContextTokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LlmExecutionBudgetTest {
    @Test fun everyProviderSettlesFinalUsageAndPreservesOwner() = runBlocking {
        for (provider in 0..2) {
            val budget = TaskModelBudget(100_000, 1_000)
            withClient(provider, budget) { server, client ->
                server.enqueue(success(provider))
                client.chatWithSchema(messages, schema)
                assertEquals(120L, budget.snapshot().knownInputTokens)
                assertEquals(15L, budget.snapshot().knownOutputTokens)
                assertEquals(0L, budget.snapshot().unknownReservedTokens)
                assertEquals(0L, budget.snapshot().pendingReservedTokens)
                assertTrue(client.lastCallInfo!!.usageComplete)
                assertEquals("parent", client.lastCallInfo!!.ownerId)
                assertNotNull(client.lastCallInfo!!.reservationId)
            }
        }
    }

    @Test fun retryReservesAgainUsingItsActualOutputLimitAndKeepsUnknownFirstUsage() = runBlocking {
        for (provider in 0..2) {
            val changes = CopyOnWriteArrayList<TaskModelBudgetSnapshot>()
            val budget = TaskModelBudget(100_000, 1_000, onChanged = { changes.add(it) })
            withClient(provider, budget) { server, client ->
                server.enqueue(if (provider == 2) MockResponse().setBody("") else stream())
                server.enqueue(success(provider))
                client.chatWithSchema(messages, schema.copy(maxAttempts = 2))
                assertEquals(2, server.requestCount)
                assertEquals(2, client.callAttempts.size)
                assertNotEquals(client.callAttempts[0].reservationId, client.callAttempts[1].reservationId)
                val pending = changes.filter { it.pendingReservedTokens > 0 }
                assertEquals(2, pending.size)
                assertEquals(if (provider == 2) 0L else 256L,
                    pending[1].pendingReservedTokens - pending[0].pendingReservedTokens)
                assertEquals(pending[0].pendingReservedTokens, budget.snapshot().unknownReservedTokens)
                assertEquals(120L, budget.snapshot().knownInputTokens)
            }
        }
    }

    @Test fun exhaustedBudgetRejectsRetryBeforeAnotherHttpRequest() = runBlocking {
        val estimate = ContextTokenEstimator().estimate(messages, schema.tool).totalTokens.toLong()
        for (provider in 0..2) {
            val budget = TaskModelBudget(estimate + 256 + 100, 100)
            withClient(provider, budget) { server, client ->
                server.enqueue(if (provider == 2) MockResponse().setBody("") else stream())
                val failure = runCatching { client.chatWithSchema(messages, schema.copy(maxAttempts = 2)) }.exceptionOrNull()
                assertTrue("provider=$provider: $failure", failure is ModelBudgetExceededException)
                assertEquals(1, server.requestCount)
                assertEquals(estimate + 256, budget.snapshot().unknownReservedTokens)
            }
        }
    }

    @Test fun anthropicInitialUsageIsNotMistakenForFinalUsage() = runBlocking {
        val budget = TaskModelBudget(100_000, 1_000)
        withClient(1, budget) { server, client ->
            server.enqueue(stream(JSONObject().put("type", "message_start").put("message", JSONObject()
                .put("usage", JSONObject().put("input_tokens", 120).put("output_tokens", 1))), action(1)))
            client.chatWithSchema(messages, schema)
            assertFalse(client.lastCallInfo!!.usageComplete)
            assertEquals(0L, budget.snapshot().knownInputTokens)
            assertTrue(budget.snapshot().unknownReservedTokens > 0)
        }
    }

    @Test fun cancellingAQueuedRequestReturnsOnlyItsUnsentReservation(): Unit = runBlocking {
        val governor = LlmRequestGovernor(1)
        val budget = TaskModelBudget(100_000, 1_000)
        val firstServer = MockWebServer().apply { start() }
        val secondServer = MockWebServer().apply { start() }
        val first = client(0, firstServer, LlmExecutionScope(budget, "first", governor = governor))
        val second = client(0, secondServer, LlmExecutionScope(budget, "second", governor = governor))
        firstServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        secondServer.enqueue(success(0))
        try {
            val firstCall = async { runCatching { first.chatWithSchema(messages, schema) } }
            withContext(Dispatchers.IO) { assertNotNull(firstServer.takeRequest(3, TimeUnit.SECONDS)) }
            val firstReserved = budget.snapshot().pendingReservedTokens
            val secondCall = async { runCatching { second.chatWithSchema(messages, schema) } }
            withTimeout(3_000) { while (budget.snapshot().pendingReservedTokens == firstReserved) delay(10) }
            assertEquals(1, governor.activeRequests)
            assertEquals(0, secondServer.requestCount)
            second.cancelRequests()
            assertTrue(withTimeout(3_000) { secondCall.await() }.exceptionOrNull() is CancellationException)
            assertEquals(firstReserved, budget.snapshot().pendingReservedTokens)
            first.cancelRequests()
            assertTrue(withTimeout(3_000) { firstCall.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, governor.activeRequests)
            assertEquals(firstReserved, budget.snapshot().unknownReservedTokens)
            assertEquals(0L, budget.snapshot().pendingReservedTokens)
        } finally {
            first.cancelRequests(); second.cancelRequests()
            first.shutdown(); second.shutdown()
            firstServer.shutdown(); secondServer.shutdown()
        }
    }

    private suspend fun withClient(provider: Int, budget: TaskModelBudget,
        block: suspend (MockWebServer, LLMClientInterface) -> Unit) {
        val server = MockWebServer().apply { start() }
        val client = client(provider, server, LlmExecutionScope(budget, "parent"))
        try { block(server, client) } finally { client.cancelRequests(); client.shutdown(); server.shutdown() }
    }

    private fun client(provider: Int, server: MockWebServer, scope: LlmExecutionScope): LLMClientInterface {
        val url = server.url("/").toString().trimEnd('/')
        return when (provider) {
            0 -> LLMClient("test-key", "test-model", url, executionScope = scope)
            1 -> AnthropicClient("test-key", "test-model", url, executionScope = scope)
            else -> OllamaClient("test-model", url, executionScope = scope)
        }
    }

    private fun action(provider: Int): JSONObject {
        val args = JSONObject().put("action_type", "finish")
        if (provider == 1) return JSONObject().put("type", "content_block_delta")
            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args.toString()))
        val function = JSONObject().put("name", "phone_action").put("arguments", if (provider == 2) args else args.toString())
        val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
        return if (provider == 2) JSONObject().put("message", message)
        else JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", message)))
    }

    private fun success(provider: Int): MockResponse = when (provider) {
        0 -> stream(action(0), JSONObject().put("choices", JSONArray()).put("usage", JSONObject()
            .put("prompt_tokens", 120).put("completion_tokens", 15)))
        1 -> stream(JSONObject().put("type", "message_start").put("message", JSONObject().put("usage", JSONObject()
            .put("input_tokens", 120).put("output_tokens", 1))), action(1),
            JSONObject().put("type", "message_delta").put("usage", JSONObject().put("output_tokens", 15)),
            JSONObject().put("type", "message_stop"))
        else -> MockResponse().setBody(action(2).put("done", true).put("prompt_eval_count", 120).put("eval_count", 15).toString())
    }

    private fun stream(vararg events: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "test"))
    private val schema get() = LlmRequestSchema(ToolSchema.phoneActionTool(), maxOutputTokens = 256, maxOutputTokensLimit = 512)
}
