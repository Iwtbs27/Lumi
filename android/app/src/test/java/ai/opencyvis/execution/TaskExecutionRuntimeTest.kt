package ai.opencyvis.execution

import android.app.Application
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.team.MaterialRef
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskExecutionRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun directCapabilitiesAndFileDelegationUseTheSameRealTaskArchive(): Unit = runBlocking {
        val store = TaskRunStore(context, instruction = "Read a document", taskId = 71)
        val runtime = TaskExecutionRuntime(context, store, null, null)
        val source = runtime.artifacts.importStream("Exact task document".byteInputStream(), "notes.txt", "text/plain")
        val reference = "artifact:${source.artifactId}:${source.version}"
        val material = runtime.material(reference, 3)
        assertEquals(source.version, material.version)
        assertEquals(store.runId, material.sourceRunId)
        val page = runtime.readMaterial(material, 0, 5)
        assertEquals("Exact", page.text)
        assertEquals(5, page.nextOffset)
        val result = runtime.tool(mapOf("action_type" to "call_capability", "capability_name" to "artifacts.read_text",
            "arguments" to mapOf("artifact_id" to source.artifactId, "version" to source.version, "offset" to 6, "limit" to 4)), 4, 0, null)
        assertEquals("task", result.getString("text"))
        assertEquals(reference, result.getJSONObject("artifact").getString("artifact_ref"))
        assertNotNull(result.getString("operation_id"))
        val description = runtime.tool(mapOf("action_type" to "describe_capabilities"), 5, 0, null)
        val capabilities = description.getJSONArray("capabilities")
        assertTrue((0 until capabilities.length()).any { capabilities.getJSONObject(it).getString("name") == "calendar.create" })
        store.finish("success")
    }

    @Test fun binaryMaterialReturnsItsVersionedMetadataAndResumedRunKeepsThePayload(): Unit = runBlocking {
        val first = TaskRunStore(context, instruction = "Read a workbook", taskId = 72)
        val runtime = TaskExecutionRuntime(context, first, null, null)
        val artifact = runtime.artifacts.importStream(byteArrayOf(0, 1, 2, 3).inputStream(), "table.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        val ref = runtime.material("artifact:${artifact.artifactId}:1", 2)
        val metadata = runtime.readMaterial(ref, 0, 4800)
        assertTrue(metadata.text.contains("table.xlsx"))
        assertTrue(metadata.text.contains("artifacts.stage"))
        first.finish("interrupted")
        val second = TaskRunStore(context, instruction = "Read a workbook", taskId = 72)
        val restored = TaskExecutionRuntime(context, second, null, null)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), restored.artifacts.resolve(artifact).readBytes())
        assertEquals(metadata.text, restored.readMaterial(ref, 0, 4800).text)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            restored.readMaterial(MaterialRef("artifact", ref.reference, 2, ref.sourceRunId), 0, 4800)
        } }
        second.finish("success")
    }
}
