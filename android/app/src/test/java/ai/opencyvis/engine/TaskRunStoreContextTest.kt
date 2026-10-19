package ai.opencyvis.engine

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import ai.opencyvis.memory.MemoryPacket
import ai.opencyvis.llm.LLMClientInterface

class TaskRunStoreContextTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var store: TaskRunStore

    @Before fun createStore() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(temporary.root)
        store = TaskRunStore(context, instruction = "比较本次价格")
    }

    @After fun finishStore() {
        store.finish("completed")
    }

    @Test fun stepDetailsPagesRestoreOriginalEventsWithoutChangingThem() {
        val detail = "精确商品型号及价格：Alpha 1，123.45。".repeat(80)
        store.recordEvent(3, "proposed", mapOf("action_type" to "note", "note" to detail))
        store.recordEvent(3, "execution", mapOf("execution_status" to "tool", "feedback" to detail))
        val original = requireNotNull(store.readStepDetails(3))
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = requireNotNull(store.readStepDetailsPage(3, offset, 101))
            assertEquals(original.length, page.totalChars)
            restored.append(page.text)
            offset = page.nextOffset ?: break
        } while (true)

        assertEquals(original, restored.toString())
        assertEquals(original, store.readStepDetails(3))
        assertNull(store.readStepDetailsPage(99))
    }

    @Test fun artifactsStayInRunArchiveAndDoNotReplaceStepTrace() {
        store.recordEvent(2, "state", mapOf("task_memory" to mapOf("price" to "原始价格 123.45")))
        val before = store.readStepDetails(2)
        val reference = store.saveContextArtifact("原始价格 123.45", 2, "task_memory.price", 1)
        val second = store.saveContextArtifact("原始价格 123.45", 2, "task_memory.price", 1)

        assertEquals(reference, second)
        assertEquals("原始价格 123.45", store.readContextArtifact(reference.artifactRef).text)
        assertEquals(before, store.readStepDetails(2))
        val directory = File(temporary.root, "agent_runs/${store.runId}/context_artifacts")
        val metadata = directory.walkTopDown().single { it.name == "metadata.json" }
        assertEquals(reference.artifactRef, JSONObject(metadata.readText()).getString("artifact_ref"))
        assertEquals("原始价格 123.45", directory.walkTopDown().single { it.name == "text.txt" }.readText())
    }

    @Test fun stepPageReferenceCanContinueReadingTheSameSnapshotAfterLaterEvents() {
        store.recordEvent(1, "execution", mapOf("text" to "first evidence ".repeat(100)))
        val original = requireNotNull(store.readStepDetails(1))
        val first = requireNotNull(store.readStepDetailsPage(1, limit = 80))
        store.recordEvent(1, "effect", mapOf("status" to "confirmed_success"))
        val rest = store.readContextArtifact(first.reference, first.nextOffset!!, original.length)
        assertEquals(original, first.text + rest.text)
        assertFalse(rest.text.contains("confirmed_success"))
    }

    @Test fun exactLargeFactsLeaveThePromptButRemainReadableWithoutChangingTaskState() {
        val original = "exact-price-123.45 ".repeat(500)
        val task = TaskState("Compare").apply {
            steps[1] = TaskState.Step(1, null, null,
                mapOf("action_type" to "type_text", "text" to original),
                mapOf("execution_status" to "executed"), mapOf("status" to "unknown"))
            memories["items"] = mapOf("value" to original, "source_step" to 1, "recorded_step" to 1)
            procedureExecution = mapOf("bindings" to mapOf("body" to original), "current_node" to "submit")
        }
        val builder = ContextBuilder(task, store)
        val messages = builder.build(2, "img", "tree", MemoryPacket(), null, null)
        assertFalse(messages.toString().contains(original))
        assertEquals(original, task.memories.getValue("items")["value"])
        assertEquals(original, (task.procedureExecution["bindings"] as Map<*, *>)["body"])
        assertEquals("unknown", task.steps.getValue(1).effect!!["status"])
        assertTrue(messages.last().toString().contains("pending_results"))
        val directory = File(temporary.root, "agent_runs/${store.runId}/context_artifacts")
        val fact = directory.walkTopDown().filter { it.name == "metadata.json" }
            .map { JSONObject(it.readText()) }.first { it.getString("label").endsWith("items.value") }
        val reference = fact.getString("artifact_ref")
        val client = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>) = emptyMap<String, Any?>()
            override fun shutdown() = Unit
        }
        ContextManager(task, store, client).recall(mapOf("artifact_ref" to reference, "recall_limit" to 4800), 2)
        assertEquals(original.take(4800), task.recalledTextPage!!["text"])
        assertNotNull(task.recalledTextPage!!["next_offset"])
    }
}
