package ai.opencyvis.team

import ai.opencyvis.config.ContextBudgetConfig
import ai.opencyvis.llm.LlmRequestSchema

data class CodingTaskSpec(
    val mode: String,
    val parameters: Map<String, Any?>,
    val completionConditions: List<String>,
    val maxExecutions: Int,
    val timeoutMs: Long,
    val sourceProgram: Map<String, Any?>? = null,
)

/** The manager retains model accounting, cancellation, source tracking and result persistence. */
class ChildWorkerSession(
    val maxSteps: Int,
    val contextBudget: ContextBudgetConfig?,
    val chat: suspend (Int, List<Map<String, Any>>, LlmRequestSchema) -> Map<String, Any?>,
    val read: suspend (MaterialRef, Int, Int) -> MaterialPage,
    val event: suspend (String, Map<String, Any?>) -> Unit,
)

fun interface ChildWorker {
    suspend fun execute(request: DelegationRequest, session: ChildWorkerSession): ChildResult
}
