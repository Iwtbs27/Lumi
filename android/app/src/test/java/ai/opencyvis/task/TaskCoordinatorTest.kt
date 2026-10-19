package ai.opencyvis.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCoordinatorTest {
    @Test fun threeTasksKeepTheirIdentityAndCancelledQueuedTaskNeverRuns() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        queue.initialize()
        val first = queue.enqueue(TaskRequest("first"))
        val second = queue.enqueue(TaskRequest("second"))
        val third = queue.enqueue(TaskRequest("third"))
        runCurrent()
        assertEquals(listOf(first.id), runner.started)
        queue.cancel(second.id)
        runner.complete("completed")
        runCurrent()
        assertEquals(listOf(first.id, third.id), runner.started)
        assertEquals(TaskStatus.CANCELLED, store.get(second.id)?.status)
        assertEquals(TaskStatus.SUCCEEDED, store.get(first.id)?.status)
        queue.close()
    }

    @Test fun waitingAndPauseKeepGuiUntilExplicitSuspension() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val first = queue.enqueue(TaskRequest("first"))
        val second = queue.enqueue(TaskRequest("second"))
        queue.initialize()
        runCurrent()
        queue.reportState(first.id, TaskStatus.WAITING_USER)
        runCurrent()
        assertEquals(first.id, queue.activeTaskId.value)
        queue.pause(first.id)
        assertEquals(true, store.get(first.id)?.guiHeld)
        assertEquals(listOf(first.id), runner.started)
        queue.suspendAndRunNext(first.id)
        runCurrent()
        assertEquals(TaskStatus.PAUSED, store.get(first.id)?.status)
        assertEquals(false, store.get(first.id)?.guiHeld)
        assertEquals(second.id, queue.activeTaskId.value)
        queue.close()
    }

    @Test fun nextTaskWaitsForStopCleanupEvenAfterRunHasReturned() = runTest {
        val store = QueueStore()
        val runner = Runner().apply { stopGate = CompletableDeferred() }
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val first = queue.enqueue(TaskRequest("first"))
        queue.enqueue(TaskRequest("second"))
        queue.initialize()
        runCurrent()
        val stopping = async { queue.cancel(first.id) }
        runCurrent()
        assertEquals(first.id, queue.activeTaskId.value)
        assertEquals(listOf(first.id), runner.started)
        assertFalse(stopping.isCompleted)
        runner.stopGate!!.complete(Unit)
        stopping.await()
        runCurrent()
        assertEquals(2, runner.started.size)
        queue.close()
    }

    @Test fun recoveryFinishesBeforeAnyNewRunStarts() = runTest {
        val store = QueueStore().apply { recoveryGate = CompletableDeferred() }
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        store.enqueue(TaskRequest("already queued"))
        val recovery = launch { queue.initialize() }
        runCurrent()
        assertTrue(runner.started.isEmpty())
        store.recoveryGate!!.complete(Unit)
        recovery.join()
        runCurrent()
        assertTrue(store.recovered)
        assertEquals(1, runner.started.size)
        queue.close()
    }

    @Test fun resumedTaskGoesToQueueTailAndOldCallbacksDoNotChangeOtherTask() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val first = queue.enqueue(TaskRequest("first"))
        val second = queue.enqueue(TaskRequest("second"))
        val third = queue.enqueue(TaskRequest("third"))
        queue.initialize()
        runCurrent()
        queue.suspendAndRunNext(first.id)
        runCurrent()
        queue.recover(first.id)
        queue.reportState(first.id, TaskStatus.RUNNING)
        assertEquals(TaskStatus.QUEUED, store.get(first.id)?.status)
        assertEquals(second.id, queue.activeTaskId.value)
        runner.complete("completed")
        runCurrent()
        assertEquals(third.id, queue.activeTaskId.value)
        runner.complete("completed")
        runCurrent()
        assertEquals(first.id, queue.activeTaskId.value)
        queue.close()
    }

    @Test fun serviceShutdownPreservesQueueAndMarksOwnerInterrupted() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val first = queue.enqueue(TaskRequest("first"))
        val second = queue.enqueue(TaskRequest("second"))
        queue.initialize()
        runCurrent()
        queue.close()
        assertEquals(TaskStatus.INTERRUPTED, store.get(first.id)?.status)
        assertEquals(TaskStatus.QUEUED, store.get(second.id)?.status)
        assertNull(queue.activeTaskId.value)
        assertEquals(listOf(first.id), runner.started)
    }

    @Test fun failedRunDoesNotStrandTheNextTask() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val first = queue.enqueue(TaskRequest("first"))
        val second = queue.enqueue(TaskRequest("second"))
        queue.initialize()
        runCurrent()
        runner.completion!!.completeExceptionally(IllegalStateException("capture failed"))
        runCurrent()
        assertEquals(TaskStatus.FAILED, store.get(first.id)?.status)
        assertEquals("capture failed", store.get(first.id)?.error)
        assertEquals(second.id, queue.activeTaskId.value)
        queue.close()
    }

    @Test fun missingRecoveryDataRemainsInterruptedInsteadOfRestartingFromScratch() = runTest {
        val store = QueueStore()
        val runner = Runner()
        val queue = TaskCoordinator(backgroundScope, store, runner)
        val task = queue.enqueue(TaskRequest("continue previous write"))
        queue.initialize()
        runCurrent()
        runner.completion!!.completeExceptionally(TaskInterruptedException("Checkpoint is unavailable"))
        runCurrent()
        assertEquals(TaskStatus.INTERRUPTED, store.get(task.id)?.status)
        assertEquals("Checkpoint is unavailable", store.get(task.id)?.error)
        assertEquals(listOf(task.id), runner.started)
        queue.close()
    }

    private class Runner : TaskRunner {
        val started = mutableListOf<Long>()
        var completion: CompletableDeferred<String>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun run(task: TaskRecord): String {
            started += task.id
            return CompletableDeferred<String>().also { completion = it }.await()
        }
        fun complete(outcome: String) { completion!!.complete(outcome) }
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun stop(suspendTask: Boolean) {
            complete("interrupted")
            stopGate?.await()
        }
    }

    private class QueueStore : TaskQueueStore {
        override val tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
        var recoveryGate: CompletableDeferred<Unit>? = null
        var recovered = false
        private var order = 0L
        override suspend fun enqueue(request: TaskRequest): TaskRecord {
            val record = TaskRecord(id = tasks.value.size + 1L, instruction = request.instruction,
                status = TaskStatus.QUEUED, queueOrder = ++order, createdAt = 1, updatedAt = 1)
            tasks.value += record
            return record
        }
        override suspend fun get(id: Long) = tasks.value.find { it.id == id }
        override suspend fun nextQueued() = tasks.value.filter { it.status == TaskStatus.QUEUED }.minByOrNull { it.queueOrder }
        override suspend fun updateStatus(id: Long, status: String, error: String?, guiHeld: Boolean): Int =
            change(id) { it.copy(status = status, error = error, guiHeld = guiHeld) }
        override suspend fun attachRun(id: Long, runId: String) = change(id) { it.copy(runId = runId) }
        override suspend fun attachConversation(id: Long, conversationId: Long) = change(id) { it.copy(conversationId = conversationId) }
        override suspend fun recoverInterrupted(): Int {
            recoveryGate?.await()
            recovered = true
            return 0
        }
        override suspend fun requeue(id: Long): Int {
            if (get(id)?.status !in setOf(TaskStatus.PAUSED, TaskStatus.INTERRUPTED)) return 0
            return change(id) { it.copy(status = TaskStatus.QUEUED, guiHeld = false, queueOrder = ++order) }
        }
        private fun change(id: Long, transform: (TaskRecord) -> TaskRecord): Int {
            if (tasks.value.none { it.id == id }) return 0
            tasks.value = tasks.value.map { if (it.id == id) transform(it) else it }
            return 1
        }
    }
}
