package ai.opencyvis.engine

import ai.opencyvis.memory.MemoryPacket
import org.junit.Assert.*
import org.junit.Test

class ContextPrefixTest {
    private fun TaskState.complete(number: Int) {
        steps[number] = TaskState.Step(number, "old-image-$number", "old-tree-$number",
            decision = mapOf("action_type" to "tap", "x" to number, "y" to 20),
            execution = mapOf("execution_status" to "executed"),
            effect = mapOf("status" to "unknown"))
    }

    private fun ContextBuilder.request(step: Int) = build(
        step, "image-$step", "tree-$step", MemoryPacket(), null, null)

    @Test fun originalTaskIsASeparateFrozenPrefixBeforeHistoryAndState() {
        val task = TaskState("original exact task")
        val builder = ContextBuilder(task, null)
        val first = builder.request(1)
        task.complete(1)
        task.addUserUpdate(2, "supplement", "new constraint")
        val second = builder.request(2)
        assertEquals("[task]\noriginal exact task", first[1]["content"])
        assertEquals(first.take(2), second.take(2))
        assertFalse(first[1].toString().contains("cur_state"))
    }

    @Test fun lateConfirmationAppendsAnEventWithoutRewritingSentHistory() {
        val task = TaskState("task").apply { complete(1) }
        val builder = ContextBuilder(task, null)
        val before = builder.request(2).dropLast(1)
        task.steps.getValue(1).effect = mapOf(
            "status" to "confirmed_success", "evidence" to "visible receipt", "evidence_step" to 2)
        task.complete(2)
        val after = builder.request(3)
        assertEquals(before, after.take(before.size))
        assertTrue(after.drop(before.size).toString().contains("visible receipt"))
        assertTrue(before.toString().contains("unknown"))
    }

    @Test fun recallAndDynamicMemoryCannotRemoveOrRewriteHistory() {
        val task = TaskState("task").apply { complete(1); complete(2) }
        val builder = ContextBuilder(task, null)
        val before = builder.request(3).dropLast(1)
        task.recalledSteps = listOf(1)
        task.memories["price"] = mapOf("value" to "12.35", "source_step" to 1)
        val recalled = builder.build(3, "new-image", "new-tree", MemoryPacket(text = "new memory"), null, null)
        assertEquals(before, recalled.take(before.size))
        task.recalledSteps = emptyList()
        assertEquals(before, builder.request(3).take(before.size))
    }

    @Test fun userCorrectionsAreAppendedOnceAndRemainVisibleInCurrentState() {
        val task = TaskState("task").apply { complete(1) }
        val builder = ContextBuilder(task, null)
        val before = builder.request(2).dropLast(1)
        task.addUserUpdate(2, "supplement", "only blue items")
        val corrected = builder.request(2)
        assertEquals(before, corrected.take(before.size))
        assertTrue(corrected.drop(before.size).dropLast(1).toString().contains("only blue items"))
        assertTrue(corrected.last().toString().contains("only blue items"))
        assertEquals(corrected, builder.request(2))
    }
}
