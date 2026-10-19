package ai.opencyvis.llm

import ai.opencyvis.config.ContextBudgetConfig

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based client for Ollama native /api/chat endpoint.
 * Uses non-streaming (stream:false) since local inference has no network latency benefit.
 *
 * Key protocol differences from OpenAI-compatible:
 * - Endpoint: /api/chat (not /chat/completions)
 * - Images: message.images = [base64] (not image_url content blocks)
 * - tool_calls[].function.arguments is an object (not a JSON string)
 * - No Authorization header needed
 * - System prompt: separate message with role=system (same as OpenAI)
 */
class OllamaClient(
    private val model: String,
    private val baseUrl: String,
    override val contextBudget: ContextBudgetConfig? = null,
    executionScope: LlmExecutionScope? = null
) : LLMClientInterface {

    companion object {
        private const val TAG = "OllamaClient"
        private const val MAX_RETRIES = 6
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 300_000L  // 5 min — local inference is slow
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val callTracker = LlmCallTracker()
    private val execution = LlmRequestExecution(executionScope)
    private val phoneSchema = LlmRequestSchema.phoneAction(MAX_RETRIES, contextBudget?.outputReserveTokens)
    override val actionTool: JSONObject get() = phoneSchema.tool
    override val lastCallInfo: LlmCallInfo? get() = callTracker.lastCallInfo
    override val callAttempts: List<LlmCallInfo> get() = callTracker.callAttempts

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        chatWithSchema(messages, phoneSchema)

    override suspend fun chatWithSchema(
        messages: List<Map<String, Any>>,
        schema: LlmRequestSchema
    ): Map<String, Any?> = withContext(Dispatchers.IO) { execution.runRound { round ->
            callTracker.reset()
            schema.outputTokenLimit(contextBudget)
            for (attempt in 0 until schema.maxAttempts) {
                round.ensureActive()
                try {
                    val payload = buildPayload(messages, schema)
                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/api/chat (attempt ${attempt + 1}), payload ${payloadStr.length} chars")

                    val request = Request.Builder()
                        .url("$baseUrl/api/chat")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val result = round.attempt(httpClient, request, payload, messages, schema,
                        schema.maxOutputTokens, attempt + 1, callTracker) { response ->
                        val code = response.code
                        if (code !in 200..299) {
                            val errorBody = response.body?.string() ?: ""
                            checkContextLimit(code, errorBody)
                            if (code in 400..499 && code != 429) {
                                throw LLMException("Ollama API error ($code): ${errorBody.take(500)}")
                            }
                            throw IOException("HTTP $code")
                        }
                        // Non-streaming completion is not first-token latency.
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isEmpty()) null else parseResponse(bodyStr, schema)
                    }
                    round.ensureActive()
                    if (result != null) return@runRound result

                    if (attempt < schema.maxAttempts - 1) {
                        Log.w(TAG, "Failed to parse response, retrying (attempt ${attempt + 1})...")
                        delay((1L shl attempt) * 1000)
                        continue
                    }
                    throw LLMException("Cannot parse Ollama response after ${schema.maxAttempts} attempts")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    round.ensureActive()
                    if (attempt == schema.maxAttempts - 1) {
                        val hint = if (e.message?.contains("Connect") == true || e.message?.contains("refused") == true) {
                            " — is Ollama running? Check with: ollama ps"
                        } else ""
                        throw LlmTransientException("Ollama connection failed after ${schema.maxAttempts} attempts: ${e.message}$hint", e)
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("Ollama API call failed: ${e.message}")
                }
            }
            throw LLMException("Ollama API call failed: exhausted retries")
        } }

    /**
     * Build the Ollama /api/chat request payload.
     *
     * Converts from the app's OpenAI-format messages:
     * - system message: passed as-is (role=system)
     * - user message with [image_url, text] content: extract base64 into message.images
     * - assistant message: passed as-is (plain text content)
     */
    private fun buildPayload(messages: List<Map<String, Any>>, schema: LlmRequestSchema): JSONObject {
        val ollamaMessages = JSONArray()

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            val ollamaMsg = JSONObject().put("role", role)

            when (val content = msg["content"]) {
                is String -> ollamaMsg.put("content", content)
                is List<*> -> {
                    // Multimodal content: extract text and images separately
                    val textParts = mutableListOf<String>()
                    val images = JSONArray()

                    for (item in content) {
                        if (item !is Map<*, *>) continue
                        when (item["type"]) {
                            "text" -> {
                                val text = item["text"] as? String
                                if (!text.isNullOrEmpty()) textParts.add(text)
                            }
                            "image_url" -> {
                                // Extract base64 from data:image/jpeg;base64,XXXX
                                val imageUrl = item["image_url"]
                                val url = when (imageUrl) {
                                    is Map<*, *> -> imageUrl["url"] as? String ?: ""
                                    is String -> imageUrl
                                    else -> ""
                                }
                                if (url.startsWith("data:")) {
                                    val commaIdx = url.indexOf(',')
                                    if (commaIdx > 0) {
                                        images.put(url.substring(commaIdx + 1))
                                        // Ollama separates images from text; preserve their order at each label.
                                        textParts.add("[image ${images.length()} in this message]")
                                    }
                                }
                            }
                        }
                    }

                    ollamaMsg.put("content", textParts.joinToString("\n"))
                    if (images.length() > 0) {
                        ollamaMsg.put("images", images)
                    }
                }
                else -> ollamaMsg.put("content", content?.toString() ?: "")
            }

            ollamaMessages.put(ollamaMsg)
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", ollamaMessages)
            put("tools", schema.toolsArray())
            put("stream", false)
            put("think", false)  // disable thinking/CoT to avoid wasting tokens
            put("options", JSONObject().put("num_predict", schema.maxOutputTokens).apply {
                contextBudget?.let { put("num_ctx", it.contextWindowTokens) }
            })
        }
    }

    /**
     * Parse the Ollama /api/chat response.
     *
     * Response format:
     * {
     *   "message": {
     *     "role": "assistant",
     *     "content": "...",
     *     "tool_calls": [{
     *       "function": { "name": "phone_action", "arguments": { ... } }
     *     }]
     *   }
     * }
     *
     * Note: arguments is already an object, not a JSON string.
     */
    private fun parseResponse(bodyStr: String, schema: LlmRequestSchema): Map<String, Any?>? {
        val json = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse response JSON: ${bodyStr.take(200)}", e)
            return null
        }

        val usage = JSONObject()
        for (key in listOf(
            "prompt_eval_count", "eval_count", "prompt_eval_cached_count",
            "total_duration", "load_duration", "prompt_eval_duration", "eval_duration"
        )) {
            if (json.has(key) && !json.isNull(key)) usage.put(key, json.get(key))
        }
        if (usage.length() > 0) {
            callTracker.recordUsage(
                LlmUsage(usage.longOrNull("prompt_eval_count"), usage.longOrNull("eval_count"),
                    usage.longOrNull("prompt_eval_cached_count")),
                usage
            )
            if (json.optBoolean("done", false)) callTracker.markUsageComplete()
        }

        val message = json.optJSONObject("message")
        if (message == null) {
            Log.w(TAG, "No 'message' in response")
            return null
        }

        val calls = SingleToolResponse(schema)
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) for (index in 0 until toolCalls.length()) {
            val function = toolCalls.getJSONObject(index).optJSONObject("function")
            calls.append(index, function?.optString("name"), function?.opt("arguments")?.toString().orEmpty())
        }
        return calls.parse(message.optString("content", "")) { args ->
            try { ResponseParser.jsonObjectToMap(JSONObject(args)) }
            catch (_: Exception) { ResponseParser.extractFieldsFromMalformedJson(args) }
        }
    }

    override fun cancelRequests() = execution.cancelRequests()

    override fun shutdown() {
        execution.shutdown()
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
    }
}
