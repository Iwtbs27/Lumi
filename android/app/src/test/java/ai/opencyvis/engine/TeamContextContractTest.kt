package ai.opencyvis.engine

import ai.opencyvis.llm.ToolSchema
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamContextContractTest {
    @Test fun `goal revision survives recovery and changes after a user correction`() {
        val state = TaskState("Compare the collected prices")
        val original = state.snapshot()["goal_revision"]
        assertEquals("0:0", original)
        state.addUserUpdate(1, "supplement", "Only compare the 256 GB variants")
        assertEquals("0:1", state.snapshot()["goal_revision"])
        val restored = TaskState.restore(state.exportCheckpoint("source", 1))
        assertEquals(state.snapshot()["goal_revision"], restored.snapshot()["goal_revision"])
    }

    @Test fun `fixed phone tool supports delegation reading review and cancellation`() {
        val schema = ToolSchema.phoneActionTool().getJSONObject("function").getJSONObject("parameters")
        val choices = schema.getJSONObject("properties").getJSONObject("action_type").getJSONArray("enum")
        val actions = (0 until choices.length()).map(choices::getString)
        assertTrue(actions.containsAll(listOf("delegate_task", "read_child", "review_child", "cancel_child")))
        assertEquals(ToolSchema.phoneActionTool().toString(), ToolSchema.phoneActionTool().toString())
    }
}
