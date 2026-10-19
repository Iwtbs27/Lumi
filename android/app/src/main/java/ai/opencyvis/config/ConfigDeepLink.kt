package ai.opencyvis.config

object ConfigDeepLink {

    fun buildUri(provider: String, apiKey: String, model: String, baseUrl: String, maxSteps: Int,
                 profile: String? = null, contextBudget: ContextBudgetConfig? = null): String {
        val params = mutableMapOf(
            "provider" to provider,
            "api_key" to apiKey,
            "model" to model,
            "base_url" to baseUrl,
            "max_steps" to maxSteps.toString()
        )
        profile?.let { params["profile"] = it }
        contextBudget?.let { budget ->
            val json = budget.toJson()
            for (key in ContextBudgetConfig.PARAMETER_KEYS) if (json.has(key)) params[key] = json.get(key).toString()
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "opencyvis://config?$query"
    }

    data class ImportedConfig(
        val provider: String,
        val apiKey: String,
        val model: String,
        val baseUrl: String,
        val maxSteps: Int?,
        val imRemoteEnabled: Boolean?,
        val telegramBotToken: String?,
        val telegramAllowedChatId: String?,
        val feishuAppId: String?,
        val feishuAppSecret: String?,
        val feishuAllowedOpenId: String?,
        val profile: String? = null,
        val contextBudget: ContextBudgetConfig? = null
    )

    fun parse(params: Map<String, String?>): Result<ImportedConfig> {
        val provider = params["provider"]?.trim()?.lowercase().orEmpty()
            .ifEmpty { ConfigRepository.PROVIDER_OPENAI }

        if (provider !in supportedProviders) {
            return Result.failure(IllegalArgumentException("Unsupported provider: $provider"))
        }

        val apiKey = firstParam(params, "api_key", "key")?.trim().orEmpty()
        if (provider != ConfigRepository.PROVIDER_OLLAMA && apiKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("API key is required for $provider"))
        }

        val maxSteps = params["max_steps"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 1 }
                ?: return Result.failure(IllegalArgumentException("max_steps must be a positive integer"))
        }

        val (defaultModel, defaultBaseUrl) = defaultsFor(provider)
        val contextBudget = if (ContextBudgetConfig.PARAMETER_KEYS.any { params.containsKey(it) }) {
            val parsed = runCatching {
                val json = org.json.JSONObject()
                for (key in ContextBudgetConfig.PARAMETER_KEYS) {
                    if (params.containsKey(key)) {
                        val value = params[key]?.trim()?.toIntOrNull()
                            ?: error("$key 必须是整数")
                        json.put(key, value)
                    }
                }
                ContextBudgetConfig.fromJson(json)
            }
            if (parsed.isFailure) return Result.failure(IllegalArgumentException("上下文预算配置无效：${parsed.exceptionOrNull()?.message}"))
            parsed.getOrThrow()
        } else null

        val imRemoteEnabled = params["im_remote_enabled"]?.trim()?.toBooleanStrictOrNull()
        val telegramBotToken = params["telegram_bot_token"]?.trim()?.ifEmpty { null }
        val telegramAllowedChatId = params["telegram_chat_id"]?.trim()?.ifEmpty { null }
        val feishuAppId = params["feishu_app_id"]?.trim()?.ifEmpty { null }
        val feishuAppSecret = params["feishu_app_secret"]?.trim()?.ifEmpty { null }
        val feishuAllowedOpenId = params["feishu_open_id"]?.trim()?.ifEmpty { null }
        val profile = params["profile"]?.trim()?.ifEmpty { null }

        return Result.success(
            ImportedConfig(
                provider = provider,
                apiKey = apiKey,
                model = params["model"]?.trim()?.ifEmpty { null } ?: defaultModel,
                baseUrl = firstParam(params, "base_url", "url")?.trim()?.ifEmpty { null } ?: defaultBaseUrl,
                maxSteps = maxSteps,
                imRemoteEnabled = imRemoteEnabled,
                telegramBotToken = telegramBotToken,
                telegramAllowedChatId = telegramAllowedChatId,
                feishuAppId = feishuAppId,
                feishuAppSecret = feishuAppSecret,
                feishuAllowedOpenId = feishuAllowedOpenId,
                profile = profile,
                contextBudget = contextBudget
            )
        )
    }

    fun redactedApiKey(apiKey: String): String {
        if (apiKey.isBlank()) return "(empty)"
        if (apiKey.length <= 8) return "..."
        return "${apiKey.take(3)}...${apiKey.takeLast(4)}"
    }

    private fun defaultsFor(provider: String): Pair<String, String> = when (provider) {
        ConfigRepository.PROVIDER_ANTHROPIC ->
            ConfigRepository.DEFAULT_ANTHROPIC_MODEL to ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL
        ConfigRepository.PROVIDER_OLLAMA ->
            ConfigRepository.DEFAULT_OLLAMA_MODEL to ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        ConfigRepository.PROVIDER_KIMI ->
            ConfigRepository.DEFAULT_KIMI_MODEL to ConfigRepository.DEFAULT_KIMI_BASE_URL
        else -> ConfigRepository.DEFAULT_MODEL to ConfigRepository.DEFAULT_BASE_URL
    }

    private fun firstParam(params: Map<String, String?>, vararg names: String): String? =
        names.firstNotNullOfOrNull { params[it] }

    private val supportedProviders = setOf(
        ConfigRepository.PROVIDER_OPENAI,
        ConfigRepository.PROVIDER_ANTHROPIC,
        ConfigRepository.PROVIDER_OLLAMA,
        ConfigRepository.PROVIDER_KIMI
    )
}
