package ai.opencyvis.engine

import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.llm.LLMClientInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentPauseWaitingTest {
    private val engines = mutableListOf<AgentEngine>()

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "AA=="
    }

    @After fun close() = runBlocking {
        engines.forEach { withTimeout(5_000) { it.stopAndJoin() }; it.destroy() }
        unmockkObject(ScreenCapture)
    }

    @Test fun `ordinary pause and resume restores question and accepts the answer`() = runBlocking {
        val client = Replies(response("ask_user") + ("question" to "Which currency?"))
        val engine = engine(client)
        engine.start("Read the price")
        val waiting = withTimeout(5_000) { engine.state.first { it is AgentState.WaitingForUser } }

        engine.pauseAndAwaitInput()
        assertEquals(AgentState.Paused, engine.state.value)
        engine.resume()

        assertEquals(waiting, engine.state.value)
        engine.submitUserResponse("CNY")
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals("completed", engine.lastOutcome)
        assertEquals(2, client.inputs.size)
        assertTrue(client.inputs.last().last().toString().contains("CNY"))
    }

    @Test fun `ordinary pause and resume restores the pending handoff`() = runBlocking {
        val client = Replies(response("handoff_user") + mapOf(
            "handoff_reason" to "Choose the account", "handoff_kind" to "account_access"))
        val engine = engine(client)
        engine.start("Read the price")
        val waiting = withTimeout(5_000) { engine.state.first { it is AgentState.WaitingForHandoff } }

        engine.pauseAndAwaitInput()
        engine.resume()

        assertEquals(waiting, engine.state.value)
        engine.completeUserHandoff("account_selected")
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals("completed", engine.lastOutcome)
        assertTrue(client.inputs.last().last().toString().contains("account_selected"))
    }

    @Test fun `human takeover return completes the question instead of restoring it`() = runBlocking {
        val client = Replies(response("ask_user") + ("question" to "Which account?"))
        val engine = engine(client)
        engine.start("Read the price")
        withTimeout(5_000) { engine.state.first { it is AgentState.WaitingForUser } }

        engine.pauseForHuman()
        assertEquals(AgentState.Paused, engine.state.value)
        engine.resumeAfterHuman("Account selected manually")

        assertFalse(engine.state.value is AgentState.WaitingForUser)
        withTimeout(5_000) { engine.awaitStopped() }
        assertEquals("completed", engine.lastOutcome)
        assertTrue(client.inputs.last().last().toString().contains("Account selected manually"))
    }

    private fun engine(client: LLMClientInterface) = AgentEngine(client, mockk(relaxed = true), maxSteps = 3)
        .also { engines += it }

    private class Replies(private val first: Map<String, Any?>) : LLMClientInterface {
        val inputs = mutableListOf<List<Map<String, Any>>>()
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            inputs += messages
            return if (inputs.size == 1) first else response("finish")
        }
        override fun shutdown() = Unit
    }

    private companion object {
        fun response(action: String): Map<String, Any?> = mapOf(
            "thought" to "Observed page", "action_type" to action, "memory_update" to emptyList<Any>())
    }
}
