package ai.opencyvis.llm

import ai.opencyvis.config.ContextBudgetConfig

import org.json.JSONArray
import org.json.JSONObject

/** One request owns its tool definition; foreground actions keep their existing schema. */
data class LlmRequestSchema(
    val tool: JSONObject,
    val maxOutputTokens: Int = 4096,
    val maxAttempts: Int = 1,
    val maxOutputTokensLimit: Int? = null,
    val purpose: LlmCallPurpose = LlmCallPurpose.GENERAL,
    val requireCompleteJson: Boolean = false
) {
    init {
        require(maxOutputTokens > 0 && maxAttempts > 0) { "输出长度与请求次数必须为正整数" }
        require(maxOutputTokensLimit == null || maxOutputTokensLimit >= maxOutputTokens) { "初始输出长度不能超过请求输出上限" }
        require(purpose != LlmCallPurpose.FINALIZATION || maxAttempts == 1) { "最终汇总只允许一次模型请求" }
    }

    fun outputTokenLimit(budget: ContextBudgetConfig?): Int {
        val limit = minOf(maxOutputTokensLimit ?: Int.MAX_VALUE, budget?.outputReserveTokens ?: Int.MAX_VALUE)
        require(maxOutputTokens <= limit) { "请求输出长度 $maxOutputTokens 超过已配置的输出预留 $limit" }
        return limit
    }

    fun nextOutputTokens(current: Int, limit: Int): Int = minOf(current.toLong() * 2, limit.toLong()).toInt()

    fun toolsArray(): JSONArray = JSONArray().put(tool)

    fun anthropicToolsArray(): JSONArray {
        val function = tool.getJSONObject("function")
        return JSONArray().put(JSONObject()
            .put("name", function.getString("name"))
            .put("description", function.getString("description"))
            .put("input_schema", function.getJSONObject("parameters")))
    }

    companion object {
        fun phoneAction(maxAttempts: Int, outputReserveTokens: Int? = null) = LlmRequestSchema(
            ToolSchema.phoneActionTool(), maxOutputTokens = minOf(4096, outputReserveTokens ?: 4096),
            maxAttempts = maxAttempts, maxOutputTokensLimit = outputReserveTokens
        )
    }
}
