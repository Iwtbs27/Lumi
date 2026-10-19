package ai.opencyvis.engine

import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.fixtures.FakeLlmClient
import ai.opencyvis.llm.LLMClientInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentTaskReviewTest {
    private val engines = mutableListOf<AgentEngine>()
    private lateinit var store: TaskRunStore

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "AA=="
        store = mockk(relaxed = true)
        every { store.runId } returns "review-run"
    }

    @After fun close() {
        engines.forEach { it.destroy() }
        unmockkObject(ScreenCapture)
    }

    @Test fun handoffCanReturnImmediatelyAndFinalContextIncludesLastStep() = runBlocking {
        val client = FakeLlmClient().apply {
            enqueue(response("handoff_user") + mapOf(
                "handoff_reason" to "Tried the add button and menu; cannot find the editor",
                "handoff_kind" to "gui_difficulty"
            ), response("finish"))
        }
        val finished = CompletableDeferred<JSONObject>()
        val engine = engine(client) { runId, outcome, context ->
            assertEquals("review-run", runId)
            assertEquals("completed", outcome)
            verify(exactly = 1) { store.finish("completed") }
            finished.complete(JSONObject(context))
        }
        // An undispatched collector returns control inside the WaitingForHandoff emission.
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            engine.state.first {
                if (it is AgentState.WaitingForHandoff) {
                    engine.completeUserHandoff()
                    true
                } else false
            } as AgentState.WaitingForHandoff
        }
        engine.start("Create a note")
        val finalContext = withTimeout(5_000) { finished.await() }
        assertEquals("gui_difficulty", waiting.await().kind)
        val recent = finalContext.getJSONArray("recent_steps")
        assertEquals("finish", recent.getJSONObject(1).getJSONObject("action").getString("action_type"))
        assertEquals("Observed page", recent.getJSONObject(0).getString("thought"))
        val continuation = finalContext.getJSONArray("continuation_steps")
        assertEquals(2, continuation.length())
        assertEquals(0, continuation.getJSONObject(1).getInt("human_intervention_count"))
        awaitStopped(engine)
    }

    @Test fun cancellationFinishesArchiveAndAllowsSuspendingReviewExactlyOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                error("Cancelled model request must not return")
            }
            override fun shutdown() = Unit
        }
        val callbacks = AtomicInteger()
        val finished = CompletableDeferred<String>()
        val engine = engine(client) { _, outcome, _ ->
            callbacks.incrementAndGet()
            verify(exactly = 1) { store.finish("interrupted") }
            delay(1) // Must still work after stop() has cancelled the parent job.
            finished.complete(outcome)
        }
        engine.start("Create a note")
        withTimeout(5_000) { entered.await() }
        engine.stop()
        assertEquals("interrupted", withTimeout(5_000) { finished.await() })
        engine.stop()
        assertEquals(1, callbacks.get())
    }

    @Test fun reviewFailureDoesNotReplaceCompletedTaskState() = runBlocking {
        val client = FakeLlmClient().apply { enqueue(response("finish")) }
        val invoked = CompletableDeferred<Unit>()
        val engine = engine(client) { _, _, _ ->
            invoked.complete(Unit)
            throw IllegalStateException("Review unavailable")
        }
        engine.start("Create a note")
        withTimeout(5_000) { invoked.await() }
        awaitStopped(engine)
        assertEquals(AgentState.Idle("Observed page"), engine.state.value)
        verify(exactly = 1) { store.finish("completed") }
    }

    private fun engine(
        client: LLMClientInterface,
        onFinished: suspend (String, String, Map<String, Any?>) -> Unit
    ): AgentEngine = AgentEngine(
        llmClient = client,
        actionExecutor = mockk<ActionExecutor>(relaxed = true),
        maxSteps = 5,
        runStoreFactory = { store },
        onRunFinished = onFinished
    ).also { engines += it }

    private suspend fun awaitStopped(engine: AgentEngine) = withTimeout(5_000) {
        while (engine.isTaskActive) delay(1)
    }

    private fun response(type: String): Map<String, Any?> = mapOf(
        "action_type" to type, "thought" to "Observed page", "memory_update" to emptyList<Any>()
    )
}
