package ai.opencyvis.llm

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

data class LlmExecutionScope(
    val budget: TaskModelBudget,
    val ownerId: String,
    val role: LlmOwnerRole = LlmOwnerRole.PARENT,
    val governor: LlmRequestGovernor = LlmRequestGovernor.processWide
)

/** Shared across owners; a permit covers one actual HTTP attempt and response-body consumption. */
class LlmRequestGovernor(maxConcurrentRequests: Int) {
    private val semaphore = Semaphore(maxConcurrentRequests)
    private val active = AtomicInteger()
    val activeRequests: Int get() = active.get()

    internal suspend fun <T> withRequest(block: suspend () -> T): T {
        semaphore.acquire()
        active.incrementAndGet()
        try { return block() }
        finally { active.decrementAndGet(); semaphore.release() }
    }

    companion object {
        val processWide = LlmRequestGovernor(2)
    }
}
