package ai.opencyvis.memory

import ai.opencyvis.llm.LLMClientInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Extracts proposals, never writes memories directly. Repository owns revision/priority rules. */
internal class MemoryConsolidator(private val repository: MemoryRepository) {
    data class Batch(
        val id: String,
        val proposals: List<MemoryProposal>,
        val checkpoint: String,
        val finished: Boolean
    )

    suspend fun run(job: MemoryJob, client: LLMClientInterface): Batch? {
        val payload = JSONObject(job.payloadJson)
        val trajectory = payload.getJSONArray("segments")
        val usages = repository.runUsages(job.runId).filter { it.event == "outcome" }
        val snapshots = mutableMapOf<Pair<String, Int>, JSONObject>()
        for ((id, revision) in usages.map { it.memoryId to it.revision }.distinct()) {
            val current = repository.get(id) ?: continue
            val snapshot = if (current.revision == revision) MemoryBodies.snapshot(current)
                else repository.revisions(id).firstOrNull { it.revision == revision }?.snapshotJson ?: continue
            snapshots[id to revision] = JSONObject(snapshot).put("current_revision", current.revision)
        }
        val segments = MemoryConsolidationInput.feedbackReviews(trajectory, usages, snapshots)
        for (i in 0 until trajectory.length()) segments.put(trajectory.getJSONObject(i))
        val instruction = payload.getString("instruction")
        val batches = MemoryConsolidationInput.batches(segments, instruction)
        val checkpoint = JSONObject(job.checkpointJson)
        val completed = checkpoint.optJSONArray("completed_batches") ?: JSONArray()
        val completedIds = (0 until completed.length()).map { completed.getString(it) }.toSet()
        val index = batches.indices.firstOrNull { it.toString() !in completedIds } ?: return null
        val batchContext = (0 until batches[index].length()).mapNotNull {
            batches[index].getJSONObject(it).optJSONObject("memory_context")?.let { context ->
                listOf(context.optString("app"), context.optString("page")).joinToString(" ")
            }
        }.distinct().joinToString(" ")
        val related = relatedMemories("$batchContext $instruction", payload.optString("app_scope").takeIf { it.isNotBlank() })
        val input = JSONObject()
            .put("run_id", job.runId).put("instruction", instruction.take(2400))
            .put("task_outcome", payload.optString("result_status"))
            .put("batch", index + 1).put("batch_count", batches.size)
            .put("segments", batches[index]).put("related_memories", related)
        val inputText = input.toString()
        checkpoint.put("batch_count", batches.size).put("input_chars", inputText.length)
        val messages = listOf(
            mapOf("role" to "system", "content" to MemoryConsolidationSchema.prompt),
            mapOf("role" to "user", "content" to inputText)
        )
        val evidence = repository.getEvidence(job.runId)
        val existing = (0 until related.length()).associate {
            val value = related.getJSONObject(it)
            value.getString("id") to value.getInt("revision")
        }.toMutableMap()
        for (i in 0 until batches[index].length()) {
            val segment = batches[index].getJSONObject(i)
            val memory = segment.optJSONObject("memory") ?: segment.optJSONObject("memory_reference") ?: continue
            if (memory.optInt("current_revision") == memory.getInt("revision"))
                existing[memory.getString("id")] = memory.getInt("revision")
        }
        val batchId = index.toString()
        var output = checkpoint.optJSONObject("last_response")
            ?.takeIf { checkpoint.optString("response_batch") == batchId }
            ?: request(job, client, checkpoint, batchId, messages)
        val proposals = try {
            parseOutput(job, output, segments, evidence, existing)
        } catch (invalid: IllegalArgumentException) {
            require(checkpoint.optString("repair_batch") != batchId) {
                "本批次已自动纠正过一次格式，请手动重试：${invalid.message}"
            }
            checkpoint.put("last_validation_error", invalid.message).put("last_invalid_response", output)
            saveCheckpoint(job.id, checkpoint)
            val repairInput = JSONObject().put("validation_error", invalid.message).put("invalid_output", output).toString()
            output = request(job, client, checkpoint, batchId, listOf(
                mapOf("role" to "system", "content" to MemoryConsolidationSchema.prompt +
                    "\n本轮只修正返回格式。按 validation_error 补齐字段并返回完整结果，保持已有事实和来源步骤；不要添加新经历。"),
                mapOf("role" to "user", "content" to repairInput)), repair = true)
            try {
                parseOutput(job, output, segments, evidence, existing)
            } catch (stillInvalid: IllegalArgumentException) {
                checkpoint.put("last_validation_error", stillInvalid.message).put("last_invalid_response", output)
                saveCheckpoint(job.id, checkpoint)
                throw stillInvalid
            }
        }
        val summaries = checkpoint.optJSONArray("batch_summaries") ?: JSONArray()
        summaries.put(JSONObject().put("batch_id", index.toString())
            .put("summary", output.getString("episode_summary").take(2000)))
        checkpoint.put("batch_summaries", summaries)
            .put("network_failures", 0)
            .put("conflicts", output.optJSONArray("conflicts") ?: JSONArray())
        return Batch(index.toString(), proposals, checkpoint.toString(), completedIds.size + 1 == batches.size)
    }

    /** Persist each call before validation, including a failed or interrupted format repair. */
    private suspend fun request(job: MemoryJob, client: LLMClientInterface, checkpoint: JSONObject,
        batchId: String, messages: List<Map<String, String>>, repair: Boolean = false): JSONObject {
        require(messages.sumOf { it.getValue("content").length } <= 20_000) { "记忆整理输入超出字符预算" }
        if (MemoryJobs.isForegroundActive()) throw CancellationException("Foreground task has priority")
        if (repair) {
            checkpoint.put("repair_batch", batchId).put("format_repairs", checkpoint.optInt("format_repairs") + 1)
            saveCheckpoint(job.id, checkpoint)
        }
        val started = System.currentTimeMillis()
        try {
            return JSONObject(client.chatWithSchema(messages, MemoryConsolidationSchema.request())).also {
                checkpoint.put("last_response", it).put("response_batch", batchId)
            }
        } finally {
            withContext(NonCancellable) {
                recordConsolidationCost(checkpoint, client, started)
                saveCheckpoint(job.id, checkpoint)
            }
        }
    }

    private suspend fun saveCheckpoint(id: String, checkpoint: JSONObject) {
        val current = repository.getJob(id) ?: return
        repository.updateJob(current.copy(checkpointJson = checkpoint.toString()))
    }

    private fun parseOutput(job: MemoryJob, output: JSONObject, segments: JSONArray,
        evidence: List<MemoryEvidence>, existing: Map<String, Int>): List<MemoryProposal> {
        require(output.opt("episode_summary") is String && output.optJSONArray("proposals") != null) {
            "记忆整理返回格式错误：需要 episode_summary 字符串和 proposals 数组"
        }
        val proposed = output.getJSONArray("proposals")
        require(proposed.length() <= 8) { "单批记忆建议超过 8 条" }
        return (0 until proposed.length()).map { position ->
            try {
                proposal(job, proposed.getJSONObject(position), segments, evidence, existing)
            } catch (invalid: JSONException) {
                throw IllegalArgumentException("proposals[$position]：${invalid.message}", invalid)
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("proposals[$position]：${invalid.message}", invalid)
            }
        }
    }

    private suspend fun relatedMemories(query: String, appScope: String?): JSONArray {
        val result = JSONArray()
        var used = 0
        // Include disabled/candidate rows so extraction cannot unknowingly bypass a prior decision.
        for (item in repository.searchForConsolidation(query, appScope, limit = 12)) {
            val value = JSONObject(MemoryBodies.snapshot(item)).put("id", item.id).put("revision", item.revision)
            val size = value.toString().length
            if (used + size > 4000) continue
            result.put(value)
            used += size
        }
        return result
    }

    private fun proposal(
        job: MemoryJob,
        value: JSONObject,
        segments: JSONArray,
        evidence: List<MemoryEvidence>,
        existing: Map<String, Int>
    ): MemoryProposal {
        val type = value.getString("type")
        require(type in setOf("fact", "procedure")) { "未知记忆类型：$type" }
        val title = value.getString("title").trim()
        val summary = value.getString("summary").trim()
        require(title.isNotEmpty() && summary.isNotEmpty()) { "记忆标题和摘要不能为空" }
        val sourceArray = value.getJSONArray("source_steps")
        val sourceSteps = (0 until sourceArray.length()).map { sourceArray.getInt(it) }.distinct()
        val knownSteps = evidence.map { it.step }.toSet()
        require(sourceSteps.all { it in knownSteps }) { "记忆建议引用了未保存的步骤" }
        val targetId = value.optString("target_id").takeIf { it.isNotBlank() }
        val revision = if (targetId != null) value.getInt("target_revision") else null
        require(targetId == null || existing[targetId] == revision) { "记忆建议引用了未提供的旧版本" }
        val body = value.getJSONObject("body")
        MemoryBodies.validate(type, body)
        val outcomeStep = value.optInt("outcome_step")
        val observed = outcomeStep in sourceSteps && confirmed(segments, sourceSteps, outcomeStep, targetId, revision, job.runId)
        val preference = type == "fact" && body.getString("domain") == "user_preference"
        val supported = observed && !preference
        if (type == "procedure") body.put("maturity", "observed")
        return MemoryProposal(
            item = MemoryItem(
                id = targetId ?: UUID.randomUUID().toString(),
                type = type, title = title, summary = summary,
                appScope = value.optString("app_scope").takeIf { it.isNotBlank() },
                taskFamily = value.optString("task_family").takeIf { it.isNotBlank() },
                status = if (supported) "active" else "candidate",
                origin = if (supported) "observed" else "inferred",
                evidenceLevel = if (supported) "observed_once" else "unverified",
                bodyJson = body.toString(),
                lastVerifiedAt = if (supported) evidence.firstOrNull { it.step == outcomeStep }?.observedAt else null
            ),
            expectedRevision = revision, reason = value.getString("reason"),
            sourceRunId = job.runId, sourceSteps = sourceSteps,
            supportScopeJson = supportedScope(segments, outcomeStep, targetId, revision).toString()
        )
    }

    /** Scope is taken from accepted runtime feedback, never expanded by the consolidation model. */
    private fun supportedScope(segments: JSONArray, outcomeStep: Int, targetId: String?, revision: Int?): JSONObject {
        if (targetId == null) return JSONObject()
        val nodes = linkedSetOf<String>()
        var whole = false
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val memory = segment.optJSONObject("memory") ?: continue
            if (segment.optString("kind") != "memory_feedback_review" || memory.optString("id") != targetId ||
                memory.optInt("revision") != revision) continue
            val feedback = segment.getJSONArray("accepted_feedback")
            for (j in 0 until feedback.length()) {
                val result = feedback.getJSONObject(j)
                if (result.optInt("evidence_step") != outcomeStep || result.optString("outcome") != "success") continue
                val scope = result.optJSONObject("details")?.optJSONObject("support_scope") ?: continue
                val supported = scope.optJSONArray("nodes") ?: JSONArray()
                for (k in 0 until supported.length()) nodes += supported.getString(k)
                whole = whole || scope.optBoolean("whole_procedure")
            }
        }
        return JSONObject().put("nodes", JSONArray(nodes.toList())).put("whole_procedure", whole)
    }

    /** Model feedback needs a later observation; host feedback carries a checked operation record. */
    private fun confirmed(segments: JSONArray, sources: List<Int>, outcomeStep: Int,
        targetId: String?, targetRevision: Int?, runId: String): Boolean {
        val observations = (0 until segments.length()).mapNotNull { segments.optJSONObject(it) }
            .filter { !it.has("kind") }.associateBy { it.optInt("step") }
        fun independent(actionStep: Int): Boolean {
            val before = observations[actionStep]?.opt("human_intervention_count")
            val after = observations[outcomeStep]?.opt("human_intervention_count")
            return before != null && before != JSONObject.NULL && before == after
        }
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val memory = segment.optJSONObject("memory")
            if (segment.optString("kind") == "memory_feedback_review" && memory != null &&
                memory.optString("id") == targetId && memory.optInt("revision") == targetRevision) {
                val feedback = segment.getJSONArray("accepted_feedback")
                for (j in 0 until feedback.length()) {
                    val result = feedback.getJSONObject(j)
                    val details = result.getJSONObject("details")
                    if (result.optString("outcome") == "success" && result.optInt("applied_step") in sources &&
                        result.optInt("applied_step") == outcomeStep && result.optInt("evidence_step") == outcomeStep &&
                        details.optBoolean("independent_execution") && verifiedOperation(details, runId, outcomeStep)) return true
                    if (result.optString("outcome") == "success" && result.optInt("applied_step") in sources &&
                        result.optInt("applied_step") < outcomeStep && result.optInt("evidence_step") == outcomeStep &&
                        details.optBoolean("independent_execution") &&
                        details.optString("source") in setOf("memory_feedback", "procedure_completion") &&
                        details.optString("evidence").isNotBlank()) return true
                }
            }
            val step = segment.optInt("step")
            val effect = segment.optJSONObject("effect")
            if (step in sources && step < outcomeStep &&
                independent(step) &&
                effect?.optString("status") == "confirmed_success" &&
                effect.optInt("evidence_step") == outcomeStep && effect.optString("evidence").isNotBlank()) return true
            val previous = segment.optJSONObject("previous_action_result")
            if (step == outcomeStep && previous?.optString("status") == "confirmed_success" &&
                independent(previous.optInt("step")) &&
                previous.optInt("step") in sources && previous.optInt("step") < step &&
                previous.optString("evidence").isNotBlank()) return true
        }
        return false
    }

    private fun verifiedOperation(details: JSONObject, runId: String, step: Int): Boolean {
        if (details.optString("source") != "program_operation" || details.optString("evidence_author") != "host_verified") return false
        val operation = details.optJSONObject("operation_evidence") ?: return false
        val issued = operation.optLong("issued_sequence")
        val finished = operation.optLong("finished_sequence")
        return operation.optString("run_id") == runId && operation.optInt("step") == step &&
            operation.optString("operation_id").isNotBlank() && operation.optString("operation_id") == details.optString("operation_id") &&
            operation.optString("execution_id").isNotBlank() && operation.optString("execution_id") == details.optString("execution_id") &&
            operation.optString("status") == "succeeded" && operation.optJSONObject("result")?.optBoolean("verified") == true &&
            issued > 0 && finished > issued && issued == details.optLong("issued_sequence") &&
            finished == details.optLong("finished_sequence") && details.optString("evidence").isNotBlank()
    }
}

/** Keep failed/cancelled requests visible in total cost, including unknown provider usage. */
internal fun recordConsolidationCost(checkpoint: JSONObject, client: LLMClientInterface, started: Long) {
    val calls = client.callAttempts
    if (calls.isEmpty()) return
    val usage = calls.mapNotNull { it.usage }
    checkpoint.put("consolidation_calls", checkpoint.optInt("consolidation_calls") + calls.size)
        .put("input_tokens", checkpoint.optLong("input_tokens") + usage.sumOf { it.inputTokens ?: 0 })
        .put("output_tokens", checkpoint.optLong("output_tokens") + usage.sumOf { it.outputTokens ?: 0 })
        .put("cached_tokens", checkpoint.optLong("cached_tokens") + usage.sumOf { it.cachedTokens ?: 0 })
        .put("usage_known", checkpoint.optBoolean("usage_known", true) && calls.all {
            it.usageComplete && it.usage?.inputTokens != null && it.usage.outputTokens != null
        })
        .put("latency_ms", checkpoint.optLong("latency_ms") + System.currentTimeMillis() - started)
}
