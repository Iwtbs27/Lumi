package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.LlmOwnerRole
import ai.opencyvis.llm.LlmUsage
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskTeamRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val settings = TeamConfig(10_000, 1_000)

    @Test fun `task restart retains original settings and charges unknown in flight calls`() {
        val first = requireNotNull(TaskTeamRuntime.open(context, 301, false, settings) { unusedClient() })
        val known = first.budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 400, 200)
        first.budget.settle(known.id, LlmUsage(300, 50), true)
        first.budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 500, 200)

        val resumed = requireNotNull(TaskTeamRuntime.open(context, 301, true,
            TeamConfig(100_000, 5_000, 2, 30)) { unusedClient() })

        assertEquals(settings, resumed.config)
        assertEquals(350L, resumed.budget.snapshot().knownInputTokens + resumed.budget.snapshot().knownOutputTokens)
        assertEquals(700L, resumed.budget.snapshot().unknownReservedTokens)
        assertEquals(0L, resumed.budget.snapshot().pendingReservedTokens)
        assertEquals(8_950L, resumed.budget.snapshot().remainingTokens)
    }

    @Test fun `enabling teams does not reset an existing single agent task on resume`() {
        assertNull(TaskTeamRuntime.open(context, 302, false, null) { unusedClient() })
        assertNull(TaskTeamRuntime.open(context, 302, true, settings) { unusedClient() })
        assertNull(TaskTeamRuntime.open(context, 303, true, settings) { unusedClient() })
        assertNotNull(TaskTeamRuntime.open(context, 304, false, settings) { unusedClient() })
    }

    private fun unusedClient(): LLMClientInterface = error("Opening a task's ledger must not start model work")
}
