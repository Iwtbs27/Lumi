package ai.opencyvis.execution.coding

import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.execution.code.CodeExecutionResult
import ai.opencyvis.execution.program.ProgramVersion
import org.json.JSONObject

/** Actual process execution, capabilities and output access belong to the parent task's host. */
interface CodingEnvironment {
    suspend fun describeCapabilities(): JSONObject
    suspend fun execute(program: ProgramVersion, params: JSONObject, timeoutMs: Long): CodingExecution
    suspend fun readOutput(reference: String, offset: Int, limit: Int): ContextTextPage
}

data class CodingExecution(
    val execution: CodeExecutionResult,
    val artifactRefs: List<String> = emptyList(),
    val operationIds: List<String> = emptyList(),
)
