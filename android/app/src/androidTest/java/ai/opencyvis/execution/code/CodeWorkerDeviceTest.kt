package ai.opencyvis.execution.code

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the installed app's real CPython process and Binder, without network or model calls. */
@RunWith(AndroidJUnit4::class)
class CodeWorkerDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workspace: File
    private lateinit var manager: CodeExecutionManager
    private val ids = mutableListOf<String>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @Before fun setup() {
        workspace = File(context.cacheDir, "code-worker-test-${System.nanoTime()}").apply { check(mkdirs()) }
        manager = CodeExecutionManager(context)
    }

    @After fun close(): Unit = runBlocking {
        gates.forEach { it.complete(Unit) }
        ids.forEach { manager.cancel(it) }
        workspace.deleteRecursively()
        Unit
    }

    @Test fun realPython311OpenpyxlHostBridgeAndOutputFilesWork() = runBlocking {
        val code = script("workbook.py", """
            import os
            import sys
            from openpyxl import Workbook, load_workbook
            def main(ctx, params):
                print("rows=" + str(params["quantity"]))
                print("diagnostic", file=sys.stderr)
                workbook = Workbook()
                workbook.active.append(["price", "quantity"])
                workbook.active.append([12.35, params["quantity"]])
                output = ctx.path("result.xlsx")
                workbook.save(output)
                verified = load_workbook(output).active.cell(2, 2).value
                answer = ctx.call("test.double", {"value": verified, "pid": os.getpid()})
                return {"answer": answer["value"], "python": list(sys.version_info[:2]), "verified": verified}
        """.trimIndent())
        var workerPid: Int? = null
        val result = withTimeout(45_000) {
            manager.execute(id("workbook"), code, workspace, JSONObject().put("quantity", 7), 30_000) { name, args ->
                assertEquals("test.double", name)
                workerPid = args.getInt("pid")
                assertNotEquals(Process.myPid(), workerPid)
                JSONObject().put("value", args.getInt("value") * 2)
            }
        }
        assertEquals(CodeExecutionStatus.COMPLETED, result.status)
        assertEquals(workerPid, result.workerPid)
        val value = result.result as JSONObject
        assertEquals(14, value.getInt("answer"))
        assertEquals(7, value.getInt("verified"))
        assertEquals("[3,11]", value.getJSONArray("python").toString())
        assertTrue(File(workspace, "result.xlsx").length() > 0)
        assertTrue(File(result.stdoutRef).readText().contains("rows=7"))
        assertTrue(File(result.stderrRef).readText().contains("diagnostic"))
        assertFalse(alive(requireNotNull(workerPid)))
    }

    @Test fun exceptionKeepsTheUserFileAndLineInStderr() = runBlocking {
        val result = withTimeout(45_000) {
            manager.execute(id("exception"), script("bad_price.py", "def main(ctx, params):\n    raise ValueError('bad price')\n"),
                workspace, JSONObject(), 30_000) { _, _ -> error("No host call expected") }
        }
        assertEquals(CodeExecutionStatus.FAILED, result.status)
        assertTrue(result.error.orEmpty().contains("bad price"))
        val error = File(result.stderrRef).readText()
        assertTrue(error.contains("bad_price.py"))
        assertTrue(error.contains("line 2"))
        assertTrue(error.contains("ValueError"))
        assertFalse(alive(requireNotNull(result.workerPid)))
    }

    @Test fun infinitePythonLoopTimesOutAndTheNextRunUsesANewProcess() = runBlocking {
        val started = CompletableDeferred<Int>()
        val looping = async {
            manager.execute(id("infinite"), script("infinite.py", """
                import os
                def main(ctx, params):
                    ctx.call("test.started", {"pid": os.getpid()})
                    while True:
                        pass
            """.trimIndent()), workspace, JSONObject(), 15_000) { name, args ->
                assertEquals("test.started", name)
                started.complete(args.getInt("pid"))
                JSONObject()
            }
        }
        val oldPid = withTimeout(12_000) { started.await() }
        val stopped = withTimeout(20_000) { looping.await() }
        assertEquals(CodeExecutionStatus.TIMED_OUT, stopped.status)
        assertFalse(alive(oldPid))
        val next = withTimeout(30_000) {
            manager.execute(id("after-timeout"), script("next.py", "def main(ctx, params): return {'value': 9}\n"),
                workspace, JSONObject(), 20_000) { _, _ -> error("No host call expected") }
        }
        assertEquals(CodeExecutionStatus.COMPLETED, next.status)
        assertEquals(9, (next.result as JSONObject).getInt("value"))
        assertNotEquals(oldPid, next.workerPid)
        assertFalse(alive(requireNotNull(next.workerPid)))
    }

    @Test fun cancellationWaitsForWorkerDeathAndAnAlreadyRunningHostOperationToExit() = runBlocking {
        val hostEntered = CompletableDeferred<Int>()
        val releaseHost = CompletableDeferred<Unit>().also { gates.add(it) }
        val hostExited = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val executionId = id("cancel-host")
        val execution = async {
            manager.execute(executionId, script("host_wait.py", """
                import os
                def main(ctx, params):
                    ctx.call("test.wait", {"pid": os.getpid()})
                    ctx.call("test.must_not_run", {})
                    return {"late": True}
            """.trimIndent()), workspace, JSONObject(), 30_000) { name, args ->
                assertEquals("test.wait", name)
                calls.incrementAndGet()
                hostEntered.complete(args.getInt("pid"))
                try { withContext(NonCancellable) { releaseHost.await() }; JSONObject() }
                finally { hostExited.complete(Unit) }
            }
        }
        val pid = withTimeout(15_000) { hostEntered.await() }
        val cancelling = async { manager.cancel(executionId) }
        try {
            withTimeout(5_000) { while (alive(pid)) delay(20) }
            assertFalse(cancelling.isCompleted)
            assertFalse(execution.isCompleted)
            assertFalse(hostExited.isCompleted)
            releaseHost.complete(Unit)
            assertTrue(withTimeout(5_000) { cancelling.await() })
            assertEquals(CodeExecutionStatus.CANCELLED, withTimeout(5_000) { execution.await() }.status)
            assertTrue(hostExited.isCompleted)
            assertEquals(1, calls.get())
        } finally { releaseHost.complete(Unit); manager.cancel(executionId) }
    }

    private fun script(name: String, content: String) = File(workspace, name).apply { writeText(content) }
    private fun id(name: String) = name.also { ids.add(it) }
    private fun alive(pid: Int): Boolean = try { Os.kill(pid, 0); true }
    catch (failure: ErrnoException) { if (failure.errno == OsConstants.ESRCH) false else throw failure }
}
