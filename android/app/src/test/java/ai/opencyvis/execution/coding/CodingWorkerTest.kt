package ai.opencyvis.execution.coding

import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.execution.code.CodeExecutionResult
import ai.opencyvis.execution.program.ProgramRef
import ai.opencyvis.execution.program.ProgramRepository
import ai.opencyvis.execution.program.ProgramVersion
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.team.ChildWorkerSession
import ai.opencyvis.team.CodingTaskSpec
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.MaterialPage
import ai.opencyvis.team.MaterialRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CodingWorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val material = MaterialRef("artifact", "source-table", 2, "run", 3)
    private val condition = "输出数量为 7"
    private val assignment = CodingTaskSpec("data", mapOf("quantity" to 7), listOf(condition), 3, 60_000)
    private fun request(spec: CodingTaskSpec = assignment) = DelegationRequest("coding-1", 7, "run", "1:0",
        "整理数量", listOf("保留原始输入"), listOf(material), "coding", spec)

    @Test fun writesRunsReadsErrorRepairsAndReadsBackTheCurrentVersionBeforeSubmitting() = runTest {
        val programs = ProgramRepository(temporary.newFolder())
        val environment = Environment().apply {
            onExecute = { program, _, _ ->
                val number = runs.size
                outputs["stderr-$number"] = if (number == 1) "ValueError: bad quantity at line 2" else ""
                outputs["result-$number"] = "{\"result\":{\"quantity\":7}}"
                outputs["artifact-$number"] = "quantity: 7"
                Environment.execution(number, if (program.ref.revision == 1) "failed" else "completed")
            }
        }
        val script = Script(listOf(action("read_material", "kind" to "artifact", "reference" to material.reference,
            "version" to 2, "offset" to 0, "limit" to 4), action("inspect_sdk"),
            write("def main(ctx, params):\n    raise ValueError('bad quantity')\n"), action("run_code"),
            read("stderr-1"), write("def main(ctx, params):\n    return {'quantity': params['quantity']}\n"),
            action("run_code"), read("artifact-2"), submit("completed", "artifact-2")))
        val result = CodingWorker(programs) { environment }.execute(request(), script.session())
        assertEquals("completed", result.status)
        assertEquals(2, result.outputs["revision"])
        assertEquals("exec-2", result.outputs["execution_id"])
        assertEquals("child_review", result.outputs["verification_scope"])
        assertEquals(listOf("operation-2"), result.outputs["operation_ids"])
        assertEquals(2, environment.runs.size)
        assertTrue(environment.runs.all { it.params.getInt("quantity") == 7 && it.timeout == 60_000L })
        val ref = ProgramRef(result.outputs["program_id"] as String, 2)
        assertTrue(programs.codeFile(ref.copy(revision = 1)).readText().contains("raise ValueError"))
        assertTrue(programs.codeFile(ref).readText().contains("params['quantity']"))
        assertEquals(listOf(condition), programs.get(ref).spec.completionConditions)
        assertEquals("coding-1", programs.get(ref).spec.sources.single().getString("child_id"))
        assertTrue(programs.validations(ref).isEmpty())
        assertEquals(listOf(material), script.materialReads)
        assertEquals(listOf("stderr-1", "artifact-2"), environment.reads)
        assertTrue(script.prompts.any { it.contains("ValueError: bad quantity") })
        assertTrue(script.prompts.any { it.contains("ctx.call") })
        assertEquals(9, script.schemas.size)
        assertEquals(1, script.schemas.map { it.tool.toString() }.distinct().size)
        assertTrue(script.schemas.all { it.requireCompleteJson && it.maxAttempts == 1 && it.maxOutputTokens == 512 &&
            it.purpose == LlmCallPurpose.CHILD_ANALYSIS && it.tool.getJSONObject("function").getString("name") == "coding_action" })
        assertTrue(script.events.any { it.first == "program_written" })
        assertEquals(2, script.events.count { it.first == "code_execution" })
    }

    @Test fun guiCandidateNeverRunsAndReturnsAnExplicitParentExecutionRequest() = runTest {
        val programs = ProgramRepository(temporary.newFolder())
        val environment = Environment()
        val script = Script(listOf(write("def main(ctx, params): return ctx.call('gui.query', {})", mode = "gui"),
            action("run_code"), submit("needs_execution")))
        val result = CodingWorker(programs) { environment }.execute(request(assignment.copy(mode = "gui")), script.session())
        assertEquals("needs_execution", result.status)
        assertTrue(environment.runs.isEmpty())
        assertTrue(script.prompts.last().contains("GUI programs must be executed by the parent"))
        val run = JSONObject(result.outputs["run_request"] as Map<*, *>)
        assertEquals("run_program", run.getString("action_type"))
        assertEquals(result.outputs["program_id"], run.getString("program_id"))
        assertEquals(1, run.getInt("revision"))
        assertEquals(7, run.getJSONObject("params").getInt("quantity"))
        assertEquals(assignment.timeoutMs, run.getLong("execution_timeout_ms"))
        assertTrue(result.collectionRequests.isNotEmpty())
        assertFalse(result.outputs.containsKey("execution_id"))
    }

    @Test fun writingANewRevisionCannotReuseAnEarlierSuccessfulExecution() = runTest {
        val environment = Environment()
        val script = Script(listOf(write("def main(ctx, params): return 7"), action("run_code"), read("result-1"),
            write("def main(ctx, params): return 8"), submit("completed", "result-1"), submit("needs_evidence")))
        val result = CodingWorker(ProgramRepository(temporary.newFolder())) { environment }.execute(request(), script.session())
        assertEquals("needs_evidence", result.status)
        assertEquals(2, result.outputs["revision"])
        assertFalse(result.outputs.containsKey("execution_id"))
        assertTrue(script.prompts.last().contains("Current code revision has no successful execution"))
        assertEquals(1, environment.runs.size)
    }

    @Test fun executionLimitAlsoCountsHostFailuresAndCannotBeResetByRewritingCode() = runTest {
        val environment = Environment().apply { onExecute = { _, _, _ -> throw IllegalStateException("worker failed to start") } }
        val script = Script(listOf(write("def main(ctx, params): return 7"), action("run_code"),
            write("def main(ctx, params): return 8"), action("run_code"), submit("needs_evidence")))
        val result = CodingWorker(ProgramRepository(temporary.newFolder())) { environment }
            .execute(request(assignment.copy(maxExecutions = 1)), script.session())
        assertEquals("needs_evidence", result.status)
        assertEquals(1, environment.runs.size)
        assertTrue(script.prompts.last().contains("Code execution limit reached"))
        assertTrue(script.events.any { it.first == "tool_error" && it.second.toString().contains("worker failed to start") })
    }

    @Test fun rerunningWithTheSameArtifactReferenceStillRequiresANewReadback() = runTest {
        val environment = Environment().apply {
            onExecute = { _, _, _ ->
                outputs["shared-artifact"] = "quantity: 7"
                Environment.execution(runs.size, "completed").copy(artifactRefs = listOf("shared-artifact"))
            }
        }
        val script = Script(listOf(write("def main(ctx, params): return 7"), action("run_code"), read("shared-artifact"),
            write("def main(ctx, params): return params['quantity']"), action("run_code"), submit("completed", "shared-artifact"),
            read("shared-artifact"), submit("completed", "shared-artifact")))
        val result = CodingWorker(ProgramRepository(temporary.newFolder())) { environment }.execute(request(), script.session())
        assertEquals("completed", result.status)
        assertEquals(listOf("shared-artifact", "shared-artifact"), environment.reads)
        assertEquals(8, script.schemas.size)
        assertEquals(1, script.events.count { it.first == "tool_error" })
    }

    @Test fun stdoutAndUninspectedResultsCannotStandInForCompletionReadback() = runTest {
        val environment = Environment()
        val script = Script(listOf(write("def main(ctx, params): return 7"), action("run_code"), read("stdout-1"),
            submit("completed", "result-1"), submit("completed", "stdout-1"), submit("needs_evidence")))
        val result = CodingWorker(ProgramRepository(temporary.newFolder())) { environment }.execute(request(), script.session())
        assertEquals("needs_evidence", result.status)
        assertEquals(2, script.events.count { it.first == "tool_error" })
        assertTrue(script.prompts.last().contains("Completion checks must cite read-back results from the current execution"))
    }

    @Test fun cancellationPropagatesAndWaitsForTheActualExecutionCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val environment = Environment().apply {
            onExecute = { _, _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { exit.await(); exited.complete(Unit) } }
            }
        }
        val script = Script(listOf(write("def main(ctx, params): return 7"), action("run_code"), submit("needs_evidence")))
        val job = async { CodingWorker(ProgramRepository(temporary.newFolder())) { environment }.execute(request(), script.session()) }
        runCurrent()
        assertTrue(entered.isCompleted)
        try {
            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted)
            assertFalse(exited.isCompleted)
            exit.complete(Unit)
            runCurrent()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(exited.isCompleted)
            assertEquals(2, script.schemas.size)
        } finally { exit.complete(Unit); job.cancel() }
    }

    @Test fun aChildCannotTurnModelStepExhaustionIntoSuccessfulCompletion() = runTest {
        val script = Script(listOf(action("inspect_sdk"), action("inspect_sdk")))
        try {
            CodingWorker(ProgramRepository(temporary.newFolder())) { Environment() }.execute(request(), script.session())
            fail("Unfinished code must not be reported as successful")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Coding child reached the model step limit"))
            assertEquals(2, script.schemas.size)
        }
    }

    private fun action(type: String, vararg values: Pair<String, Any?>) = mapOf("action_type" to type) + values
    private fun write(code: String, mode: String = "data") = action("write_code", "code" to code,
        "spec" to mapOf("name" to "数量处理", "mode" to mode,
            "parameters" to listOf(mapOf("name" to "quantity", "type" to "integer", "required" to true)),
            "completion_conditions" to listOf("model supplied condition must not replace the task"),
            "sources" to listOf(mapOf("child_id" to "invented"))))
    private fun read(ref: String) = action("read_output", "reference" to ref, "offset" to 0, "limit" to 4800)
    private fun submit(status: String, evidence: String? = null) = action("submit_result", "status" to status,
        "summary" to "数量处理结果", "gaps" to emptyList<String>(), "collection_requests" to listOf("由父任务继续核对"),
        "checks" to if (evidence == null) emptyList<Any>() else listOf(mapOf("name" to condition,
            "passed" to true, "evidence_refs" to listOf(evidence))))

    private inner class Script(private val actions: List<Map<String, Any?>>) {
        val schemas = mutableListOf<LlmRequestSchema>()
        val prompts = mutableListOf<String>()
        val materialReads = mutableListOf<MaterialRef>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        fun session() = ChildWorkerSession(actions.size, ContextBudgetConfig(128_000, 512),
            chat = { step, messages, schema ->
                assertEquals(schemas.size + 1, step)
                schemas += schema
                prompts += messages.joinToString("\n") { it["content"].toString() }
                actions[step - 1]
            }, read = { ref, offset, limit ->
                assertEquals(material, ref)
                materialReads += ref
                val page = ContextTextPage.from(ref.reference, "quantity: 7", offset, limit)
                MaterialPage(ref, page.text, offset, page.nextOffset, page.totalChars)
            }, event = { type, data -> events += type to data })
    }

    private data class Run(val program: ProgramVersion, val params: JSONObject, val timeout: Long)
    private class Environment : CodingEnvironment {
        val runs = mutableListOf<Run>()
        val reads = mutableListOf<String>()
        val outputs = mutableMapOf<String, String>()
        var onExecute: suspend (ProgramVersion, JSONObject, Long) -> CodingExecution = { _, _, _ ->
            val number = runs.size
            outputs["result-$number"] = "{\"result\":{\"quantity\":7}}"
            outputs["stdout-$number"] = "quantity is 7"
            outputs["stderr-$number"] = ""
            execution(number, "completed")
        }
        override suspend fun describeCapabilities() = JSONObject().put("capabilities", listOf("files.import"))
        override suspend fun execute(program: ProgramVersion, params: JSONObject, timeoutMs: Long): CodingExecution {
            runs += Run(program, JSONObject(params.toString()), timeoutMs)
            return onExecute(program, params, timeoutMs)
        }
        override suspend fun readOutput(reference: String, offset: Int, limit: Int): ContextTextPage {
            reads += reference
            return ContextTextPage.from(reference, outputs.getValue(reference), offset, limit)
        }
        companion object {
            fun execution(number: Int, status: String) = CodingExecution(CodeExecutionResult("exec-$number", status,
                null, "result-$number", "stdout-$number", "stderr-$number", if (status == "failed") "bad quantity" else null),
                listOf("artifact-$number"), listOf("operation-$number"))
        }
    }
}
