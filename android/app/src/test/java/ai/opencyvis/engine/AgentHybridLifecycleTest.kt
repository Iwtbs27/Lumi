package ai.opencyvis.engine

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.action.Action
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.execution.code.AndroidCodeWorkerConnection
import ai.opencyvis.execution.code.CodeHostCall
import ai.opencyvis.execution.gui.GuiActionAttempt
import ai.opencyvis.execution.gui.GuiBounds
import ai.opencyvis.execution.gui.GuiNodeSnapshot
import ai.opencyvis.execution.gui.GuiPageIdentity
import ai.opencyvis.execution.gui.GuiQueryResult
import ai.opencyvis.execution.gui.GuiSelector
import ai.opencyvis.execution.program.ProgramRepository
import ai.opencyvis.execution.program.ProgramSpec
import ai.opencyvis.llm.LLMClientInterface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real host/runtime/archive and process-slot lifecycle; Android/Binder endpoints are controlled fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentHybridLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val engines = mutableListOf<AgentEngine>()
    private val exitGates = mutableListOf<CompletableDeferred<Unit>>()
    private val vdm = mockk<VirtualDisplayManager>()
    private val executor = mockk<ActionExecutor>(relaxed = true)
    private val captures = AtomicInteger()
    private val page = GuiPageIdentity(7, "test.notes", 31)
    private var title = "Original title"
    private var saved = "Save"

    @Before fun setup() {
        val root = temporary.root
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64(any(), any()) } returns "AA=="
        every { vdm.displayId } returns page.displayId
        every { vdm.width } returns 1000
        every { vdm.height } returns 2000
        every { vdm.captureFrame(any(), any()) } answers {
            captures.incrementAndGet()
            VirtualDisplayManager.CapturedFrame(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                page.displayId, 1000, 2000, 1, 2, "test_frame")
        }
        mockkObject(VdAccessibilityService.Companion)
        every { VdAccessibilityService.getTopPackageOnDisplay(page.displayId) } returns page.packageName
        every { VdAccessibilityService.queryGui(any()) } answers {
            val selector = firstArg<GuiSelector>()
            GuiQueryResult(page, nodes().filter(selector::matches))
        }
        every { VdAccessibilityService.setGuiText(any(), any(), any(), any()) } answers {
            val target = nodes().single(firstArg<GuiSelector>()::matches)
            title = thirdArg()
            GuiActionAttempt(true, target)
        }
        coEvery { executor.execute(any(), any()) } coAnswers {
            val action = firstArg<Action>()
            saved = "Saved"
            StepResult(secondArg(), action.typeName, action.thought, true, "input delivered", 1, false)
        }
    }

    @After fun close(): Unit = runBlocking {
        exitGates.forEach { it.complete(Unit) }
        engines.forEach { withTimeout(8_000) { it.stopAndJoin() }; it.destroy() }
        unmockkConstructor(AndroidCodeWorkerConnection::class)
        unmockkObject(VdAccessibilityService.Companion, ScreenCapture)
    }

    @Test fun capabilitiesAndGuiBlockUseRealRuntimeAndKeepOperationAttribution(): Unit = runBlocking {
        val requests = mutableListOf<List<Map<String, Any>>>()
        val client = replies(requests, response("describe_capabilities"), block(), response("finish"))
        val store = TaskRunStore(context, instruction = "Fill and save the note", taskId = 801)
        val engine = engine(client, store)
        engine.start("Fill and save the note")
        withTimeout(8_000) { engine.awaitStopped() }

        assertEquals("completed", engine.lastOutcome)
        assertEquals("Title from runtime", title)
        assertEquals("Saved", saved)
        assertTrue(currentState(requests.first()).getJSONObject("execution").getBoolean("enabled"))
        val cards = currentState(requests.last()).getJSONObject("execution").getJSONArray("capabilities")
        assertTrue((0 until cards.length()).any { cards.getJSONObject(it).getString("name") == "gui.execute_block" })
        coVerify(exactly = 1) { executor.execute(Action.Tap(400, 250), 2) }
        val result = hybridEvent(store, 2).getJSONObject("result")
        assertEquals("completed", result.getString("status"))
        assertEquals("Title from runtime", result.getJSONArray("operations").getJSONObject(0).getJSONObject("after").getString("text"))
        val operations = File(context.filesDir, "task_execution/801/journal/operations").listFiles()!!.map { JSONObject(it.readText()) }
        assertEquals(2, operations.size)
        assertTrue(operations.all { it.getString("run_id") == store.runId && it.getInt("step") == 2 &&
            it.getString("status") == "succeeded" && it.getJSONObject("result").getBoolean("verified") })
    }

    @Test fun capabilityCannotWriteIntoABlacklistedTargetPackage(): Unit = runBlocking {
        val store = TaskRunStore(context, instruction = "Inspect the note", taskId = 802)
        val requests = mutableListOf<List<Map<String, Any>>>()
        val client = replies(requests, block("protected.app"), response("finish"))
        val engine = engine(client, store, blacklist = setOf("protected.app"))
        engine.start("Inspect the note")
        withTimeout(8_000) { engine.awaitStopped() }

        val result = hybridEvent(store, 1).getJSONObject("result")
        assertEquals("needs_agent", result.getString("status"))
        assertTrue(result.getString("reason").contains("blacklist"))
        assertEquals("Original title", title)
        verify(exactly = 0) { VdAccessibilityService.setGuiText(any(), any(), any(), any()) }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun pauseWaitsForGuiWorkerExitThenResumesWithAFreshObservation(): Unit = verifyProgramExit(stop = false)

    @Test fun stopWaitsForGuiWorkerExitBeforeRunFinalization(): Unit = verifyProgramExit(stop = true)

    private fun verifyProgramExit(stop: Boolean): Unit = runBlocking {
        val taskId = if (stop) 804L else 803L
        val store = TaskRunStore(context, instruction = "Run note workflow", taskId = taskId)
        val program = ProgramRepository(File(context.filesDir, "programs")).saveCode(
            "def main(ctx, params): return ctx.call('gui.query', params)",
            ProgramSpec("Note workflow", "gui", requiredCapabilities = listOf("gui.query")))
        val entered = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>().also(exitGates::add)
        var closed = false
        mockkConstructor(AndroidCodeWorkerConnection::class)
        every { anyConstructed<AndroidCodeWorkerConnection>().workerPid } returns 123
        coEvery { anyConstructed<AndroidCodeWorkerConnection>().run(any(), any()) } coAnswers {
            // This re-enters the Engine's GUI lock through the real SDK host. Holding that lock
            // around run_program would deadlock before entered is completed.
            val call = secondArg<CodeHostCall>()
            val actual = call("gui.query", JSONObject().put("selector", JSONObject(selector("title"))))
            assertEquals("Original title", actual.getJSONArray("nodes").getJSONObject(0).getString("text"))
            entered.complete(Unit)
            awaitCancellation()
        }
        coEvery { anyConstructed<AndroidCodeWorkerConnection>().closeAndAwaitExit() } coAnswers {
            closing.complete(Unit)
            exit.await()
            closed = true
        }
        val requests = mutableListOf<List<Map<String, Any>>>()
        val client = replies(requests, response("run_program") + mapOf("program_id" to program.ref.programId,
            "revision" to program.ref.revision, "params" to emptyMap<String, Any>(), "execution_timeout_ms" to 30_000), response("finish"))
        val finalized = CompletableDeferred<Unit>()
        val engine = engine(client, store, finalized = finalized)
        engine.start("Run note workflow")
        withTimeout(8_000) { entered.await() }
        val beforePause = captures.get()
        val control = async { if (stop) engine.stopAndJoin() else engine.pauseForHuman() }
        try {
            withTimeout(5_000) { closing.await() }
            yield()
            assertFalse(control.isCompleted)
            assertFalse(closed)
            assertFalse(finalized.isCompleted)
            assertTrue(engine.isTaskActive)
            assertNull(engine.lastOutcome)
            exit.complete(Unit)
            withTimeout(8_000) { control.await() }
            assertTrue(closed)
            if (!stop) {
                assertEquals(AgentState.Paused, engine.state.value)
                assertTrue(engine.isTaskActive)
                assertEquals(1, JSONObject(engine.handoverSnapshot()).getInt("human_intervention_count"))
                engine.resume()
                withTimeout(8_000) { engine.awaitStopped() }
                assertTrue(captures.get() > beforePause)
                assertEquals("needs_agent", hybridEvent(store, 1).getJSONObject("result").getString("status"))
            }
            assertEquals(if (stop) "interrupted" else "completed", engine.lastOutcome)
            assertTrue(finalized.isCompleted)
            val record = File(context.filesDir, "task_execution/$taskId/program-runs").listFiles()!!
                .single().resolve("execution.json")
            assertEquals("cancelled", JSONObject(record.readText()).getString("status"))
            coVerify(exactly = 1) { anyConstructed<AndroidCodeWorkerConnection>().run(any(), any()) }
        } finally { exit.complete(Unit); control.cancel() }
    }

    private fun engine(client: LLMClientInterface, store: TaskRunStore, blacklist: Set<String> = emptySet(),
        finalized: CompletableDeferred<Unit>? = null) = AgentEngine(client, executor, maxSteps = 5,
        virtualDisplayManager = vdm, executionContext = context, blacklistedPackages = blacklist,
        runStoreFactory = { store }, onRunFinished = { _, _, _ -> finalized?.complete(Unit); Unit }).also(engines::add)

    private fun replies(requests: MutableList<List<Map<String, Any>>>, vararg responses: Map<String, Any?>) = object : LLMClientInterface {
        override val contextBudget = ContextBudgetConfig.preset(128_000)
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            requests += messages
            return responses[requests.lastIndex]
        }
        override fun shutdown() = Unit
    }

    private fun currentState(messages: List<Map<String, Any>>): JSONObject {
        val content = messages.last().getValue("content") as List<*>
        val text = content.filterIsInstance<Map<*, *>>().mapNotNull { it["text"] as? String }.first { "[cur_state]" in it }
        return JSONObject(text.substringAfter("[cur_state]\n"))
    }

    private fun hybridEvent(store: TaskRunStore, step: Int): JSONObject =
        File(context.filesDir, "agent_runs/${store.runId}/steps/$step/events.jsonl").readLines()
            .map(::JSONObject).single { it.getString("type") == "hybrid_execution" }.getJSONObject("data")

    private fun nodes() = listOf(node("page", "Note editor"), node("title", title, editable = true),
        node("save", saved, clickable = true))
    private fun node(id: String, text: String, editable: Boolean = false, clickable: Boolean = false) = GuiNodeSnapshot(
        page, "test.notes:id/$id", "android.widget.TextView", text, null, GuiBounds(300, 400, 500, 600),
        enabled = true, visible = true, editable = editable, clickable = clickable, checked = false, selected = false)
    private fun selector(id: String, pkg: String = page.packageName) = mapOf("package_name" to pkg, "resource_id" to "$pkg:id/$id")
    private fun response(type: String): Map<String, Any?> = mapOf("thought" to "Observed page", "action_type" to type,
        "memory_update" to emptyList<Any>())
    private fun block(pkg: String = page.packageName) = response("call_capability") + mapOf(
        "capability_name" to "gui.execute_block", "arguments" to mapOf("page_anchor" to selector("page", pkg), "actions" to listOf(
            mapOf("type" to "set_text", "selector" to selector("title", pkg), "text" to "Title from runtime"),
            mapOf("type" to "tap", "selector" to selector("save", pkg), "expect" to mapOf("selector" to selector("save", pkg), "text" to "Saved")))))
}
