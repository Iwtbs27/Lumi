package ai.opencyvis.memory

import ai.opencyvis.engine.ContextBuilder
import ai.opencyvis.engine.TaskState
import org.junit.Assert.*
import org.junit.Test

class MemoryContextTest {
    private fun task() = TaskState("比较本次商品").apply {
        for (step in 1..4) steps[step] = TaskState.Step(step, "old-image-$step", "old-tree-$step")
        memories["price"] = mapOf("value" to "本次价格", "source_step" to 4)
    }

    private fun images(messages: List<Map<String, Any>>): List<String> = messages.flatMap { message ->
        (message["content"] as? List<*>)?.mapNotNull { part ->
            ((part as? Map<*, *>)?.get("image_url") as? Map<*, *>)?.get("url") as? String
        }.orEmpty()
    }

    @Test fun normalRequestContainsOnlyCurrentImageAndKeepsTaskFacts() {
        val messages = ContextBuilder(task(), null).build(5, "current-image", "current-tree",
            MemoryPacket(text = "相关方法摘要"), null, null)
        assertEquals(listOf("data:image/jpeg;base64,current-image"), images(messages))
        assertTrue(messages.toString().contains("本次价格"))
        assertTrue(messages.toString().contains("相关方法摘要"))
        assertFalse(messages.toString().contains("old-tree-"))
    }

    @Test fun ExplicitRecallSharesTwoHistoryImageSlotsAndRetainsCurrentImage() {
        val task = task().apply { recalledSteps = listOf(1, 2); recallContent = "image" }
        val packet = MemoryPacket(recalled = listOf(
            MemoryRecall("episode-one", "历史证据", "episode-image-1"),
            MemoryRecall("episode-two", "历史证据", "episode-image-2")))
        val messages = ContextBuilder(task, null).build(5, "current-image", "current-tree", packet, null, null)
        assertEquals(listOf("data:image/jpeg;base64,episode-image-1", "data:image/jpeg;base64,episode-image-2",
            "data:image/jpeg;base64,current-image"), images(messages))
    }

    @Test fun budgetReductionDoesNotConsumeExplicitlyRequestedEvidenceWithoutShowingIt() {
        val task = task().apply { recalledSteps = listOf(1); recallContent = "image" }
        val packet = MemoryPacket(recalled = listOf(MemoryRecall("requested", "Exact requested text", "requested-image")))
        val messages = ContextBuilder(task, null).build(5, "current-image", "current-tree", packet, null, null,
            reduceRecall = true)
        assertTrue(messages.toString().contains("Exact requested text"))
        assertEquals(listOf("data:image/jpeg;base64,requested-image", "data:image/jpeg;base64,old-image-1",
            "data:image/jpeg;base64,current-image"), images(messages))
    }
}
