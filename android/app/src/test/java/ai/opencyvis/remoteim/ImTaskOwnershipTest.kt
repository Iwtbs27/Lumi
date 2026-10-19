package ai.opencyvis.remoteim

import ai.opencyvis.AgentService
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult
import ai.opencyvis.task.TaskRecord
import ai.opencyvis.task.TaskStatus
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImTaskOwnershipTest {
    private val dispatcher = StandardTestDispatcher()
    private val manager = mockk<ImChannelManager>(relaxed = true)
    private val strings = mockk<ImStringProvider>(relaxed = true)
    private val service = mockk<AgentService>(relaxed = true)
    private val active = MutableStateFlow<Long?>(1)
    private val records = MutableStateFlow(listOf(record(1, "telegram", "chat-a"), record(2, "feishu", "chat-b")))
    private val first = EngineFlows()
    private val second = EngineFlows()
    private val engines = MutableStateFlow<AgentEngine?>(first.engine)
    private lateinit var bridge: ImAgentBridge

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { service.activeTaskId } returns active
        every { service.tasks } returns records
        every { service.engineFlow } returns engines
        coEvery { service.getTask(any()) } answers { records.value.first { it.id == firstArg<Long>() } }
        every { strings.notifyAskUser(any()) } answers { "question: ${firstArg<String>()}" }
        every { strings.notifyTaskComplete(any()) } returns "completed"
        every { strings.stopped() } returns "stopped"
        bridge = ImAgentBridge(manager, mockk<ConfigRepository>(relaxed = true), strings)
        bridge.bind(service)
    }

    @After fun close() {
        bridge.unbind()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun queueingAnotherChatDoesNotReplaceTheRunningConversation() = runTest(dispatcher) {
        runCurrent()
        coEvery { service.enqueueTask(any()) } returns records.value[1]
        assertEquals(2L, bridge.startTask("second", "feishu", "chat-b"))
        assertTrue(bridge.ownsActiveSession("telegram", "chat-a"))
        assertFalse(bridge.ownsActiveSession("feishu", "chat-b"))
        assertFalse(bridge.answerAskUser("foreign answer", "feishu", "chat-b"))
        verify(exactly = 0) { service.answerTask(any(), any()) }
        assertTrue(bridge.stop("telegram", "chat-a"))
        verify { service.cancelTask(1L) }
    }

    @Test fun switchingEngineCancelsOldCollectorsAndPendingProgressMessage() = runTest(dispatcher) {
        runCurrent()
        first.steps.emit(StepResult(1, "wait", "old progress", true, "", 1, false))
        runCurrent()
        active.value = 2
        engines.value = second.engine
        runCurrent()
        first.state.value = AgentState.WaitingForUser("old question", 1)
        second.state.value = AgentState.WaitingForUser("new question", 1)
        advanceTimeBy(900)
        runCurrent()
        coVerify { manager.sendText("feishu", "chat-b", "question: new question") }
        coVerify(exactly = 0) { manager.sendText(any(), any(), "question: old question") }
        coVerify(exactly = 0) { manager.sendText(any(), any(), "old progress") }
    }

    @Test fun cancelledTaskNotificationKeepsItsOwnChatAndIsNotCalledSuccess() = runTest(dispatcher) {
        runCurrent()
        active.value = 2
        engines.value = second.engine
        runCurrent()
        records.value = records.value.map { if (it.id == 1L) it.copy(status = TaskStatus.CANCELLED) else it }
        runCurrent()
        coVerify { manager.sendText("telegram", "chat-a", "stopped") }
        coVerify(exactly = 0) { manager.sendText("feishu", "chat-b", "stopped") }
        coVerify(exactly = 0) { manager.sendText(any(), any(), "completed") }
    }

    private class EngineFlows {
        val state = MutableStateFlow<AgentState>(AgentState.Idle())
        val steps = MutableSharedFlow<StepResult>(extraBufferCapacity = 4)
        val engine = mockk<AgentEngine>().also {
            every { it.state } returns state
            every { it.stepResults } returns steps
            every { it.stepScreenshots } returns MutableSharedFlow<ByteArray>()
        }
    }

    companion object {
        private fun record(id: Long, channel: String, chat: String) = TaskRecord(
            id = id, instruction = "task $id", status = TaskStatus.RUNNING,
            source = "im:$channel", remoteChatId = chat, createdAt = 1, updatedAt = 1
        )
    }
}
