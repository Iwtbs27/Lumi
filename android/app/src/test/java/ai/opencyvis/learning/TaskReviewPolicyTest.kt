package ai.opencyvis.learning

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskReviewPolicyTest {
    @Test fun accessAndCaptchaHandoffsAreExcludedEvenWhenKindIsUnknown() {
        assertFalse(TaskReviewPolicy.shouldCapture("agent_handoff", "unknown", "京东需要拖动滑块完成验证"))
        assertFalse(TaskReviewPolicy.shouldCapture("manual_takeover", "unknown", "需要扫码登录"))
        assertFalse(TaskReviewPolicy.shouldCapture("agent_handoff", "protected_app", "页面不可操作"))
        assertTrue(TaskReviewPolicy.shouldCapture("agent_handoff", "gui_difficulty", "连续点击未打开编辑页"))
        // Recording an ordinary takeover leaves the actual learning decision to evidence review.
        assertTrue(TaskReviewPolicy.shouldCapture("manual_takeover", "unknown", "用户手动接管"))
    }

    @Test fun FramesAndRejectedOrCancelledGesturesDoNotCountAsHumanCorrections() {
        val events = listOf(event("capture_unavailable"), event("gesture", injected = false),
            event("gesture", cancelled = true), event("text_edit"))
        assertEquals("no_human_action", TaskReviewPolicy.localSkip(JSONObject(), events)?.category)
        assertNull(TaskReviewPolicy.localSkip(JSONObject(), events + event("gesture")))
        assertEquals("account_access", TaskReviewPolicy.localSkip(JSONObject(),
            events + event("gesture") + event("password_input_omitted"))?.category)
    }

    @Test fun LearningDecisionNeedsDifficultyAndHumanActionEvidence() {
        val seen = setOf("before", "action", "image")
        val good = decision("learn", "gui_difficulty", listOf("before", "action", "image"))
        assertEquals("learn", TaskReviewDecision.parse(good, seen, setOf("action"), setOf("before")).decision)
        assertThrows(IllegalArgumentException::class.java) {
            TaskReviewDecision.parse(decision("learn", "gui_difficulty", listOf("action")), seen, setOf("action"), setOf("before"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskReviewDecision.parse(decision("learn", "captcha", listOf("before", "action")), seen, setOf("action"), setOf("before"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskReviewDecision.parse(decision("learn", "gui_difficulty", listOf("before", "action", "unseen_image")), seen, setOf("action"), setOf("before"))
        }
        assertEquals("skip", TaskReviewDecision.parse(decision("skip", "routine_takeover", emptyList()),
            seen, setOf("action"), setOf("before")).decision)
    }

    @Test fun ContinuationRetainsImmediateRecoveryAndLateOutcomeAcrossALongTask() {
        val steps = JSONArray((1..100).map { step -> JSONObject().put("step", step)
            .put("action", JSONObject().put("action_type", "tap"))
            .put("effect", JSONObject().put("status", if (step == 100) "confirmed_success" else "unknown")) })
        steps.put(JSONObject().put("kind", "task_state").put("step", 100))
        val input = TaskReviewInput.continuation(steps, 20)
        val provided = input.getJSONArray("provided_steps")
        val numbers = (0 until provided.length()).map { provided.getJSONObject(it).getInt("step") }
        assertEquals(80, input.getInt("total_steps"))
        assertEquals(56, input.getInt("omitted_steps"))
        assertEquals(listOf(21, 22, 23, 24), numbers.take(4))
        assertTrue(numbers.any { it in 45..65 })
        assertEquals(100, numbers.last())
        assertTrue(provided.getJSONObject(provided.length() - 1).getString("effect").contains("confirmed_success"))
    }

    @Test fun LaterHumanInterventionDoesNotBecomeEvidenceForAnEarlierCorrection() {
        val steps = JSONArray((10..16).map { step -> JSONObject().put("step", step)
            .put("human_intervention_count", if (step < 14) 1 else 2) })
        val input = TaskReviewInput.continuation(steps, 10, 1)
        val provided = input.getJSONArray("provided_steps")
        assertEquals(listOf(11, 12, 13), (0 until provided.length()).map { provided.getJSONObject(it).getInt("step") })
        assertTrue(input.getBoolean("stopped_at_next_handoff"))
    }

    @Test fun ParserAndCompilerKeepReviewContextAndBoundedAgentExplanation() {
        val thought = "点击新建后页面未变化，找不到下一步入口。".repeat(100)
        val completion = JSONObject().put("outcome", "completed")
            .put("continuation", JSONObject().put("provided_steps", JSONArray().put(JSONObject().put("step", 12))))
        val source = JSONObject().put("automatic_review", true)
            .put("recent_steps", JSONArray().put(JSONObject().put("step", 10).put("thought", thought)))
            .put("task_review", decision("learn", "gui_difficulty", listOf("before", "action")))
            .put("task_completion", completion)
        val session = LearningSession(mode = "correction", goal = "保存笔记", goalSource = "task", displayId = 1,
            sourceJson = source.toString())
        val context = LearningInput.sessionHeader(session).getJSONObject("correction_context")
        val explanation = context.getJSONArray("recent_steps").getJSONObject(0).getString("thought")
        assertTrue(explanation.startsWith(thought.take(1200)))
        assertTrue(explanation.endsWith("[后文未提供]"))
        assertEquals("learn", context.getJSONObject("task_review").getString("decision"))
        assertEquals(completion.toString(), context.getJSONObject("task_completion").toString())
        assertEquals(thought, JSONObject(session.sourceJson).getJSONArray("recent_steps").getJSONObject(0).getString("thought"))
        val archived = JSONObject().put("step", 10).put("action", JSONObject().put("thought", thought).put("action_type", "tap"))
        val compact = TaskReviewInput.step(archived)
        assertTrue(compact.getString("thought").startsWith(thought.take(1200)))
        assertEquals("tap", JSONObject(compact.getString("action")).getString("action_type"))
        assertEquals(thought, archived.getJSONObject("action").getString("thought"))
    }

    private fun event(kind: String, injected: Boolean = true, cancelled: Boolean = false) =
        JSONObject().put("kind", kind).put("details", JSONObject().put("injected", injected).put("cancelled", cancelled))

    private fun decision(decision: String, category: String, refs: List<String>) = JSONObject()
        .put("decision", decision).put("category", category).put("reason", "根据已提供证据判断")
        .put("evidence_ids", JSONArray(refs))
}
