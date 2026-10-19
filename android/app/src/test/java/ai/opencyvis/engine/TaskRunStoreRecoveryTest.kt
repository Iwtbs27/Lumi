package ai.opencyvis.engine

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class TaskRunStoreRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context() = mock<Context>().also { whenever(it.filesDir).thenReturn(temporary.root) }

    @Test fun newRunReadsOldSourcesAndPreservesCheckpointInbox() {
        val context = context()
        val first = TaskRunStore(context, instruction = "Compare", taskId = 42)
        val original = "Exact item 12.3500\n".repeat(100)
        val reference = first.saveContextArtifact(original, 1, "prices")
        first.recordEvent(1, "execution", mapOf("execution_status" to "issued"))
        val task = TaskState("Compare").apply {
            steps[1] = TaskState.Step(1, null, null, mapOf("action_type" to "tap", "x" to 2, "y" to 3),
                mapOf("execution_status" to "issued"), mapOf("status" to "unknown"))
        }
        first.saveCheckpoint(task.exportCheckpoint(first.runId, 2))
        first.updatePendingInputs(listOf("Use exact units"), "Blue")
        first.finish("interrupted")
        val checkpoint = requireNotNull(TaskRunStore.readCheckpoint(context, first.runId))
        val resumed = TaskRunStore(context, instruction = "Compare", taskId = 42, resume = checkpoint)
        try {
            assertNotEquals(first.runId, resumed.runId)
            assertEquals(listOf("Use exact units"), checkpoint.pendingUserInputs)
            assertEquals("Blue", checkpoint.pendingUserAnswer)
            val page = resumed.readContextArtifact(reference.artifactRef, limit = 90)
            assertEquals(original, page.text + resumed.readContextArtifact(page.reference, page.nextOffset!!, original.length).text)
            assertEquals(first.readStepDetails(1), resumed.readStepDetails(1))
            val details = requireNotNull(resumed.readStepDetailsPage(1, limit = 20))
            assertEquals(first.readStepDetails(1), details.text + resumed.readContextArtifact(details.reference,
                details.nextOffset!!, 4800).text)
            assertFalse(File(temporary.root, "agent_runs/${resumed.runId}/steps/1").exists())
        } finally { resumed.finish("completed"); TaskRunStore.releaseTaskRetention(context, 42) }
    }

    @Test fun interruptedEvidenceIsRetainedUntilItsTaskReleasesIt() {
        val context = context()
        val store = TaskRunStore(context, instruction = "Compare", taskId = 42)
        store.finish("interrupted")
        val directory = File(temporary.root, "agent_runs/${store.runId}")
        val metadata = File(directory, "run.json")
        metadata.writeText(JSONObject(metadata.readText()).put("finished_at", 1L).toString())
        TaskRunStore.markInterruptedRuns(context)
        assertTrue(directory.exists())
        TaskRunStore.releaseTaskRetention(context, 42)
        assertFalse(directory.exists())
    }

    @Test fun durableTraceAfterCheckpointRestoresExecutionAndLaterReadBack() {
        val context = context()
        val store = TaskRunStore(context, instruction = "Enter price", taskId = 43)
        val task = TaskState("Enter price").apply {
            steps[1] = TaskState.Step(1, null, null, mapOf("action_type" to "type_text", "text" to "12.3500"),
                mapOf("execution_status" to "issued"), mapOf("status" to "unknown"))
        }
        try {
            store.saveCheckpoint(task.exportCheckpoint(store.runId, 2))
            store.recordEvent(1, "execution", mapOf("action_type" to "type_text", "execution_status" to "executed"))
            val unknown = TaskState.restore(requireNotNull(TaskRunStore.readCheckpoint(context, store.runId)))
            assertEquals("executed", unknown.steps.getValue(1).execution["execution_status"])
            assertEquals("unknown", unknown.steps.getValue(1).effect!!["status"])
            store.recordEvent(2, "effect", mapOf("target_step" to 1, "status" to "confirmed_success",
                "evidence" to "Current screen contains 12.3500", "evidence_step" to 2))
            val saved = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
            assertEquals(3, saved.nextStep)
            assertEquals("confirmed_success", TaskState.restore(saved).steps.getValue(1).effect!!["status"])
        } finally { store.finish("completed"); TaskRunStore.releaseTaskRetention(context, 43) }
    }

    @Test fun takeoverPatchPreservesWaitingQuestionAndUnfoldedTrace() {
        val context = context()
        val store = TaskRunStore(context, instruction = "Enter price", taskId = 44)
        val task = TaskState("Enter price").apply {
            steps[1] = TaskState.Step(1, null, null, mapOf("action_type" to "type_text", "text" to "12.3500"),
                mapOf("execution_status" to "issued"), mapOf("status" to "unknown"))
        }
        try {
            store.saveCheckpoint(task.exportCheckpoint(store.runId, 2,
                pendingUserInputs = listOf("Keep the original units"),
                waitingContext = mapOf("kind" to "ask_user", "question" to "Which currency?")))
            // A state event can be durable while its full checkpoint has not yet been saved.
            store.recordEvent(1, "state", task.snapshot())
            store.recordEvent(1, "execution", mapOf("action_type" to "type_text", "execution_status" to "executed"))
            store.updateHumanIntervention(1)
            val recovered = requireNotNull(TaskRunStore.readCheckpoint(context, store.runId))
            val restored = TaskState.restore(recovered)
            assertEquals(1, restored.humanInterventionCount)
            assertEquals("executed", restored.steps.getValue(1).execution["execution_status"])
            assertEquals("Which currency?", recovered.waitingContext["question"])
            assertEquals(true, recovered.waitingContext["human_control_granted"])
            assertEquals(listOf("Keep the original units"), recovered.pendingUserInputs)
        } finally { store.finish("interrupted"); TaskRunStore.releaseTaskRetention(context, 44) }
    }
}
