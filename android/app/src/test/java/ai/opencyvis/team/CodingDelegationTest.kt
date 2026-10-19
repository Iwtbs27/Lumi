package ai.opencyvis.team

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmRequestSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CodingDelegationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val spec = CodingTaskSpec("data", mapOf("region" to "华东"), listOf("只保留目标地区"), 3, 60000)
    private fun request(id: String = "code") = DelegationRequest(id, 7, "run", "1:0", "筛选资料",
        emptyList(), emptyList(), "coding", spec)

    @Test fun codingUsesTheExistingLifecycleBudgetArchiveAndIndependentRole() = runTest {
        val directory = temporary.newFolder()
        val client = Client()
        val manager = DelegationManager(directory, 7, "run", backgroundScope, currentGoalRevision = { "1:0" },
            createClient = { client }, readMaterial = { _, _, _, _ -> error("No material read") },
            codingWorker = ChildWorker { _, session ->
                session.chat(1, listOf(mapOf("role" to "user", "content" to "Write a filter")),
                    LlmRequestSchema(JSONObject().put("type", "function").put("function", JSONObject()
                        .put("name", "coding_action")), requireCompleteJson = true))
                session.event("code_execution", mapOf("execution_id" to "exec-1", "status" to "completed"))
                ChildResult("completed", emptyList(), emptyList(), emptyList(),
                    mapOf("program_id" to "filter", "revision" to 2))
            })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        val record = manager.get("code")!!
        assertEquals("completed", record.status)
        assertEquals(1, record.usage.calls)
        assertEquals("pending", record.decision)
        assertEquals("filter", record.result!!.outputs["program_id"])
        assertTrue(record.events.any { it.type == "code_execution" })
        assertTrue(client.closed)
        manager.close()
        val restored = DelegationManager(directory, 7, "new-run", backgroundScope, currentGoalRevision = { "1:0" },
            createClient = { error("Completed code must not be rerun") }, readMaterial = { _, _, _, _ -> error("No read") })
        restored.initialize()
        val saved = restored.get("code")!!
        assertEquals(spec, saved.request.coding)
        assertEquals("coding", saved.request.role)
        assertEquals("filter", saved.result!!.outputs["program_id"])
        restored.close()
    }

    @Test fun parentCloseWaitsForTheCodingWorkersRealCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val allowExit = CompletableDeferred<Unit>()
        val manager = DelegationManager(temporary.newFolder(), 7, "run", backgroundScope,
            currentGoalRevision = { "1:0" }, createClient = { Client() },
            readMaterial = { _, _, _, _ -> error("No read") }, codingWorker = ChildWorker { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { allowExit.await() } }
            })
        manager.initialize()
        manager.delegate(request())
        runCurrent()
        assertTrue("Coding role was never invoked", entered.isCompleted)
        val closing = async { manager.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        allowExit.complete(Unit)
        runCurrent()
        closing.await()
        assertEquals("cancelled", manager.get("code")!!.status)
    }

    @Test fun codeWithNewParametersIsNotDeduplicatedToAnOldRun() = runTest {
        val manager = DelegationManager(temporary.newFolder(), 7, "run", backgroundScope,
            currentGoalRevision = { "1:0" }, createClient = { Client() },
            readMaterial = { _, _, _, _ -> error("No read") }, codingWorker = ChildWorker { _, _ -> awaitCancellation() })
        manager.initialize()
        val first = manager.delegate(request())
        val second = manager.delegate(request("code-2").copy(coding = spec.copy(parameters = mapOf("region" to "华北"))))
        assertNotEquals(first.request.childId, second.request.childId)
        manager.close()
    }

    private class Client : LLMClientInterface {
        var closed = false
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Wrong client path")
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> =
            mapOf("action_type" to "submit_result", "status" to "needs_evidence", "facts" to emptyList<Any>(),
                "gaps" to emptyList<Any>(), "collection_requests" to listOf("Need a file"))
        override fun shutdown() { closed = true }
    }
}
