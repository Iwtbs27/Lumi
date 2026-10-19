package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.llm.LLMClientInterface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentRecoveryLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val engines = mutableListOf<AgentEngine>()
    private val releaseOnClose = mutableListOf<CompletableDeferred<Unit>>()
    private val context = mockk<Context>()

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

    @Test fun cancellationRemainsActiveUntilSuspendingFinalizationReallyExits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { releaseOnClose += it }
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                entered.complete(Unit)
                awaitCancellation()
            }
            override fun shutdown() = Unit
        }
        val store = TaskRunStore(context, instruction = "Compare", taskId = 1)
        val engine = AgentEngine(client, mockk(relaxed = true), runStoreFactory = { store },
            onRunFinished = { _, _, _ -> finalizing.complete(Unit); release.await() }).also { engines += it }
        engine.start("Compare")
        withTimeout(5_000) { entered.await() }
        engine.stop()
        withTimeout(5_000) { finalizing.await() }
        assertTrue(engine.isTaskActive)
        assertNull(engine.lastOutcome)
        val joined = async { engine.awaitStopped() }
        yield()
        assertFalse(joined.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { joined.await() }
        assertFalse(engine.isTaskActive)
        assertEquals("interrupted", engine.lastOutcome)
    }

    @Test fun interruptedDispatchKeepsUnknownEffectAndNewUserInputDurably() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val executor = mockk<ActionExecutor>(relaxed = true)
        coEvery { executor.execute(any(), any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        val store = TaskRunStore(context, instruction = "Enter exact price", taskId = 2)
        val client = Replies(listOf(response("type_text") + ("text" to "12.3500")))
        val engine = AgentEngine(client, executor, runStoreFactory = { store }).also { engines += it }
        engine.start("Enter exact price")
        withTimeout(5_000) { entered.await() }
        engine.submitUserSupplement("Do not change the currency")
        val issued = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
        assertEquals("issued", TaskState.restore(issued).steps.getValue(1).execution["execution_status"])
        assertEquals(listOf("Do not change the currency"), issued.pendingUserInputs)
        withTimeout(5_000) { engine.stopAndJoin() }
        val saved = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
        assertEquals("unknown", TaskState.restore(saved).steps.getValue(1).effect!!["status"])
        assertEquals(2, saved.nextStep)
        coVerify(exactly = 1) { executor.execute(any(), any()) }
    }

    @Test fun humanTakeoverIsDurableWhileTheModelRequestIsStillPending() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                entered.complete(Unit)
                awaitCancellation()
            }
            override fun shutdown() = Unit
        }
        val store = TaskRunStore(context, instruction = "Compare", taskId = 4)
        val engine = AgentEngine(client, mockk(relaxed = true), runStoreFactory = { store }).also { engines += it }
        engine.start("Compare")
        withTimeout(5_000) { entered.await() }
        engine.submitUserSupplement("Keep the original units")
        engine.pauseAndAwaitInput()
        assertEquals(0, TaskState.restore(requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))).humanInterventionCount)
        engine.pauseForHuman()
        engine.pauseForHuman()
        val duringTakeover = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
        assertEquals(1, TaskState.restore(duringTakeover).humanInterventionCount)
        assertEquals(true, duringTakeover.waitingContext["human_control_granted"])
        assertEquals(listOf("Keep the original units"), duringTakeover.pendingUserInputs)
        assertTrue(engine.isTaskActive)
        withTimeout(5_000) { engine.stopAndJoin() }
        val afterStopping = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
        assertEquals(1, TaskState.restore(afterStopping).humanInterventionCount)
        assertEquals(true, afterStopping.waitingContext["human_control_granted"])
    }

    @Test fun recoveryObservesFreshScreenAndDoesNotRetryUnconfirmedOldWrite() = runBlocking {
        val old = TaskRunStore(context, instruction = "Compare", taskId = 3)
        val task = TaskState("Compare").apply {
            steps[9] = TaskState.Step(9, null, null, response("type_text") + ("text" to "12.3500"),
                mapOf("execution_status" to "issued"), mapOf("status" to "unknown"))
            memories["price"] = mapOf("value" to "12.3500", "source_step" to 9)
            addUserUpdate(9, "supplement", "Blue items only")
        }
        old.saveCheckpoint(task.exportCheckpoint(old.runId, 10,
            waitingContext = mapOf("kind" to "ask_user", "question" to "Which currency?")))
        old.finish("interrupted")
        val checkpoint = requireNotNull(TaskRunStore.readCheckpoint(context, old.runId))
        val store = TaskRunStore(context, instruction = "Compare", taskId = 3, resume = checkpoint)
        val executor = mockk<ActionExecutor>(relaxed = true)
        val client = Replies(listOf(response("type_text") + ("text" to "12.3500"), response("finish")))
        var startedRun: String? = null
        var finalContext: Map<String, Any?>? = null
        val engine = AgentEngine(client, executor, maxSteps = 2, runStoreFactory = { store },
            onRunStarted = { startedRun = it },
            onRunFinished = { _, _, context -> finalContext = context }).also { engines += it }
        engine.start("Compare", checkpoint)
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals(store.runId, startedRun)
        assertEquals(2, client.inputs.size)
        val first = client.inputs.first().last().toString()
        assertTrue(first.contains("current_step=10"))
        assertTrue(first.contains("12.3500"))
        assertTrue(first.contains("Blue items only"))
        assertTrue(first.contains("Which currency?"))
        assertTrue(first.contains("pending_results"))
        coVerify(exactly = 0) { executor.execute(any(), any()) }
        val restored = TaskState.restore(requireNotNull(TaskRunStore.readCheckpoint(context, store.runId)))
        assertEquals(setOf(9, 10, 11), restored.steps.keys)
        assertEquals("blocked", restored.steps.getValue(10).execution["execution_status"])
        assertEquals("unknown", restored.steps.getValue(9).effect!!["status"])
        assertEquals("completed", engine.lastOutcome)
        val review = JSONObject(requireNotNull(finalContext))
        val continuation = review.getJSONArray("continuation_steps").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
        assertEquals(listOf(10, 11), continuation.map { it.getInt("step") })
        assertTrue(continuation.all { it.getString("source_run") == store.runId })
        val oldContext = review.getJSONArray("recent_steps").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getInt("step") == 9 }
        }
        assertEquals(old.runId, oldContext.getString("source_run"))
    }

    @Test fun sharedEpochDiscardsDelayedDecisionAndTakesAnotherObservation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { releaseOnClose += it }
        val epoch = AtomicLong(10)
        var calls = 0
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                calls++
                if (calls == 1) { entered.complete(Unit); release.await(); return response("tap") + mapOf("x" to 100, "y" to 100) }
                return response("finish")
            }
            override fun shutdown() = Unit
        }
        val executor = mockk<ActionExecutor>(relaxed = true)
        val engine = AgentEngine(client, executor, maxSteps = 2, controlEpoch = epoch).also { engines += it }
        engine.start("Compare")
        withTimeout(5_000) { entered.await() }
        epoch.incrementAndGet()
        release.complete(Unit)
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals(2, calls)
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    private class Replies(private val replies: List<Map<String, Any?>>) : LLMClientInterface {
        val inputs = mutableListOf<List<Map<String, Any>>>()
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            inputs += messages
            return replies[inputs.lastIndex]
        }
        override fun shutdown() = Unit
    }

    private companion object {
        fun response(action: String): Map<String, Any?> = mapOf("thought" to "Observed page", "action_type" to action,
            "memory_update" to emptyList<Any>())
    }
}
