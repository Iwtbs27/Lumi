package ai.opencyvis.llm

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import org.json.JSONObject

/** Collect through the usage footer, then validate the single requested function. */
internal class SingleToolResponse(private val schema: LlmRequestSchema) {
    private class Call {
        val names = linkedSetOf<String>()
        val arguments = StringBuilder()
    }
    private val calls = linkedMapOf<Int, Call>()

    fun append(index: Int, name: String?, arguments: String = "") {
        val call = calls.getOrPut(index) { Call() }
        if (!name.isNullOrBlank()) call.names += name
        call.arguments.append(arguments)
    }

    fun replace(index: Int, name: String?, arguments: String) {
        append(index, name)
        calls.getValue(index).arguments.apply { setLength(0); append(arguments) }
    }

    fun parse(text: String, legacyArguments: (String) -> Map<String, Any?>?): Map<String, Any?>? {
        if (calls.isEmpty()) {
            if (schema.requireCompleteJson) throw LLMException("Expected a named function call, received text or no call")
            return text.takeIf(String::isNotBlank)?.let(ResponseParser::extractJsonFromText)
        }
        if (calls.size != 1) throw LLMException("Expected one function call, received ${calls.size}")
        val call = calls.values.single()
        val expected = schema.tool.getJSONObject("function").getString("name")
        if (call.names.any { it != expected } || schema.requireCompleteJson && call.names.isEmpty()) {
            throw LLMException("Expected function $expected, received ${call.names.ifEmpty { setOf("<missing>") }}")
        }
        val arguments = call.arguments.toString()
        return if (schema.requireCompleteJson) completeObject(arguments) else legacyArguments(arguments)
    }

    private fun completeObject(text: String): Map<String, Any?>? = try {
        // JSONObject accepts several non-JSON forms on Android. Verify the entire input first.
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.skipValue()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        ResponseParser.jsonObjectToMap(JSONObject(text))
    } catch (_: Exception) { null }
}
