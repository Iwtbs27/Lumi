package ai.opencyvis.execution.api

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarApiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: RecordingCalendarProvider
    private lateinit var api: AndroidApiCapabilities

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider = RecordingCalendarProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        api = AndroidApiCapabilities(context, ArtifactRepository(temporary.newFolder()))
    }

    @Test fun createRecordsActualIdBeforeReadingTheInsertedEvent(): Unit = runBlocking {
        val effects = mutableListOf<JSONObject>()
        val result = api.call("calendar.create", createArgs()) {
            provider.trace += "effect"
            effects += it
        }
        val event = result.getJSONObject("event")
        assertEquals("succeeded", result.getString("status"))
        assertTrue(result.getBoolean("verified"))
        assertEquals(51L, event.getLong("event_id"))
        assertEquals(51L, effects.single().getLong("event_id"))
        assertEquals("Class", event.getString("title"))
        assertEquals(START, event.getLong("start_ms"))
        assertEquals("Asia/Shanghai", event.getString("time_zone"))
        val insert = provider.trace.indexOf("insert")
        assertEquals(listOf("insert", "effect", "get:51"), provider.trace.drop(insert))
        assertEquals(0, provider.events.getValue(51L).getAsInteger(CalendarContract.Events.ALL_DAY).toInt())
    }

    @Test fun partialUpdatePreservesOtherFieldsAndUsesActualIdReadback(): Unit = runBlocking {
        provider.events[51] = provider.singleEvent()
        val result = api.call("calendar.update", JSONObject().put("event_id", 51).put("title", "New title")
            .put("location", "Room 2"))
        val event = result.getJSONObject("event")
        assertTrue(result.getBoolean("verified"))
        assertEquals("New title", event.getString("title"))
        assertEquals("Room 2", event.getString("location"))
        assertEquals(START, event.getLong("start_ms"))
        assertEquals(START + 3_600_000, event.getLong("end_ms"))
        assertEquals("get:51", provider.trace.last())
        assertEquals(setOf(CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION),
            provider.updates.single().keySet())
    }

    @Test fun queryUsesInstancesRangeAndAnExplicitCalendar(): Unit = runBlocking {
        provider.events[51] = provider.singleEvent()
        val result = api.call("calendar.query", JSONObject().put("calendar_id", 7)
            .put("start_ms", START - 1000).put("end_ms", START + 7_200_000))
        assertEquals(51L, result.getJSONArray("events").getJSONObject(0).getLong("event_id"))
        val query = requireNotNull(provider.instanceQuery)
        assertEquals((START - 1000).toString(), query.first.pathSegments[2])
        assertEquals((START + 7_200_000).toString(), query.first.pathSegments[3])
        assertTrue(query.second.orEmpty().contains(CalendarContract.Instances.CALENDAR_ID))
        assertTrue(query.third.orEmpty().contains("7"))
    }

    @Test fun readOnlyCalendarAndRecurringEventCannotBeWritten(): Unit = runBlocking {
        var rejected = false
        try { api.call("calendar.create", createArgs().put("calendar_id", 8)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Read-only calendar must be rejected", rejected)
        assertFalse(provider.trace.contains("insert"))
        provider.events[51] = provider.singleEvent().apply { put(CalendarContract.Events.RRULE, "FREQ=DAILY") }
        rejected = false
        try { api.call("calendar.update", JSONObject().put("event_id", 51).put("title", "Unsupported series edit")) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Recurring events are outside the single-event update contract", rejected)
        assertTrue(provider.updates.isEmpty())
    }

    @Test fun readbackFailureRetainsTheCreatedEventIdForRecovery(): Unit = runBlocking {
        provider.failEventReadback = true
        val effects = mutableListOf<JSONObject>()
        val result = api.call("calendar.create", createArgs()) { effects += it }
        assertEquals("unknown", result.getString("status"))
        assertFalse(result.getBoolean("verified"))
        assertEquals(51L, result.getLong("event_id"))
        assertEquals(51L, effects.single().getLong("event_id"))
        assertEquals(1, provider.events.size)
        assertEquals(1, provider.trace.count { it == "insert" })
    }

    private fun createArgs() = JSONObject().put("calendar_id", 7).put("title", "Class")
        .put("start_ms", START).put("end_ms", START + 3_600_000).put("time_zone", "Asia/Shanghai")
        .put("location", "Room 1")

    class RecordingCalendarProvider : ContentProvider() {
        val events = linkedMapOf<Long, ContentValues>()
        val updates = mutableListOf<ContentValues>()
        val trace = mutableListOf<String>()
        var failEventReadback = false
        var instanceQuery: Triple<Uri, String?, Array<out String>?>? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.item/event"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection).map { it }.toTypedArray()
            val cursor = MatrixCursor(columns)
            val rows: List<ContentValues> = when (uri.pathSegments.first()) {
                "calendars" -> listOf(calendar(7, true), calendar(8, false)).filter { row ->
                    val id = uri.lastPathSegment?.toLongOrNull() ?: selectionArgs?.firstOrNull()?.toLongOrNull()
                    id == null || row.getAsLong(CalendarContract.Calendars._ID) == id
                }
                "instances" -> {
                    instanceQuery = Triple(uri, selection, selectionArgs)
                    events.values.map { ContentValues(it).apply {
                        put(CalendarContract.Instances.EVENT_ID, getAsLong(CalendarContract.Events._ID))
                        put(CalendarContract.Instances.BEGIN, getAsLong(CalendarContract.Events.DTSTART))
                        put(CalendarContract.Instances.END, getAsLong(CalendarContract.Events.DTEND))
                    } }
                }
                else -> {
                    val id = ContentUris.parseId(uri)
                    trace += "get:$id"
                    if (failEventReadback) error("Fixture readback unavailable")
                    listOfNotNull(events[id])
                }
            }
            rows.forEach { row -> cursor.addRow(columns.map { row[it] }) }
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            trace += "insert"
            val id = 51L
            events[id] = ContentValues(requireNotNull(values)).apply { put(CalendarContract.Events._ID, id) }
            return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val value = requireNotNull(values)
            updates += ContentValues(value)
            events.getValue(ContentUris.parseId(uri)).putAll(value)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Not a tested capability")
        fun singleEvent() = ContentValues().apply {
            put(CalendarContract.Events._ID, 51L)
            put(CalendarContract.Events.CALENDAR_ID, 7L)
            put(CalendarContract.Events.TITLE, "Class")
            put(CalendarContract.Events.DTSTART, START)
            put(CalendarContract.Events.DTEND, START + 3_600_000)
            put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Shanghai")
            put(CalendarContract.Events.EVENT_LOCATION, "Room 1")
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.DELETED, 0)
        }
        private fun calendar(id: Long, writable: Boolean) = ContentValues().apply {
            put(CalendarContract.Calendars._ID, id)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Fixture $id")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                if (writable) CalendarContract.Calendars.CAL_ACCESS_OWNER else CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
    }

    companion object { private const val START = 1_800_000_000_000L }
}
