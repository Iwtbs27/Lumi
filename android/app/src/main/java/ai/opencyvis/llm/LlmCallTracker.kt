package ai.opencyvis.llm

import org.json.JSONObject

/** Keeps only the current decision's actual HTTP attempts; no headers are recorded. */
internal class LlmCallTracker {
    private val calls = mutableListOf<LlmCallInfo>()
    private var attemptStartedNanos: Long? = null
    val lastCallInfo: LlmCallInfo? get() = calls.lastOrNull()
    val callAttempts: List<LlmCallInfo> get() = calls.toList()

    fun reset() {
        calls.clear()
        attemptStartedNanos = null
    }

    fun begin(payload: JSONObject, attempt: Int, reservationId: Long? = null,
              ownerId: String? = null, purpose: LlmCallPurpose = LlmCallPurpose.GENERAL) {
        calls += LlmCallInfo(ResponseParser.jsonObjectToMap(payload), null, attempt,
            reservationId = reservationId, ownerId = ownerId, purpose = purpose)
        attemptStartedNanos = System.nanoTime()
    }

    /** Only a non-empty streaming content delta counts, never headers or usage events. */
    fun recordFirstToken() {
        val started = attemptStartedNanos ?: return
        val last = calls.lastIndex
        if (last >= 0 && calls[last].firstTokenLatencyMs == null) {
            calls[last] = calls[last].copy(firstTokenLatencyMs = (System.nanoTime() - started) / 1_000_000)
        }
    }

    fun recordUsage(usage: LlmUsage, rawUsage: JSONObject) {
        val last = calls.lastIndex
        if (last >= 0) {
            calls[last] = calls[last].copy(
                usage = usage,
                rawUsage = ResponseParser.jsonObjectToMap(rawUsage)
            )
        }
    }

    fun markUsageComplete() {
        val last = calls.lastIndex
        val usage = calls.lastOrNull()?.usage ?: return
        if (usage.inputTokens != null && usage.outputTokens != null) {
            calls[last] = calls[last].copy(usageComplete = true)
        }
    }
}

internal fun JSONObject.longOrNull(key: String): Long? =
    (opt(key) as? Number)?.toLong()

internal fun JSONObject.hasNonEmptyText(key: String): Boolean =
    (opt(key) as? String)?.isNotEmpty() == true

/** Only explicit input/context-limit errors trigger the engine's history trimming. */
internal fun checkContextLimit(code: Int, body: String) {
    if (code !in 400..499) return
    val message = body.lowercase()
    if (listOf(
            "context_length_exceeded",
            "maximum context length",
            "context window exceeded",
            "exceeds the context window",
            "exceeds the model's context",
            "prompt is too long",
            "input is too long",
            "too many input tokens",
            "input length exceeds",
            "input token count exceeds"
        ).any(message::contains)) {
        throw ContextLimitException("LLM context limit ($code): ${body.take(500)}")
    }
}

class ContextLimitException(message: String) : LLMException(message)
