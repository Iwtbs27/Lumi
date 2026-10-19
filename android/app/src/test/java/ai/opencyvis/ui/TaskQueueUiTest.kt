package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import ai.opencyvis.App
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskQueueUiTest {
    @After fun tearDown() {
        App.agentService = null
        unmockkAll()
    }

    private fun record(status: String, held: Boolean = false) = TaskRecord(
        id = 7L, instruction = "查找资料", status = status, guiHeld = held,
        createdAt = 100L, updatedAt = 100L, queueOrder = 1L
    )

    @Test fun activePauseAndParkedPauseHaveDifferentActions() {
        assertTrue(TaskQueueAction.PAUSE in taskQueueActions(record(TaskStatus.RUNNING, true), 7L))
        assertTrue(TaskQueueAction.PARK in taskQueueActions(record(TaskStatus.RUNNING, true), 7L))
        assertTrue(TaskQueueAction.RESUME in taskQueueActions(record(TaskStatus.PAUSED, true), 7L))
        assertTrue(TaskQueueAction.REQUEUE in taskQueueActions(record(TaskStatus.PAUSED), null))
        assertTrue(TaskQueueAction.REQUEUE in taskQueueActions(record(TaskStatus.INTERRUPTED), null))
        assertFalse(TaskQueueAction.PAUSE in taskQueueActions(record(TaskStatus.RUNNING, true), 8L))
        assertFalse(TaskQueueAction.CANCEL in taskQueueActions(record(TaskStatus.SUCCEEDED), null))
    }

    @Test fun historyOwnershipRequiresMatchingTaskAndConversation() {
        val task = record(TaskStatus.RUNNING, true).copy(conversationId = 12L)
        assertTrue(isConversationTaskActive(task, 12L, 7L, 12L))
        assertFalse(isConversationTaskActive(task, 12L, 8L, 13L))
        assertFalse(isConversationTaskActive(task, 13L, 7L, 12L))
        assertFalse(isConversationTaskActive(task.copy(status = TaskStatus.SUCCEEDED), 12L, 7L, 12L))
    }

    @Test fun handoffOffersTheScreenAndTracksQuestionChangesWithoutDatabaseUpdates() {
        val rows = MutableStateFlow(listOf(record(TaskStatus.WAITING_USER, true)))
        val state = MutableStateFlow<AgentState>(AgentState.WaitingForUser("Which account?", 1))
        val engine = mockk<AgentEngine>()
        every { engine.state } returns state
        every { engine.childAgents } returns MutableStateFlow(emptyList())
        val service = mockk<AgentService>()
        every { service.activeTaskId } returns MutableStateFlow<Long?>(7L)
        every { service.engineFlow } returns MutableStateFlow<AgentEngine?>(engine)
        every { service.stateFlow } returns state
        every { service.enterViewMode(true) } returns true
        App.agentService = service
        val context = ApplicationProvider.getApplicationContext<Context>()
        val binder = mockk<AgentService.AgentBinder>()
        every { binder.getService() } returns service
        shadowOf(context as App).setComponentNameAndServiceForBindService(
            ComponentName(context, AgentService::class.java), binder
        )
        mockkConstructor(TaskRepository::class)
        every { anyConstructed<TaskRepository>().tasks } returns rows
        val controller = Robolectric.buildActivity(TaskQueueActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun primaryButton(): TextView {
                shadowOf(Looper.getMainLooper()).idle()
                val list = activity.findViewById<RecyclerView>(R.id.task_queue_list)
                list.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 1080, 1600)
                return list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById(R.id.task_queue_primary)
            }
            assertEquals(activity.getString(R.string.task_queue_reply), primaryButton().text.toString())
            state.value = AgentState.WaitingForHandoff("Choose an account", 2)
            val button = primaryButton()
            assertNotEquals(activity.getString(R.string.task_queue_reply), button.text.toString())
            button.performClick()
            verify(exactly = 1) { service.enterViewMode(true) }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun addTaskPersistsBeforeWakingServiceWhenItIsNotBound() {
        App.agentService = null
        val rows = MutableStateFlow<List<TaskRecord>>(emptyList())
        val persisted = CompletableDeferred<Unit>()
        mockkConstructor(TaskRepository::class)
        every { anyConstructed<TaskRepository>().tasks } returns rows
        coEvery { anyConstructed<TaskRepository>().enqueue(any()) } coAnswers {
            persisted.await()
            val request = firstArg<TaskRequest>()
            record(TaskStatus.QUEUED).copy(instruction = request.instruction).also { rows.value = listOf(it) }
        }
        val controller = Robolectric.buildActivity(TaskQueueActivity::class.java).setup()
        try {
            val activity = controller.get()
            val application = shadowOf(activity.application)
            fun takeStartedIntents(): List<Intent> = generateSequence { application.nextStartedService }.toList()
            // Opening the list starts foreground service ownership before it binds.
            assertTrue(takeStartedIntents().any { it.component?.className == AgentService::class.java.name })
            activity.findViewById<EditText>(R.id.task_queue_input).setText("独立任务")
            activity.findViewById<TextView>(R.id.task_queue_add).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(takeStartedIntents().none { it.hasExtra("task_id") })
            assertEquals("独立任务", activity.findViewById<EditText>(R.id.task_queue_input).text.toString())
            persisted.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()

            coVerify(exactly = 1) { anyConstructed<TaskRepository>().enqueue(TaskRequest("独立任务")) }
            assertTrue(takeStartedIntents().any {
                it.component?.className == AgentService::class.java.name && it.getLongExtra("task_id", -1L) == 7L
            })
            assertEquals("", activity.findViewById<EditText>(R.id.task_queue_input).text.toString())
            assertEquals("独立任务", rows.value.single().instruction)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun deletingConversationWaitsForItsTaskToFinishCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rows = MutableStateFlow(listOf(record(TaskStatus.RUNNING, true).copy(conversationId = 12L)))
        val stopped = CompletableDeferred<Unit>()
        mockkConstructor(TaskRepository::class, ChatHistoryRepository::class)
        every { anyConstructed<TaskRepository>().tasks } returns rows
        coEvery { anyConstructed<ChatHistoryRepository>().getMessages(12L) } returns emptyList()
        coEvery { anyConstructed<ChatHistoryRepository>().deleteConversation(12L, false) } returns Unit
        val service = mockk<AgentService>()
        every { service.tasks } returns rows
        every { service.activeTaskId } returns MutableStateFlow<Long?>(7L)
        every { service.currentConversationId } returns 12L
        every { service.engineFlow } returns MutableStateFlow<AgentEngine?>(null)
        coEvery { service.cancelTaskAndWait(7L) } coAnswers { stopped.await() }
        val binder = mockk<AgentService.AgentBinder>()
        every { binder.getService() } returns service
        shadowOf(context as App).setComponentNameAndServiceForBindService(
            ComponentName(context, AgentService::class.java), binder
        )
        val intent = Intent(context, ConversationDetailActivity::class.java)
            .putExtra("conversation_id", 12L).putExtra("task_id", 7L)
        val controller = Robolectric.buildActivity(ConversationDetailActivity::class.java, intent).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.get().findViewById<TextView>(R.id.btn_delete).performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            coVerify(exactly = 1) { service.cancelTaskAndWait(7L) }
            coVerify(exactly = 0) { anyConstructed<ChatHistoryRepository>().deleteConversation(any(), any()) }

            stopped.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            coVerify(exactly = 1) { anyConstructed<ChatHistoryRepository>().deleteConversation(12L, false) }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
