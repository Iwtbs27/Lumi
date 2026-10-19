package ai.opencyvis.config

import org.json.JSONObject

class InvalidContextBudgetException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Explicit deployment limits. A model name alone never supplies a context window. */
data class ContextBudgetConfig(
    val contextWindowTokens: Int,
    val outputReserveTokens: Int,
    val estimationMarginPercent: Int = 5,
    val workingInputLimitTokens: Int? = null
) {
    val estimationMarginTokens: Int
        get() = ((contextWindowTokens.toLong() * estimationMarginPercent + 99) / 100).toInt()
    val maximumInputTokens: Int get() = contextWindowTokens - outputReserveTokens - estimationMarginTokens
    val usableInputTokens: Int get() = workingInputLimitTokens ?: maximumInputTokens
    val triggerTokens: Int get() = (usableInputTokens.toLong() * 75 / 100).toInt()
    val targetTokens: Int get() = usableInputTokens / 2

    init {
        require(contextWindowTokens > 0) { "上下文窗口必须是正整数" }
        require(outputReserveTokens > 0 && outputReserveTokens < contextWindowTokens) { "输出预留必须大于 0 且小于上下文窗口" }
        require(estimationMarginPercent in 0..99) { "估算余量必须在 0% 到 99% 之间" }
        require(maximumInputTokens > 0) { "扣除输出预留和估算余量后，输入预算必须大于 0" }
        require(workingInputLimitTokens == null || workingInputLimitTokens in 1..maximumInputTokens) {
            "工作输入上限必须大于 0 且不超过可用输入预算（$maximumInputTokens）"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("context_window_tokens", contextWindowTokens)
        put("output_reserve_tokens", outputReserveTokens)
        put("estimation_margin_percent", estimationMarginPercent)
        workingInputLimitTokens?.let { put("working_input_limit_tokens", it) }
    }

    companion object {
        val PRESET_WINDOWS = listOf(64_000, 128_000, 256_000, 512_000, 1_000_000)
        val PARAMETER_KEYS = setOf("context_window_tokens", "output_reserve_tokens", "estimation_margin_percent", "working_input_limit_tokens")

        fun preset(windowTokens: Int): ContextBudgetConfig {
            require(windowTokens in PRESET_WINDOWS) { "请选择预设窗口或填写自定义预算" }
            val reserve = when (windowTokens) {
                64_000 -> 8_000
                128_000, 256_000 -> 16_000
                else -> 32_000
            }
            return ContextBudgetConfig(windowTokens, reserve)
        }

        fun fromJsonString(value: String): ContextBudgetConfig = try {
            fromJson(JSONObject(value))
        } catch (e: InvalidContextBudgetException) {
            throw e
        } catch (e: Exception) {
            throw InvalidContextBudgetException("上下文预算配置格式无效，请在模型设置中重新填写", e)
        }

        fun fromJson(json: JSONObject): ContextBudgetConfig = try {
            ContextBudgetConfig(
                contextWindowTokens = json.getInt("context_window_tokens"),
                outputReserveTokens = json.getInt("output_reserve_tokens"),
                estimationMarginPercent = if (json.has("estimation_margin_percent")) json.getInt("estimation_margin_percent") else 5,
                workingInputLimitTokens = if (json.has("working_input_limit_tokens") && !json.isNull("working_input_limit_tokens"))
                    json.getInt("working_input_limit_tokens") else null
            )
        } catch (e: Exception) {
            throw InvalidContextBudgetException("上下文预算配置无效：${e.message}", e)
        }
    }
}
