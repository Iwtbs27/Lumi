package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmOwnerRole
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.team.DelegationStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentTeamLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val engines = mutableListOf<AgentEngine>()
    private val releaseOnClose = mutableListOf<CompletableDeferred<Unit>>()

    @Before fun setup() {
        every { context.filesDir } returns temporary.root
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "AA=="
    }

    @After fun close() = runBlocking {
        releaseOnClose.forEach { it.complete(Unit) }
        engines.forEach { withTimeout(5_000) { it.stopAndJoin() }; it.destroy() }
        unmockkObject(ScreenCapture)
    }

    @Test fun parentFinishWaitsForChildExitAndPublishesItsCancelledFinalState() = verifyParentExit(stop = false)

    @Test fun parentStopWaitsForChildExitAndPublishesItsCancelledFinalState() = verifyParentExit(stop = true)

    @Test fun ordinaryPauseCancelsOnlyTheParentRoundAndTheSameClientCanResume() = verifyPause(returnLateResponse = false)

    @Test fun lateParentResponseAfterPauseCannotExecuteItsOldGuiAction() = verifyPause(returnLateResponse = true)

    private fun verifyParentExit(stop: Boolean): Unit = runBlocking {
        val child = WaitingChild(gate())
        val store = TaskRunStore(context, instruction = "Compare recorded prices", taskId = 701)
        val reference = store.saveContextArtifact("Recorded price: 12.35 CNY", 1, "Price notes").artifactRef
        val parentEntered = CompletableDeferred<Unit>()
        val parent = object : LLMClientInterface {
            private var calls = 0
            override val contextBudget = limits
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                if (++calls == 1) return delegate(reference)
                child.entered.await()
                parentEntered.complete(Unit)
                if (stop) awaitCancellation()
                return response("finish")
            }
            override fun shutdown() = Unit
        }
        val finalContext = CompletableDeferred<Map<String, Any?>>()
        val executor = mockk<ActionExecutor>(relaxed = true)
        val engine = engine(parent, child, store, executor, finalContext)
        engine.start("Compare recorded prices")
        withTimeout(5_000) { parentEntered.await() }
        if (stop) engine.stop()
        withTimeout(5_000) { child.cancelling.await() }

        assertTrue(engine.isTaskActive)
        assertNull(engine.lastOutcome)
        assertFalse(child.closed)
        assertFalse(finalContext.isCompleted)
        val joined = async { engine.awaitStopped() }
        yield()
        assertFalse(joined.isCompleted)

        child.exitGate.complete(Unit)
        withTimeout(5_000) { joined.await() }
        assertTrue(child.closed)
        assertFalse(engine.isTaskActive)
        assertEquals(if (stop) "interrupted" else "completed", engine.lastOutcome)
        assertEquals(DelegationStatus.CANCELLED, engine.childAgents.value.single().status)
        assertNull(engine.childAgents.value.single().result)
        assertCancelledChild(withTimeout(5_000) { finalContext.await() })
        assertCancelledChild(engine.handoverSnapshot())
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    private fun verifyPause(returnLateResponse: Boolean): Unit = runBlocking {
        val child = WaitingChild(gate())
        val store = TaskRunStore(context, instruction = "Compare recorded prices", taskId = 702)
        val reference = store.saveContextArtifact("Recorded price: 12.35 CNY", 1, "Price notes").artifactRef
        val parent = PausingParent(reference, child, returnLateResponse, gate(), gate())
        val finalContext = CompletableDeferred<Map<String, Any?>>()
        val executor = mockk<ActionExecutor>(relaxed = true)
        val engine = engine(parent, child, store, executor, finalContext)
        engine.start("Compare recorded prices")
        withTimeout(5_000) { parent.pending.await() }
        withTimeout(5_000) { engine.childAgents.first { it.singleOrNull()?.status == DelegationStatus.RUNNING } }

        engine.pauseAndAwaitInput()

        assertEquals("A normal pause must cancel the current parent request", 1, parent.cancels.get())
        withTimeout(5_000) { parent.roundExited.await() }
        assertEquals(AgentState.Paused, engine.state.value)
        assertTrue(engine.isTaskActive)
        assertNull(engine.lastOutcome)
        assertEquals(2, parent.calls.get())
        assertEquals(0, child.cancels.get())
        assertFalse(child.closed)
        assertEquals(DelegationStatus.RUNNING, engine.childAgents.value.single().status)
        coVerify(exactly = 0) { executor.execute(any(), any()) }

        engine.resume()
        withTimeout(5_000) { parent.resumed.await() }
        assertEquals(3, parent.calls.get())
        assertEquals(0, child.cancels.get())
        assertFalse(child.closed)
        parent.allowFinish.complete(Unit)
        withTimeout(5_000) { child.cancelling.await() }
        child.exitGate.complete(Unit)
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals("completed", engine.lastOutcome)
        assertCancelledChild(withTimeout(5_000) { finalContext.await() })
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    private fun engine(parent: LLMClientInterface, child: WaitingChild, store: TaskRunStore,
        executor: ActionExecutor, finalContext: CompletableDeferred<Map<String, Any?>>): AgentEngine {
        val runtime = requireNotNull(TaskTeamRuntime.open(context, requireNotNull(store.taskId), false,
            TeamConfig(100_000, 1_000)) { owner ->
            check(owner?.role == LlmOwnerRole.CHILD)
            child
        })
        return AgentEngine(parent, executor, maxSteps = 4, runStoreFactory = { store }, teamRuntime = runtime,
            onRunFinished = { _, _, handover -> finalContext.complete(handover) }).also { engines.add(it) }
    }

    private fun gate() = CompletableDeferred<Unit>().also { releaseOnClose.add(it) }

    private fun assertCancelledChild(handover: Map<String, Any?>) {
        val children = JSONObject(handover).getJSONObject("current_state")
            .getJSONObject("team").getJSONArray("children")
        assertEquals(1, children.length())
        assertEquals(DelegationStatus.CANCELLED, children.getJSONObject(0).getString("status"))
    }

    private class WaitingChild(val exitGate: CompletableDeferred<Unit>) : LLMClientInterface {
        val entered = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val cancels = AtomicInteger()
        @Volatile var closed = false
        override val contextBudget = limits
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Child needs its own schema")
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            entered.complete(Unit)
            try { awaitCancellation() }
            catch (cancelled: CancellationException) {
                cancelling.complete(Unit)
                withContext(NonCancellable) { exitGate.await() }
                // A transport that drains late must not turn cancellation into an accepted child result.
                return mapOf("action_type" to "submit_result", "status" to "needs_evidence",
                    "facts" to emptyList<Any>(), "gaps" to listOf("late response"),
                    "collection_requests" to listOf("Collect another price"))
            }
        }
        override fun cancelRequests() { cancels.incrementAndGet() }
        override fun shutdown() { closed = true }
    }

    private class PausingParent(private val reference: String, private val child: WaitingChild,
        private val returnLateResponse: Boolean, private val cancelled: CompletableDeferred<Unit>,
        val allowFinish: CompletableDeferred<Unit>) : LLMClientInterface {
        val pending = CompletableDeferred<Unit>()
        val roundExited = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cancels = AtomicInteger()
        override val contextBudget = limits
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = when (calls.incrementAndGet()) {
            1 -> delegate(reference)
            2 -> {
                child.entered.await()
                pending.complete(Unit)
                try {
                    cancelled.await()
                    if (returnLateResponse) response("tap") + mapOf("x" to 100, "y" to 100)
                    else throw CancellationException("Parent request round cancelled")
                } finally { roundExited.complete(Unit) }
            }
            3 -> { resumed.complete(Unit); allowFinish.await(); response("finish") }
            else -> error("Unexpected parent request after finish")
        }
        override fun cancelRequests() { cancels.incrementAndGet(); cancelled.complete(Unit) }
        override fun shutdown() = Unit
    }

    private companion object {
        val limits = ContextBudgetConfig.preset(128_000)
        fun response(action: String): Map<String, Any?> = mapOf("thought" to "Observed page", "action_type" to action,
            "memory_update" to emptyList<Any>())
        fun delegate(reference: String) = response("delegate_task") + mapOf("child_goal" to "Compare the recorded price",
            "artifact_refs" to listOf(reference))
    }
}
