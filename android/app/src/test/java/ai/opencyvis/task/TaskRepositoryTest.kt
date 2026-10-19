package ai.opencyvis.task

import ai.opencyvis.db.AppDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private var now = 100L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TaskRepository(database.taskDao()) { now }
    }

    @After fun tearDown() = database.close()

    @Test fun enqueuePreservesExplicitTaskDataAndTimestamps() = runBlocking {
        val task = repository.enqueue(TaskRequest("查找资料", "telegram", "chat-7", 4L, 9L))

        assertTrue(task.id > 0)
        assertTrue(task.queueOrder > 0)
        assertEquals(TaskStatus.QUEUED, task.status)
        assertEquals("查找资料", task.instruction)
        assertEquals("telegram", task.source)
        assertEquals("chat-7", task.remoteChatId)
        assertEquals(4L, task.routineId)
        assertEquals(9L, task.conversationId)
        assertEquals(100L, task.createdAt)
        assertEquals(100L, task.updatedAt)
        assertFalse(task.guiHeld)
        assertNull(task.runId)
        assertEquals(task, repository.get(task.id))
    }

    @Test fun queueIsFifoEvenWhenWallClockMovesBackwards() = runBlocking {
        val first = repository.enqueue(TaskRequest("first"))
        now = 50L
        val second = repository.enqueue(TaskRequest("second"))

        assertEquals(first, repository.nextQueued())
        assertEquals(listOf(first.id, second.id), repository.tasks.first().map { it.id })
        assertEquals(1, repository.updateStatus(first.id, TaskStatus.RUNNING))
        assertEquals(second, repository.nextQueued())
    }

    @Test fun concurrentEnqueueAssignsUniqueQueuePositions() = runBlocking {
        val tasks = (1..12).map { async { repository.enqueue(TaskRequest("task-$it")) } }.awaitAll()

        assertEquals(12, tasks.map { it.id }.toSet().size)
        assertEquals(12, tasks.map { it.queueOrder }.toSet().size)
        val ordered = repository.tasks.first()
        assertEquals(ordered.sortedBy { it.queueOrder }, ordered)
        assertEquals(ordered.first(), repository.nextQueued())
    }

    @Test fun updatesAttachRunConversationAndReportMissingRows() = runBlocking {
        val task = repository.enqueue(TaskRequest("work"))
        now = 200L

        assertEquals(1, repository.attachRun(task.id, "run-1"))
        assertEquals(1, repository.attachConversation(task.id, 42L))
        assertEquals(1, repository.updateStatus(task.id, TaskStatus.RUNNING))
        val actual = requireNotNull(repository.get(task.id))
        assertEquals("run-1", actual.runId)
        assertEquals(42L, actual.conversationId)
        assertEquals(200L, actual.updatedAt)
        assertTrue(actual.guiHeld)
        assertEquals(0, repository.attachRun(-1L, "missing"))
        assertEquals(0, repository.attachConversation(-1L, 42L))
        assertEquals(0, repository.updateStatus(-1L, TaskStatus.RUNNING))
        assertNull(repository.get(-1L))
    }

    @Test fun terminalTasksCannotBeRevivedOrOverwrittenByLateStatusUpdates() = runBlocking {
        for (status in listOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            val task = repository.enqueue(TaskRequest(status))
            assertEquals(1, repository.updateStatus(task.id, status, "final result"))
            assertEquals(0, repository.updateStatus(task.id, TaskStatus.RUNNING))
            assertEquals(0, repository.updateStatus(task.id, TaskStatus.FAILED, "late error"))
            assertEquals(0, repository.requeue(task.id))
            val actual = requireNotNull(repository.get(task.id))
            assertEquals(status, actual.status)
            assertEquals("final result", actual.error)
            assertFalse(actual.guiHeld)
        }
    }

    @Test fun recoveryInterruptsActiveWorkButKeepsParkedAndQueuedTasks() = runBlocking {
        val running = repository.enqueue(TaskRequest("running"))
        val waiting = repository.enqueue(TaskRequest("waiting"))
        val paused = repository.enqueue(TaskRequest("active paused"))
        val parked = repository.enqueue(TaskRequest("parked"))
        val queued = repository.enqueue(TaskRequest("queued"))
        val finished = repository.enqueue(TaskRequest("finished"))
        repository.updateStatus(running.id, TaskStatus.RUNNING)
        repository.updateStatus(waiting.id, TaskStatus.WAITING_USER)
        repository.updateStatus(paused.id, TaskStatus.PAUSED)
        repository.updateStatus(parked.id, TaskStatus.PAUSED, guiHeld = false)
        repository.updateStatus(finished.id, TaskStatus.SUCCEEDED)
        now = 300L

        assertEquals(3, repository.recoverInterrupted())
        for (task in listOf(running, waiting, paused)) {
            val actual = requireNotNull(repository.get(task.id))
            assertEquals(TaskStatus.INTERRUPTED, actual.status)
            assertFalse(actual.guiHeld)
            assertEquals(300L, actual.updatedAt)
        }
        assertEquals(TaskStatus.PAUSED, repository.get(parked.id)!!.status)
        assertEquals(100L, repository.get(parked.id)!!.updatedAt)
        assertEquals(TaskStatus.QUEUED, repository.get(queued.id)!!.status)
        assertEquals(TaskStatus.SUCCEEDED, repository.get(finished.id)!!.status)
        assertEquals(0, repository.recoverInterrupted())
    }

    @Test fun explicitRequeueMovesTaskToTailWithoutChangingItsIdentityOrLinks() = runBlocking {
        val old = repository.enqueue(TaskRequest("old", conversationId = 12L))
        repository.attachRun(old.id, "old-run")
        repository.updateStatus(old.id, TaskStatus.INTERRUPTED, "process stopped")
        val next = repository.enqueue(TaskRequest("next"))
        now = 400L

        assertEquals(1, repository.requeue(old.id))
        val requeued = requireNotNull(repository.get(old.id))
        assertEquals(TaskStatus.QUEUED, requeued.status)
        assertTrue(requeued.queueOrder > next.queueOrder)
        assertEquals(next.id, repository.nextQueued()!!.id)
        assertEquals(listOf(next.id, old.id), repository.tasks.first().map { it.id })
        assertEquals(12L, requeued.conversationId)
        assertEquals("old-run", requeued.runId)
        assertEquals(old.createdAt, requeued.createdAt)
        assertEquals(400L, requeued.updatedAt)
        assertNull(requeued.error)
        assertFalse(requeued.guiHeld)
        assertEquals(0, repository.requeue(old.id))
    }

    @Test fun onlyPausedOrInterruptedTasksCanRequeue() = runBlocking {
        val task = repository.enqueue(TaskRequest("work"))
        assertEquals(0, repository.requeue(task.id))
        repository.updateStatus(task.id, TaskStatus.RUNNING)
        assertEquals(0, repository.requeue(task.id))
        repository.updateStatus(task.id, TaskStatus.PAUSED, guiHeld = false)
        assertEquals(1, repository.requeue(task.id))
        assertEquals(0, repository.requeue(-1L))
    }

    @Test fun emptyQueueHasNoNextTask() = runBlocking {
        assertNull(repository.nextQueued())
        assertTrue(repository.tasks.first().isEmpty())
    }

    @Test fun blankTaskDataIsRejectedWithoutWritingRows() = runBlocking {
        for (request in listOf(TaskRequest("  "), TaskRequest("work", source = ""))) {
            try {
                repository.enqueue(request)
                fail("Invalid task data must be rejected")
            } catch (_: IllegalArgumentException) {
                assertTrue(repository.tasks.first().isEmpty())
            }
        }
    }
}
