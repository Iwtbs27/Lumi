package ai.opencyvis.engine

import android.content.Context
import ai.opencyvis.config.TeamConfig
import ai.opencyvis.execution.TaskExecutionRuntime
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmExecutionScope
import ai.opencyvis.llm.LlmOwnerRole
import ai.opencyvis.llm.TaskModelBudget
import ai.opencyvis.llm.TaskModelBudgetSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** A task keeps its original team settings and cumulative allowance across new runs. */
class TaskTeamRuntime private constructor(
    private val directory: File,
    private val taskId: Long,
    val config: TeamConfig,
    val budget: TaskModelBudget,
    private val clients: (LlmExecutionScope?) -> LLMClientInterface,
) {
    fun parentClient(): LLMClientInterface = clients(LlmExecutionScope(budget, "task:$taskId:parent"))

    fun session(store: TaskRunStore, revision: () -> String, scope: CoroutineScope,
        execution: TaskExecutionRuntime? = null,
        constraints: () -> List<String>): TeamSession = TeamSession(File(directory, "children"),
        store, config, scope, revision, constraints, execution) { request ->
        clients(LlmExecutionScope(budget, "task:$taskId:child:${request.childId}", LlmOwnerRole.CHILD))
    }

    companion object {
        private const val ROOT = "task_teams"

        fun open(context: Context, taskId: Long, resuming: Boolean, configured: TeamConfig?,
            clients: (LlmExecutionScope?) -> LLMClientInterface): TaskTeamRuntime? {
            require(taskId > 0)
            val directory = File(context.filesDir, "$ROOT/$taskId").apply { mkdirs() }
            val modeFile = File(directory, "mode.json")
            val config = if (modeFile.exists()) {
                val saved = JSONObject(modeFile.readText())
                if (saved.getBoolean("enabled")) TeamConfig.fromJson(saved.getJSONObject("config")) else null
            } else {
                // Pre-M3 checkpoints have no team allowance; never invent a fresh one on resume.
                val initial = if (resuming) null else configured
                write(modeFile, JSONObject().put("enabled", initial != null)
                    .put("config", initial?.toJson() ?: JSONObject.NULL))
                initial
            } ?: return null
            val budgetFile = File(directory, "budget.json")
            val saved = if (budgetFile.exists()) TaskModelBudgetSnapshot.fromJson(JSONObject(budgetFile.readText()))
                else {
                    require(!resuming) { "任务的累计额度记录缺失，不能重新分配预算。" }
                    null
                }
            val budget = TaskModelBudget(config.totalTokenBudget, config.finalizationReserveTokens, saved) {
                write(budgetFile, it.toJson())
            }
            write(budgetFile, budget.snapshot().toJson())
            return TaskTeamRuntime(directory, taskId, config, budget, clients)
        }

        internal fun delete(context: Context, taskId: Long) {
            require(taskId > 0)
            File(context.filesDir, "$ROOT/$taskId").deleteRecursively()
        }

        private fun write(file: File, value: JSONObject) {
            TaskRunStore.writeAtomic(file, value.toString())
        }
    }
}
