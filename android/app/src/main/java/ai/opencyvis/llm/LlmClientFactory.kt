package ai.opencyvis.llm

import ai.opencyvis.config.ConfigRepository

object LlmClientFactory {
    fun create(config: ConfigRepository, executionScope: LlmExecutionScope? = null): LLMClientInterface =
        capture(config)(executionScope)

    /** Freeze one task's provider settings while returning an independent client for each owner. */
    fun capture(config: ConfigRepository): (LlmExecutionScope?) -> LLMClientInterface {
        val provider = config.apiProvider
        val model = config.model
        val url = config.baseUrl.trimEnd('/')
        val apiKey = config.apiKey
        val budget = config.contextBudget
        require(model.isNotBlank()) { "请先配置模型名称" }
        require(url.isNotBlank()) { "请先配置模型服务地址" }
        require(provider == ConfigRepository.PROVIDER_OLLAMA || apiKey.isNotBlank()) {
            "请先配置模型 API Key"
        }
        require(provider in setOf(ConfigRepository.PROVIDER_ANTHROPIC, ConfigRepository.PROVIDER_OLLAMA,
            ConfigRepository.PROVIDER_OPENAI, ConfigRepository.PROVIDER_KIMI)) { "不支持的模型服务类型：$provider" }
        return { executionScope -> when (provider) {
            ConfigRepository.PROVIDER_ANTHROPIC -> AnthropicClient(apiKey, model, url, contextBudget = budget, executionScope = executionScope)
            ConfigRepository.PROVIDER_OLLAMA -> OllamaClient(model, url, contextBudget = budget, executionScope = executionScope)
            ConfigRepository.PROVIDER_OPENAI -> LLMClient(apiKey, model, url, contextBudget = budget, executionScope = executionScope)
            ConfigRepository.PROVIDER_KIMI -> LLMClient(
                apiKey, model, url, temperature = 0.6, disableThinking = true, contextBudget = budget, executionScope = executionScope
            )
            else -> error("不支持的模型服务类型：$provider")
        } }
    }
}
