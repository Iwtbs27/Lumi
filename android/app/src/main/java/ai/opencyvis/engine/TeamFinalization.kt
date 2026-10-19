package ai.opencyvis.engine

import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.LlmRequestSchema
import ai.opencyvis.llm.ModelBudgetExceededException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** The host may spend the parent's one reserved request on a partial-progress summary. */
internal object TeamFinalization {
    suspend fun summarize(client: LLMClientInterface, task: TaskState, runtime: TaskTeamRuntime,
        store: TaskRunStore?, step: Int): String {
        val prefix = "任务已达到累计模型额度，已停止后续操作。"
        val output = minOf(1024, requireNotNull(client.contextBudget).outputReserveTokens)
        val tool = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "finalize_task").put("description", "Report partial progress after the task budget was exhausted")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("summary", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("summary")))))
        val messages = listOf(
            mapOf<String, Any>("role" to "system", "content" to
                "Summarize the recorded progress and remaining work in the user's language. No further actions will run. " +
                "Distinguish confirmed facts from unknown effects. Do not claim task completion or invent evidence."),
            mapOf<String, Any>("role" to "user", "content" to JSONObject(mapOf(
                "task" to task.instruction, "user_updates" to task.userUpdates, "recorded_state" to task.snapshot())).toString()))
        return try {
            val estimate = ContextTokenEstimator().estimate(messages, tool).totalTokens
            if (estimate.toLong() + output > runtime.config.finalizationReserveTokens ||
                estimate > requireNotNull(client.contextBudget).usableInputTokens) {
                throw ModelBudgetExceededException("收尾额度不足以容纳当前进度；原始任务记录仍可回查。")
            }
            val result = client.chatWithSchema(messages, LlmRequestSchema(tool, output, maxAttempts = 1,
                maxOutputTokensLimit = output, purpose = LlmCallPurpose.FINALIZATION))
            val summary = requireNotNull(result["summary"] as? String) { "收尾响应缺少摘要" }
            store?.recordEvent(step, "team_finalization", mapOf("summary" to summary))
            "$prefix\n$summary"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            store?.recordEvent(step, "team_finalization_error", mapOf("message" to failure.message))
            "$prefix ${failure.message}"
        } finally {
            client.callAttempts.filter { it.purpose == LlmCallPurpose.FINALIZATION }.forEach { call ->
                store?.recordEvent(step, "team_finalization_request", mapOf("request" to call.request,
                    "input_tokens" to call.usage?.inputTokens, "output_tokens" to call.usage?.outputTokens,
                    "usage_complete" to call.usageComplete, "reservation_id" to call.reservationId,
                    "owner_id" to call.ownerId))
            }
        }
    }
}
