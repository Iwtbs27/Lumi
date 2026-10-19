package ai.opencyvis.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

/**
 * Component counts are uncalibrated estimates. totalTokens applies calibrationFactor
 * to their sum; provider usage remains the source of actual input-token counts.
 */
internal data class ContextTokenEstimate(
    val totalTokens: Int,
    val textTokens: Int,
    val toolTokens: Int,
    val imageTokens: Int,
    val calibrationFactor: Double,
    val imageCount: Int
) {
    fun toMap(): Map<String, Any> = mapOf(
        "total_tokens" to totalTokens,
        "text_tokens" to textTokens,
        "tool_tokens" to toolTokens,
        "image_tokens" to imageTokens,
        "calibration_factor" to calibrationFactor,
        "image_count" to imageCount
    )
}

/**
 * Provider-independent budgeting heuristic, not a model tokenizer. ASCII uses roughly
 * four characters per token; non-ASCII code points reserve two tokens each. Images
 * reserve 4096 tokens each, independent of their encoded size, resolution or detail.
 * Actual usage can raise the estimate for subsequent requests from this instance.
 */
internal class ContextTokenEstimator {
    private var calibrationFactor = 1.0

    fun estimate(messages: List<Map<String, Any>>, tool: JSONObject): ContextTokenEstimate {
        var imageCount = 0

        fun withoutImagePayload(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                if (value["type"] == "image_url") {
                    imageCount++
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to "[image]"))
                } else {
                    value.mapValues { (_, nested) -> withoutImagePayload(nested) }
                }
            }
            is Iterable<*> -> value.map(::withoutImagePayload)
            else -> value
        }

        // Count role/content fields and JSON structure, plus message/request framing.
        val textTokens = REQUEST_OVERHEAD + messages.sumOf { message ->
            val textOnly = message.mapValues { (_, value) -> withoutImagePayload(value) }
            MESSAGE_OVERHEAD + estimateText(JSONObject(textOnly).toString())
        }
        val toolsEnvelope = JSONObject().put("tools", JSONArray().put(tool))
        val toolTokens = TOOL_OVERHEAD + estimateText(toolsEnvelope.toString())
        val imageTokens = imageCount * TOKENS_PER_IMAGE
        val baseTokens = textTokens.toLong() + toolTokens + imageTokens
        val factor = calibrationFactor
        return ContextTokenEstimate(
            totalTokens = ceil(baseTokens * factor).toInt(),
            textTokens = textTokens,
            toolTokens = toolTokens,
            imageTokens = imageTokens,
            calibrationFactor = factor,
            imageCount = imageCount
        )
    }

    fun calibrate(estimate: ContextTokenEstimate, actualInputTokens: Long?) {
        if (actualInputTokens == null) return
        val baseTokens = estimate.textTokens.toLong() + estimate.toolTokens + estimate.imageTokens
        // Compare with the original basis, not the already calibrated total. Reusing
        // an observation therefore cannot multiply the correction a second time.
        calibrationFactor = max(calibrationFactor, actualInputTokens.toDouble() / baseTokens)
    }

    private fun estimateText(text: String): Int {
        var ascii = 0
        var nonAscii = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (codePoint <= 0x7f) ascii++ else nonAscii++
            offset += Character.charCount(codePoint)
        }
        return (ascii + 3) / 4 + nonAscii * 2
    }

    private companion object {
        const val REQUEST_OVERHEAD = 3
        const val MESSAGE_OVERHEAD = 6
        const val TOOL_OVERHEAD = 8
        const val TOKENS_PER_IMAGE = 4096
    }
}
