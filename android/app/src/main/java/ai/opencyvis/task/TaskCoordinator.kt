package ai.opencyvis.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Setup or recovery needs attention; previously issued work must remain recoverable. */
class TaskInterruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** run returns only after execution, archive callbacks and GUI cleanup have exited. */
interface TaskRunner {
    suspend fun run(task: TaskRecord): String
    suspend fun pause()
    suspend fun resume()
    suspend fun stop(suspendTask: Boolean)
    suspend fun finished(task: TaskRecord, status: String) = Unit
}

/** One GUI owner. Queue records survive the service; coroutines and display IDs do not. */
class TaskCoordinator(
    private val scope: CoroutineScope,
    private val repository: TaskQueueStore,
    private val runner: TaskRunner,
) {
    private class Active(val task: TaskRecord) {
        var requestedStatus: String? = null
        var stopFinished: CompletableDeferred<Unit>? = null
    }

    private val control = Mutex()
    private val commands = Mutex()
    private var ready = false
    private var closed = false
    private var pumpJob: Job? = null
    private var active: Active? = null
    private val _activeTaskId = MutableStateFlow<Long?>(null)
    val activeTaskId = _activeTaskId.asStateFlow()
    val tasks = repository.tasks

    suspend fun initialize() = control.withLock {
        check(!closed) { "Task coordinator is closed" }
        if (!ready) {
            repository.recoverInterrupted()
            ready = true
        }
        pumpLocked()
    }

    suspend fun enqueue(request: TaskRequest): TaskRecord = control.withLock {
        check(!closed) { "Task coordinator is closed" }
        repository.enqueue(request).also { pumpLocked() }
    }

    /** A scheduler can persist an item before waking the foreground service. */
    suspend fun kick() = control.withLock { pumpLocked() }

    suspend fun pause(id: Long) = commands.withLock {
        control.withLock {
            if (active?.task?.id == id && active?.requestedStatus == null) {
                runner.pause()
                repository.updateStatus(id, TaskStatus.PAUSED, guiHeld = true)
            }
        }
    }

    suspend fun resume(id: Long) = commands.withLock {
        control.withLock {
            if (active?.task?.id == id) {
                if (active?.requestedStatus == null) {
                    runner.resume()
                    repository.updateStatus(id, TaskStatus.RUNNING, guiHeld = true)
                }
            } else if (repository.requeue(id) > 0) {
                pumpLocked()
            }
        }
    }

    suspend fun recover(id: Long) = resume(id)

    suspend fun reportState(id: Long, status: String) = control.withLock {
        require(status in TaskStatus.ACTIVE) { "Not an active task state: $status" }
        if (active?.task?.id == id && active?.requestedStatus == null) {
            repository.updateStatus(id, status, guiHeld = true)
        }
    }

    suspend fun cancel(id: Long) = stopTask(id, TaskStatus.CANCELLED)
    suspend fun suspendAndRunNext(id: Long) = stopTask(id, TaskStatus.PAUSED)

    private suspend fun stopTask(id: Long, status: String) = commands.withLock {
        val stopping = control.withLock {
            val current = active?.takeIf { it.task.id == id }
            if (current == null) {
                if (status == TaskStatus.CANCELLED) {
                    val task = repository.get(id)
                    if (task != null && repository.updateStatus(id, status, guiHeld = false) > 0) {
                        runner.finished(task, status)
                    }
                }
                pumpLocked()
                null
            } else {
                current.requestedStatus = status
                current.stopFinished = CompletableDeferred()
                current
            }
        }
        if (stopping != null) stopRunner(stopping, status == TaskStatus.PAUSED)
    }

    /** Service destruction leaves recoverable records and must never start the next item. */
    suspend fun close() = commands.withLock {
        val stopping = control.withLock {
            closed = true
            active?.also {
                it.requestedStatus = TaskStatus.INTERRUPTED
                it.stopFinished = CompletableDeferred()
            }
        }
        if (stopping != null) stopRunner(stopping, false)
        pumpJob?.join()
    }

    private suspend fun stopRunner(stopping: Active, suspendTask: Boolean) {
        // Do not hold control: engine finalization may report state or save its run ID.
        try {
            runner.stop(suspendTask)
        } finally {
            stopping.stopFinished!!.complete(Unit)
        }
    }

    private fun pumpLocked() {
        if (!ready || closed || pumpJob != null) return
        pumpJob = scope.launch {
            while (true) {
                val slot = control.withLock {
                    val next = if (closed) null else repository.nextQueued()
                    if (next == null) {
                        pumpJob = null
                        return@launch
                    }
                    repository.updateStatus(next.id, TaskStatus.RUNNING, guiHeld = true)
                    Active(next).also { active = it; _activeTaskId.value = next.id }
                }
                var status = TaskStatus.INTERRUPTED
                var error: String? = null
                try {
                    status = when (runner.run(slot.task)) {
                        "completed", "succeeded" -> TaskStatus.SUCCEEDED
                        "failed", "max_steps" -> TaskStatus.FAILED
                        else -> TaskStatus.INTERRUPTED
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: TaskInterruptedException) {
                    status = TaskStatus.INTERRUPTED
                    error = interrupted.message
                } catch (failure: Exception) {
                    status = TaskStatus.FAILED
                    error = failure.message ?: failure.javaClass.simpleName
                } finally {
                    withContext(NonCancellable) { finish(slot, status, error) }
                }
            }
        }
    }

    private suspend fun finish(slot: Active, result: String, error: String?) {
        while (true) {
            val pendingStop = control.withLock {
                val gate = slot.stopFinished
                if (gate != null && !gate.isCompleted) return@withLock gate
                val status = slot.requestedStatus ?: result
                repository.updateStatus(slot.task.id, status, error, guiHeld = false)
                try {
                    runner.finished(slot.task, status)
                } finally {
                    active = null
                    _activeTaskId.value = null
                }
                null
            }
            if (pendingStop == null) return
            pendingStop.await()
        }
    }
}
