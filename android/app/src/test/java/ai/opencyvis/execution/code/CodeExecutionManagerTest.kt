package ai.opencyvis.execution.code

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CodeExecutionManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val noHost: CodeHostCall = { _, _ -> error("Unexpected host operation") }
    private fun script() = temporary.newFile().apply { writeText("def main(ctx, params): return params") }

    @Test fun successfulExecutionClosesWorkerAndKeepsPagedOutput() = runTest {
        val text = "first\n第二行\nthird\n"
        val worker = Worker { request, host ->
            request.stdoutFile.writeText(text)
            request.stderrFile.writeText("diagnostic")
            request.resultFile.writeText(JSONObject().put("result", host("calculate", request.params)).toString())
            CodeWorkerCompletion(CodeExecutionStatus.COMPLETED)
        }
        val manager = CodeExecutionManager { worker }
        val result = manager.execute("one", script(), temporary.root, JSONObject().put("value", 7), 10_000) { name, args ->
            assertEquals("calculate", name)
            JSONObject().put("value", args.getInt("value") * 2)
        }
        assertEquals(CodeExecutionStatus.COMPLETED, result.status)
        assertEquals(14, (result.result as JSONObject).getInt("value"))
        assertTrue(worker.closed)
        assertEquals(worker.workerPid, result.workerPid)
        assertEquals("diagnostic", File(result.stderrRef).readText())
        val first = manager.readLog(result.stdoutRef, limit = 8)
        val second = manager.readLog(result.stdoutRef, requireNotNull(first.nextOffset), 100)
        assertEquals(text, first.text + second.text)
        assertEquals(8, second.offset)
        assertNull(second.nextOffset)
    }

    @Test fun cancellationHoldsTheSingleSlotAcrossManagersUntilActualWorkerExit() = runTest {
        val exit = CompletableDeferred<Unit>()
        val firstWorker = Worker(exitGate = exit) { _, _ -> awaitCancellation() }
        val secondWorker = Worker { request, _ -> request.resultFile.writeText("{\"result\":2}"); CodeWorkerCompletion("completed") }
        val firstManager = CodeExecutionManager { firstWorker }
        val secondManager = CodeExecutionManager { secondWorker }
        val first = async { firstManager.execute("first", script(), temporary.root, JSONObject(), 10_000, noHost) }
        runCurrent()
        assertTrue(firstWorker.started.isCompleted)
        val second = async { secondManager.execute("second", script(), temporary.root, JSONObject(), 10_000, noHost) }
        val cancelling = async { firstManager.cancel("first") }
        runCurrent()
        try {
            assertTrue(firstWorker.closing.isCompleted)
            assertFalse(firstWorker.closed)
            assertFalse(cancelling.isCompleted)
            assertFalse(first.isCompleted)
            assertFalse(secondWorker.started.isCompleted)
            exit.complete(Unit)
            runCurrent()
            assertTrue(cancelling.await())
            assertEquals(CodeExecutionStatus.CANCELLED, first.await().status)
            assertEquals(CodeExecutionStatus.COMPLETED, second.await().status)
            assertTrue(firstWorker.closed && secondWorker.closed)
        } finally { exit.complete(Unit); first.cancel(); second.cancel() }
    }

    @Test fun cancellingAQueuedExecutionDoesNotCreateItsWorkerOrCancelTheActiveExecution() = runTest {
        val finish = CompletableDeferred<Unit>()
        val active = Worker { request, _ -> finish.await(); request.resultFile.writeText("{\"result\":1}"); CodeWorkerCompletion("completed") }
        var queuedCreations = 0
        val manager = CodeExecutionManager { active }
        val queuedManager = CodeExecutionManager { queuedCreations++; error("Queued worker must not be created") }
        val first = async { manager.execute("active", script(), temporary.root, JSONObject(), 10_000, noHost) }
        runCurrent()
        val queued = async { queuedManager.execute("queued", script(), temporary.root, JSONObject(), 10_000, noHost) }
        runCurrent()
        try {
            assertTrue(queuedManager.cancel("queued"))
            assertEquals(CodeExecutionStatus.CANCELLED, queued.await().status)
            assertEquals(0, queuedCreations)
            assertFalse(active.closing.isCompleted)
            assertFalse(first.isCompleted)
            finish.complete(Unit)
            assertEquals(CodeExecutionStatus.COMPLETED, first.await().status)
        } finally { finish.complete(Unit); first.cancel(); queued.cancel() }
    }

    @Test fun timeoutWaitsForTerminationBeforeReturningAndTheNextRunCreatesANewWorker() = runTest {
        val exit = CompletableDeferred<Unit>()
        val stuck = Worker(exitGate = exit) { _, _ -> awaitCancellation() }
        val next = Worker { request, _ -> request.resultFile.writeText("{\"result\":true}"); CodeWorkerCompletion("completed") }
        var creations = 0
        val manager = CodeExecutionManager { if (creations++ == 0) stuck else next }
        val first = async { manager.execute("timeout", script(), temporary.root, JSONObject(), 100, noHost) }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        try {
            assertTrue(stuck.closing.isCompleted)
            assertFalse(first.isCompleted)
            exit.complete(Unit)
            assertEquals(CodeExecutionStatus.TIMED_OUT, first.await().status)
            assertEquals(CodeExecutionStatus.COMPLETED,
                manager.execute("after-timeout", script(), temporary.root, JSONObject(), 10_000, noHost).status)
            assertEquals(2, creations)
        } finally { exit.complete(Unit); first.cancel() }
    }

    @Test fun callerCancellationStillWaitsForCleanupAndPropagatesCancellation() = runTest {
        val exit = CompletableDeferred<Unit>()
        val worker = Worker(exitGate = exit) { _, _ -> awaitCancellation() }
        val manager = CodeExecutionManager { worker }
        val call = async { manager.execute("caller-cancel", script(), temporary.root, JSONObject(), 10_000, noHost) }
        runCurrent()
        call.cancel()
        runCurrent()
        try {
            assertTrue(worker.closing.isCompleted)
            assertFalse(call.isCompleted)
            exit.complete(Unit)
            call.join()
            assertTrue(call.isCancelled)
            assertTrue(worker.closed)
            assertFalse(manager.cancel("caller-cancel"))
        } finally { exit.complete(Unit) }
    }

    @Test fun workerFailureIsReportedAfterCleanupAndRetainsDiagnostics() = runTest {
        val worker = Worker { request, _ ->
            request.stderrFile.writeText("Traceback: user_program.py line 3: ValueError: bad price")
            CodeWorkerCompletion(CodeExecutionStatus.FAILED, "ValueError: bad price")
        }
        val result = CodeExecutionManager { worker }.execute("bad-script", script(), temporary.root,
            JSONObject(), 10_000, noHost)
        assertEquals(CodeExecutionStatus.FAILED, result.status)
        assertEquals("ValueError: bad price", result.error)
        assertTrue(File(result.stderrRef).readText().contains("line 3"))
        assertTrue(worker.closed)
    }

    private class Worker(private val exitGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val action: suspend (CodeWorkerRequest, CodeHostCall) -> CodeWorkerCompletion) : CodeWorkerConnection {
        override val workerPid = 1234
        val started = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun run(request: CodeWorkerRequest, hostCall: CodeHostCall): CodeWorkerCompletion {
            started.complete(Unit)
            return action(request, hostCall)
        }
        override suspend fun closeAndAwaitExit() { closing.complete(Unit); exitGate.await(); closed = true }
    }
}
