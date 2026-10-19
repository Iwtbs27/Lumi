package ai.opencyvis.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskCheckpointTest {
    @Test fun roundTripKeepsAuthorityAndIssuedEffectsWithoutReplayingResponses() {
        val task = TaskState("Collect exact prices").apply {
            steps[7] = TaskState.Step(7, null, null,
                mapOf("action_type" to "type_text", "text" to "12.3500"),
                mapOf("execution_status" to "issued"), mapOf("status" to "unknown"))
            applyResponse(mapOf("thought" to "Keep current goal", "action_type" to "update_plan",
                "memory_update" to listOf(mapOf("op" to "upsert", "key" to "price", "value" to "12.3500", "source_step" to 7)),
                "plan" to listOf(mapOf("id" to "collect", "goal" to "Collect", "done_when" to "Read back", "status" to "active")),
                "current_goal_id" to "collect", "plan_reason" to "Exact data needed"), 8)
            addUserUpdate(8, "supplement", "Blue items only")
            humanInterventionCount = 2
            procedureExecution = mapOf("id" to "saved-method", "revision" to 3,
                "node_id" to "read_back", "pending_action_step" to 7, "status" to "awaiting_result")
        }
        val encoded = task.exportCheckpoint("first-run", 8, pendingUserInputs = listOf("Keep units"),
            pendingUserAnswer = "Alice", waitingContext = mapOf("kind" to "ask_user", "question" to "Which person?"))
        val checkpoint = TaskCheckpoint.fromJson(JSONObject(encoded.toJson().toString()))
        val restored = TaskState.restore(checkpoint)

        assertEquals(task.snapshot() - "memory_context", restored.snapshot() - "memory_context")
        assertEquals(true, restored.memoryContext["possibly_stale"])
        assertEquals(task.userUpdates, restored.userUpdates)
        assertEquals(8, restored.runStartStep)
        assertEquals("first-run", restored.stepSources[7])
        assertEquals(listOf("Keep units"), checkpoint.pendingUserInputs)
        assertEquals("Alice", checkpoint.pendingUserAnswer)
        assertEquals("Which person?", checkpoint.waitingContext["question"])
        val pending = restored.snapshot()["pending_results"] as List<*>
        assertEquals(1, pending.size)
        restored.steps[8] = TaskState.Step(8, null, null)
        restored.applyResponse(mapOf("thought" to "Observed saved value", "action_type" to "wait",
            "memory_update" to emptyList<Any>(), "previous_action_result" to mapOf("step" to 7,
                "status" to "confirmed_success", "evidence" to "Fresh screen contains 12.3500")), 8)
        assertTrue((restored.snapshot()["pending_results"] as List<*>).isEmpty())
    }

    @Test fun exportedCheckpointIsIndependentOfLaterTaskChanges() {
        val task = TaskState("Compare").apply { memories["price"] = mapOf("value" to "12.35", "source_step" to 1) }
        val checkpoint = task.exportCheckpoint("first-run", 1)
        task.memories["price"] = mapOf("value" to "99.00", "source_step" to 2)
        assertEquals("12.35", TaskState.restore(checkpoint).memories.getValue("price")["value"])
    }
}
