package ai.opencyvis.memory

import ai.opencyvis.engine.TaskState
import org.junit.Assert.*
import org.junit.Test

class ProcedureExecutionTest {
    private fun task() = TaskState("保存本次内容").apply {
        for (step in 1..4) steps[step] = TaskState.Step(step, null, null)
        memories["body"] = mapOf("value" to "旧内容", "recorded_step" to 1, "source_step" to 1)
    }
    private fun method() = MemoryItem(id = "save", type = "procedure", title = "保存", summary = "保存笔记",
        status = "active", bodyJson = """{"goal":"保存","parameters":[{"name":"body","required":true}],
            "preconditions":[],"nodes":[{"node_id":"input","intent":"输入","expected":"显示正文","next":"save"},
            {"node_id":"save","intent":"保存","expected":"已保存","next":""}],"completion":"正文保存"}""")
    private fun start() = mapOf("op" to "start", "revision" to 1, "node_id" to "input",
        "bindings" to listOf(mapOf("name" to "body", "value" to "旧内容", "task_memory_key" to "body")))

    @Test fun changedFactCannotBeAcknowledgedWithoutRebindingIt() {
        val task = task()
        val runner = ProcedureExecution(task)
        runner.apply(start(), 1, method())
        task.memories["body"] = mapOf("value" to "新内容", "recorded_step" to 2, "source_step" to 2)
        runner.refresh(method())
        assertEquals("needs_rebind", task.procedureExecution["status"])
        assertThrows(IllegalArgumentException::class.java) {
            runner.apply(mapOf("op" to "rebind", "bindings" to emptyList<Any>()), 2)
        }
        assertNull(runner.activeItem())
        runner.apply(mapOf("op" to "rebind", "bindings" to listOf(mapOf("name" to "body", "value" to "新内容", "task_memory_key" to "body"))), 2)
        assertEquals("ready", task.procedureExecution["status"])
    }

    @Test fun anExecutedActionNeedsALaterObservationBeforeAdvancing() {
        val task = task()
        val runner = ProcedureExecution(task)
        runner.apply(start(), 1, method())
        runner.executed(2, "save")
        assertThrows(IllegalArgumentException::class.java) {
            runner.apply(mapOf("op" to "advance", "node_id" to "save", "evidence_step" to 2, "evidence" to "刚发送输入"), 2)
        }
        runner.apply(mapOf("op" to "advance", "node_id" to "save", "evidence_step" to 3, "evidence" to "正文已显示"), 3)
        assertEquals("save", task.procedureExecution["node_id"])
    }

    @Test fun disablingAProcedureStopsItsCurrentExecution() {
        val task = task()
        val runner = ProcedureExecution(task)
        runner.apply(start(), 1, method())
        runner.refresh(method().copy(status = "disabled", revision = 2))
        assertNull(runner.activeItem())
        assertEquals("aborted", task.procedureExecution["status"])
    }

    @Test fun completionCannotSkipADeclaredRemainingNode() {
        val runner = ProcedureExecution(task())
        runner.apply(start(), 1, method())
        assertThrows(IllegalArgumentException::class.java) {
            runner.apply(mapOf("op" to "complete", "evidence_step" to 2, "evidence" to "看到文字"), 2)
        }
    }

    @Test fun restoredCursorWaitsForObservationAndPreservesParameterRevision() {
        val task = task()
        ProcedureExecution(task).apply {
            apply(start(), 1, method())
            executed(2, "save")
        }
        val restored = ProcedureExecution(task)
        restored.restore(method())
        assertEquals("awaiting_result", task.procedureExecution["status"])
        assertThrows(IllegalArgumentException::class.java) {
            restored.apply(mapOf("op" to "advance", "node_id" to "save", "evidence_step" to 2, "evidence" to "old page"), 2)
        }
        restored.apply(mapOf("op" to "advance", "node_id" to "save", "evidence_step" to 3, "evidence" to "fresh page shows text"), 3)
        task.userUpdates.add(mapOf("step" to 4, "text" to "new requirement"))
        restored.refresh(method())
        assertEquals("needs_rebind", task.procedureExecution["status"])
    }

    @Test fun changedOrDeletedProcedureCannotResumeWithOldCursor() {
        for (current in listOf(null, method().copy(revision = 2))) {
            val task = task()
            ProcedureExecution(task).apply(start(), 1, method())
            val restored = ProcedureExecution(task)
            restored.restore(current)
            assertNull(restored.activeItem())
            assertEquals("aborted", task.procedureExecution["status"])
            assertEquals("save", task.procedureExecution["id"])
        }
    }

    @Test fun resumedRunDoesNotTurnOldNodesIntoIndependentValidation() {
        val original = task()
        ProcedureExecution(original).apply {
            apply(start(), 1, method())
            executed(1, "save")
            apply(mapOf("op" to "advance", "node_id" to "save", "evidence_step" to 2, "evidence" to "text visible"), 2)
        }
        val resumed = TaskState.restore(original.exportCheckpoint("old-run", 5))
        resumed.steps[5] = TaskState.Step(5, null, null)
        resumed.steps[6] = TaskState.Step(6, null, null)
        val runner = ProcedureExecution(resumed)
        runner.restore(method())
        assertEquals(0, runner.supportScope().getJSONArray("nodes").length())
        runner.executed(5, "save")
        runner.apply(mapOf("op" to "complete", "evidence_step" to 6, "evidence" to "note visible"), 6)
        assertEquals("save", runner.supportScope().getJSONArray("nodes").getString(0))
        assertFalse(runner.supportScope().getBoolean("whole_procedure"))
    }

    @Test fun observingAnOldActionAfterResumeIsNotAnotherIndependentExecution() {
        val original = task()
        ProcedureExecution(original).apply {
            apply(start() + ("node_id" to "save"), 1, method())
            executed(2, "save")
        }
        val resumed = TaskState.restore(original.exportCheckpoint("old-run", 5))
        resumed.steps[5] = TaskState.Step(5, null, null)
        val runner = ProcedureExecution(resumed)
        runner.restore(method())
        runner.apply(mapOf("op" to "complete", "evidence_step" to 5, "evidence" to "old save is visible"), 5)
        assertEquals("completed", resumed.procedureExecution["status"])
        assertEquals(0, runner.supportScope().getJSONArray("nodes").length())
        assertFalse(runner.supportScope().getBoolean("whole_procedure"))
    }
}
