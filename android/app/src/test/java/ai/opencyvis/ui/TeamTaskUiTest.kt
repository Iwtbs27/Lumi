package ai.opencyvis.ui

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import ai.opencyvis.AgentService
import ai.opencyvis.App
import ai.opencyvis.R
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.team.ChildFact
import ai.opencyvis.team.ChildResult
import ai.opencyvis.team.CodingTaskSpec
import ai.opencyvis.team.DelegationRecord
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.DelegationStatus
import ai.opencyvis.team.MaterialRef
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class TeamTaskUiTest {
    @After fun tearDown() {
        App.agentService = null
        unmockkAll()
    }

    @Test fun childUpdatesRefreshTheCurrentRowWithoutDatabaseChangesAndRemainVisibleWhilePaused() = withScreen { screen ->
        assertFalse(screen.status().contains("Task helpers"))
        screen.children.value = listOf(child("one", DelegationStatus.RUNNING))
        assertTrue(screen.status().contains("Task helpers: 1 active · 0 completed"))

        screen.children.value = listOf(child("one", DelegationStatus.COMPLETED))
        assertTrue(screen.status().contains("Task helpers: 0 active · 1 completed"))
        screen.rows.value = listOf(screen.rows.value.single().copy(status = TaskStatus.PAUSED, guiHeld = true))
        assertNotNull(screen.menu().findItemByTitle("Task helpers"))

        screen.activeId.value = null
        assertFalse(screen.status().contains("Task helpers"))
        assertNull(screen.menu().findItemByTitle("Task helpers"))
    }

    @Test fun detailsShowCurrentFactsAndGapsAndCancelOnlyTheSelectedHelper() = withScreen { screen ->
        val result = ChildResult(DelegationStatus.NEEDS_EVIDENCE,
            listOf(ChildFact("The dates match", listOf(
                MaterialRef("evidence", "internal-evidence-ref", 1, "internal-run-id", 4, "Pricing page")))),
            listOf("The delivery fee is missing"), emptyList())
        screen.children.value = listOf(
            child("one", DelegationStatus.RUNNING),
            child("two", DelegationStatus.RUNNING),
            child("done", DelegationStatus.NEEDS_EVIDENCE).copy(result = result)
        )
        val dialog = screen.openChecks()
        val text = texts(dialog.window!!.decorView).joinToString("\n")
        assertTrue(text.contains("Check one"))
        assertTrue(text.contains("The dates match"))
        assertTrue(text.contains("The delivery fee is missing"))
        assertTrue(text.contains("Needs more information"))
        assertTrue(text.contains("Source: Step 4 · Pricing page"))
        assertTrue(text.contains("Main task review: Pending"))
        assertFalse(text.contains("internal-result-ref"))
        assertFalse(text.contains("internal-run-id"))
        assertFalse(text.contains("internal-evidence-ref"))
        assertNull(dialog.window!!.decorView.findViewWithTag<View>("team_cancel_done"))

        dialog.window!!.decorView.findViewWithTag<View>("team_cancel_one").performClick()
        verify(exactly = 1) { screen.engine.cancelChild("one") }
        verify(exactly = 0) { screen.engine.cancelChild("two") }
        verify(exactly = 0) { screen.service.cancelTask(any()) }

        screen.children.value = screen.children.value.map {
            if (it.request.childId == "one") it.copy(status = DelegationStatus.CANCELLED) else it
        }
        idle()
        assertNull(dialog.window!!.decorView.findViewWithTag<View>("team_cancel_one"))
        assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("team_cancel_two"))

        screen.children.value = screen.children.value.map {
            if (it.request.childId == "done") it.copy(decision = "accepted", reviewReason = "Checked against the collected page") else it
        }
        idle()
        val accepted = texts(dialog.window!!.decorView).joinToString("\n")
        assertTrue(accepted.contains("Main task review: Accepted"))
        assertTrue(accepted.contains("Checked against the collected page"))
        screen.children.value = screen.children.value.map {
            if (it.request.childId == "done") it.copy(decision = "rejected", reviewReason = "The task now uses a different date") else it
        }
        idle()
        val rejected = texts(dialog.window!!.decorView).joinToString("\n")
        assertTrue(rejected.contains("Main task review: Rejected"))
        assertTrue(rejected.contains("The task now uses a different date"))
    }

    @Test fun switchingTasksClosesOldDetailsAndAnOldCancelButtonCannotAffectTheNewRun() = withScreen { screen ->
        screen.children.value = listOf(child("one", DelegationStatus.RUNNING))
        val dialog = screen.openChecks()
        val oldButton = dialog.window!!.decorView.findViewWithTag<View>("team_cancel_one")
        val nextEngine = mockk<AgentEngine>(relaxed = true)
        every { nextEngine.state } returns MutableStateFlow<AgentState>(AgentState.Idle())
        every { nextEngine.childAgents } returns MutableStateFlow(listOf(
            child("other", DelegationStatus.RUNNING).let { it.copy(request = it.request.copy(rootTaskId = 8L)) }
        ))
        screen.activeId.value = 8L
        screen.engineFlow.value = nextEngine
        idle()
        assertFalse(dialog.isShowing)
        oldButton.performClick()
        verify(exactly = 0) { screen.engine.cancelChild(any()) }
        verify(exactly = 0) { nextEngine.cancelChild(any()) }
        assertFalse(screen.status().contains("Task helpers"))
    }

    @Test fun codingCandidateShowsItsPendingRunAndSummaryWithoutInternalProgramData() = withScreen { screen ->
        val candidate = child("code", DelegationStatus.NEEDS_EXECUTION).let { it.copy(
            request = it.request.copy(role = "coding", coding = CodingTaskSpec("gui", emptyMap(), listOf("Title matches"), 2, 30_000)),
            result = ChildResult(DelegationStatus.NEEDS_EXECUTION, emptyList(), emptyList(), listOf("Run the saved form program"),
                mapOf("summary" to "The form program is ready", "program_id" to "internal-code-id", "revision" to 2,
                    "artifact_refs" to listOf("artifact:internal-output:1"), "code_ref" to "/private/code/main.py"))) }
        screen.children.value = listOf(candidate)
        val dialog = screen.openChecks()
        val content = texts(dialog.window!!.decorView).joinToString("\n")
        assertTrue(content.contains("Coding helper"))
        assertTrue(content.contains("Waiting for the main task to run"))
        assertTrue(content.contains("The form program is ready"))
        assertTrue(content.contains("Outputs: 1"))
        assertFalse(content.contains("internal-code-id"))
        assertFalse(content.contains("internal-output"))
        assertFalse(content.contains("/private/code"))
        assertNull(dialog.window!!.decorView.findViewWithTag<View>("team_cancel_code"))

        screen.children.value = listOf(candidate.copy(result = candidate.result!!.copy(
            outputs = candidate.result!!.outputs + ("summary" to "The form program now includes the notes field"))))
        idle()
        assertTrue(texts(dialog.window!!.decorView).any { it.contains("now includes the notes field") })
    }

    private fun child(id: String, status: String) = DelegationRecord(
        request = DelegationRequest(id, 7L, "internal-run-id", "revision-1", "Check $id", emptyList(), emptyList()),
        status = status, resultRef = "internal-result-ref-$id", createdAt = 1L, updatedAt = 1L
    )

    private fun withScreen(block: (Screen) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screen = Screen()
        every { screen.engine.state } returns MutableStateFlow<AgentState>(AgentState.Idle())
        every { screen.engine.childAgents } returns screen.children
        every { screen.service.activeTaskId } returns screen.activeId
        every { screen.service.engineFlow } returns screen.engineFlow
        every { screen.service.stateFlow } answers { screen.engineFlow.value?.state }
        every { screen.service.getEngine() } answers { screen.engineFlow.value }
        App.agentService = screen.service
        val binder = mockk<AgentService.AgentBinder>()
        every { binder.getService() } returns screen.service
        shadowOf(context as App).setComponentNameAndServiceForBindService(
            ComponentName(context, AgentService::class.java), binder)
        mockkConstructor(TaskRepository::class)
        every { anyConstructed<TaskRepository>().tasks } returns screen.rows
        val controller = Robolectric.buildActivity(TaskQueueActivity::class.java).setup()
        screen.activity = controller.get()
        try { block(screen) }
        finally { controller.pause().stop().destroy() }
    }

    private class Screen {
        val rows = MutableStateFlow(listOf(TaskRecord(
            id = 7L, instruction = "Compare the collected offers", status = TaskStatus.RUNNING,
            guiHeld = true, conversationId = 12L, createdAt = 1L, updatedAt = 1L, queueOrder = 1L
        )))
        val children = MutableStateFlow<List<DelegationRecord>>(emptyList())
        val engine = mockk<AgentEngine>(relaxed = true)
        val service = mockk<AgentService>(relaxed = true)
        val activeId = MutableStateFlow<Long?>(7L)
        val engineFlow = MutableStateFlow<AgentEngine?>(engine)
        lateinit var activity: TaskQueueActivity

        fun row(): View {
            idle()
            val list = activity.findViewById<RecyclerView>(R.id.task_queue_list)
            list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 1080, 1600)
            return list.findViewHolderForAdapterPosition(0)!!.itemView
        }

        fun status(): String = row().findViewById<TextView>(R.id.task_queue_status).text.toString()

        fun menu(): PopupMenu {
            row().findViewById<View>(R.id.task_queue_more).performClick()
            idle()
            return ShadowPopupMenu.getLatestPopupMenu()
        }

        fun openChecks(): AlertDialog {
            val popup = menu()
            val item = popup.findItemByTitle("Task helpers")
            assertNotNull("Current task must expose material checks", item)
            popup.menu.performIdentifierAction(item!!.itemId, 0)
            idle()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
    }

    companion object {
        private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
        private fun PopupMenu.findItemByTitle(title: String) =
            (0 until menu.size()).map { menu.getItem(it) }.firstOrNull { it.title.toString() == title }
        private fun texts(view: View): List<String> = buildList {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
        }
    }
}
