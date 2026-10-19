package ai.opencyvis.execution.api

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Ordinary application access to existing calendars; never creates accounts or calendar containers. */
internal class CalendarApi(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun call(name: String, args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject {
        permission(Manifest.permission.READ_CALENDAR)
        return when (name) {
            "calendar.list" -> list()
            "calendar.query" -> query(args)
            "calendar.get" -> success().put("event", get(args.positiveId("event_id")))
            "calendar.create" -> create(args, onEffect)
            "calendar.update" -> update(args, onEffect)
            else -> throw IllegalArgumentException("Unknown calendar capability: $name")
        }
    }

    private fun list(): JSONObject {
        val calendars = JSONArray()
        queryCursor(Calendars.CONTENT_URI, CALENDAR_COLUMNS, null, null, "${Calendars._ID} ASC").use { cursor ->
            while (cursor.moveToNext()) calendars.put(calendar(cursor))
        }
        return success().put("calendars", calendars)
    }

    private fun calendar(cursor: Cursor): JSONObject = JSONObject()
        .put("calendar_id", cursor.long(Calendars._ID))
        .put("name", cursor.string(Calendars.CALENDAR_DISPLAY_NAME))
        .put("access_level", cursor.long(Calendars.CALENDAR_ACCESS_LEVEL))
        .put("writable", cursor.long(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR)
        .put("visible", cursor.long(Calendars.VISIBLE) != 0L)
        .put("sync_events", cursor.long(Calendars.SYNC_EVENTS) != 0L)

    private fun requireWritable(id: Long) {
        permission(Manifest.permission.WRITE_CALENDAR)
        queryCursor(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), CALENDAR_COLUMNS, null, null).use {
            require(it.moveToFirst()) { "Calendar $id is not accessible; choose an ID from calendar.list" }
            require(it.long(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR) {
                "Calendar $id is read-only"
            }
        }
    }

    private fun query(args: JSONObject): JSONObject {
        val calendarId = args.positiveId("calendar_id")
        val start = args.requiredLong("start_ms")
        val end = args.requiredLong("end_ms")
        require(end > start) { "end_ms must be after start_ms" }
        val offset = args.pageOffset()
        val limit = args.pageLimit()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        queryCursor(uri, INSTANCE_COLUMNS, "${Instances.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()), "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC").use { cursor ->
            val events = JSONArray()
            var position = offset
            while (events.length() < limit && cursor.moveToPosition(position)) {
                events.put(event(cursor, instance = true))
                position++
            }
            return success().put("events", events).put("offset", offset).put("total", cursor.count)
                .put("next_offset", if (position < cursor.count) position else JSONObject.NULL)
        }
    }

    private fun get(id: Long): JSONObject =
        queryCursor(ContentUris.withAppendedId(Events.CONTENT_URI, id), EVENT_COLUMNS, null, null).use {
            require(it.moveToFirst() && it.long(Events.DELETED) == 0L) { "Event $id is not accessible" }
            event(it)
        }

    private fun event(cursor: Cursor, instance: Boolean = false): JSONObject = JSONObject()
        .put("event_id", cursor.long(if (instance) Instances.EVENT_ID else Events._ID))
        .put("calendar_id", cursor.long(Events.CALENDAR_ID))
        .put("title", cursor.string(Events.TITLE))
        .put("start_ms", cursor.number(if (instance) Instances.BEGIN else Events.DTSTART))
        .put("end_ms", cursor.number(if (instance) Instances.END else Events.DTEND))
        .put("time_zone", cursor.string(Events.EVENT_TIMEZONE))
        .put("location", cursor.string(Events.EVENT_LOCATION))
        .put("all_day", cursor.long(Events.ALL_DAY) != 0L)
        .put("recurring", !cursor.nullableString(Events.RRULE).isNullOrBlank() ||
            !cursor.nullableString(Events.RDATE).isNullOrBlank() || !cursor.isNull(cursor.getColumnIndexOrThrow(Events.ORIGINAL_ID)))

    private fun create(args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject {
        args.onlyKeys(CREATE_FIELDS)
        val calendarId = args.positiveId("calendar_id")
        val title = args.requiredText("title")
        val start = args.requiredLong("start_ms")
        val end = args.requiredLong("end_ms")
        val zone = args.requiredText("time_zone")
        validateTime(start, end, zone)
        requireWritable(calendarId)
        val expected = JSONObject().put("calendar_id", calendarId).put("title", title).put("start_ms", start)
            .put("end_ms", end).put("time_zone", zone).put("location", if (args.has("location")) args.getString("location") else "")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, start)
            put(Events.DTEND, end)
            put(Events.EVENT_TIMEZONE, zone)
            put(Events.EVENT_LOCATION, expected.getString("location"))
            put(Events.ALL_DAY, 0)
        }
        val uri = requireNotNull(resolver.insert(Events.CONTENT_URI, values)) { "Calendar provider did not create an event" }
        val id = ContentUris.parseId(uri)
        onEffect(JSONObject().put("operation", "calendar.create").put("event_id", id).put("calendar_id", calendarId).put("uri", uri.toString()))
        return verify(id, uri, expected)
    }

    private fun update(args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject {
        args.onlyKeys(UPDATE_FIELDS + "event_id")
        val id = args.positiveId("event_id")
        val before = get(id)
        require(!before.getBoolean("recurring") && !before.getBoolean("all_day")) {
            "calendar.update supports ordinary single timed events; recurring/all-day events require their calendar app"
        }
        requireWritable(before.getLong("calendar_id"))
        val selected = UPDATE_FIELDS.filter { args.has(it) }
        require(selected.isNotEmpty()) { "Specify a title, time or location to update" }
        val expected = JSONObject()
        selected.forEach { key -> expected.put(key, when (key) {
            "start_ms", "end_ms" -> args.requiredLong(key)
            "title", "time_zone" -> args.requiredText(key)
            else -> args.getString(key)
        }) }
        validateTime(if (expected.has("start_ms")) expected.getLong("start_ms") else before.getLong("start_ms"),
            if (expected.has("end_ms")) expected.getLong("end_ms") else before.getLong("end_ms"),
            if (expected.has("time_zone")) expected.getString("time_zone") else before.getString("time_zone"))
        val values = ContentValues().apply {
            selected.forEach { key ->
                val column = FIELD_COLUMNS.getValue(key)
                if (key == "start_ms" || key == "end_ms") put(column, expected.getLong(key))
                else put(column, expected.getString(key))
            }
        }
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
        val updated = resolver.update(uri, values, null, null)
        require(updated == 1) { "Calendar provider updated $updated events for ID $id" }
        onEffect(JSONObject().put("operation", "calendar.update").put("event_id", id).put("uri", uri.toString()))
        return verify(id, uri, expected)
    }

    private fun verify(id: Long, uri: Uri, expected: JSONObject): JSONObject = try {
        val actual = get(id)
        val mismatched = expected.keys().asSequence().filter { key -> expected.get(key) != actual.opt(key) }.toList()
        JSONObject().put("status", if (mismatched.isEmpty()) "succeeded" else "failed")
            .put("verified", mismatched.isEmpty()).put("event_id", id).put("uri", uri.toString())
            .put("event", actual).put("checked_fields", JSONArray(expected.keys().asSequence().toList()))
            .put("mismatched_fields", JSONArray(mismatched))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        JSONObject().put("status", "unknown").put("verified", false).put("event_id", id)
            .put("uri", uri.toString()).put("error", "Event was written; readback failed: ${failure.message}")
    }

    private fun permission(name: String) {
        require(context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) { "Calendar access requires $name; grant it in Settings" }
    }

    private fun validateTime(start: Long, end: Long, zone: String) {
        require(end > start) { "end_ms must be after start_ms" }
        try { ZoneId.of(zone) } catch (invalid: DateTimeException) {
            throw IllegalArgumentException("Invalid time_zone: $zone", invalid)
        }
    }

    private fun queryCursor(uri: Uri, columns: Array<String>, selection: String?, selectionArgs: Array<String>?,
        order: String? = null): Cursor = requireNotNull(resolver.query(uri, columns, selection, selectionArgs, order)) {
        "Calendar provider is unavailable"
    }

    companion object {
        private val CALENDAR_COLUMNS = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.SYNC_EVENTS)
        private val EVENT_COLUMNS = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DTSTART,
            Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_LOCATION, Events.ALL_DAY,
            Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.DELETED)
        private val INSTANCE_COLUMNS = EVENT_COLUMNS.filterNot { it == Events._ID }.toTypedArray() +
            arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END)
        private val FIELD_COLUMNS = mapOf("title" to Events.TITLE, "start_ms" to Events.DTSTART,
            "end_ms" to Events.DTEND, "time_zone" to Events.EVENT_TIMEZONE, "location" to Events.EVENT_LOCATION)
        private val UPDATE_FIELDS = FIELD_COLUMNS.keys
        private val CREATE_FIELDS = UPDATE_FIELDS + "calendar_id"
    }
}

internal fun success(): JSONObject = JSONObject().put("status", "succeeded")
internal fun JSONObject.requiredText(key: String): String = getString(key).also { require(it.isNotBlank()) { "$key must not be blank" } }
internal fun JSONObject.requiredLong(key: String): Long {
    val value = get(key)
    require(value is Number && value.toDouble() == value.toLong().toDouble()) { "$key must be an integer" }
    return value.toLong()
}
internal fun JSONObject.positiveId(key: String): Long = requiredLong(key).also { require(it > 0) { "$key must be positive" } }
internal fun JSONObject.pageOffset(): Int = (if (has("offset")) requiredLong("offset") else 0L).also {
    require(it in 0L..Int.MAX_VALUE.toLong()) { "offset must be nonnegative" }
}.toInt()
internal fun JSONObject.pageLimit(): Int = (if (has("limit")) requiredLong("limit") else 50L).also {
    require(it in 1..200) { "limit must be 1..200" }
}.toInt()
internal fun JSONObject.onlyKeys(allowed: Set<String>) {
    require(keys().asSequence().all { it in allowed }) { "Unsupported fields; allowed: ${allowed.joinToString()}" }
}
internal fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
internal fun Cursor.number(column: String): Any = getColumnIndexOrThrow(column).let { if (isNull(it)) JSONObject.NULL else getLong(it) }
internal fun Cursor.nullableString(column: String): String? = getString(getColumnIndexOrThrow(column))
internal fun Cursor.string(column: String): Any = nullableString(column) ?: JSONObject.NULL
