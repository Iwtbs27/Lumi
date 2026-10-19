package ai.opencyvis.execution.api

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.CalendarContract
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only fixture-owned documents and a newly created local test calendar are modified. */
@RunWith(AndroidJUnit4::class)
class AndroidApiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "m4-api-e2e").apply { mkdirs() }

    @Test fun grantedTreeImportsExportsAndRenamesWithReadback(): Unit = runBlocking {
        val provider = ComponentName(instrumentation.context.packageName, FixtureDocumentsProvider::class.java.name)
        val authority = context.packageManager.getProviderInfo(provider, 0).authority
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val modes = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val repository = ArtifactRepository(File(context.filesDir, "m4-api-fixtures/${UUID.randomUUID()}"))
        startGrantActivity(authority, cleanup = false)
        try {
            withTimeout(10_000) {
                while (context.checkUriPermission(tree, Process.myPid(), Process.myUid(), modes) != PackageManager.PERMISSION_GRANTED) delay(100)
            }
            AndroidApiCapabilities.persistTreeGrant(context, tree, modes)
            // Recreate the adapter after grant retention; no picker or injected grant list is used.
            val api = AndroidApiCapabilities(context, repository)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
            val roots = api.call("files.roots", JSONObject()).getJSONArray("roots")
            assertTrue((0 until roots.length()).any { roots.getJSONObject(it).getString("tree_uri") == tree.toString() })
            val files = api.call("files.list", JSONObject().put("tree_uri", tree.toString())).getJSONArray("documents")
            val source = (0 until files.length()).map { files.getJSONObject(it) }.single { it.getString("name") == "input.bin" }
            val imported = api.call("files.import", JSONObject().put("tree_uri", tree.toString()).put("document_uri", source.getString("uri")))
                .getJSONObject("artifact")
            val ref = repository.get(imported.getString("artifact_id"), imported.getInt("version"))
            assertArrayEquals(FixtureDocumentsProvider.BYTES, repository.resolve(ref).readBytes())
            val effects = mutableListOf<JSONObject>()
            val exported = api.call("files.export", JSONObject().put("tree_uri", tree.toString()).put("name", "copied.bin")
                .put("artifact_id", ref.artifactId).put("version", ref.version)) { effects += it }
            assertTrue(exported.getBoolean("verified"))
            val exportedUri = exported.getJSONObject("document").getString("uri")
            assertEquals(exportedUri, effects.single().getString("uri"))
            assertArrayEquals(FixtureDocumentsProvider.BYTES, context.contentResolver.openInputStream(Uri.parse(exportedUri))!!.use { it.readBytes() })
            val renamed = api.call("files.rename", JSONObject().put("tree_uri", tree.toString()).put("document_uri", exportedUri).put("name", "renamed.bin"))
            assertTrue(renamed.getBoolean("verified"))
            val finalDocument = renamed.getJSONObject("document")
            assertEquals("renamed.bin", finalDocument.getString("name"))
            assertNotEquals("This provider deliberately changes its document ID when renamed", exportedUri, finalDocument.getString("uri"))
            assertArrayEquals(FixtureDocumentsProvider.BYTES, context.contentResolver.openInputStream(Uri.parse(finalDocument.getString("uri")))!!.use { it.readBytes() })
            File(output, "files-result.json").writeText(JSONObject().put("import", imported).put("export", exported)
                .put("rename", renamed).put("result", "passed").toString(2))
        } finally {
            if (context.contentResolver.persistedUriPermissions.any { it.uri == tree }) {
                context.contentResolver.releasePersistableUriPermission(tree, modes)
            }
            startGrantActivity(authority, cleanup = true)
            withTimeout(10_000) {
                while (context.checkUriPermission(tree, Process.myPid(), Process.myUid(), modes) == PackageManager.PERMISSION_GRANTED) delay(100)
            }
        }
    }

    @Test fun localCalendarCreatesUpdatesAndReadsActualEvent(): Unit = runBlocking {
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val account = "m4-fixture-${UUID.randomUUID()}"
        val syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        var calendarUri: Uri? = null
        try {
            calendarUri = requireNotNull(context.contentResolver.insert(syncUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, account)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "M4 fixture calendar")
                put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Asia/Shanghai")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            }))
            val calendarId = ContentUris.parseId(calendarUri)
            val api = AndroidApiCapabilities(context, ArtifactRepository(File(context.filesDir, "m4-api-fixtures/${UUID.randomUUID()}")))
            val calendars = api.call("calendar.list", JSONObject()).getJSONArray("calendars")
            assertTrue((0 until calendars.length()).any { calendars.getJSONObject(it).getLong("calendar_id") == calendarId })
            val start = 1_800_000_000_000L
            val created = api.call("calendar.create", JSONObject().put("calendar_id", calendarId).put("title", "M4 fixture lesson")
                .put("start_ms", start).put("end_ms", start + 3_600_000).put("time_zone", "Asia/Shanghai").put("location", "Room 1"))
            assertTrue(created.getBoolean("verified"))
            val eventId = created.getJSONObject("event").getLong("event_id")
            val updated = api.call("calendar.update", JSONObject().put("event_id", eventId)
                .put("title", "M4 revised lesson").put("start_ms", start + 1_800_000).put("end_ms", start + 5_400_000)
                .put("location", "Room 2"))
            assertTrue(updated.getBoolean("verified"))
            val actual = api.call("calendar.get", JSONObject().put("event_id", eventId)).getJSONObject("event")
            assertEquals("M4 revised lesson", actual.getString("title"))
            assertEquals("Room 2", actual.getString("location"))
            assertEquals(start + 1_800_000, actual.getLong("start_ms"))
            assertEquals(calendarId, actual.getLong("calendar_id"))
            val range = api.call("calendar.query", JSONObject().put("calendar_id", calendarId)
                .put("start_ms", start).put("end_ms", start + 7_200_000)).getJSONArray("events")
            assertEquals(1, range.length())
            assertEquals(eventId, range.getJSONObject(0).getLong("event_id"))
            File(output, "calendar-result.json").writeText(JSONObject().put("calendar_id", calendarId)
                .put("created", created).put("updated", updated).put("actual", actual).put("result", "passed").toString(2))
        } finally {
            try {
                calendarUri?.let { uri ->
                    val deleteUri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                    assertEquals(1, context.contentResolver.delete(deleteUri, null, null))
                }
            } finally { automation.dropShellPermissionIdentity() }
        }
    }

    private fun startGrantActivity(authority: String, cleanup: Boolean) {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName,
            FixtureDocumentGrantActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("target_package", context.packageName).putExtra("authority", authority).putExtra("cleanup", cleanup))
    }
}
