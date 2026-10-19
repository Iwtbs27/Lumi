package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.execution.TaskExecutionRuntime
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.team.ChildResult
import ai.opencyvis.team.ChildWorker
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.MaterialPage
import ai.opencyvis.team.MaterialRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TeamCodingSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun resetMocks() = unmockkAll()
    private val context get() = mockk<Context>().also { every { it.filesDir } returns temporary.root }
    private val config = TeamConfig(100_000, 8_000)

    @Test fun taskRuntimeDelegatesCodingWithoutObservationsAndCarriesTheActualParentStep() = runTest {
        val context = context
        val store = TaskRunStore(context, instruction = "处理数据", taskId = 7)
        val host = mockk<TaskExecutionRuntime>()
        var captured: DelegationRequest? = null
        every { host.codingWorker } returns ChildWorker { request, _ ->
            captured = request
            ChildResult("needs_execution", emptyList(), emptyList(), listOf("请父任务运行"),
                mapOf("summary" to "表单填写程序已准备好", "program_id" to "internal-program", "revision" to 1))
        }
        val task = requireNotNull(TaskTeamRuntime.open(context, 7, false, config) { Client(emptyList()) })
        val session = task.session(store, { "2:0" }, backgroundScope, execution = host) { listOf("保留原文件") }
        try {
            session.initialize()
            val result = JSONObject(session.tool(codingAction(), 9))
            runCurrent()
            val request = requireNotNull(captured)
            assertEquals(result.getString("child_id"), request.childId)
            assertEquals("coding", request.role)
            assertEquals(9, request.sourceStep)
            assertTrue(request.materials.isEmpty())
            assertEquals("2:0", request.goalRevision)
            assertEquals(listOf("保留原文件", "只填标题"), request.constraints)
            assertEquals("gui", request.coding!!.mode)
            assertEquals(7, (request.coding!!.parameters["quantity"] as Number).toInt())
            assertEquals(2, request.coding!!.maxExecutions)
            assertEquals(40_000L, request.coding!!.timeoutMs)
            assertEquals(listOf("标题为目标文字"), request.coding!!.completionConditions)
            val child = (session.context()["children"] as List<*>).single() as Map<*, *>
            assertEquals("coding", child["role"])
            assertEquals("needs_execution", child["status"])
        } finally { session.manager.close(); store.finish("completed") }
    }

    @Test fun realFileMaterialsAndExistingContextMaterialsUseTheirOwnReadersWithinTheSameChild() = runTest {
        val store = TaskRunStore(context, instruction = "核对资料", taskId = 7)
        val note = store.saveContextArtifact("Collected note", 1, "note")
        val file = MaterialRef("artifact", "artifact:sheet:2", 2, store.runId, 3, "sheet.xlsx")
        val host = mockk<TaskExecutionRuntime>()
        every { host.codingWorker } returns ChildWorker { _, _ -> error("Not a coding child") }
        every { host.material(file.reference, 4) } returns file
        coEvery { host.readMaterial(file, 0, 4800) } returns MaterialPage(file, "Workbook metadata", 0, null, 17)
        val client = Client(listOf(
            mapOf("action_type" to "read_artifact", "reference" to file.reference, "version" to 2),
            mapOf("action_type" to "read_artifact", "reference" to note.artifactRef, "version" to note.version),
            mapOf("action_type" to "submit_result", "status" to "completed", "facts" to listOf(mapOf(
                "text" to "The workbook matches the note", "refs" to listOf(
                    mapOf("reference" to file.reference, "version" to 2),
                    mapOf("reference" to note.artifactRef, "version" to note.version)))),
                "gaps" to emptyList<String>(), "collection_requests" to emptyList<String>())))
        val session = TeamSession(temporary.newFolder(), store, config, backgroundScope, { "1:0" }, { emptyList() }, host) { client }
        try {
            session.initialize()
            val result = JSONObject(session.tool(mapOf("action_type" to "delegate_task", "child_goal" to "核对工作簿",
                "artifact_refs" to listOf(file.reference, note.artifactRef)), 4))
            runCurrent()
            val child = session.manager.get(result.getString("child_id"))!!
            assertEquals("completed", child.status)
            assertEquals(2, child.reads.size)
            assertTrue(client.messages.any { it.contains("Workbook metadata") && it.contains("Collected note") })
            coVerify(exactly = 1) { host.readMaterial(file, 0, 4800) }
        } finally { session.manager.close(); store.finish("completed") }
    }

    @Test fun researchStillNeedsMaterialWhileAnExplicitParameterOnlyCodingTaskCanStart() = runTest {
        val store = TaskRunStore(context, instruction = "处理数据", taskId = 7)
        val host = mockk<TaskExecutionRuntime>()
        every { host.codingWorker } returns ChildWorker { _, _ ->
            ChildResult("needs_evidence", emptyList(), emptyList(), listOf("请提供输入文件"))
        }
        val session = TeamSession(temporary.newFolder(), store, config, backgroundScope, { "1:0" }, { emptyList() }, host) { Client(emptyList()) }
        try {
            session.initialize()
            try {
                session.tool(mapOf("action_type" to "delegate_task", "child_goal" to "核对不存在的材料"), 2)
                fail("Research must not invent source material")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Delegate existing"))
            }
            assertTrue(session.manager.list().isEmpty())
            val result = JSONObject(session.tool(codingAction() + mapOf("coding_mode" to "data", "params" to emptyMap<String, Any>()), 2))
            runCurrent()
            assertEquals("needs_evidence", session.manager.get(result.getString("child_id"))!!.status)
        } finally { session.manager.close(); store.finish("completed") }
    }

    private fun codingAction(): Map<String, Any?> = mapOf("action_type" to "delegate_coding", "child_goal" to "编写表单填写程序",
        "coding_mode" to "gui", "params" to mapOf("quantity" to 7), "completion_conditions" to listOf("标题为目标文字"),
        "max_executions" to 2, "execution_timeout_ms" to 40_000L, "child_constraints" to listOf("只填标题"))

    private class Client(private val actions: List<Map<String, Any?>>) : LLMClientInterface {
        val messages = mutableListOf<String>()
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = error("Wrong model route")
        override suspend fun chatWithSchema(messages: List<Map<String, Any>>, schema: LlmRequestSchema): Map<String, Any?> {
            this.messages += messages.toString()
            return actions[this.messages.size - 1]
        }
        override fun shutdown() = Unit
    }
}
