package ai.opencyvis

import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.learning.LearningSessionController
import ai.opencyvis.task.TaskCoordinator
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskRequest
import ai.opencyvis.task.TaskStatus
import ai.opencyvis.ui.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class AgentServiceQueueTest {
    private fun set(service: AgentService, name: String, value: Any) {
        AgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun service(coordinator: TaskCoordinator, engine: AgentEngine): AgentService {
        val config = mockk<ConfigRepository>()
        every { config.contextBudget } returns ContextBudgetConfig(128000, 16000)
        return AgentService().also {
            set(it, "config", config)
            set(it, "taskCoordinator", coordinator)
            set(it, "engine", engine)
            @Suppress("UNCHECKED_CAST")
            val ready = AgentService::class.java.getDeclaredField("queueReady").apply { isAccessible = true }
                .get(it) as CompletableDeferred<Unit>
            ready.complete(Unit)
        }
    }

    private fun task(id: Long) = TaskRecord(id = id, instruction = "任务 $id", status = TaskStatus.RUNNING,
        createdAt = 1, updatedAt = 1)

    private fun activeCoordinator(id: Long) = mockk<TaskCoordinator>().also {
        every { it.activeTaskId } returns MutableStateFlow<Long?>(id)
    }

    @Test fun `enqueue while another engine is active never stops or replaces it`() = runBlocking {
        val coordinator = mockk<TaskCoordinator>()
        val engine = mockk<AgentEngine>(relaxed = true)
        val request = TaskRequest("第二项任务")
        val receipt = mockk<TaskRecord>()
        coEvery { coordinator.enqueue(request) } returns receipt
        val service = service(coordinator, engine)

        assertSame(receipt, service.enqueueTask(request))
        assertSame(engine, service.getEngine())
        coVerify(exactly = 1) { coordinator.enqueue(request) }
        verify(exactly = 0) { engine.stop() }
        verify(exactly = 0) { engine.destroy() }
    }

    @Test fun `answer and supplement for an old task never reach the current engine`() {
        val coordinator = mockk<TaskCoordinator>()
        every { coordinator.activeTaskId } returns MutableStateFlow<Long?>(20L)
        val engine = mockk<AgentEngine>(relaxed = true)
        val service = service(coordinator, engine)

        assertFalse(service.answerTask(10L, "旧任务的回答"))
        assertFalse(service.supplementTask(10L, "旧任务的补充"))
        verify(exactly = 0) { engine.submitUserResponse(any()) }
        verify(exactly = 0) { engine.submitUserSupplement(any()) }
    }

    @Test fun `cancel before run registration never creates or destroys an engine`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        val service = service(activeCoordinator(20), engine)
        service.stopCurrentRun(suspendTask = false)

        assertEquals("interrupted", service.runQueuedTask(task(20)))
        verify(exactly = 0) { engine.destroy() }
        verify(exactly = 0) { engine.start(any(), any()) }
    }

    @Test fun `cancelling a task waiting for teaching keeps the teaching display`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        val service = service(activeCoordinator(20), engine)
        val learning = mockk<LearningSessionController>()
        every { learning.sessionId } returns "teaching-in-progress"
        val display = mockk<VirtualDisplayManager>(relaxed = true)
        set(service, "learning\$delegate", lazyOf(learning))
        set(service, "virtualDisplayManager", display)
        set(service, "displayState", AgentService.DisplayState.TAKEOVER)
        val execution = async { service.runQueuedTask(task(20)) }
        runCurrent()
        assertFalse(execution.isCompleted)

        service.stopCurrentRun(suspendTask = false)
        assertEquals("interrupted", execution.await())
        assertSame(display, service.getVirtualDisplayManager())
        assertEquals(AgentService.DisplayState.TAKEOVER, service.displayState)
        verify(exactly = 0) { display.destroy() }
        coVerify(exactly = 0) { engine.pauseAndAwaitInput() }
    }

    @Test fun `cancel after terminal outcome before terminal event does not wait forever`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.lastOutcome } returns "completed"
        val service = service(activeCoordinator(20), engine)
        set(service, "requestedStopTaskId", 20L)
        set(service, "historyTerminalWritten", CompletableDeferred<Unit>())

        val settled = async { service.settleRunHistory(20, engine) }
        runCurrent()
        assertTrue(settled.isCompleted)
        settled.await()
    }

    @Test fun `queue waits while teaching service cleanup remains after session was cleared`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        val service = service(activeCoordinator(20), engine)
        val learning = mockk<LearningSessionController>()
        every { learning.sessionId } returns null
        val display = mockk<VirtualDisplayManager>(relaxed = true)
        set(service, "learning\$delegate", lazyOf(learning))
        set(service, "virtualDisplayManager", display)
        // LearningSessionController has closed the session; AgentService still owns its cleanup.
        set(service, "finishingLearning", true)
        val execution = async { service.runQueuedTask(task(20)) }
        runCurrent()
        assertFalse(execution.isCompleted)

        service.stopCurrentRun(suspendTask = false)
        assertEquals("interrupted", execution.await())
        verify(exactly = 0) { display.destroy() }
        verify(exactly = 0) { engine.destroy() }
    }

    @Test fun `normal completion waits for the final history write`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.lastOutcome } returns "completed"
        val service = service(activeCoordinator(20), engine)
        val written = CompletableDeferred<Unit>()
        set(service, "historyTerminalWritten", written)
        val settled = async { service.settleRunHistory(20, engine) }
        runCurrent()
        assertFalse(settled.isCompleted)

        written.complete(Unit)
        settled.await()
    }

    @Test fun `stopped task history waits for the accepted supplement to be saved`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.isTaskActive } returns true
        every { engine.lastOutcome } returns "interrupted"
        val history = mockk<ChatHistoryRepository>()
        val entered = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        coEvery { history.addMessage(87, MessageType.USER_SUPPLEMENT, "保持币种") } coAnswers {
            entered.complete(Unit)
            saved.await()
        }
        val service = service(activeCoordinator(20), engine)
        set(service, "scope", this)
        set(service, "historyRepo", history)
        set(service, "activeRunEngine", engine)
        set(service, "currentConversationId", 87L)
        every { engine.submitUserSupplement("保持币种") } answers {
            assertTrue("History must be registered before the engine consumes input", entered.isCompleted)
        }

        try {
            assertTrue(service.supplementTask(20, "保持币种"))
            set(service, "requestedStopTaskId", 20L)
            val settled = async { service.settleRunHistory(20, engine) }
            runCurrent()
            assertFalse(settled.isCompleted)

            saved.complete(Unit)
            settled.await()
            coVerify(exactly = 1) { history.addMessage(87, MessageType.USER_SUPPLEMENT, "保持币种") }
        } finally { saved.complete(Unit) }
    }

    @Test fun `supplement queued behind another history write is drained before task cleanup`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.isTaskActive } returns true
        every { engine.lastOutcome } returns "interrupted"
        val history = mockk<ChatHistoryRepository>()
        val entered = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        coEvery { history.addMessage(87, MessageType.USER_SUPPLEMENT, "保留原单位") } coAnswers {
            entered.complete(Unit)
            saved.await()
        }
        val service = service(activeCoordinator(20), engine)
        set(service, "scope", this)
        set(service, "historyRepo", history)
        set(service, "activeRunEngine", engine)
        set(service, "currentConversationId", 87L)
        val writes = AgentService::class.java.getDeclaredField("historyWrites")
            .apply { isAccessible = true }.get(service) as Mutex
        writes.lock()
        var initialWriteHeld = true
        try {
            assertTrue(service.supplementTask(20, "保留原单位"))
            val settled = async { service.settleRunHistory(20, engine) }
            runCurrent()
            assertFalse(entered.isCompleted)
            assertFalse(settled.isCompleted)

            writes.unlock()
            initialWriteHeld = false
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(settled.isCompleted)
            saved.complete(Unit)
            settled.await()
        } finally {
            saved.complete(Unit)
            if (initialWriteHeld) writes.unlock()
        }
    }

    @Test fun `failed execution exposes the engine error for task persistence`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.lastOutcome } returns "failed"
        every { engine.state } returns MutableStateFlow<AgentState>(AgentState.Error("LLM error: quota exceeded"))
        val service = service(activeCoordinator(20), engine)

        val failure = runCatching { service.awaitExecutionResult(engine) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("LLM error: quota exceeded", failure?.message)
        coVerify(exactly = 1) { engine.awaitStopped() }
    }

    @Test fun `step limit exposes its specific reason only after engine finalization`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        val stopped = CompletableDeferred<Unit>()
        val state = MutableStateFlow<AgentState>(AgentState.Running(25, "processing"))
        every { engine.state } returns state
        every { engine.lastOutcome } returns "max_steps"
        coEvery { engine.awaitStopped() } coAnswers {
            stopped.await()
            state.value = AgentState.Idle("Reached maximum steps (25)")
        }
        val service = service(activeCoordinator(20), engine)
        val result = async { runCatching { service.awaitExecutionResult(engine) } }
        runCurrent()
        assertFalse(result.isCompleted)

        stopped.complete(Unit)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Reached maximum steps (25)", failure?.message)
    }

    @Test fun `successful execution preserves its outcome`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        every { engine.lastOutcome } returns "completed"
        val service = service(activeCoordinator(20), engine)

        assertEquals("completed", service.awaitExecutionResult(engine))
        coVerify(exactly = 1) { engine.awaitStopped() }
    }

    @Test fun `restored conversation becomes running only after startup pause is released and engine starts`() = runTest {
        val engine = mockk<AgentEngine>(relaxed = true)
        val history = mockk<ChatHistoryRepository>(relaxed = true)
        val service = service(activeCoordinator(20), engine)
        set(service, "historyRepo", history)
        var engineStarted = false
        every { engine.start("恢复任务", null) } answers { engineStarted = true }
        coEvery { history.updateStatus(87, "running") } coAnswers { assertTrue(engineStarted) }
        service.pauseCurrentRun()
        val started = async {
            service.withStartupPermission(20) {
                service.startEngineRun(engine, "恢复任务", null, 87)
            }
        }
        runCurrent()
        assertFalse(engineStarted)
        coVerify(exactly = 0) { history.updateStatus(any(), any()) }

        service.resumeCurrentRun()

        assertTrue(started.await())
        coVerify(exactly = 1) { history.updateStatus(87, "running") }
        coVerify(exactly = 0) { history.startConversation(any()) }
    }

    @Test fun `pause during startup blocks the actual start until resume`() = runTest {
        val previousEngine = mockk<AgentEngine>(relaxed = true)
        val service = service(activeCoordinator(20), previousEngine)
        service.pauseCurrentRun()
        var started = false
        val start = async { service.withStartupPermission(20) { started = true } }
        runCurrent()
        assertFalse(started)
        coVerify(exactly = 0) { previousEngine.pauseAndAwaitInput() }

        service.resumeCurrentRun()
        assertTrue(start.await())
        assertTrue(started)
        verify(exactly = 0) { previousEngine.resume() }
    }
}
