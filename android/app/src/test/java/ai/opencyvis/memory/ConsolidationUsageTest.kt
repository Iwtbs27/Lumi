package ai.opencyvis.memory

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmUsage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsolidationUsageTest {
    @Test fun `partial provider usage is not reported as a complete cost`() {
        assertFalse(cost(LlmUsage(100, 0), complete = false).getBoolean("usage_known"))
        assertFalse(cost(LlmUsage(100, null), complete = true).getBoolean("usage_known"))
        assertTrue(cost(LlmUsage(100, 20), complete = true).getBoolean("usage_known"))
    }

    private fun cost(usage: LlmUsage, complete: Boolean): JSONObject {
        val client = object : LLMClientInterface {
            override val callAttempts = listOf(LlmCallInfo(emptyMap(), usage, 1, usageComplete = complete))
            override suspend fun chatWithTools(messages: List<Map<String, Any>>) = error("No model call needed")
            override fun shutdown() = Unit
        }
        return JSONObject().also { recordConsolidationCost(it, client, System.currentTimeMillis()) }
    }
}
