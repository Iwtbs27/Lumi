package ai.opencyvis.execution

import ai.opencyvis.engine.TaskState
import ai.opencyvis.llm.ToolSchema
import org.junit.Assert.*
import org.junit.Test

class ExecutionToolSchemaTest {
    @Test fun parentKeepsOneFunctionWithStableExecutionAndCodingActions() {
        val tools = ToolSchema.toolsArray()
        assertEquals(1, tools.length())
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("phone_action", function.getString("name"))
        val properties = function.getJSONObject("parameters").getJSONObject("properties")
        val actions = properties.getJSONObject("action_type").getJSONArray("enum")
        val names = (0 until actions.length()).map(actions::getString)
        assertTrue(names.containsAll(TaskExecutionRuntime.ACTIONS + "delegate_coding"))
        assertEquals("object", properties.getJSONObject("arguments").getString("type"))
        assertEquals("object", properties.getJSONObject("params").getString("type"))
    }

    @Test fun taskAcceptsNewActionsAndRejectsMissingExecutionInputs() {
        val task = TaskState("Combine file and GUI work")
        fun response(type: String, extra: Map<String, Any?> = emptyMap()) =
            mapOf("thought" to "Use the available capability", "action_type" to type, "memory_update" to emptyList<Any>()) + extra
        task.applyResponse(response("describe_capabilities"), 1)
        task.applyResponse(response("call_capability", mapOf("capability_name" to "calendar.list", "arguments" to emptyMap<String, Any>())), 1)
        task.applyResponse(response("run_program", mapOf("program_id" to "program-1", "revision" to 2,
            "params" to mapOf("region" to "Shanghai"), "execution_timeout_ms" to 60_000)), 1)
        task.applyResponse(response("delegate_coding", mapOf("child_goal" to "Filter rows", "coding_mode" to "data",
            "params" to emptyMap<String, Any>(), "completion_conditions" to listOf("Output contains only matching rows"),
            "max_executions" to 3, "execution_timeout_ms" to 60_000)), 1)
        assertThrows(IllegalArgumentException::class.java) { task.applyResponse(response("call_capability"), 1) }
        assertThrows(IllegalArgumentException::class.java) { task.applyResponse(response("run_program"), 1) }
    }
}
