package ai.opencyvis.llm

import android.content.Context
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.ContextBudgetConfig
import androidx.test.core.app.ApplicationProvider
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
class LlmClientFactoryTest {
    @Test fun capturedConfigurationSurvivesSettingsChangesAndParentShutdown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ConfigRepository(context)
        val originalBudget = ContextBudgetConfig.preset(128_000)
        for (provider in listOf(ConfigRepository.PROVIDER_OPENAI, ConfigRepository.PROVIDER_ANTHROPIC,
            ConfigRepository.PROVIDER_OLLAMA)) {
            val originalServer = MockWebServer().apply { start() }
            val changedServer = MockWebServer().apply { start() }
            val clients = mutableListOf<LLMClientInterface>()
            try {
                config.applyProfile(provider, "test-initial-key", "initial-model",
                    originalServer.url("/").toString(), "initial", originalBudget)
                val createClient = LlmClientFactory.capture(config)
                val sharedBudget = TaskModelBudget(100_000, 1_000)
                val parent = createClient(LlmExecutionScope(sharedBudget, "parent")).also { clients.add(it) }

                config.applyProfile(if (provider == ConfigRepository.PROVIDER_OLLAMA)
                    ConfigRepository.PROVIDER_OPENAI else ConfigRepository.PROVIDER_OLLAMA,
                    "test-changed-key", "changed-model", changedServer.url("/").toString(),
                    "changed", ContextBudgetConfig.preset(256_000))

                originalServer.enqueue(success(provider))
                parent.chatWithSchema(messages, LlmRequestSchema.phoneAction(1))
                parent.shutdown()

                // A child created later keeps the captured settings and has its own HTTP owner.
                val child = createClient(LlmExecutionScope(sharedBudget, "child", LlmOwnerRole.CHILD))
                    .also { clients.add(it) }
                assertNotSame(parent, child)
                originalServer.enqueue(success(provider))
                child.chatWithSchema(messages, LlmRequestSchema.phoneAction(1)
                    .copy(purpose = LlmCallPurpose.CHILD_ANALYSIS))
                assertEquals(originalBudget, parent.contextBudget)
                assertEquals(originalBudget, child.contextBudget)
                assertEquals("parent", parent.lastCallInfo!!.ownerId)
                assertEquals("child", child.lastCallInfo!!.ownerId)
                assertEquals(LlmCallPurpose.CHILD_ANALYSIS, child.lastCallInfo!!.purpose)
                assertEquals(0, changedServer.requestCount)
                assertEquals(2, originalServer.requestCount)
                repeat(2) {
                    val request = requireNotNull(originalServer.takeRequest(3, TimeUnit.SECONDS))
                    assertEquals(when (provider) {
                        ConfigRepository.PROVIDER_ANTHROPIC -> "/v1/messages"
                        ConfigRepository.PROVIDER_OLLAMA -> "/api/chat"
                        else -> "/chat/completions"
                    }, request.path)
                    assertEquals("initial-model", JSONObject(request.body.readUtf8()).getString("model"))
                    if (provider != ConfigRepository.PROVIDER_OLLAMA) {
                        assertEquals("Bearer test-initial-key", request.getHeader("Authorization"))
                    }
                }
            } finally {
                clients.forEach { it.shutdown() }
                originalServer.shutdown()
                changedServer.shutdown()
            }
        }
    }

    private fun success(provider: String): MockResponse {
        val args = JSONObject().put("action_type", "finish")
        if (provider == ConfigRepository.PROVIDER_ANTHROPIC) {
            return stream(JSONObject().put("type", "content_block_delta").put("delta", JSONObject()
                .put("type", "input_json_delta").put("partial_json", args.toString())),
                JSONObject().put("type", "message_stop"))
        }
        val function = JSONObject().put("name", "phone_action")
            .put("arguments", if (provider == ConfigRepository.PROVIDER_OLLAMA) args else args.toString())
        val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
        return if (provider == ConfigRepository.PROVIDER_OLLAMA) {
            MockResponse().setBody(JSONObject().put("message", message).put("done", true).toString())
        } else stream(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", message))))
    }

    private fun stream(vararg events: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "test"))
}
