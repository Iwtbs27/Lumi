package ai.opencyvis.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.TaskState
import ai.opencyvis.execution.OperationJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
class MemorySessionTest {
    @get:Rule val operationFiles = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var manager: MemoryManager
    private lateinit var task: TaskState
    private lateinit var store: TaskRunStore
    private lateinit var session: MemorySession

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric resets SQLite between cases, but not our application singleton.
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        database = AppDatabase.getInstance(context)
        withContext(Dispatchers.IO) { database.clearAllTables() }
        manager = MemoryManager(context)
        task = TaskState("保存笔记")
        store = TaskRunStore(context, instruction = task.instruction)
        session = manager.session(task, store)
    }

    @After fun close() {
        if (::store.isInitialized) store.finish("completed")
        database.close()
    }

    private suspend fun memory() = manager.repository.saveExplicit("保存笔记", "按返回后检查列表中的笔记")

    private suspend fun action(step: Int, executed: Boolean = true, item: MemoryItem? = null) {
        val decision = mutableMapOf<String, Any?>("action_type" to "key_event", "key" to "back")
        if (item != null) decision["memory_use"] = listOf(mapOf("id" to item.id, "revision" to item.revision))
        task.steps[step] = TaskState.Step(step, null, null, decision)
        session.prepare(step, null)
        session.respond(step, decision)
        task.steps.getValue(step).execution = mapOf("execution_status" to if (executed) "executed" else "blocked")
        session.checkpoint(step)
    }

    private suspend fun feedback(item: MemoryItem, step: Int = 2, revision: Int = item.revision) {
        task.steps[step] = TaskState.Step(step, null, null)
        session.prepare(step, null)
        session.respond(step, mapOf("action_type" to "finish", "memory_feedback" to listOf(mapOf(
            "id" to item.id, "revision" to revision, "applied_step" to 1, "outcome" to "success",
            "evidence_step" to 2, "evidence" to "笔记列表中出现新建的笔记", "cause" to "unknown"
        ))))
        session.checkpoint(step)
    }

    @Test fun feedbackAfterUseDoesNotRequirePriorSelectionAndDoesNotDoubleCount() = runBlocking {
        val item = memory()
        action(1)
        assertFalse(manager.repository.runUsages(store.runId).any { it.event == "selected" })
        feedback(item)
        feedback(item, step = 3)
        val usages = manager.repository.runUsages(store.runId)
        assertEquals(1, usages.count { it.event == "applied" })
        val result = usages.single { it.event == "outcome" }
        assertEquals("success", result.outcome)
        assertEquals(1, result.step)
        assertEquals(2, result.evidenceStep)
        assertEquals("memory_feedback", JSONObject(result.detailsJson).getString("source"))
        assertEquals("model_observed", manager.repository.getEvidence(store.runId, 2).single().author)
    }

    @Test fun feedbackCannotUseMemoryFirstSuppliedAfterTheAction() = runBlocking {
        action(1)
        val item = memory()
        feedback(item)
        assertFalse(manager.repository.runUsages(store.runId).any { it.event in setOf("applied", "outcome") })
    }

    @Test fun feedbackCannotValidateBlockedActionOrWrongRevision() = runBlocking {
        val item = memory()
        action(1, executed = false)
        feedback(item)
        assertFalse(manager.repository.runUsages(store.runId).any { it.event == "outcome" })
        task.steps.getValue(1).execution = mapOf("execution_status" to "executed")
        feedback(item, step = 3, revision = item.revision + 1)
        assertFalse(manager.repository.runUsages(store.runId).any { it.event in setOf("applied", "outcome") })
    }

    @Test fun injectedMemoryAloneDoesNotBecomeAReportedSuccess() = runBlocking {
        memory()
        action(1)
        task.steps[2] = TaskState.Step(2, null, null)
        session.prepare(2, null)
        session.respond(2, mapOf("action_type" to "finish", "previous_action_result" to mapOf(
            "step" to 1, "status" to "confirmed_success", "evidence" to "笔记列表可见"
        )))
        assertFalse(manager.repository.runUsages(store.runId).any { it.event in setOf("applied", "outcome") })
    }

    @Test fun explicitFeedbackReplacesAutomaticActionOutcome() = runBlocking {
        val item = memory()
        action(1, item = item)
        task.steps[2] = TaskState.Step(2, null, null)
        session.respond(2, mapOf("action_type" to "wait", "previous_action_result" to mapOf(
            "step" to 1, "status" to "confirmed_success", "evidence" to "返回列表"
        )))
        val automatic = manager.repository.runUsages(store.runId).single { it.event == "outcome" }
        assertEquals("previous_action_result", JSONObject(automatic.detailsJson).getString("source"))
        feedback(item)
        val explicit = manager.repository.runUsages(store.runId).single { it.event == "outcome" }
        assertEquals("memory_feedback", JSONObject(explicit.detailsJson).getString("source"))
    }

    @Test fun laterFeedbackAttachesToAnAlreadyArchivedObservation() = runBlocking {
        val item = memory()
        action(1)
        task.steps[2] = TaskState.Step(2, null, null, mapOf("action_type" to "wait"))
        session.checkpoint(2)
        assertEquals("model_report", manager.repository.getEvidence(store.runId, 2).single().author)
        feedback(item, step = 3)
        assertEquals("model_observed", manager.repository.getEvidence(store.runId, 2).single().author)
    }

    @Test fun laterAutomaticResultDoesNotEraseExplicitMemoryFeedback() = runBlocking {
        val item = memory()
        action(1)
        feedback(item)
        task.steps[3] = TaskState.Step(3, null, null)
        session.respond(3, mapOf("action_type" to "finish", "previous_action_result" to mapOf(
            "step" to 1, "status" to "unknown", "evidence" to "当前页面已离开笔记列表"
        )))
        val result = manager.repository.runUsages(store.runId).single { it.event == "outcome" }
        assertEquals("memory_feedback", JSONObject(result.detailsJson).getString("source"))
        assertEquals("success", result.outcome)
        assertEquals(2, result.evidenceStep)
    }

    @Test fun humanConfirmationIsVisibleInTheRetrievedCard() = runBlocking {
        val item = memory()
        manager.repository.confirmAndEnable(item.id, item.revision)
        val packet = session.prepare(1, null)
        assertTrue(packet.text.contains("\"user_confirmed\":true"))
        assertEquals(1, packet.items.size)
    }

    @Test fun verifiedProgramOperationsKeepDistinctOutcomesInsideOneModelStep() = runBlocking {
        val item = MemoryItem(id = "program-method", type = "procedure", title = "Save note", summary = "Fill then save",
            status = "active", bodyJson = """{"goal":"Save note","parameters":[],"preconditions":[],"nodes":[
                {"node_id":"fill","intent":"Fill fields","expected":"Fields read back","next":"save"},
                {"node_id":"save","intent":"Save","expected":"Saved note reads back","next":""}],
                "completion":"Note saved"}""")
        withContext(Dispatchers.IO) { database.memoryDao().put(item) }
        task.steps[1] = TaskState.Step(1, null, null, mapOf("action_type" to "run_program"))
        session.prepare(1, null)
        session.respond(1, mapOf("action_type" to "run_program", "procedure_update" to mapOf(
            "op" to "start", "id" to item.id, "revision" to item.revision)))
        val journal = OperationJournal(operationFiles.newFolder())
        val operations = List(3) { index ->
            journal.begin(store.runId, 1, "execution", "gui.set_text", JSONObject().put("field", index)).also {
                journal.finish(it, "succeeded", JSONObject().put("verified", true).put("text", "Readback $index"))
            }
        }
        val mappings = listOf(ProcedureOperationMapping("fill", operations.take(2)),
            ProcedureOperationMapping("save", operations.takeLast(1)))
        session.applyVerifiedOperations(1, "execution", mappings, journal)
        session.applyVerifiedOperations(1, "execution", mappings, journal)
        session.checkpoint(1)
        assertEquals("completed", task.procedureExecution["status"])
        val outcomes = manager.repository.runUsages(store.runId).filter { it.event == "outcome" }
        assertEquals(3, outcomes.size)
        assertEquals(3, outcomes.map { it.id }.distinct().size)
        assertEquals(operations.toSet(), outcomes.map { JSONObject(it.detailsJson).getString("operation_id") }.toSet())
        outcomes.forEach { usage ->
            assertEquals(1, usage.step)
            assertEquals(1, usage.evidenceStep)
            val details = JSONObject(usage.detailsJson)
            assertEquals("program_operation", details.getString("source"))
            assertEquals("host_verified", details.getString("evidence_author"))
            assertTrue(details.getBoolean("independent_execution"))
            assertTrue(details.getLong("finished_sequence") > details.getLong("issued_sequence"))
        }
        val proof = manager.repository.getEvidence(store.runId, 1).filter { it.author == "host_verified" }
        assertEquals(3, proof.size)
        assertEquals(operations.toSet(), proof.map { JSONObject(it.text).getString("operation_id") }.toSet())
    }

    @Test fun resumedEpisodeDoesNotClaimThePreviousRunsActionsAsNewEvidence() = runBlocking {
        action(1)
        task.memories["exact"] = mapOf("value" to "previously collected", "source_step" to 1, "recorded_step" to 1)
        val checkpoint = task.exportCheckpoint(store.runId, 2)
        store.finish("interrupted")
        val resumed = TaskState.restore(checkpoint)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val newStore = TaskRunStore(context, instruction = resumed.instruction, resume = checkpoint)
        try {
            resumed.steps[2] = TaskState.Step(2, null, null, mapOf("action_type" to "wait"))
            MemoryEpisodeRecorder(manager, resumed, newStore).checkpoint(2)
            val episode = requireNotNull(manager.repository.getEpisode(newStore.runId))
            val rows = org.json.JSONArray(episode.segmentsJson)
            assertEquals(2, rows.length()) // One new action and the authoritative state.
            assertEquals(2, rows.getJSONObject(0).getInt("step"))
            assertEquals("previously collected", rows.getJSONObject(1).getJSONObject("task_memory")
                .getJSONObject("exact").getString("value"))
            assertTrue(manager.repository.getEvidence(newStore.runId, 1).isEmpty())
        } finally {
            newStore.finish("completed")
        }
    }
}
