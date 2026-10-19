package ai.opencyvis.execution

import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.program.ProgramRef
import ai.opencyvis.execution.program.ProgramSpec
import ai.opencyvis.execution.program.ProgramStage
import ai.opencyvis.execution.program.ProgramVersion
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecutionHostTest {
    @get:Rule val files = TemporaryFolder()
    private fun owner() = ExecutionOwner("run-1", 3, "data", 0, "execution-1", files.newFolder())
    private fun cards() = JSONArray().put(JSONObject().put("name", "calendar.create").put("mutates", true)
        .put("available", true)).put(JSONObject().put("name", "calendar.get").put("mutates", false).put("available", true))
    private fun host(journal: OperationJournal,
        call: suspend (String, JSONObject, (JSONObject) -> Unit) -> JSONObject) =
        ExecutionHost(ArtifactRepository(files.newFolder()), journal, ::cards, call, null)

    @Test fun cancelledWriteKeepsTheRealEventIdWithoutRepeatingTheMutation() = runTest {
        val directory = files.newFolder()
        val journal = OperationJournal(directory)
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val host = host(journal) { _, _, effect ->
            calls++
            effect(JSONObject().put("event_id", 42))
            entered.complete(Unit)
            CompletableDeferred<JSONObject>().await()
        }
        val work = launch { host.call(owner(), "calendar.create", JSONObject()) }
        entered.await()
        work.cancelAndJoin()
        val uncertain = OperationJournal(directory).recover().single()
        assertEquals("unknown", uncertain.getString("status"))
        assertEquals(42, uncertain.getJSONObject("effect").getInt("event_id"))
        assertEquals(1, calls)
    }

    @Test fun writesShareOneSlotAcrossHostsWhileReadsRemainIndependent() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = host(OperationJournal(files.newFolder())) { _, _, _ ->
            order += "first"
            entered.complete(Unit)
            release.await()
            JSONObject().put("status", "succeeded").put("verified", true)
        }
        val second = host(OperationJournal(files.newFolder())) { name, _, _ ->
            order += name
            JSONObject().put("status", "succeeded").put("verified", true)
        }
        val a = async { first.call(owner(), "calendar.create", JSONObject()) }
        entered.await()
        val b = async { second.call(owner(), "calendar.create", JSONObject()) }
        runCurrent()
        second.call(owner(), "calendar.get", JSONObject())
        assertEquals(listOf("first", "calendar.get"), order)
        release.complete(Unit)
        a.await(); b.await()
        assertEquals(listOf("first", "calendar.get", "calendar.create"), order)
    }

    @Test fun workspaceUsesFixedArtifactVersionsAndCapturesNewOutputs() = runTest {
        val host = host(OperationJournal(files.newFolder())) { _, _, _ -> error("No Android API expected") }
        val first = host.artifacts.importStream("old input".byteInputStream(), "input.txt", "text/plain")
        host.artifacts.importStream("new input".byteInputStream(), "input.txt", "text/plain", artifactId = first.artifactId)
        val owner = owner().copy(allowedArtifacts = setOf("${first.artifactId}:${first.version}"))
        host.call(owner, "artifacts.stage", JSONObject().put("artifact_id", first.artifactId).put("version", 1).put("path", "input.txt"))
        assertEquals("old input", File(owner.workspace, "input.txt").readText())
        File(owner.workspace, "result.txt").writeText("filtered output")
        val captured = host.call(owner, "artifacts.capture_output", JSONObject().put("path", "result.txt")
            .put("name", "result.txt").put("mime_type", "text/plain"))
        val ref = captured.getJSONObject("artifact")
        assertTrue(captured.getBoolean("verified"))
        val read = host.call(owner.copy(allowedArtifacts = null), "artifacts.read_text", JSONObject()
            .put("artifact_id", ref.getString("artifact_id")).put("version", ref.getInt("version"))
            .put("offset", 0).put("limit", 8))
        assertEquals("filtered", read.getString("text"))
        assertEquals(8, read.getInt("next_offset"))
        assertEquals("old input", host.artifacts.resolve(first).readText())
    }

    @Test fun aDeclaredStageNeedsActualVerifiedOperationsAndDataCannotUseGui() = runTest {
        val journal = OperationJournal(files.newFolder())
        val host = host(journal) { _, _, _ -> JSONObject().put("status", "succeeded").put("verified", true) }
        val owner = owner().copy(program = ProgramVersion(ProgramRef("program-1", 1), ProgramSpec("Create event", "data",
            stages = listOf(ProgramStage("create", "calendar is writable", "event read back"))), "main.py"))
        val result = host.call(owner, "calendar.create", JSONObject())
        val checkpoint = host.call(owner, "execution.checkpoint", JSONObject().put("stage", "create")
            .put("operation_ids", JSONArray().put(result.getString("operation_id"))).put("state", JSONObject().put("event_id", 42)))
        assertEquals("create", checkpoint.getString("stage"))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            host.call(owner, "execution.checkpoint", JSONObject().put("stage", "invented").put("operation_ids", JSONArray()).put("state", JSONObject()))
        } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            host.call(owner, "gui.query", JSONObject())
        } }
        assertEquals(1, journal.forExecution("execution-1").size)
    }
}
