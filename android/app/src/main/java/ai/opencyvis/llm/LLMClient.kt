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
 * OkHttp-based client for OpenAI-compatible /chat/completions endpoint.
 * Uses connection pooling, HTTP/2, and streaming (SSE) for lower latency.
 */
class LLMClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    private val disableThinking: Boolean = false,
    override val contextBudget: ContextBudgetConfig? = null,
    executionScope: LlmExecutionScope? = null
) : LLMClientInterface {

    companion object {
        private const val TAG = "LLMClient"
        private const val MAX_RETRIES = 6
        private const val DEFAULT_TEMPERATURE = 0.1
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 90_000L  // longer for streaming
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)  // we handle retries ourselves
        .build()

    private val callTracker = LlmCallTracker()
    private val execution = LlmRequestExecution(executionScope)
    private val phoneSchema = LlmRequestSchema.phoneAction(MAX_RETRIES, contextBudget?.outputReserveTokens)
    override val actionTool: JSONObject get() = phoneSchema.tool
    override val lastCallInfo: LlmCallInfo? get() = callTracker.lastCallInfo
    override val callAttempts: List<LlmCallInfo> get() = callTracker.callAttempts

    /**
     * Send messages to the OpenAI-compatible /chat/completions endpoint with tool calling.
     * Uses streaming (SSE) and reads the final usage event before returning.
     * Retries a response that does not contain parseable action arguments.
     *
     * @param messages List of message maps in OpenAI format
     * @return Map of action parameters from the tool call
     * @throws LLMException if all retries fail
     */
    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        chatWithSchema(messages, phoneSchema)

    override suspend fun chatWithSchema(
        messages: List<Map<String, Any>>,
        schema: LlmRequestSchema
    ): Map<String, Any?> = withContext(Dispatchers.IO) { execution.runRound { round ->
            callTracker.reset()
            val outputLimit = schema.outputTokenLimit(contextBudget)
            val messagesArray = convertMessagesToJson(messages)
            var maxTokens = schema.maxOutputTokens

            for (attempt in 0 until schema.maxAttempts) {
                round.ensureActive()
                try {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("messages", messagesArray)
                        put("max_tokens", maxTokens)
                        put("temperature", temperature)
                        put("tools", schema.toolsArray())
                        put("tool_choice", "required")
                        if (disableThinking) {
                            put("thinking", JSONObject().put("type", "disabled"))
                        } else if (model.contains("qwen3") || model.contains("qwq")) {
                            put("enable_thinking", false)
                        }
                        put("stream", true)
                        put("stream_options", JSONObject().put("include_usage", true))
                    }

                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/chat/completions (attempt ${attempt + 1}), payload ${payloadStr.length} chars, stream=true")

                    val request = Request.Builder()
                        .url("$baseUrl/chat/completions")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()

                    val result = round.attempt(httpClient, request, payload, messages, schema,
                        maxTokens, attempt + 1, callTracker) { response ->
                        val code = response.code
                        if (code !in 200..299) {
                            val errorBody = response.body?.string() ?: ""
                            checkContextLimit(code, errorBody)
                            if (code in 400..499 && code != 429) {
                                throw LLMException("LLM API error ($code): ${errorBody.take(500)}")
                            }
                            throw IOException("HTTP $code")
                        }
                        parseSSEStream(response, schema)
                    }
                    round.ensureActive()
                    if (result != null) return@runRound result

                    // No parseable action was returned.
                    if (attempt < schema.maxAttempts - 1) {
                        Log.w(TAG, "Stream parse returned null, retrying with more tokens")
                        maxTokens = schema.nextOutputTokens(maxTokens, outputLimit)
                        continue
                    }

                    throw LLMException("Cannot parse LLM streaming response")

                } catch (e: CancellationException) {
                    throw e
                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    round.ensureActive()
                    if (attempt == schema.maxAttempts - 1) {
                        throw LlmTransientException(
                            "LLM API failed after ${schema.maxAttempts} attempts: ${e.message}", e
                        )
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("LLM API call failed: ${e.message}")
                }
            }

            throw LLMException("LLM API call failed: exhausted retries")
        } }

    /**
     * Parse SSE stream from the OpenAI-compatible /chat/completions API.
     *
     * Each chunk: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"..."}}]}, "finish_reason":null}]}
     * Final chunk: {"choices":[{"delta":{}, "finish_reason":"tool_calls"}]}
     *
     * Reads through the final usage chunk before returning the action.
     */
    private fun parseSSEStream(response: okhttp3.Response, schema: LlmRequestSchema): Map<String, Any?>? {
        val body = response.body ?: return null
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        val calls = SingleToolResponse(schema)
        val contentBuilder = StringBuilder()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                if (!currentLine.startsWith("data:")) continue
                val data = currentLine.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                val event: JSONObject = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping non-JSON SSE line: ${data.take(100)}")
                    continue
                }

                val eventType = event.optString("type", "")
                if (eventType in listOf("response.output_text.delta", "response.function_call_arguments.delta",
                        "response.reasoning_text.delta", "response.reasoning_summary_text.delta") &&
                    event.hasNonEmptyText("delta")) {
                    callTracker.recordFirstToken()
                }
                val usage = event.optJSONObject("usage")
                    ?: event.optJSONObject("response")?.optJSONObject("usage")
                if (usage != null) {
                    callTracker.recordUsage(
                        LlmUsage(
                            usage.longOrNull("prompt_tokens") ?: usage.longOrNull("input_tokens"),
                            usage.longOrNull("completion_tokens") ?: usage.longOrNull("output_tokens"),
                            usage.optJSONObject("prompt_tokens_details")?.longOrNull("cached_tokens")
                                ?: usage.optJSONObject("input_tokens_details")?.longOrNull("cached_tokens")
                        ),
                        usage
                    )
                    if (eventType == "response.completed" || event.optJSONArray("choices")?.length() == 0) {
                        callTracker.markUsageComplete()
                    }
                }
                if (eventType == "response.output_item.added" || eventType == "response.output_item.done") {
                    val item = event.optJSONObject("item")
                    if (item?.optString("type") == "function_call") {
                        val index = event.optInt("output_index", 0)
                        if (eventType == "response.output_item.done") calls.replace(index,
                            item.optString("name"), item.optString("arguments", ""))
                        else calls.append(index, item.optString("name"))
                    }
                }
                if (eventType == "response.function_call_arguments.delta") {
                    calls.append(event.optInt("output_index", 0), null, event.optString("delta", ""))
                }
                if (eventType == "response.function_call_arguments.done") {
                    calls.replace(event.optInt("output_index", 0), null, event.optString("arguments", ""))
                }

                if (eventType == "response.output_text.done") {
                    val text = event.optString("text", "")
                    if (text.isNotEmpty()) {
                        contentBuilder.append(text)
                    }
                }

                val choices = event.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val choice = choices.getJSONObject(0)

                val delta = choice.optJSONObject("delta") ?: continue
                if (delta.hasNonEmptyText("content") || delta.hasNonEmptyText("reasoning_content") ||
                    delta.hasNonEmptyText("reasoning")) {
                    callTracker.recordFirstToken()
                }

                // Accumulate tool_calls argument chunks
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null) for (index in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(index)
                    val function = call.optJSONObject("function")
                    if (function?.hasNonEmptyText("arguments") == true) {
                        callTracker.recordFirstToken()
                    }
                    calls.append(call.optInt("index", index), function?.optString("name"),
                        function?.optString("arguments", "").orEmpty())
                }

                // Accumulate text content (fallback if model ignores tool_choice)
                // Note: optString returns "null" for JSON null values on Android
                val content = delta.optString("content", "")
                if (content.isNotEmpty() && content != "null") {
                    contentBuilder.append(content)
                }

            }
        } finally {
            reader.close()
            body.close()
        }

        return calls.parse(contentBuilder.toString(), ::parseFunctionCallArgs)
    }

    /**
     * Parse function_call arguments JSON string into a Map.
     * Falls back to regex-based field extraction when SSE streaming corrupts
     * escaped quotes (e.g. \"iphone16pro\" decoded to bare "iphone16pro").
     */
    private fun parseFunctionCallArgs(argsStr: String): Map<String, Any?>? {
        Log.d(TAG, "parseFunctionCallArgs raw: ${argsStr.take(500)}")
        // 1. Try direct JSON parse
        try {
            return ResponseParser.jsonObjectToMap(JSONObject(argsStr))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse function_call arguments: ${argsStr.take(200)}", e)
        }

        // 2. Try closing truncated JSON (missing trailing "}")
        if (!argsStr.trimEnd().endsWith("}")) {
            try {
                return ResponseParser.jsonObjectToMap(JSONObject("$argsStr}"))
            } catch (_: Exception) {}
        }

        // 3. Regex-based field extraction for malformed JSON
        //    (handles SSE decoding corrupting escaped quotes in string values)
        return ResponseParser.extractFieldsFromMalformedJson(argsStr)
    }

    /**
     * Convert messages list to JSONArray in OpenAI chat/completions format.
     * Handles nested Maps (e.g. image_url: {url: "..."}).
     */
    private fun convertMessagesToJson(messages: List<Map<String, Any>>): JSONArray {
        val array = JSONArray()
        for (msg in messages) {
            val jsonMsg = JSONObject()
            jsonMsg.put("role", msg["role"])

            when (val content = msg["content"]) {
                is String -> jsonMsg.put("content", content)
                is List<*> -> {
                    val contentArray = JSONArray()
                    for (item in content) {
                        if (item is Map<*, *>) {
                            contentArray.put(mapToJsonObject(item))
                        }
                    }
                    jsonMsg.put("content", contentArray)
                }
                else -> jsonMsg.put("content", content?.toString() ?: "")
            }

            array.put(jsonMsg)
        }
        return array
    }

    /** Recursively convert a Map to JSONObject, handling nested Maps. */
    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> obj.put(k.toString(), mapToJsonObject(v))
                is List<*> -> obj.put(k.toString(), JSONArray(v))
                else -> obj.put(k.toString(), v)
            }
        }
        return obj
    }

    /**
     * Shut down the HTTP client and release connections.
     */
    override fun cancelRequests() = execution.cancelRequests()

    override fun shutdown() {
        execution.shutdown()
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {
            // Ignore — may already be shut down
        }
    }
}

/**
 * Exception type for LLM client errors.
 */
open class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A transport/rate-limit failure that a persistent background job may retry. */
class LlmTransientException(message: String, cause: Throwable? = null) : LLMException(message, cause)
