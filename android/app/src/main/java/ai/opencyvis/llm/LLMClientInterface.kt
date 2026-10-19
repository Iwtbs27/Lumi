package ai.opencyvis.llm

import ai.opencyvis.config.ContextBudgetConfig
import org.json.JSONObject

interface LLMClientInterface {
    val contextBudget: ContextBudgetConfig? get() = null
    val actionTool: JSONObject get() = ToolSchema.phoneActionTool()
    val lastCallInfo: LlmCallInfo? get() = null
    val callAttempts: List<LlmCallInfo> get() = listOfNotNull(lastCallInfo)
    suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
    suspend fun chatWithSchema(
        messages: List<Map<String, Any>>,
        schema: LlmRequestSchema
    ): Map<String, Any?> = throw UnsupportedOperationException("This client does not support custom tools")
    fun cancelRequests() {}
    fun shutdown()
}

data class LlmUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long? = null
)

data class LlmCallInfo(
    val request: Map<String, Any?>,
    val usage: LlmUsage?,
    val attempts: Int,
    val rawUsage: Map<String, Any?>? = null,
    val firstTokenLatencyMs: Long? = null,
    val usageComplete: Boolean = false,
    val reservationId: Long? = null,
    val ownerId: String? = null,
    val purpose: LlmCallPurpose = LlmCallPurpose.GENERAL
)
