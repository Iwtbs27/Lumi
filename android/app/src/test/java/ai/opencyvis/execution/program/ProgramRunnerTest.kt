package ai.opencyvis.execution.program

import ai.opencyvis.execution.ExecutionHost
import ai.opencyvis.execution.ExecutionOwner
import ai.opencyvis.execution.OperationJournal
import ai.opencyvis.execution.api.ArtifactRef
import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.code.CodeExecutionManager
import ai.opencyvis.execution.code.CodeHostCall
import ai.opencyvis.execution.code.CodeWorkerCompletion
import ai.opencyvis.execution.code.CodeWorkerConnection
import ai.opencyvis.execution.code.CodeWorkerRequest
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun differentParametersProduceIndependentWorkspacesAndMechanicalValidationSamples() = runTest {
        val fixture = fixture()
        val version = fixture.programs.saveCode("def main(ctx, params): return params", spec())
        val input = fixture.artifacts.importStream("fixed input".byteInputStream(), "input.txt", "text/plain")
        val requests = mutableListOf<CodeWorkerRequest>()
        val manager = CodeExecutionManager { Worker { request, call ->
            requests += request
            val ref = request.params.getJSONObject("input")
            call("artifacts.stage", JSONObject(ref.toString()).put("path", "input.txt"))
            assertEquals("fixed input", File(request.workspace, "input.txt").readText())
            File(request.workspace, "output.txt").writeText(request.params.getInt("minimum").toString())
            val output = call("artifacts.capture_output", JSONObject().put("path", "output.txt")
                .put("name", "output.txt").put("mime_type", "text/plain"))
            call("execution.checkpoint", JSONObject().put("stage", "filter")
                .put("operation_ids", JSONArray().put(output.getString("operation_id"))).put("state", JSONObject()))
            request.resultFile.writeText(JSONObject().put("result", output.getJSONObject("artifact")).toString())
            CodeWorkerCompletion("completed")
        } }
        val runner = fixture.runner(manager)
        val first = runner.run(owner(), version.ref, bindings(input, 10), 10_000)
        fixture.artifacts.importStream("new source".byteInputStream(), "input.txt", "text/plain", artifactId = input.artifactId)
        val second = runner.run(owner().copy(step = 4), version.ref, bindings(input, 20), 10_000)
        assertEquals("completed", first.status)
        assertEquals("completed", second.status)
        assertNotEquals(first.executionId, second.executionId)
        assertNotEquals(requests[0].workspace, requests[1].workspace)
        assertNotEquals(fixture.programs.codeFile(version.ref), requests[0].codeFile)
        assertEquals(fixture.programs.codeFile(version.ref).readText(), requests[0].codeFile.readText())
        assertEquals("10", fixture.artifacts.resolve(first.artifacts.single()).readText())
        assertEquals("20", fixture.artifacts.resolve(second.artifacts.single()).readText())
        assertEquals(listOf("filter"), first.checkedStages)
        assertTrue(first.operationIds.isNotEmpty())
        assertTrue(first.operationIds.none { it in second.operationIds })
        val record = runner.getRun(first.executionId)
        assertEquals("completed", record.getString("status"))
        assertEquals(version.ref, ProgramRef.fromJson(record.getJSONObject("program_ref")))
        val bound = JSONObject(File(fixture.directory, record.getString("bindings_ref")).readText())
        assertEquals(10, bound.getJSONObject("params").getInt("minimum"))
        assertEquals(0, bound.getJSONObject("sources").getJSONObject("minimum").getInt("user_source_index"))
        val validations = fixture.programs.validations(version.ref)
        assertEquals(2, validations.size)
        assertTrue(validations.all { it.scope.getBoolean("mechanical_only") && !it.scope.getBoolean("semantic_completion_verified") })
        assertEquals(1, validations.first().inputArtifactRefs.single().getInt("version"))
        assertTrue(fixture.programs.card(version.ref).getBoolean("parameter_variation_observed"))
        val revised = fixture.programs.saveCode("# changed\npass", spec(), version.ref.programId)
        assertTrue(fixture.programs.validations(revised.ref).isEmpty())
    }

    @Test fun cancellationWaitsForWorkerExitAndKeepsTheActualUncertainEffect() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val fixture = fixture { _, _, effect ->
            effect(JSONObject().put("event_id", 42))
            writeStarted.complete(Unit)
            awaitCancellation()
        }
        val version = fixture.programs.saveCode("pass", ProgramSpec("Create event", "data",
            requiredCapabilities = listOf("calendar.create")))
        lateinit var request: CodeWorkerRequest
        val worker = Worker(exit) { value, call ->
            request = value
            call("calendar.create", JSONObject())
            error("Cancelled mutation cannot complete")
        }
        val runner = fixture.runner(CodeExecutionManager { worker })
        val call = async { runner.run(owner(), version.ref, emptyMap(), 10_000) }
        writeStarted.await()
        call.cancel()
        runCurrent()
        try {
            assertTrue(worker.closing.isCompleted)
            assertFalse(call.isCompleted)
            assertFalse(worker.closed)
            exit.complete(Unit)
            call.join()
            assertTrue(worker.closed)
            val record = runner.getRun(request.executionId)
            assertEquals("cancelled", record.getString("status"))
            val operation = fixture.journal.forExecution(request.executionId).single()
            assertEquals("unknown", operation.getString("status"))
            assertEquals(42, operation.getJSONObject("effect").getInt("event_id"))
            assertEquals(operation.getString("operation_id"), record.getJSONArray("operation_ids").getString(0))
            assertTrue(fixture.programs.validations(version.ref).isEmpty())
        } finally { exit.complete(Unit); call.cancel() }
    }

    @Test fun invalidBindingsDisabledProgramsUnavailableCapabilitiesAndGuiUpgradeNeverStartWorker() = runTest {
        val fixture = fixture(available = false)
        var starts = 0
        val runner = fixture.runner(CodeExecutionManager { starts++; error("Preflight must reject before creating a worker") })
        val typed = fixture.programs.saveCode("pass", ProgramSpec("typed", "data", listOf(ProgramParameter("count", "integer"))))
        reject { runner.run(owner(), typed.ref, mapOf("count" to ProgramBinding("wrong", JSONObject())), 10_000) }
        fixture.programs.setEnabled(typed.ref, false, "disabled test implementation")
        reject { runner.run(owner(), typed.ref, mapOf("count" to ProgramBinding(1, JSONObject())), 10_000) }
        val unavailable = fixture.programs.saveCode("pass", ProgramSpec("calendar", "data", requiredCapabilities = listOf("calendar.create")))
        reject { runner.run(owner(), unavailable.ref, emptyMap(), 10_000) }
        val gui = fixture.programs.saveCode("pass", ProgramSpec("GUI", "gui"))
        reject { runner.run(owner(), gui.ref, emptyMap(), 10_000) }
        val disguised = fixture.programs.saveCode("pass", ProgramSpec("data GUI", "data", requiredCapabilities = listOf("gui.query")))
        reject { runner.run(owner(), disguised.ref, emptyMap(), 10_000) }
        val multiple = fixture.programs.saveCode("pass", ProgramSpec("multiple nodes", "data", stages = listOf(
            ProgramStage("stage", "ready", "done", procedureNodeIds = listOf("one", "two")))))
        reject { runner.run(owner(), multiple.ref, emptyMap(), 10_000) }
        assertEquals(0, starts)
    }

    @Test fun resumeUsesExactArchivedBoundaryAndUnknownEffectsReturnForReviewWithoutReplay() = runTest {
        val fixture = fixture()
        val program = fixture.programs.saveCode("pass", ProgramSpec("Two stage program", "data",
            parameters = listOf(ProgramParameter("minimum", "integer")),
            requiredCapabilities = listOf("calendar.create", "artifacts.capture_output", "artifacts.stage"), stages = listOf(
                ProgramStage("created", "calendar available", "event read back", procedureNodeIds = listOf("create")),
                ProgramStage("report", "event exists", "report shown", procedureNodeIds = listOf("report")))))
        var starts = 0
        var resumeState: JSONObject? = null
        val manager = CodeExecutionManager { Worker { request, call ->
            starts++
            if (starts == 1) {
                val operation = call("calendar.create", JSONObject())
                File(request.workspace, "partial-report.txt").writeText("event 42 was created")
                val output = call("artifacts.capture_output", JSONObject().put("path", "partial-report.txt")
                    .put("name", "partial-report.txt").put("mime_type", "text/plain"))
                call("execution.checkpoint", JSONObject().put("stage", "created")
                    .put("operation_ids", JSONArray().put(operation.getString("operation_id")).put(output.getString("operation_id")))
                    .put("state", JSONObject().put("event_id", 42).put("artifact", output.getJSONObject("artifact"))))
                CodeWorkerCompletion("failed", "Stopped after the completed stage")
            } else {
                resumeState = call("execution.state", JSONObject())
                val prior = resumeState!!.getJSONArray("previous_checkpoints").getJSONObject(0)
                    .getJSONObject("state").getJSONObject("artifact")
                call("artifacts.stage", JSONObject().put("artifact_id", prior.getString("artifact_id"))
                    .put("version", prior.getInt("version")).put("path", "continued-report.txt"))
                assertEquals("event 42 was created", File(request.workspace, "continued-report.txt").readText())
                request.resultFile.writeText("{\"result\":\"continued without creating another event\"}")
                CodeWorkerCompletion("completed")
            }
        } }
        val runner = fixture.runner(manager)
        val params = mapOf("minimum" to ProgramBinding(10, JSONObject().put("user_source_index", 0)))
        val original = runner.run(owner(), program.ref, params, 10_000)
        assertEquals("failed", original.status)
        val resumed = runner.run(owner().copy(runId = "run-2", step = 1), program.ref, params, 10_000,
            ProgramResume(original.executionId, "created"))
        assertEquals("completed", resumed.status)
        assertEquals("created", resumeState!!.getString("resume_stage"))
        assertEquals(original.executionId, resumeState!!.getString("previous_execution_id"))
        assertEquals(1, resumeState!!.getJSONArray("previous_checkpoints").length())
        assertEquals(0, resumeState!!.getJSONArray("checkpoints").length())
        assertEquals(1, resumed.operationIds.size)
        assertTrue(resumed.operationIds.none { it in original.operationIds })
        assertEquals("artifacts.stage", fixture.journal.get(resumed.operationIds.single()).getString("capability"))
        assertTrue(resumed.checkedStages.isEmpty())
        assertTrue(fixture.programs.validations(program.ref).single().coveredNodes.isEmpty())
        reject { runner.run(owner(), program.ref, params + ("minimum" to ProgramBinding(20, JSONObject())), 10_000,
            ProgramResume(original.executionId, "created")) }
        val revised = fixture.programs.saveCode("# changed\npass", program.spec, program.ref.programId)
        reject { runner.run(owner(), revised.ref, params, 10_000, ProgramResume(original.executionId, "created")) }
        val uncertain = fixture.journal.begin("run-1", 3, original.executionId, "calendar.create", JSONObject())
        fixture.journal.effect(uncertain, JSONObject().put("event_id", 99))
        val held = runner.run(owner(), program.ref, params, 10_000, ProgramResume(original.executionId, "created"))
        assertEquals("needs_review", held.status)
        assertNull(held.codeResult)
        assertEquals(2, starts)
        assertTrue(held.error.orEmpty().contains(uncertain))
        assertEquals(99, fixture.journal.get(uncertain).getJSONObject("effect").getInt("event_id"))
    }

    private fun owner() = ExecutionOwner("run-1", 3, "data", 0)
    private fun spec() = ProgramSpec("Filter", "data", listOf(ProgramParameter("minimum", "integer"),
        ProgramParameter("input", "artifact")), requiredCapabilities = listOf("artifacts.stage", "artifacts.capture_output"),
        stages = listOf(ProgramStage("filter", "input staged", "output archived", procedureNodeIds = listOf("filter"))))

    private fun bindings(input: ArtifactRef, minimum: Int) = mapOf(
        "minimum" to ProgramBinding(minimum, JSONObject().put("user_source_index", 0)),
        "input" to ProgramBinding(JSONObject().put("artifact_id", input.artifactId).put("version", input.version),
            JSONObject().put("run_id", "run-1").put("step", 2)))

    private fun fixture(available: Boolean = true,
        apiCall: suspend (String, JSONObject, (JSONObject) -> Unit) -> JSONObject = { _, _, effect ->
            effect(JSONObject().put("event_id", 42)); JSONObject().put("status", "succeeded").put("verified", true).put("event_id", 42)
        }): Fixture {
        val directory = temporary.newFolder()
        val artifacts = ArtifactRepository(directory)
        val journal = OperationJournal(File(directory, "journal"))
        val host = ExecutionHost(artifacts, journal, { JSONArray().put(JSONObject().put("name", "calendar.create")
            .put("mutates", true).put("available", available)) }, apiCall, null)
        return Fixture(directory, ProgramRepository(File(directory, "programs")), artifacts, journal, host)
    }

    private data class Fixture(val directory: File, val programs: ProgramRepository,
        val artifacts: ArtifactRepository, val journal: OperationJournal, val host: ExecutionHost) {
        fun runner(code: CodeExecutionManager) = ProgramRunner(1, directory, programs, host, code)
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected a visible preflight rejection") } catch (_: IllegalArgumentException) { }
    }

    private class Worker(private val exit: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val action: suspend (CodeWorkerRequest, CodeHostCall) -> CodeWorkerCompletion) : CodeWorkerConnection {
        override val workerPid = 100
        val closing = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun run(request: CodeWorkerRequest, hostCall: CodeHostCall) = action(request, hostCall)
        override suspend fun closeAndAwaitExit() { closing.complete(Unit); exit.await(); closed = true }
    }
}
