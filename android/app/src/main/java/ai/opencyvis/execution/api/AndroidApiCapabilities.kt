package ai.opencyvis.execution.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Android's file/calendar adapters, callable by the parent or its code execution host. */
class AndroidApiCapabilities(private val context: Context, private val artifacts: ArtifactRepository) {
    private val files = SafFileApi(context, artifacts)
    private val calendar = CalendarApi(context)

    /** Stable capability schemas plus current authorization state; no picker is launched here. */
    fun describe(): JSONArray {
        val grants = context.contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
        val fileRead = grants.any { it.isReadPermission }
        val fileWrite = grants.any { it.isReadPermission && it.isWritePermission }
        val calendarRead = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val calendarWrite = calendarRead && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val text = mapOf("type" to "string")
        val integer = mapOf("type" to "integer")
        val id = mapOf("type" to "integer", "minimum" to 1)
        val paging = mapOf("offset" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "default" to 50))
        val event = mapOf("title" to text, "start_ms" to integer, "end_ms" to integer, "time_zone" to text, "location" to text)
        fun card(name: String, description: String, available: Boolean, mutates: Boolean,
            properties: Map<String, Any>, required: List<String>, requirement: String? = null): JSONObject =
            JSONObject().put("name", name).put("description", description).put("available", available).put("mutates", mutates)
                .put("unavailable_reason", if (available) JSONObject.NULL else requirement)
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject(properties))
                    .put("required", JSONArray(required)).put("additionalProperties", false))
        return JSONArray().apply {
            put(card("files.roots", "List directory grants retained from the Android picker; inspect available/can_write and provider flags.",
                true, false, emptyMap(), emptyList()))
            put(card("files.list", "List one granted directory. Omit parent_uri to list the tree root. Results have document URIs and pagination.",
                fileRead, false, mapOf("tree_uri" to text, "parent_uri" to text) + paging,
                listOf("tree_uri"), "Choose a readable directory in Settings"))
            put(card("files.import", "Read the real document into a fixed binary artifact version; does not modify the provider file.",
                fileRead, false, mapOf("tree_uri" to text, "document_uri" to text), listOf("tree_uri", "document_uri"),
                "Choose a readable directory in Settings"))
            put(card("files.export", "Create a NEW file from a saved artifact. Read it back and return the actual URI/name and an immutable readback artifact.",
                fileWrite, true, mapOf("tree_uri" to text, "parent_uri" to text, "artifact_id" to text, "version" to id,
                    "name" to text, "mime_type" to text), listOf("tree_uri", "artifact_id", "version", "name"),
                "Choose a writable directory in Settings"))
            put(card("files.rename", "Rename a provider-supported document and query its actual name; use the returned URI because it may change.",
                fileWrite, true, mapOf("tree_uri" to text, "document_uri" to text, "name" to text),
                listOf("tree_uri", "document_uri", "name"), "Choose a writable directory in Settings"))
            put(card("calendar.list", "List existing accessible calendars and their actual IDs/access levels.",
                calendarRead, false, emptyMap(), emptyList(), "Grant READ_CALENDAR in Settings"))
            put(card("calendar.query", "Query occurrences in one calendar for a UTC epoch-millisecond range. Repeating events are expanded as occurrences.",
                calendarRead, false, mapOf("calendar_id" to id, "start_ms" to integer, "end_ms" to integer) + paging,
                listOf("calendar_id", "start_ms", "end_ms"), "Grant READ_CALENDAR in Settings"))
            put(card("calendar.get", "Read an existing event by its actual event_id.", calendarRead, false,
                mapOf("event_id" to id), listOf("event_id"), "Grant READ_CALENDAR in Settings"))
            put(card("calendar.create", "Create an ordinary single timed event in an existing writable calendar, then read its actual ID back. Times are UTC milliseconds; time_zone is an explicit zone ID.",
                calendarWrite, true, mapOf("calendar_id" to id) + event,
                listOf("calendar_id", "title", "start_ms", "end_ms", "time_zone"), "Grant READ_CALENDAR and WRITE_CALENDAR in Settings"))
            put(card("calendar.update", "Update specified title/time/location fields of an ordinary single timed event and read it back. Other fields remain unchanged; recurring/all-day edits are unsupported.",
                calendarWrite, true, mapOf("event_id" to id) + event, listOf("event_id"),
                "Grant READ_CALENDAR and WRITE_CALENDAR in Settings"))
        }
    }

    /** onEffect records a provider-issued URI/ID before subsequent verification. */
    suspend fun call(name: String, args: JSONObject,
        onEffect: (JSONObject) -> Unit = {}): JSONObject = withContext(Dispatchers.IO) {
        try {
            when {
                name.startsWith("files.") -> files.call(name, args, onEffect)
                name.startsWith("calendar.") -> calendar.call(name, args, onEffect)
                else -> throw IllegalArgumentException("Unknown Android capability: $name")
            }
        } catch (invalid: JSONException) {
            throw IllegalArgumentException("Invalid $name arguments or provider result: ${invalid.message}", invalid)
        }
    }

    companion object {
        fun persistTreeGrant(context: Context, uri: Uri, returnedFlags: Int) {
            require(DocumentsContract.isTreeUri(uri)) { "Select a directory with ACTION_OPEN_DOCUMENT_TREE" }
            val modes = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(modes and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "The picker did not grant read access" }
            context.contentResolver.takePersistableUriPermission(uri, modes)
        }
    }
}
