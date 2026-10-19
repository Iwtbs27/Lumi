package ai.opencyvis.engine

import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LLMException
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.ContextLimitException
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmUsage
import ai.opencyvis.llm.ModelBudgetExceededException
import ai.opencyvis.memory.MemoryPacket
import ai.opencyvis.memory.MemoryRecall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextManagerTest {
    private class SummaryClient : LLMClientInterface {
        override val contextBudget = ContextBudgetConfig(128_000, 16_000, workingInputLimitTokens = 32_000)
        var summaries = 0
        var fail = false
        var cancel = false
        var exhausted = false
        var rejectFirstAction = false
        var inventConfirmation = false
        var actionCalls = 0
        var onSummary: () -> Unit = {}
        var usageMultiplier = 1
        val summaryEstimates = mutableListOf<Int>()
        override var lastCallInfo: LlmCallInfo? = null
        val actionInputs = mutableListOf<List<Map<String, Any>>>()
        val summaryInputs = mutableListOf<String>()
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            summaries++
            val rawEstimate = ContextTokenEstimator().estimate(messages, schema.tool).totalTokens
            summaryEstimates += rawEstimate
            lastCallInfo = LlmCallInfo(mapOf("kind" to "summary"), LlmUsage(
                if (usageMultiplier > 1) rawEstimate.toLong() * usageMultiplier else 100, 20), 1)
            if (cancel) throw CancellationException("stopped")
            if (exhausted) throw ModelBudgetExceededException("Task allowance exhausted")
            if (fail) throw LLMException("summary unavailable")
            val raw = messages.last()["content"] as String
            summaryInputs += raw
            val events = JSONObject(raw).getJSONArray("events")
            val sources = (0 until events.length()).map { events.getJSONObject(it).getInt("source_step") }.distinct()
            onSummary()
            return mapOf("summary" to "Compared recorded items; consult source steps for exact values.",
                "evidence_steps" to sources, "confirmed_steps" to if (inventConfirmation) sources.take(1) else emptyList<Int>(),
                "open_questions" to listOf("unconfirmed actions"))
        }
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            actionCalls++
            actionInputs += messages
            lastCallInfo = LlmCallInfo(mapOf("kind" to "action"), LlmUsage(200, 30), 1)
            if (rejectFirstAction && actionCalls == 1) throw ContextLimitException("provider overflow")
            return mapOf("action_type" to "finish")
        }
        override fun shutdown() = Unit
    }

    private fun task(count: Int, detail: Int = 80) = TaskState("Compare items and preserve exact prices").apply {
        for (step in 1..count) steps[step] = TaskState.Step(step, null, "tree-$step",
            mapOf("action_type" to "tap", "x" to 5, "y" to 20),
            mapOf("execution_status" to "executed", "detail" to "item-$step " + "evidence ".repeat(detail)),
            mapOf("status" to "unknown"))
        memories["price"] = mapOf("value" to "12.35", "source_step" to 1)
        addUserUpdate(count + 1, "supplement", "blue items only")
    }

    private fun input(step: Int) = ContextInput(step, "current-image", "current-tree", MemoryPacket(), null, null)

    @Test fun shortTasksDoNotSummarizeAndOldHistoryRemainsStable() = runBlocking {
        val client = SummaryClient()
        val task = task(2, 1)
        val manager = ContextManager(task, null, client)
        val first = manager.prepare(input(3))
        task.memories["price"] = mapOf("value" to "13.00", "source_step" to 2)
        val second = manager.prepare(input(3))
        assertEquals(0, client.summaries)
        assertEquals(first.dropLast(1), second.dropLast(1))
    }

    @Test fun compactionKeepsRecentCompleteStepsExactFactsAndPendingEffects() = runBlocking {
        val client = SummaryClient()
        val task = task(70)
        val manager = ContextManager(task, null, client)
        val messages = manager.prepare(input(71))
        assertTrue(client.summaries > 0)
        assertNotNull(manager.builder.history.checkpoint)
        assertTrue(manager.builder.history.visible.any { it.sourceStep == 65 })
        assertEquals(70, task.steps.size)
        assertTrue(task.steps.values.all { it.effect?.get("status") == "unknown" })
        assertTrue(messages.last().toString().contains("12.35"))
        assertTrue(messages.last().toString().contains("blue items only"))
        assertTrue(messages.last().toString().contains("pending_results"))
        val calls = client.summaries
        assertEquals(messages.dropLast(1), manager.prepare(input(71)).dropLast(1))
        assertEquals(calls, client.summaries)
    }

    @Test fun failedSummaryDoesNotCommitOrDeleteHistory() = runBlocking {
        val client = SummaryClient().apply { fail = true }
        val task = task(20, 20)
        val manager = ContextManager(task, null, client)
        val original = manager.prepare(input(21))
        manager.prepare(input(21), forceCompact = true)
        assertEquals(1, client.summaries)
        assertNull(manager.builder.history.checkpoint)
        assertEquals(original.dropLast(1), manager.builder.build(21, "current-image", "current-tree", MemoryPacket(), null, null).dropLast(1))
    }

    @Test fun summaryCancellationPropagatesWithoutCommittingCheckpoint() = runBlocking {
        val client = SummaryClient().apply { cancel = true }
        val manager = ContextManager(task(20, 20), null, client)
        try {
            manager.prepare(input(21), forceCompact = true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(manager.builder.history.checkpoint)
    }

    @Test fun requiredContentAboveBudgetStopsBeforeAnyModelCall() = runBlocking {
        val client = SummaryClient()
        val manager = ContextManager(TaskState("exact requirement ".repeat(12_000)), null, client)
        try {
            manager.prepare(input(1))
            fail("Oversized required content must fail")
        } catch (e: LLMException) {
            assertTrue(e.message!!.contains("budget"))
        }
        assertEquals(0, client.summaries)
    }

    @Test fun exhaustedSummaryBudgetStopsBeforeAnyActionAndKeepsHistory() = runBlocking {
        val client = SummaryClient().apply { exhausted = true }
        val manager = ContextManager(task(70), null, client)
        try {
            manager.decide(input(71)) { true }
            fail("A summary exhausting the shared allowance must stop the task")
        } catch (_: ModelBudgetExceededException) { }
        assertEquals(1, client.summaries)
        assertEquals(0, client.actionCalls)
        assertNull(manager.builder.history.checkpoint)
    }

    @Test fun overflowRetriesWithASmallerRequestAndDoesNotEraseRawSteps() = runBlocking {
        val client = SummaryClient().apply { rejectFirstAction = true }
        val task = task(20, 20)
        val manager = ContextManager(task, null, client)
        assertEquals("finish", manager.decide(input(21)) { true }["action_type"])
        assertEquals(2, client.actionCalls)
        assertEquals(1, client.summaries)
        assertTrue(client.actionInputs[1].toString().length < client.actionInputs[0].toString().length)
        assertEquals(20, task.steps.size)
    }

    @Test fun newCompactionUsesNewOriginalEventsAndDoesNotResummarizeOldCheckpoint() = runBlocking {
        val client = SummaryClient()
        val task = task(20, 20)
        val manager = ContextManager(task, null, client)
        manager.prepare(input(21), forceCompact = true)
        val firstCheckpoint = manager.builder.history.checkpoint
        for (step in 21..40) task.steps[step] = TaskState.Step(step, null, null,
            mapOf("action_type" to "note", "note" to "new raw evidence $step".repeat(40)),
            mapOf("execution_status" to "tool"))
        manager.prepare(input(41), forceCompact = true)
        assertEquals(2, client.summaries)
        assertNotEquals(firstCheckpoint, manager.builder.history.checkpoint)
        assertTrue(client.summaryInputs[1].contains("new raw evidence"))
        assertFalse(client.summaryInputs[1].contains("Compared recorded items"))
    }

    @Test fun summariesCannotPromoteAnUnknownActionToConfirmedSuccess() = runBlocking {
        val client = SummaryClient().apply { inventConfirmation = true }
        val task = task(20, 20)
        val manager = ContextManager(task, null, client)
        manager.prepare(input(21), forceCompact = true)
        assertNull(manager.builder.history.checkpoint)
        assertTrue(task.steps.values.all { it.effect?.get("status") == "unknown" })
    }

    @Test fun staleObservationCannotReachActionModelAfterContextPreparation() = runBlocking {
        val client = SummaryClient()
        val manager = ContextManager(task(70), null, client)
        try {
            manager.decide(input(71)) { false }
            fail("Stale observation must stop")
        } catch (e: LLMException) { assertTrue(e.message!!.contains("Observation changed")) }
        assertEquals(0, client.actionCalls)
    }

    @Test fun explicitlyRequestedMemoryPageSurvivesBudgetReduction() = runBlocking {
        val client = SummaryClient()
        val manager = ContextManager(task(70), null, client)
        val observation = input(71).copy(memory = MemoryPacket(recalled = listOf(
            MemoryRecall("requested-method", "Exact requested procedure with source", null))))
        val messages = manager.prepare(observation)
        assertTrue(messages.last().toString().contains("Exact requested procedure with source"))
        assertTrue(messages.last().toString().contains("requested-method"))
    }

    @Test fun failedSegmentWaitsForSeveralNewStepsBeforeTryingAgain() = runBlocking {
        val client = SummaryClient().apply { fail = true }
        val task = task(20, 20)
        val manager = ContextManager(task, null, client)
        manager.prepare(input(21), forceCompact = true)
        for (step in 21..24) {
            task.steps[step] = TaskState.Step(step, null, null,
                mapOf("action_type" to "wait"), mapOf("execution_status" to "tool"))
            manager.prepare(input(step + 1), forceCompact = true)
        }
        assertEquals(1, client.summaries)
        assertNull(manager.builder.history.checkpoint)
        assertEquals(24, task.steps.size)
    }

    @Test fun controlChangeDuringSummaryStopsBeforeCheckpointAndFurtherCalls() = runBlocking {
        var current = true
        val client = SummaryClient().apply { onSummary = { current = false } }
        val manager = ContextManager(task(100, 160), null, client)
        try {
            manager.decide(input(101)) { current }
            fail("Control change must stop further calls")
        } catch (e: LLMException) { assertTrue(e.message!!.contains("Observation changed")) }
        assertEquals(1, client.summaries)
        assertEquals(0, client.actionCalls)
        assertNull(manager.builder.history.checkpoint)
        assertEquals(100L, client.lastCallInfo!!.usage!!.inputTokens)
    }

    @Test fun everySummaryChunkFitsAfterUsageIncreasesTheEstimate() = runBlocking {
        val client = SummaryClient().apply { usageMultiplier = 2 }
        val manager = ContextManager(task(170, 160), null, client)
        try { manager.prepare(input(171)) } catch (_: LLMException) {
            // Required dynamic state may now be over budget; never send an oversized chunk to hide it.
        }
        assertTrue(client.summaries >= 2)
        assertTrue(client.summaryEstimates.first() <= client.contextBudget.usableInputTokens)
        assertTrue(client.summaryEstimates.drop(1).all { it * 2 <= client.contextBudget.usableInputTokens })
    }
}
