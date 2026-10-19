package ai.opencyvis.config

import org.json.JSONArray
import org.json.JSONObject

data class ProviderProfile(
    val name: String,
    val provider: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val contextBudget: ContextBudgetConfig? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("provider", provider)
        put("api_key", apiKey)
        put("model", model)
        put("base_url", baseUrl)
        contextBudget?.let { put("context_budget", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): ProviderProfile = ProviderProfile(
            name = json.getString("name"),
            provider = json.optString("provider", ConfigRepository.PROVIDER_OPENAI),
            apiKey = json.optString("api_key", ""),
            model = json.optString("model", ConfigRepository.DEFAULT_MODEL),
            baseUrl = json.optString("base_url", ConfigRepository.DEFAULT_BASE_URL),
            contextBudget = readContextBudget(json)
        )

        private fun readContextBudget(json: JSONObject): ContextBudgetConfig? {
            if (!json.has("context_budget") || json.isNull("context_budget")) return null
            try {
                val budget = json.get("context_budget") as? JSONObject
                    ?: throw InvalidContextBudgetException("上下文预算必须为配置对象")
                return ContextBudgetConfig.fromJson(budget)
            } catch (e: InvalidContextBudgetException) {
                throw InvalidContextBudgetException("配置 ${json.optString("name")}：${e.message}", e)
            }
        }

        fun fromJsonArray(jsonStr: String): List<ProviderProfile> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: InvalidContextBudgetException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toJsonArray(profiles: List<ProviderProfile>): String {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
