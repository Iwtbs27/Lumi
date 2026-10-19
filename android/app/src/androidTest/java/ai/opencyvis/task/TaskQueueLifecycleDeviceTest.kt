package ai.opencyvis.task

import android.content.Context
import android.os.Looper
import ai.opencyvis.db.AppDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android SQLite and Main dispatcher integration; the runner does not operate a GUI. */
@RunWith(AndroidJUnit4::class)
class TaskQueueLifecycleDeviceTest {
    private lateinit var app: Context
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var scope: CoroutineScope
    private var coordinator: TaskCoordinator? = null

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        directory = File(app.cacheDir, "task_queue_device_${UUID.randomUUID()}").apply { check(mkdirs()) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        openDatabase()
    }

    @After fun tearDown(): Unit = runBlocking {
        try {
            withTimeout(10_000) { withContext(Dispatchers.Main.immediate) { coordinator?.close() } }
        } finally {
            scope.cancel()
            withContext(Dispatchers.IO) {
                database.close()
                directory.deleteRecursively()
            }
        }
    }

    @Test fun fifoCancellationAndExplicitSuspensionKeepTaskOutputsSeparate() = runBlocking {
        val runner = FileRunner(repository, File(directory, "outputs"))
        val queue = createCoordinator(runner)
        val tasks = withContext(Dispatchers.Main.immediate) {
            listOf(queue.enqueue(TaskRequest("first task")), queue.enqueue(TaskRequest("cancelled task")),
                queue.enqueue(TaskRequest("third task")))
        }
        val first = tasks[0]
        val second = tasks[1]
        val third = tasks[2]
        withContext(Dispatchers.Main.immediate) { queue.initialize() }
        assertEquals(first.id, runner.awaitStart().id)
        withContext(Dispatchers.Main.immediate) {
            queue.cancel(second.id)
            queue.reportState(first.id, TaskStatus.WAITING_USER)
            queue.kick()
        }
        assertOwner(queue, first.id, TaskStatus.WAITING_USER)
        assertEquals(TaskStatus.QUEUED, repository.get(third.id)!!.status)
        assertEquals(listOf(first.id), runner.startedIds())

        withContext(Dispatchers.Main.immediate) { queue.pause(first.id); queue.kick() }
        assertOwner(queue, first.id, TaskStatus.PAUSED)
        assertEquals(listOf(first.id), runner.startedIds())
        withContext(Dispatchers.Main.immediate) { queue.resume(first.id) }
        assertOwner(queue, first.id, TaskStatus.RUNNING)
        assertEquals(listOf(first.id), runner.startedIds())

        withContext(Dispatchers.Main.immediate) { queue.suspendAndRunNext(first.id) }
        assertEquals(third.id, runner.awaitStart().id)
        val parked = repository.get(first.id)!!
        assertEquals(TaskStatus.PAUSED, parked.status)
        assertFalse(parked.guiHeld)
        assertOwner(queue, third.id, TaskStatus.RUNNING)
        assertEquals(TaskStatus.CANCELLED, repository.get(second.id)!!.status)
        assertFalse(File(directory, "outputs/task-${second.id}").exists())

        withContext(Dispatchers.Main.immediate) { runner.complete("completed") }
        awaitStatus(third.id, TaskStatus.SUCCEEDED)
        withContext(Dispatchers.Main.immediate) { queue.recover(first.id) }
        assertEquals(first.id, runner.awaitStart().id)
        withContext(Dispatchers.Main.immediate) { runner.complete("completed") }
        awaitStatus(first.id, TaskStatus.SUCCEEDED)
        assertEquals(listOf(first.id, third.id, first.id), runner.startedIds())
        withContext(Dispatchers.IO) {
            val firstOutputs = File(directory, "outputs/task-${first.id}").listFiles()!!.sortedBy { it.name }
            assertEquals(2, firstOutputs.size)
            assertTrue(firstOutputs.all { it.readText().contains("task_id=${first.id}\ninstruction=first task\n") })
            val thirdOutput = File(directory, "outputs/task-${third.id}/attempt-1.txt").readText()
            assertTrue(thirdOutput.contains("task_id=${third.id}\ninstruction=third task\n"))
            assertTrue(thirdOutput.contains("outcome=completed"))
        }
    }

    @Test fun reopeningDatabaseRecoversOwnersAndKeepsOriginalTaskIdentity() = runBlocking {
        val running = repository.enqueue(TaskRequest("running task", conversationId = 71))
        val waiting = repository.enqueue(TaskRequest("waiting task", conversationId = 72))
        val paused = repository.enqueue(TaskRequest("paused owner"))
        val parked = repository.enqueue(TaskRequest("parked task"))
        val queued = repository.enqueue(TaskRequest("queued task"))
        repository.updateStatus(running.id, TaskStatus.RUNNING)
        repository.updateStatus(waiting.id, TaskStatus.WAITING_USER)
        repository.updateStatus(paused.id, TaskStatus.PAUSED)
        repository.updateStatus(parked.id, TaskStatus.PAUSED, guiHeld = false)
        for (task in listOf(running, waiting, paused)) repository.attachRun(task.id, "previous-run-${task.id}")
        withContext(Dispatchers.IO) { database.close() }
        openDatabase()

        val runner = FileRunner(repository, File(directory, "outputs"))
        val queue = createCoordinator(runner)
        withContext(Dispatchers.Main.immediate) { queue.initialize() }
        assertEquals(queued.id, runner.awaitStart().id)
        for (original in listOf(running, waiting, paused)) {
            val restored = repository.get(original.id)!!
            assertEquals(original.id, restored.id)
            assertEquals(original.instruction, restored.instruction)
            assertEquals(original.conversationId, restored.conversationId)
            assertEquals("previous-run-${original.id}", restored.runId)
            assertEquals(TaskStatus.INTERRUPTED, restored.status)
            assertFalse(restored.guiHeld)
        }
        assertEquals(TaskStatus.PAUSED, repository.get(parked.id)!!.status)
        assertFalse(repository.get(parked.id)!!.guiHeld)
        assertEquals(listOf(queued.id), runner.startedIds())

        withContext(Dispatchers.Main.immediate) { queue.recover(waiting.id) }
        val requeued = repository.get(waiting.id)!!
        assertEquals(TaskStatus.QUEUED, requeued.status)
        assertEquals("previous-run-${waiting.id}", requeued.runId)
        assertEquals(72L, requeued.conversationId)
        withContext(Dispatchers.Main.immediate) { runner.complete("completed") }
        assertEquals(waiting.id, runner.awaitStart().id)
        assertOwner(queue, waiting.id, TaskStatus.RUNNING)
        assertEquals(listOf(queued.id, waiting.id), runner.startedIds())
        assertTrue(File(directory, "queue.db").isFile)
    }

    private fun openDatabase() {
        // An absolute path puts SQLite, WAL and SHM files in this test's cache directory.
        database = Room.databaseBuilder(app, AppDatabase::class.java, File(directory, "queue.db").absolutePath).build()
        repository = TaskRepository(database.taskDao())
    }

    private suspend fun createCoordinator(runner: TaskRunner): TaskCoordinator = withContext(Dispatchers.Main.immediate) {
        TaskCoordinator(scope, repository, runner).also { coordinator = it }
    }

    private suspend fun assertOwner(queue: TaskCoordinator, id: Long, status: String) {
        val task = repository.get(id)!!
        assertEquals(status, task.status)
        assertTrue(task.guiHeld)
        assertEquals(id, withContext(Dispatchers.Main.immediate) { queue.activeTaskId.value })
    }

    private suspend fun awaitStatus(id: Long, status: String) = withTimeout(10_000) {
        repository.tasks.first { tasks -> tasks.any { it.id == id && it.status == status } }
    }

    private class FileRunner(private val repository: TaskRepository, private val output: File) : TaskRunner {
        private data class Run(val completion: CompletableDeferred<String> = CompletableDeferred(),
                               val exited: CompletableDeferred<Unit> = CompletableDeferred())
        private val starts = Channel<TaskRecord>(Channel.UNLIMITED)
        private val started = mutableListOf<Long>()
        private val attempts = mutableMapOf<Long, Int>()
        private var active: Run? = null

        override suspend fun run(task: TaskRecord): String {
            assertMainThread()
            check(active == null)
            val run = Run().also { active = it }
            val attempt = (attempts[task.id] ?: 0) + 1
            attempts[task.id] = attempt
            started += task.id
            try {
                repository.attachRun(task.id, "local-fake-${task.id}-$attempt")
                val file = File(output, "task-${task.id}/attempt-$attempt.txt")
                withContext(Dispatchers.IO) {
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    file.writeText("task_id=${task.id}\ninstruction=${task.instruction}\nattempt=$attempt\n")
                }
                starts.send(task)
                val result = run.completion.await()
                withContext(Dispatchers.IO) { file.appendText("outcome=$result\n") }
                return result
            } finally {
                active = null
                run.exited.complete(Unit)
            }
        }

        override suspend fun pause() { assertMainThread(); check(active != null) }
        override suspend fun resume() { assertMainThread(); check(active != null) }
        override suspend fun stop(suspendTask: Boolean) {
            assertMainThread()
            val run = active ?: return
            run.completion.complete("interrupted")
            run.exited.await()
        }

        fun complete(result: String) { assertMainThread(); check(requireNotNull(active).completion.complete(result)) }
        suspend fun awaitStart(): TaskRecord = withTimeout(10_000) { starts.receive() }
        suspend fun startedIds(): List<Long> = withContext(Dispatchers.Main.immediate) { started.toList() }
        private fun assertMainThread() = assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
}
