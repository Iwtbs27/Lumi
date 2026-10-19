package ai.opencyvis.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryConsolidationInputTest {
    @Test fun acceptedFeedbackKeepsItsMemoryAndBothObservationsTogether() {
        val memory = MemoryItem(id = "old", title = "保存入口", summary = "返回保存", revision = 2,
            bodyJson = MemoryBodies.fact("返回后需确认列表出现笔记", "app_knowledge"))
        val segments = JSONArray().put(JSONObject().put("step", 3)
            .put("action", JSONObject().put("action_type", "press_back"))
            .put("execution", JSONObject().put("execution_status", "executed")))
            .put(JSONObject().put("step", 40).put("memory_context", JSONObject().put("page", "列表已有新笔记")))
        val outcome = MemoryUsage(memoryId = "old", revision = 2, runId = "run", step = 3,
            event = "outcome", outcome = "success", evidenceStep = 40,
            detailsJson = """{"source":"memory_feedback","evidence":"新笔记出现在列表","scope":"action"}""")
        val snapshots = mapOf(("old" to 2) to JSONObject(MemoryBodies.snapshot(memory)))
        val reviews = MemoryConsolidationInput.feedbackReviews(segments,
            listOf(outcome.copy(event = "injected"), outcome, outcome), snapshots)
        assertEquals(1, reviews.length())
        val review = reviews.getJSONObject(0)
        assertEquals("old", review.getJSONObject("memory").getString("id"))
        assertEquals(2, review.getJSONObject("memory").getInt("revision"))
        assertEquals(1, review.getJSONArray("accepted_feedback").length())
        val evidence = review.getJSONArray("observations")
        assertEquals(listOf(3, 40), (0 until evidence.length()).map { evidence.getJSONObject(it).getInt("step") })
        assertEquals("memory_feedback", review.getJSONArray("accepted_feedback").getJSONObject(0)
            .getJSONObject("details").getString("source"))
        val fullInput = JSONArray().put(review)
        for (step in 1..80) fullInput.put(JSONObject().put("step", step)
            .put("execution", JSONObject().put("execution_status", "blocked")
                .put("feedback", "页面未出现预期结果。".repeat(80))))
        val batches = MemoryConsolidationInput.batches(fullInput, "保存比较结果")
        assertTrue(batches.size > 1)
        assertEquals(evidence.toString(), batches.first().getJSONObject(0).getJSONArray("observations").toString())
    }

    @Test fun batchesKeepEverySourceCorrectionAndOversizedTaskStateWithinBudget() {
        val correction = "本次改用另一个笔记应用，不修改长期偏好。"
        val instruction = "收集商品信息并保存比较结果"
        val segments = JSONArray()
        for (step in 1..80) {
            segments.put(JSONObject().put("step", step)
                .put("action", JSONObject().put("action_type", "tap").put("thought", "观察当前页面。".repeat(50)))
                .put("execution", JSONObject().put("execution_status", "blocked").put("feedback", "失败原因 $step ".repeat(40)))
                .put("effect", JSONObject().put("status", "confirmed_failure").put("evidence", "入口被弹窗遮挡")
                    .put("evidence_step", step + 1))
                .put("user_updates", if (step == 23) JSONArray().put(JSONObject().put("step", step).put("text", correction)) else JSONArray()))
        }
        val largeFact = "已保存的历史结果。".repeat(4000)
        segments.put(JSONObject().put("kind", "task_state").put("step", 80)
            .put("task_memory", JSONObject().put("result", JSONObject().put("value", largeFact))))
        val batches = MemoryConsolidationInput.batches(segments, instruction)
        assertTrue(batches.size > 1)
        val flattened = batches.flatMap { batch -> (0 until batch.length()).map { batch.getJSONObject(it) } }
        assertEquals((1..80).toSet(), flattened.filter { !it.has("kind") }.map { it.getInt("step") }.toSet())
        assertTrue(flattened.any { it.optJSONArray("user_updates")?.toString()?.contains(correction) == true })
        val fragments = flattened.filter { it.optString("kind") == "fragment" }.sortedBy { it.getInt("part") }
        assertTrue(fragments.isNotEmpty())
        val restored = JSONObject(fragments.joinToString("") { it.getString("text") })
        assertEquals(largeFact, restored.getJSONObject("task_memory").getJSONObject("result").getString("value"))
        for (batch in batches) assertTrue(
            batch.toString().length + MemoryConsolidationSchema.prompt.length +
                JSONObject.quote(instruction).length + 4000 + 500 <= 20_000
        )
    }
}
