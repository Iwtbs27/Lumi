package ai.opencyvis.ui

import android.content.Context
import ai.opencyvis.R
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.task.TaskRepository
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses recoverable records only: opening this screen does not send a model request. */
@RunWith(AndroidJUnit4::class)
class TaskQueueDeviceTest {
    @Test fun taskListShowsIndependentRecoverableTasksAndAddEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)
        val repository = TaskRepository(database.taskDao())
        val interrupted = repository.enqueue(TaskRequest("M2 UI interrupted task"))
        repository.updateStatus(interrupted.id, TaskStatus.INTERRUPTED)
        val parked = repository.enqueue(TaskRequest("M2 UI suspended task"))
        repository.updateStatus(parked.id, TaskStatus.PAUSED, guiHeld = false)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            ActivityScenario.launch(TaskQueueActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "task_queue_add")), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text(interrupted.instruction)), 5_000))
                assertTrue(device.wait(Until.hasObject(By.text(parked.instruction)), 5_000))
                assertNotNull(device.findObject(By.text(context.getString(R.string.task_queue_interrupted))))
                assertNotNull(device.findObject(By.text(context.getString(R.string.task_queue_parked))))
                assertEquals(TaskStatus.PAUSED, repository.get(parked.id)!!.status)
                val image = File(context.getExternalFilesDir(null), "m2-ui/task-queue.png")
                check(image.parentFile!!.isDirectory || image.parentFile!!.mkdirs())
                assertTrue(device.takeScreenshot(image))
            }
        } finally {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM agent_tasks WHERE id IN (?, ?)", arrayOf(interrupted.id, parked.id)
            )
        }
    }
}
