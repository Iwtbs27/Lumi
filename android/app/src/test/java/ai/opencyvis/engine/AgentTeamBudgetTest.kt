package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LLMException
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.ModelBudgetExceededException
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentTeamBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val engines = mutableListOf<AgentEngine>()

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "AA=="
    }

    @After fun close() = runBlocking {
        engines.forEach { withTimeout(5_000) { it.stopAndJoin() }; it.destroy() }
        unmockkObject(ScreenCapture)
    }

    @Test fun exhaustedTaskUsesOneSummaryAndDoesNotClaimCompletionOrExecutePhoneActions() = verifyStop(false)

    @Test fun failedFinalSummaryIsNotRetriedAndOriginalTaskStillStops() = verifyStop(true)

    private fun verifyStop(summaryFails: Boolean) = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        var actionCalls = 0
        var summaryCalls = 0
        val client = object : LLMClientInterface {
            override val contextBudget = ContextBudgetConfig(128_000, 8_192)
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                actionCalls++
                throw ModelBudgetExceededException("Task allowance exhausted")
            }
            override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
                summaryCalls++
                assertEquals(LlmCallPurpose.FINALIZATION, schema.purpose)
                assertEquals(1, schema.maxAttempts)
                assertEquals("finalize_task", schema.tool.getJSONObject("function").getString("name"))
                assertTrue(messages.last().toString().contains("Compare prices"))
                if (summaryFails) throw LLMException("Summary provider unavailable")
                return mapOf("summary" to "已停止；尚未完成价格比较。")
            }
            override fun shutdown() = Unit
        }
        val runtime = requireNotNull(TaskTeamRuntime.open(context, 1, false, TeamConfig(50_000, 8_192)) { client })
        val store = TaskRunStore(context, instruction = "Compare prices", taskId = 1)
        val executor = mockk<ActionExecutor>(relaxed = true)
        val engine = AgentEngine(client, executor, runStoreFactory = { store }, teamRuntime = runtime)
            .also { engines += it }
        engine.start("Compare prices")
        withTimeout(5_000) { engine.awaitStopped() }

        assertEquals(1, actionCalls)
        assertEquals(1, summaryCalls)
        assertEquals("failed", engine.lastOutcome)
        val error = (engine.state.value as AgentState.Error).message
        assertTrue(error.contains("已停止后续操作"))
        assertTrue(error.contains(if (summaryFails) "Summary provider unavailable" else "尚未完成价格比较"))
        coVerify(exactly = 0) { executor.execute(any(), any()) }
        assertNotNull(TaskRunStore.readCheckpoint(context, store.runId))
    }
}
