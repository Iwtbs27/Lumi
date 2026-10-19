package ai.opencyvis.execution

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedWriteRetainsTheRealIdAndIsNeverPromotedToSuccess() {
        val directory = temporary.newFolder()
        val original = OperationJournal(directory)
        val id = original.begin("run-1", 8, "exec-1", "calendar.create", JSONObject().put("title", "课程"))
        original.effect(id, JSONObject().put("event_id", 53))
        val recovered = OperationJournal(directory)
        val unknown = recovered.recover().single()
        assertEquals(id, unknown.getString("operation_id"))
        assertEquals("unknown", unknown.getString("status"))
        assertEquals(53, unknown.getJSONObject("effect").getInt("event_id"))
        assertEquals(8, unknown.getInt("step"))
        assertEquals("run-1", unknown.getString("run_id"))
        assertEquals("unknown", recovered.get(id).getString("status"))
    }

    @Test fun verifiedStagesKeepOrderedOperationEvidenceAcrossRestart() {
        val directory = temporary.newFolder()
        val journal = OperationJournal(directory)
        val ids = (1..2).map { index ->
            journal.begin("run", 4, "exec", "gui.set_text", JSONObject().put("text", "$index")).also {
                journal.finish(it, "succeeded", JSONObject().put("verified", true).put("text", "$index"))
            }
        }
        journal.checkpoint("exec", "fields_filled", ids, JSONObject().put("next_stage", "save"))
        val restored = OperationJournal(directory)
        assertTrue(restored.recover().isEmpty())
        val stage = restored.checkpoints("exec").single()
        assertEquals(ids, (0 until stage.getJSONArray("operation_ids").length()).map {
            stage.getJSONArray("operation_ids").getString(it)
        })
        assertEquals("save", stage.getJSONObject("state").getString("next_stage"))
        assertEquals(2, restored.forExecution("exec").size)
    }

    @Test fun unconfirmedOrForeignEffectsCannotBecomeACompletedStage() {
        val journal = OperationJournal(temporary.newFolder())
        val pending = journal.begin("run", 1, "exec", "files.export", JSONObject())
        reject { journal.checkpoint("exec", "saved", listOf(pending), JSONObject()) }
        journal.finish(pending, "succeeded", JSONObject().put("verified", true))
        reject { journal.checkpoint("another", "saved", listOf(pending), JSONObject()) }
    }

    @Test fun aFailedReadbackPreservesDeliveredEffectForLaterInspection() {
        val journal = OperationJournal(temporary.newFolder())
        val id = journal.begin("run", 2, "exec", "calendar.update", JSONObject().put("event_id", 9))
        journal.effect(id, JSONObject().put("event_id", 9).put("updated_rows", 1))
        journal.finish(id, "unknown", JSONObject().put("error", "provider read interrupted"))
        val saved = journal.get(id)
        assertEquals(9, saved.getJSONObject("effect").getInt("event_id"))
        assertEquals("unknown", saved.getString("status"))
        reject { journal.checkpoint("exec", "updated", listOf(id), JSONObject()) }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Unverified stage was accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
