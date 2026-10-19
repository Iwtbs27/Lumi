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
class SingleToolProtocolTest {
    @Test fun completeCodeArgumentsSurviveStreamingAndEscaping() = eachProvider { provider, client, server ->
        val code = "def main(ctx, params):\n    return {\"text\": params[\"标题\"]}\n"
        val args = JSONObject().put("action_type", "write_code").put("code", code).toString()
        server.enqueue(response(provider, listOf("coding_action" to args)))
        assertEquals(code, client.chatWithSchema(messages, schema)["code"])
    }

    @Test fun wrongFunctionIsRejectedBeforeArgumentsAreUsed() = eachProvider { provider, client, server ->
        server.enqueue(response(provider, listOf("phone_action" to goodArgs)))
        reject(client)
    }

    @Test fun multipleCallsAreRejectedInsteadOfDroppingTheSecond() = eachProvider { provider, client, server ->
        server.enqueue(response(provider, listOf("coding_action" to goodArgs, "coding_action" to goodArgs)))
        reject(client)
    }

    @Test fun truncatedCodeIsNeverRecoveredAsAnExecutableAction() = eachProvider { provider, client, server ->
        server.enqueue(response(provider, listOf("coding_action" to "{\"action_type\":\"write_code\",\"code\":\"print(1)\"")))
        reject(client)
    }

    @Test fun textCannotMasqueradeAsCodingFunctionCall() = eachProvider { provider, client, server ->
        server.enqueue(response(provider, emptyList(), goodArgs))
        reject(client)
    }

    @Test fun missingFunctionNameIsRejectedForCoding() = eachProvider { provider, client, server ->
        server.enqueue(response(provider, listOf("" to goodArgs)))
        reject(client)
    }

    private suspend fun reject(client: LLMClientInterface) {
        try {
            client.chatWithSchema(messages, schema)
            fail("Invalid coding tool response was accepted")
        } catch (_: LLMException) { }
    }

    private fun eachProvider(test: suspend (String, LLMClientInterface, MockWebServer) -> Unit) = runBlocking {
        for (provider in listOf("openai", "anthropic", "ollama")) {
            val server = MockWebServer().apply { start() }
            val url = server.url("/").toString().trimEnd('/')
            val client: LLMClientInterface = when (provider) {
                "anthropic" -> AnthropicClient("test-key", "test-model", url)
                "ollama" -> OllamaClient("test-model", url)
                else -> LLMClient("test-key", "test-model", url)
            }
            try { test(provider, client, server) }
            finally { client.shutdown(); server.shutdown() }
        }
    }

    private fun response(provider: String, calls: List<Pair<String, String>>, text: String? = null): MockResponse {
        if (provider == "ollama") {
            val message = JSONObject().put("tool_calls", JSONArray(calls.map { (name, args) ->
                JSONObject().put("function", JSONObject().put("name", name).put("arguments", args))
            }))
            text?.let { message.put("content", it) }
            return MockResponse().setBody(JSONObject().put("message", message).put("done", true).toString())
        }
        val events = mutableListOf<JSONObject>()
        calls.forEachIndexed { index, (name, args) ->
            if (provider == "anthropic") {
                events += JSONObject().put("type", "content_block_start").put("index", index)
                    .put("content_block", JSONObject().put("type", "tool_use").put("name", name).put("input", JSONObject()))
                args.chunked(7).forEach { chunk -> events += JSONObject().put("type", "content_block_delta").put("index", index)
                    .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", chunk)) }
            } else {
                args.chunked(7).forEachIndexed { part, chunk ->
                    val function = JSONObject().put("arguments", chunk)
                    if (part == 0) function.put("name", name)
                    events += openaiDelta(JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                        .put("index", index).put("function", function))))
                }
            }
        }
        text?.let {
            events += if (provider == "anthropic") JSONObject().put("type", "content_block_delta")
                .put("delta", JSONObject().put("type", "text_delta").put("text", it))
                else openaiDelta(JSONObject().put("content", it))
        }
        if (provider == "anthropic") events += JSONObject().put("type", "message_stop")
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
    }

    private fun openaiDelta(delta: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "Write a program"))
    private val goodArgs = "{\"action_type\":\"write_code\",\"code\":\"print(1)\"}"
    private val schema = LlmRequestSchema(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "coding_action").put("description", "Write and inspect a program")
        .put("parameters", JSONObject().put("type", "object"))), requireCompleteJson = true)
}
