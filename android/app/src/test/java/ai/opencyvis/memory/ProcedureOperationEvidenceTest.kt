package ai.opencyvis.memory

import ai.opencyvis.engine.TaskState
import ai.opencyvis.execution.OperationJournal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcedureOperationEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val memory = MemoryItem(id = "save-note", type = "procedure", title = "Save note", summary = "Fill then save",
        status = "active", bodyJson = """{"goal":"Save note","parameters":[],"preconditions":[],"nodes":[
            {"node_id":"fill","intent":"Fill title and body","expected":"Both fields read back","next":"save"},
            {"node_id":"save","intent":"Save","expected":"Saved content reads back","next":""}],
            "completion":"Note saved"}""")
    private fun task() = TaskState("Save a note").apply {
        steps[1] = TaskState.Step(1, null, null)
        steps[2] = TaskState.Step(2, null, null)
    }
    private fun runner(task: TaskState) = ProcedureExecution(task).apply {
        apply(mapOf("op" to "start", "revision" to memory.revision), 1, memory)
    }
    private fun issue(journal: OperationJournal, run: String = "run-a", execution: String = "program-a", step: Int = 2) =
        journal.begin(run, step, execution, "gui.set_text", JSONObject().put("text", "Exact content"))
    private fun finish(journal: OperationJournal, id: String) {
        journal.finish(id, "succeeded", JSONObject().put("verified", true).put("text", "Exact content"))
    }
    private fun verified(journal: OperationJournal, run: String = "run-a", execution: String = "program-a", step: Int = 2) =
        issue(journal, run, execution, step).also { finish(journal, it) }
    @Suppress("UNCHECKED_CAST")
    private fun completed(task: TaskState) = task.procedureExecution.getValue("completed_nodes") as List<Map<String, Any?>>

    @Test fun oneModelStepCanVerifySeveralNodesUsingActualOrderedOperations() {
        val task = task()
        val runner = runner(task)
        val journal = OperationJournal(temporary.newFolder())
        val title = verified(journal)
        val body = verified(journal)
        val save = verified(journal)
        val mappings = listOf(ProcedureOperationMapping("fill", listOf(title, body)),
            ProcedureOperationMapping("save", listOf(save)))
        val accepted = runner.applyVerifiedOperations(2, "run-a", "program-a", mappings, journal)
        assertEquals(listOf("fill", "save"), accepted.map { it.nodeId })
        assertEquals(listOf(title, body), accepted.first().operations.map { it.getString("operation_id") })
        assertTrue(accepted.all { it.independentExecution })
        assertEquals("completed", task.procedureExecution["status"])
        assertEquals(listOf("fill", "save"), completed(task).map { it["node_id"] })
        assertTrue(completed(task).all { (it["applied_step"] as Number).toInt() == 2 })
        assertTrue(runner.supportScope().getBoolean("whole_procedure"))
        assertTrue(runner.applyVerifiedOperations(2, "run-a", "program-a", mappings, journal).isEmpty())
        assertEquals(2, completed(task).size)
    }

    @Test fun anInterruptedSuffixKeepsOnlyTheVerifiedNodePrefix() {
        val task = task()
        val runner = runner(task)
        val directory = temporary.newFolder()
        val journal = OperationJournal(directory)
        val fill = verified(journal)
        val save = issue(journal)
        OperationJournal(directory).recover()
        val accepted = runner.applyVerifiedOperations(2, "run-a", "program-a", listOf(
            ProcedureOperationMapping("fill", listOf(fill)), ProcedureOperationMapping("save", listOf(save))), journal)
        assertEquals(listOf("fill"), accepted.map { it.nodeId })
        assertEquals("ready", task.procedureExecution["status"])
        assertEquals("save", task.procedureExecution["node_id"])
        runner.executed(2, memory.id)
        assertEquals("The outer program action did not execute the save node", "ready", task.procedureExecution["status"])
        assertFalse(runner.supportScope().getBoolean("whole_procedure"))
        assertEquals("unknown", journal.get(save).getString("status"))
    }

    @Test fun operationOwnershipAndVerificationCannotBeClaimedByTheCaller() {
        for ((run, execution) in listOf("old-run" to "program-a", "run-a" to "another-program")) {
            val task = task()
            val runner = runner(task)
            val journal = OperationJournal(temporary.newFolder())
            val operation = verified(journal, run, execution)
            assertThrows(IllegalArgumentException::class.java) {
                runner.applyVerifiedOperations(2, "run-a", "program-a",
                    listOf(ProcedureOperationMapping("fill", listOf(operation))), journal)
            }
            assertTrue(completed(task).isEmpty())
        }
        val task = task()
        val runner = runner(task)
        val journal = OperationJournal(temporary.newFolder())
        val operation = issue(journal)
        journal.finish(operation, "succeeded", JSONObject().put("verified", false))
        assertTrue(runner.applyVerifiedOperations(2, "run-a", "program-a",
            listOf(ProcedureOperationMapping("fill", listOf(operation))), journal).isEmpty())
        assertTrue(completed(task).isEmpty())
    }

    @Test fun theNextNodeMustBeIssuedAfterThePreviousNodeWasVerified() {
        val task = task()
        val runner = runner(task)
        val journal = OperationJournal(temporary.newFolder())
        val fill = issue(journal)
        val save = issue(journal)
        finish(journal, fill)
        finish(journal, save)
        assertThrows(IllegalArgumentException::class.java) {
            runner.applyVerifiedOperations(2, "run-a", "program-a", listOf(
                ProcedureOperationMapping("fill", listOf(fill)), ProcedureOperationMapping("save", listOf(save))), journal)
        }
        assertTrue(completed(task).isEmpty())
    }

    @Test fun restoredOperationsAreNotCountedAsAnotherIndependentRun() {
        val original = task()
        val runner = runner(original)
        val journal = OperationJournal(temporary.newFolder())
        val fill = verified(journal)
        val priorMapping = listOf(ProcedureOperationMapping("fill", listOf(fill)))
        runner.applyVerifiedOperations(2, "run-a", "program-a", priorMapping, journal)
        val resumed = TaskState.restore(original.exportCheckpoint("run-a", 3))
        resumed.steps[3] = TaskState.Step(3, null, null)
        val restored = ProcedureExecution(resumed).apply { restore(memory) }
        assertTrue(restored.applyVerifiedOperations(3, "run-b", "program-b", priorMapping, journal).isEmpty())
        assertEquals(1, completed(resumed).size)
        assertEquals(0, restored.supportScope().getJSONArray("nodes").length())
        val save = verified(journal, "run-b", "program-b", 3)
        restored.applyVerifiedOperations(3, "run-b", "program-b",
            listOf(ProcedureOperationMapping("save", listOf(save))), journal)
        assertEquals("completed", resumed.procedureExecution["status"])
        assertEquals("save", restored.supportScope().getJSONArray("nodes").getString(0))
        assertFalse(restored.supportScope().getBoolean("whole_procedure"))
    }
}
