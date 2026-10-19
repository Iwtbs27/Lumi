package ai.opencyvis.llm

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskModelBudgetTest {
    @Test fun concurrentOwnersCannotBothReserveTheLastBalance() {
        val budget = TaskModelBudget(1_000, 200)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map { owner -> executor.submit<Boolean> {
                start.await()
                try {
                    budget.reserve("child-$owner", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 400, 200)
                    true
                } catch (_: ModelBudgetExceededException) { false }
            } }
            start.countDown()
            assertEquals(1, requests.count { it.get(3, TimeUnit.SECONDS) })
            assertEquals(600L, budget.snapshot().pendingReservedTokens)
        } finally { executor.shutdownNow() }
    }

    @Test fun onlyOneParentFinalizationCanSpendItsDedicatedReserve() {
        val budget = TaskModelBudget(1_000, 300)
        budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 500, 200)
        assertThrows(ModelBudgetExceededException::class.java) {
            budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.FINALIZATION, 100, 100)
        }
        val summary = budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.FINALIZATION, 200, 100)
        assertTrue(budget.snapshot().finalizationUsed)
        assertEquals(1_000L, budget.snapshot().pendingReservedTokens)
        assertThrows(ModelBudgetExceededException::class.java) {
            budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.FINALIZATION, 1, 1)
        }
        assertTrue(budget.releaseUnsent(summary.id))
        assertFalse(budget.snapshot().finalizationUsed)
    }

    @Test fun finalizationReserveIncludesItsInputAndDoesNotBorrowRegularBalance() {
        val budget = TaskModelBudget(10_000, 500)
        assertThrows(ModelBudgetExceededException::class.java) {
            budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.FINALIZATION, 450, 100)
        }
        assertEquals(0L, budget.snapshot().pendingReservedTokens)
    }

    @Test fun completeUsageSettlesOnceAndReturnsOnlyTheVerifiedRemainder() {
        val budget = TaskModelBudget(1_000, 100)
        val request = budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.CONTEXT_SUMMARY, 300, 200)
        assertTrue(budget.settle(request.id, LlmUsage(100, 30, 80), usageComplete = true))
        assertFalse(budget.settle(request.id, LlmUsage(100, 30, 80), usageComplete = true))
        assertEquals(100L, budget.snapshot().knownInputTokens)
        assertEquals(30L, budget.snapshot().knownOutputTokens)
        assertEquals(0L, budget.snapshot().pendingReservedTokens)
        assertEquals(0L, budget.snapshot().unknownReservedTokens)
        // Cached input is still part of input usage; it is not deducted from the task token budget.
        budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 700, 70)
    }

    @Test fun absentPartialAndNonFinalUsageKeepTheirReservations() {
        for ((usage, complete) in listOf(
            null to false,
            LlmUsage(50, null) to true,
            LlmUsage(50, 1) to false
        )) {
            val budget = TaskModelBudget(1_000, 100)
            val request = budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 300, 200)
            budget.settle(request.id, usage, complete)
            assertEquals(500L, budget.snapshot().unknownReservedTokens)
            assertEquals(0L, budget.snapshot().pendingReservedTokens)
            assertThrows(ModelBudgetExceededException::class.java) {
                budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 400, 1)
            }
        }
    }

    @Test fun actualUsageAboveEstimateIsRecordedWithoutClamping() {
        val budget = TaskModelBudget(1_000, 100)
        val request = budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 100, 100)
        budget.settle(request.id, LlmUsage(850, 100), true)
        assertEquals(850L, budget.snapshot().knownInputTokens)
        assertEquals(100L, budget.snapshot().knownOutputTokens)
        assertThrows(ModelBudgetExceededException::class.java) {
            budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 1, 1)
        }
    }

    @Test fun restorePreservesLimitsAndRetainsPreviouslyPendingWorkAsUnknown() {
        val budget = TaskModelBudget(1_000, 200)
        val known = budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 100, 100)
        budget.settle(known.id, LlmUsage(50, 20), true)
        val pending = budget.reserve("child", LlmOwnerRole.CHILD, LlmCallPurpose.CHILD_ANALYSIS, 300, 100)
        val saved = TaskModelBudgetSnapshot.fromJson(budget.snapshot().toJson())
        val restored = TaskModelBudget(99_000, 9_000, restored = saved)
        assertEquals(1_000L, restored.snapshot().limitTokens)
        assertEquals(200L, restored.snapshot().finalizationReserveTokens)
        assertEquals(50L, restored.snapshot().knownInputTokens)
        assertEquals(400L, restored.snapshot().unknownReservedTokens)
        assertEquals(0L, restored.snapshot().pendingReservedTokens)
        val next = restored.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 100, 100)
        assertTrue(next.id > pending.id)
    }

    @Test fun persistenceFailurePreventsReservationAndNoSilentInMemoryBudgetIsCreated() {
        var writes = 0
        val budget = TaskModelBudget(1_000, 100, onChanged = {
            writes++
            throw IllegalStateException("storage unavailable")
        })
        assertThrows(IllegalStateException::class.java) {
            budget.reserve("parent", LlmOwnerRole.PARENT, LlmCallPurpose.GENERAL, 100, 100)
        }
        assertEquals(1, writes)
        assertEquals(0L, budget.snapshot().pendingReservedTokens)
    }
}
