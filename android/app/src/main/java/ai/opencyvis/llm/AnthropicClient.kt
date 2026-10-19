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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based client for Anthropic Messages API (/v1/messages).
 * Implements the same interface as LLMClient but with Anthropic protocol.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    override val contextBudget: ContextBudgetConfig? = null,
    executionScope: LlmExecutionScope? = null
) : LLMClientInterface {

    companion object {
        private const val TAG = "AnthropicClient"
        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 90_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
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
            val outputLimit = schema.outputTokenLimit(contextBudget)
            var maxTokens = schema.maxOutputTokens

            for (attempt in 0 until schema.maxAttempts) {
                round.ensureActive()
                try {
                    val payload = buildRequestPayload(messages, maxTokens, schema)
                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/v1/messages (attempt ${attempt + 1}), payload ${payloadStr.length} chars")

                    val request = Request.Builder()
                        .url("$baseUrl/v1/messages")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val result = round.attempt(httpClient, request, payload, messages, schema,
                        maxTokens, attempt + 1, callTracker) { response ->
                        val code = response.code
                        if (code !in 200..299) {
                            val errorBody = response.body?.string() ?: ""
                            checkContextLimit(code, errorBody)
                            if (code in 400..499 && code != 429) {
                                throw LLMException("Anthropic API error ($code): ${errorBody.take(500)}")
                            }
                            throw IOException("HTTP $code")
                        }
                        parseSSEStream(response, schema)
                    }
                    round.ensureActive()
                    if (result != null) return@runRound result
                    if (attempt < schema.maxAttempts - 1) {
                        Log.w(TAG, "Stream parse returned null, retrying with more tokens")
                        maxTokens = schema.nextOutputTokens(maxTokens, outputLimit)
                        continue
                    }

                    throw LLMException("Cannot parse Anthropic streaming response")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    round.ensureActive()
                    if (attempt == schema.maxAttempts - 1) {
                        throw LlmTransientException("Anthropic API failed after ${schema.maxAttempts} attempts: ${e.message}", e)
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("Anthropic API call failed: ${e.message}")
                }
            }
            throw LLMException("Anthropic API call failed: exhausted retries")
        } }

    /**
     * Build the Anthropic /v1/messages request payload.
     *
     * Key differences from OpenAI:
     * - system prompt is a top-level "system" string, not a message
     * - tool definitions use input_schema (not parameters)
     * - tool_choice {"type":"any"} = must use a tool
     * - image content uses {type:"image", source:{type:"base64",...}}
     */
    private fun buildRequestPayload(messages: List<Map<String, Any>>, maxTokens: Int, schema: LlmRequestSchema): JSONObject {
        var systemPrompt: String? = null
        val anthropicMessages = JSONArray()

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            if (role == "system") {
                systemPrompt = msg["content"] as? String
                continue
            }

            val jsonMsg = JSONObject()
            jsonMsg.put("role", role)

            when (val content = msg["content"]) {
                is String -> jsonMsg.put("content", content)
                is List<*> -> {
                    val contentArray = JSONArray()
                    for (item in content) {
                        if (item is Map<*, *>) {
                            contentArray.put(convertContentBlock(item))
                        }
                    }
                    jsonMsg.put("content", contentArray)
                }
                else -> jsonMsg.put("content", content?.toString() ?: "")
            }
            anthropicMessages.put(jsonMsg)
        }

        return JSONObject().apply {
            put("model", model)
            if (systemPrompt != null) put("system", systemPrompt)
            put("messages", anthropicMessages)
            put("max_tokens", maxTokens)
            put("tools", schema.anthropicToolsArray())
            put("tool_choice", JSONObject().put("type", "any"))
            put("stream", true)
        }
    }

    /**
     * Convert a content block from OpenAI format to Anthropic format.
     * Main conversion: image_url -> image with base64 source.
     */
    private fun convertContentBlock(block: Map<*, *>): JSONObject {
        val type = block["type"] as? String
        if (type == "image_url") {
            // Convert from {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}
            // to {type:"image", source:{type:"base64", media_type:"image/jpeg", data:"..."}}
            val imageUrl = block["image_url"]
            val url = when (imageUrl) {
                is Map<*, *> -> imageUrl["url"] as? String ?: ""
                is String -> imageUrl
                else -> ""
            }
            val obj = JSONObject()
            obj.put("type", "image")
            val source = JSONObject()
            if (url.startsWith("data:")) {
                // Parse data URI: data:image/jpeg;base64,XXXX
                val commaIdx = url.indexOf(',')
                val meta = url.substring(5, url.indexOf(';')) // "image/jpeg"
                val data = url.substring(commaIdx + 1)
                source.put("type", "base64")
                source.put("media_type", meta)
                source.put("data", data)
            } else {
                // Plain URL - use url source type
                source.put("type", "url")
                source.put("url", url)
            }
            obj.put("source", source)
            return obj
        }

        // text and other types pass through
        val obj = JSONObject()
        for ((k, v) in block) {
            when (v) {
                is Map<*, *> -> {
                    val inner = JSONObject()
                    for ((ik, iv) in v) inner.put(ik.toString(), iv)
                    obj.put(k.toString(), inner)
                }
                else -> obj.put(k.toString(), v)
            }
        }
        return obj
    }

    /**
     * Parse Anthropic SSE stream.
     *
     * Anthropic event types:
     * - message_start: {message: {id, model, ...}}
     * - content_block_start: {index, content_block: {type: "tool_use", id, name}}
     * - content_block_delta: {index, delta: {type: "input_json_delta", partial_json: "..."}}
     * - content_block_stop: {index}
     * - message_delta: {delta: {stop_reason: "tool_use"}, usage: {...}}
     * - message_stop
     *
     * Accumulate arguments and cumulative usage through message_stop.
     */
    private fun parseSSEStream(response: okhttp3.Response, schema: LlmRequestSchema): Map<String, Any?>? {
        val body = response.body ?: return null
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        val calls = SingleToolResponse(schema)
        val textBuilder = StringBuilder()
        val accumulatedUsage = JSONObject()
        var receivedOutputUsage = false

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                if (!currentLine.startsWith("data:")) continue
                val data = currentLine.removePrefix("data:").trim()
                if (data.isEmpty()) continue

                val event: JSONObject = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping non-JSON SSE line: ${data.take(100)}")
                    continue
                }

                val eventType = event.optString("type", "")
                val usage = if (eventType == "message_start") {
                    event.optJSONObject("message")?.optJSONObject("usage")
                } else {
                    event.optJSONObject("usage")
                }
                if (usage != null) {
                    if (eventType == "message_delta" && usage.longOrNull("output_tokens") != null) {
                        receivedOutputUsage = true
                    }
                    // message_delta counts are cumulative; merge fields, never sum deltas.
                    for (key in usage.keys()) accumulatedUsage.put(key, usage.get(key))
                    val inputTokens = accumulatedUsage.longOrNull("input_tokens")?.let { input ->
                        input + (accumulatedUsage.longOrNull("cache_read_input_tokens") ?: 0L) +
                            (accumulatedUsage.longOrNull("cache_creation_input_tokens") ?: 0L)
                    }
                    callTracker.recordUsage(
                        LlmUsage(inputTokens, accumulatedUsage.longOrNull("output_tokens"),
                            accumulatedUsage.longOrNull("cache_read_input_tokens")),
                        accumulatedUsage
                    )
                }

                when (eventType) {
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block")
                        if (block?.optString("type") == "tool_use") {
                            val input = block.optJSONObject("input")?.takeIf { it.length() > 0 }?.toString().orEmpty()
                            calls.append(event.optInt("index", 0), block.optString("name"), input)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        val deltaType = delta.optString("type", "")
                        val tokenField = when (deltaType) {
                            "input_json_delta" -> "partial_json"
                            "text_delta" -> "text"
                            "thinking_delta" -> "thinking"
                            else -> null
                        }
                        if (tokenField != null && delta.hasNonEmptyText(tokenField)) callTracker.recordFirstToken()
                        when (deltaType) {
                            "input_json_delta" -> {
                                val chunk = delta.optString("partial_json", "")
                                calls.append(event.optInt("index", 0), null, chunk)
                            }
                            "text_delta" -> {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) textBuilder.append(text)
                            }
                        }
                    }
                    "message_stop" -> {
                        // Stream complete
                        if (receivedOutputUsage) callTracker.markUsageComplete()
                        break
                    }
                }

            }
        } finally {
            reader.close()
            body.close()
        }

        return calls.parse(textBuilder.toString(), ::parseFunctionCallArgs)
    }

    private fun parseFunctionCallArgs(argsStr: String): Map<String, Any?>? {
        return try {
            ResponseParser.jsonObjectToMap(JSONObject(argsStr))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool args: ${argsStr.take(200)}", e)
            null
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
