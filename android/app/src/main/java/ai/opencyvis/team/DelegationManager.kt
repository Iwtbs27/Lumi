package ai.opencyvis.team

import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.engine.ContextTokenEstimator
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LlmCallInfo
import ai.opencyvis.llm.LlmCallPurpose
import ai.opencyvis.llm.ResponseParser
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One parent run owns child lifecycle and model accounting; GUI authority remains with the parent. */
class DelegationManager(
    private val directory: File,
    private val rootTaskId: Long,
    private val rootRunId: String,
    private val scope: CoroutineScope,
    private val maxActive: Int = 1,
    private val maxSteps: Int = 8,
    private val currentGoalRevision: () -> String,
    private val createClient: (DelegationRequest) -> LLMClientInterface,
    private val readMaterial: suspend (DelegationRequest, MaterialRef, Int, Int) -> MaterialPage,
    private val codingWorker: ChildWorker? = null,
) {
    private class Running {
        lateinit var job: Job
        @Volatile var client: LLMClientInterface? = null
        @Volatile var cancellationReason: String? = null
    }

    init {
        require(rootTaskId > 0 && rootRunId.isNotBlank()) { "Parent task and run are required" }
        require(maxActive in 1..2 && maxSteps > 0) { "Use one or two active children and a positive step limit" }
    }

    private val control = Mutex()
    private val archive = DelegationArchive(directory, rootTaskId)
    private val children = linkedMapOf<String, DelegationRecord>()
    private val running = linkedMapOf<String, Running>()
    private var initialized = false
    private var closed = false
    private val mutableRecords = MutableStateFlow<List<DelegationRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()

    suspend fun initialize() = control.withLock {
        check(!closed) { "Delegation manager is closed" }
        if (initialized) return@withLock
        archive.load().forEach { original ->
            require(original.request.rootTaskId == rootTaskId) { "Child archive belongs to another task" }
            val recovered = if (original.status in DelegationStatus.IN_FLIGHT) {
                event(original.copy(status = DelegationStatus.INTERRUPTED, error = "宿主重启，子任务未自动恢复"),
                    "child_interrupted", mapOf("reason" to "host_restarted"))
            } else original
            children[original.request.childId] = applicable(recovered)
        }
        saveLocked()
        initialized = true
    }

    suspend fun delegate(request: DelegationRequest): DelegationRecord = control.withLock {
        check(initialized && !closed) { "Delegation manager is not accepting work" }
        require(request.rootTaskId == rootTaskId && request.rootRunId == rootRunId) { "Delegation belongs to another parent run" }
        require(request.childId.isNotBlank() && request.goal.isNotBlank() && request.goalRevision.isNotBlank()) {
            "Child ID, goal and revision are required"
        }
        require(request.goalRevision == currentGoalRevision()) { "Delegation goal revision is no longer current" }
        require(request.role in setOf("research", "coding")) { "Unknown child role" }
        if (request.role == "coding") {
            val coding = requireNotNull(request.coding) { "Coding assignment is required" }
            requireNotNull(codingWorker) { "Coding execution is unavailable" }
            require(coding.mode in setOf("data", "gui") && coding.maxExecutions in 1..10 && coding.timeoutMs in 1..300_000) {
                "Coding mode and bounded execution limits are required"
            }
            require(coding.completionConditions.isNotEmpty() && coding.completionConditions.all(String::isNotBlank)) {
                "Coding completion conditions are required"
            }
        }
        refreshLocked()
        val frozen = request.copy(goal = request.goal.trim(), constraints = request.constraints.toList(),
            materials = request.materials.distinct().toList(), coding = request.coding?.let {
                it.copy(parameters = ResponseParser.jsonObjectToMap(JSONObject(it.parameters)),
                    completionConditions = it.completionConditions.toList(),
                    sourceProgram = it.sourceProgram?.let { source -> ResponseParser.jsonObjectToMap(JSONObject(source)) })
            })
        children.values.firstOrNull { record ->
            val previous = record.request
            !record.stale && record.decision != "rejected" && record.status in setOf(DelegationStatus.QUEUED,
                DelegationStatus.RUNNING, DelegationStatus.COMPLETED, DelegationStatus.NEEDS_EVIDENCE, DelegationStatus.NEEDS_EXECUTION) &&
                previous.goalRevision == frozen.goalRevision && previous.goal == frozen.goal &&
                previous.role == frozen.role && previous.coding == frozen.coding &&
                previous.constraints.toSet() == frozen.constraints.toSet() && previous.materials.toSet() == frozen.materials.toSet()
        }?.let { return@withLock it }
        require(!children.containsKey(frozen.childId)) { "Child ID already exists with different work" }
        val now = System.currentTimeMillis()
        children[frozen.childId] = event(DelegationRecord(frozen, DelegationStatus.QUEUED,
            resultRef = "${frozen.rootRunId}:child:${frozen.childId}", createdAt = now, updatedAt = now),
            "delegation_created", DelegationJson.requestMap(frozen))
        saveLocked()
        pumpLocked()
        children.getValue(frozen.childId)
    }

    suspend fun get(childId: String): DelegationRecord? = control.withLock { refreshLocked(); children[childId] }
    suspend fun list(): List<DelegationRecord> = control.withLock { refreshLocked(); children.values.toList() }
    suspend fun refreshApplicability() = control.withLock { refreshLocked() }

    /** Explicitly supersede a source version; new independent observations do not call this. */
    suspend fun markStale(childId: String, reason: String): DelegationRecord = control.withLock {
        require(reason.isNotBlank()) { "A stale-result reason is required" }
        val record = requireNotNull(children[childId]) { "Unknown child: $childId" }
        event(record.copy(stale = true), "child_stale", mapOf("reason" to reason)).also {
            children[childId] = it
            saveLocked()
        }
    }

    suspend fun review(childId: String, accept: Boolean, reason: String): DelegationRecord = control.withLock {
        require(reason.isNotBlank()) { "父任务必须提供采用或拒绝的理由" }
        refreshLocked()
        val record = requireNotNull(children[childId]) { "Unknown child: $childId" }
        require(record.status !in DelegationStatus.IN_FLIGHT) { "Child is still running" }
        if (accept) {
            check(!closed) { "Parent run has already closed" }
            require(!record.stale && record.result != null &&
                record.status in setOf(DelegationStatus.COMPLETED, DelegationStatus.NEEDS_EVIDENCE, DelegationStatus.NEEDS_EXECUTION)) {
                "旧目标、已终止或没有有效结果的子任务不能采用"
            }
        }
        event(record.copy(decision = if (accept) "accepted" else "rejected", reviewReason = reason),
            "parent_review", mapOf("accepted" to accept, "reason" to reason)).also {
            children[childId] = it
            saveLocked()
        }
    }

    suspend fun cancel(childId: String, reason: String): DelegationRecord {
        require(reason.isNotBlank()) { "A cancellation reason is required" }
        val active = control.withLock {
            val record = requireNotNull(children[childId]) { "Unknown child: $childId" }
            if (record.status !in DelegationStatus.IN_FLIGHT) return@withLock null
            val active = running[childId]
            if (active == null) {
                children[childId] = event(record.copy(status = DelegationStatus.CANCELLED, error = reason),
                    "child_cancelled", mapOf("reason" to reason))
            } else {
                active.cancellationReason = reason
                children[childId] = event(record, "cancellation_requested", mapOf("reason" to reason))
            }
            saveLocked()
            active
        }
        if (active != null) {
            active.client?.cancelRequests()
            active.job.cancel()
            active.job.join()
        }
        return requireNotNull(get(childId))
    }

    suspend fun close(reason: String = "parent_finished") {
        val active = control.withLock {
            closed = true
            if (!initialized) return@withLock emptyList<Running>()
            children.toMap().forEach { (id, record) ->
                if (record.status in DelegationStatus.IN_FLIGHT) {
                    val slot = running[id]
                    if (slot == null) children[id] = event(record.copy(status = DelegationStatus.CANCELLED, error = reason),
                        "child_cancelled", mapOf("reason" to reason))
                    else slot.cancellationReason = reason
                }
            }
            saveLocked()
            running.values.toList()
        }
        active.forEach { it.client?.cancelRequests(); it.job.cancel() }
        active.map { it.job }.joinAll()
    }

    suspend fun readResult(childId: String, offset: Int = 0, limit: Int = 4800): ContextTextPage {
        require(limit in 1..DelegationTool.PAGE_LIMIT) { "Result page limit must be 1..4800" }
        val record = requireNotNull(get(childId)) { "Unknown child: $childId" }
        return ContextTextPage.from(record.resultRef, JSONObject(DelegationJson.recordMap(record)).toString(), offset, limit)
    }

    private fun pumpLocked() {
        if (closed) return
        while (running.size < maxActive) {
            val record = children.values.firstOrNull { it.status == DelegationStatus.QUEUED } ?: break
            val id = record.request.childId
            val slot = Running()
            running[id] = slot
            children[id] = record.copy(status = DelegationStatus.RUNNING)
            // Enter runChild's try/finally before publishing the job, even if cancellation is immediate.
            slot.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { runChild(record.request, slot) }
        }
        saveLocked()
    }

    private suspend fun runChild(request: DelegationRequest, slot: Running) {
        var result: ChildResult? = null
        var status = DelegationStatus.FAILED
        var failure: String? = null
        try {
            append(request.childId, "child_started", emptyMap())
            currentCoroutineContext().ensureActive()
            val client = createClient(request).also { slot.client = it }
            currentCoroutineContext().ensureActive()
            val session = workerSession(request, client)
            val submitted = if (request.role == "coding") requireNotNull(codingWorker).execute(request, session)
                else execute(request, client, session)
            result = submitted
            currentCoroutineContext().ensureActive()
            status = submitted.status
        } catch (cancelled: CancellationException) {
            status = if (slot.cancellationReason != null) DelegationStatus.CANCELLED else DelegationStatus.INTERRUPTED
            failure = slot.cancellationReason ?: "子任务执行被中断"
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                try { slot.client?.shutdown() }
                finally {
                    control.withLock {
                        if (slot.cancellationReason != null) {
                            status = DelegationStatus.CANCELLED
                            failure = slot.cancellationReason
                            result = null
                        }
                        val record = children.getValue(request.childId)
                        children[request.childId] = event(applicable(record.copy(status = status, result = result, error = failure)),
                            "child_finished", mapOf("status" to status, "error" to failure,
                                "result" to result?.let(DelegationJson::resultMap)))
                        running.remove(request.childId)
                        saveLocked()
                        pumpLocked()
                    }
                }
            }
        }
    }

    private fun workerSession(request: DelegationRequest, client: LLMClientInterface): ChildWorkerSession {
        val estimator = ContextTokenEstimator()
        var calls = 0
        return ChildWorkerSession(maxSteps, client.contextBudget, chat = { step, messages, requested ->
            require(step == calls + 1 && step <= maxSteps) { "Child model calls must follow the bounded step sequence" }
            currentCoroutineContext().ensureActive()
            val schema = requested.copy(purpose = LlmCallPurpose.CHILD_ANALYSIS)
            val estimate = estimator.estimate(messages, schema.tool)
            client.contextBudget?.let { budget ->
                require(estimate.totalTokens <= budget.usableInputTokens) {
                    "子任务输入估计 ${estimate.totalTokens} 超过上下文预算 ${budget.usableInputTokens}，请缩小委派资料范围"
                }
            }
            calls++
            append(request.childId, "model_call", mapOf("step" to step, "estimated_input_tokens" to estimate.totalTokens))
            val response = try { client.chatWithSchema(messages, schema) }
            finally { withContext(NonCancellable) { recordUsage(request.childId, step, client.callAttempts) } }
            val frozen = ResponseParser.jsonObjectToMap(JSONObject(response))
            withContext(NonCancellable) { append(request.childId, "model_response", mapOf("step" to step, "action" to frozen)) }
            currentCoroutineContext().ensureActive()
            estimator.calibrate(estimate, client.lastCallInfo?.usage?.inputTokens)
            frozen
        }, read = { ref, offset, limit -> readAndRecord(request, ref, offset, limit) },
            event = { type, data -> append(request.childId, type, data) })
    }

    private suspend fun readAndRecord(request: DelegationRequest, ref: MaterialRef, offset: Int, limit: Int): MaterialPage {
        require(ref in request.materials) { "Reference/version is not in the delegated materials" }
        require(offset >= 0 && limit in 1..DelegationTool.PAGE_LIMIT) { "Read offset must be nonnegative and limit 1..4800" }
        val page = readMaterial(request, ref, offset, limit)
        require(page.reference == ref && page.offset == offset) { "Material source/version did not match the delegated reference" }
        require(page.text.length <= limit + 1 && page.totalChars >= offset + page.text.length) { "Material reader returned an unbounded page" }
        control.withLock {
            val record = children.getValue(request.childId)
            children[request.childId] = event(record.copy(reads = record.reads + MaterialRead(ref, offset,
                page.text.length, page.imageBase64 != null)), "material_read", mapOf(
                "reference" to DelegationJson.materialMap(ref), "offset" to offset,
                "length" to page.text.length, "next_offset" to page.nextOffset,
                "included_image" to (page.imageBase64 != null)))
            saveLocked()
        }
        return page
    }

    private suspend fun execute(request: DelegationRequest, client: LLMClientInterface, session: ChildWorkerSession): ChildResult {
        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to DelegationTool.SYSTEM),
            mapOf("role" to "user", "content" to JSONObject(DelegationJson.requestMap(request)).toString()))
        val schema = DelegationTool.schema(client)
        val readRefs = linkedSetOf<MaterialRef>()
        repeat(maxSteps) { index ->
            currentCoroutineContext().ensureActive()
            val frozen = session.chat(index + 1, messages.toList(), schema)
            messages += mapOf("role" to "assistant", "content" to JSONObject(frozen).toString())
            try {
                when (frozen["action_type"]) {
                    "read_artifact", "read_evidence" -> {
                        val kind = frozen["action_type"].toString().removePrefix("read_")
                        val reference = frozen["reference"] as? String ?: error("reference is required")
                        val version = integer(frozen, "version")
                        val ref = requireNotNull(request.materials.firstOrNull {
                            it.kind == kind && it.reference == reference && it.version == version
                        }) { "Reference/version is not in the delegated materials: $reference v$version" }
                        val offset = integer(frozen, "offset", 0)
                        val limit = integer(frozen, "limit", DelegationTool.PAGE_LIMIT)
                        val page = session.read(ref, offset, limit)
                        readRefs += ref
                        val content = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to JSONObject(mapOf(
                            "material" to DelegationJson.materialMap(ref), "text" to page.text, "offset" to page.offset,
                            "next_offset" to page.nextOffset, "total_chars" to page.totalChars)).toString()))
                        page.imageBase64?.let { image -> content += mapOf("type" to "image_url",
                            "image_url" to mapOf("url" to "data:${page.mimeType};base64,$image")) }
                        messages += mapOf("role" to "user", "content" to content)
                    }
                    "submit_result" -> return validateResult(frozen, readRefs)
                    else -> error("Only read_artifact, read_evidence and submit_result are available")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val feedback = mapOf("tool_error" to (error.message ?: error.javaClass.simpleName))
                append(request.childId, "tool_error", feedback)
                messages += mapOf("role" to "user", "content" to JSONObject(feedback).toString())
            }
        }
        error("子任务达到最大执行步数 $maxSteps，尚未提交有效结果")
    }

    private fun validateResult(response: Map<String, Any?>, readRefs: Set<MaterialRef>): ChildResult {
        val status = response["status"] as? String
        require(status in setOf(DelegationStatus.COMPLETED, DelegationStatus.NEEDS_EVIDENCE)) { "Result status must be completed or needs_evidence" }
        val rawFacts = response["facts"] as? List<*> ?: error("facts must be an array")
        val facts = rawFacts.map { raw ->
            val fact = raw as? Map<*, *> ?: error("Each fact must contain text and refs")
            val text = (fact["text"] as? String)?.takeIf(String::isNotBlank) ?: error("Fact text is required")
            val citations = fact["refs"] as? List<*> ?: error("Fact refs must be an array")
            require(citations.isNotEmpty()) { "Each fact must cite material actually read" }
            val refs = citations.map { rawCitation ->
                val citation = rawCitation as? Map<*, *> ?: error("Citation must contain reference and version")
                val reference = citation["reference"] as? String ?: error("Citation reference is required")
                val version = integer(citation, "version")
                requireNotNull(readRefs.firstOrNull { it.reference == reference && it.version == version }) {
                    "Citation has not been read by this child: $reference v$version"
                }
            }.distinct()
            ChildFact(text, refs)
        }
        val gaps = strings(response, "gaps")
        val requests = strings(response, "collection_requests")
        require(status != DelegationStatus.COMPLETED || facts.isNotEmpty()) { "Completed analysis must contain evidence-backed facts" }
        require(status != DelegationStatus.NEEDS_EVIDENCE || requests.isNotEmpty()) { "needs_evidence must describe what the parent should collect" }
        return ChildResult(requireNotNull(status), facts, gaps, requests)
    }

    private suspend fun recordUsage(childId: String, step: Int, attempts: List<LlmCallInfo>) = control.withLock {
        var record = children.getValue(childId)
        val actualAttempts = attempts.map { attempt ->
            val requestRef = archive.saveRequest(attempt.request)
            record = event(record, "call.request", mapOf("step" to step, "attempt" to attempt.attempts,
                "request_ref" to requestRef))
            mapOf("attempt" to attempt.attempts, "owner_id" to attempt.ownerId,
                "reservation_id" to attempt.reservationId, "purpose" to attempt.purpose.name,
                "usage_complete" to attempt.usageComplete, "input_tokens" to attempt.usage?.inputTokens,
                "output_tokens" to attempt.usage?.outputTokens, "first_token_latency_ms" to attempt.firstTokenLatencyMs,
                "request_ref" to requestRef)
        }
        val previous = record.usage
        val usage = previous.copy(calls = previous.calls + 1, attempts = previous.attempts + attempts.size,
            knownInputTokens = previous.knownInputTokens + attempts.sumOf { it.usage?.inputTokens ?: 0L },
            knownOutputTokens = previous.knownOutputTokens + attempts.sumOf { it.usage?.outputTokens ?: 0L },
            unknownUsageAttempts = previous.unknownUsageAttempts +
                attempts.count { !it.usageComplete || it.usage?.inputTokens == null || it.usage?.outputTokens == null })
        children[childId] = event(record.copy(usage = usage), "usage_recorded", DelegationJson.usageMap(usage) +
            ("call_attempts" to actualAttempts))
        saveLocked()
    }

    private suspend fun append(childId: String, type: String, data: Map<String, Any?>) = control.withLock {
        children[childId] = event(children.getValue(childId), type, data)
        saveLocked()
    }

    private fun applicable(record: DelegationRecord): DelegationRecord =
        if (!record.stale && record.request.goalRevision != currentGoalRevision()) {
            event(record.copy(stale = true), "child_stale", mapOf("reason" to "goal_revision_changed",
                "current_goal_revision" to currentGoalRevision()))
        } else record

    private fun refreshLocked() {
        var changed = false
        children.toMap().forEach { (id, record) ->
            val refreshed = applicable(record)
            if (refreshed !== record) { children[id] = refreshed; changed = true }
        }
        if (changed) saveLocked()
    }

    private fun saveLocked() {
        archive.save(children.values)
        mutableRecords.value = children.values.toList()
    }

    private fun event(record: DelegationRecord, type: String, data: Map<String, Any?>): DelegationRecord {
        val now = System.currentTimeMillis()
        return record.copy(updatedAt = now, events = record.events + DelegationEvent(type, now, data))
    }

    private fun integer(value: Map<*, *>, key: String, default: Int? = null): Int {
        val number = value[key] ?: return requireNotNull(default) { "$key is required" }
        require(number is Number && number.toDouble() == number.toInt().toDouble()) { "$key must be an integer" }
        return number.toInt()
    }

    private fun strings(value: Map<*, *>, key: String): List<String> {
        val items = value[key] as? List<*> ?: error("$key must be an array")
        require(items.all { it is String && it.isNotBlank() }) { "$key must contain nonblank strings" }
        return items.map { it as String }
    }
}
