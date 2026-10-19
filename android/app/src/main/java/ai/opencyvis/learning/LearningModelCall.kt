package ai.opencyvis.learning

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.memory.recordConsolidationCost
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** Persist every response before interpreting it; recovery reuses completed paid calls. */
internal class LearningModelCall(private val memory: MemoryRepository) {
    suspend fun <T> validated(jobId: String, key: String, checkpoint: JSONObject, client: LLMClientInterface,
        messages: List<Map<String, Any>>, schema: LlmRequestSchema, validate: (JSONObject) -> T): T {
        val repairing = checkpoint.optString("repair_for") == key
        val input = if (repairing) messages + mapOf("role" to "user", "content" to
            "上次返回格式不符合要求。只修正结构和引用，保持已有证据。错误：${checkpoint.optString("validation_error")}\n上次输出：${checkpoint.optJSONObject("invalid_response")}") else messages
        val output = request(jobId, if (repairing) "$key:repair" else key, checkpoint, client, input, schema)
        try { return validate(output) }
        catch (invalid: Exception) {
            if (invalid !is IllegalArgumentException && invalid !is org.json.JSONException) throw invalid
            checkpoint.put("validation_error", invalid.message).put("invalid_response", output)
            if (repairing) {
                persist(jobId, checkpoint)
                throw IllegalArgumentException("学习结果格式纠正后仍不合格：${invalid.message}", invalid)
            }
            checkpoint.put("repair_for", key)
            persist(jobId, checkpoint)
            return validated(jobId, key, checkpoint, client, messages, schema, validate)
        }
    }

    suspend fun request(jobId: String, key: String, checkpoint: JSONObject, client: LLMClientInterface,
        messages: List<Map<String, Any>>, schema: LlmRequestSchema): JSONObject {
        if (checkpoint.optString("response_key") == key) checkpoint.optJSONObject("last_response")?.let { return it }
        val started = System.currentTimeMillis()
        var received = false
        try {
            val response = JSONObject(client.chatWithSchema(messages, schema))
            checkpoint.put("response_key", key).put("last_response", response)
            received = true
            return response
        } finally {
            withContext(NonCancellable) {
                recordConsolidationCost(checkpoint, client, started)
                val calls = checkpoint.optJSONArray("learning_calls") ?: JSONArray()
                client.callAttempts.forEach { call ->
                    calls.put(JSONObject().put("stage", key).put("attempt", call.attempts)
                        .put("started_at", started).put("stage_response_received", received)
                        .put("input_tokens", call.usage?.inputTokens)
                        .put("output_tokens", call.usage?.outputTokens)
                        .put("cached_tokens", call.usage?.cachedTokens))
                }
                checkpoint.put("learning_calls", calls)
                persist(jobId, checkpoint)
            }
        }
    }

    private suspend fun persist(id: String, checkpoint: JSONObject) {
        val job = memory.getJob(id)
        if (job != null && job.status != "cancelled") memory.updateJob(job.copy(checkpointJson = checkpoint.toString()))
    }
}
