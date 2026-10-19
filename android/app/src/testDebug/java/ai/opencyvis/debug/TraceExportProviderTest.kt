package ai.opencyvis.debug

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.memory.MemoryJob
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TraceExportProviderTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private val provider = TraceExportProvider()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() {
        database.close()
        File(context.filesDir, "agent_runs").deleteRecursively()
    }

    @Test fun indexIncludesRunningStepWritesAndLaterMemoryUpdates() {
        val folder = File(context.filesDir, "agent_runs/run-1").apply { mkdirs() }
        val metadata = File(folder, "run.json").apply {
            writeText(JSONObject().put("run_id", "run-1").put("conversation_id", 7)
                .put("instruction", "Record keyboard prices").put("status", "running")
                .put("created_at", 1000L).toString())
            assertTrue(setLastModified(1000L))
        }
        val step = File(folder, "steps/1").apply { mkdirs() }
        val events = File(step, "events.jsonl").apply {
            writeText("{}\n")
            assertTrue(setLastModified(2000L))
        }
        File(folder, "steps/unrelated").mkdirs()
        var run = provider.listRuns(context, database).getJSONArray("runs").getJSONObject(0)
        assertEquals(1, run.getInt("step_count"))
        assertEquals(2000L, run.getLong("updated_at"))
        assertTrue(run.isNull("finished_at"))
        assertTrue(run.isNull("memory_status"))

        events.appendText("{}\n")
        assertTrue(events.setLastModified(3000L))
        run = provider.listRuns(context, database).getJSONArray("runs").getJSONObject(0)
        assertEquals(3000L, run.getLong("updated_at"))
        assertEquals(1000L, metadata.lastModified())

        database.memoryDao().putJob(MemoryJob(id = "run-1:v1", runId = "run-1",
            status = "completed", payloadJson = "{\"private_payload\":true}", updatedAt = 4000L))
        run = provider.listRuns(context, database).getJSONArray("runs").getJSONObject(0)
        assertEquals("completed", run.getString("memory_status"))
        assertEquals(4000L, run.getLong("memory_updated_at"))
        assertEquals(4000L, run.getLong("updated_at"))
        assertFalse(run.toString().contains("private_payload"))
    }

    @Test fun indexOrdersNewestFirstAndSkipsDirectoriesWithoutMetadata() {
        for (time in listOf(1000L, 3000L, 2000L)) {
            val folder = File(context.filesDir, "agent_runs/run-$time").apply { mkdirs() }
            File(folder, "run.json").writeText(JSONObject().put("run_id", "run-$time")
                .put("conversation_id", 7).put("instruction", "Task").put("status", "completed")
                .put("created_at", time).put("finished_at", time + 100).toString())
        }
        File(context.filesDir, "agent_runs/incomplete").mkdirs()
        val runs = provider.listRuns(context, database).getJSONArray("runs")
        assertEquals(3, runs.length())
        assertEquals("run-3000", runs.getJSONObject(0).getString("run_id"))
        assertEquals(1100L, runs.getJSONObject(2).getLong("finished_at"))
    }

    @Test fun writesAreRejected() {
        val uri = Uri.parse("content://ai.opencyvis.standard.trace/runs")
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.update(uri, null, null, null) }
    }
}
