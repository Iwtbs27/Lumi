package ai.opencyvis.execution.program

import ai.opencyvis.execution.ExecutionHost
import ai.opencyvis.execution.ExecutionOwner
import ai.opencyvis.execution.api.ArtifactRef
import ai.opencyvis.execution.code.CodeExecutionManager
import ai.opencyvis.execution.code.CodeExecutionResult
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.memory.MemoryBodies
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Resume strictly after an archived, completed stage; never resume a Python stack. */
data class ProgramResume(val previousExecutionId: String, val afterStage: String)

data class ProgramRunResult(
    val executionId: String,
    val status: String,
    val programRef: ProgramRef,
    val codeResult: CodeExecutionResult?,
    val operationIds: List<String>,
    val artifacts: List<ArtifactRef>,
    val checkedStages: List<String>,
    val recordRef: String,
    val error: String? = null,
) {
    /** Result/log contents remain in their files; this is the compact execution card. */
    fun toJson(): JSONObject = JSONObject().put("execution_id", executionId).put("status", status)
        .put("program_ref", programRef.toJson()).put("code_result", codeResult?.let {
            JSONObject().put("status", it.status).put("result_ref", it.resultRef).put("stdout_ref", it.stdoutRef)
                .put("stderr_ref", it.stderrRef).put("error", it.error ?: JSONObject.NULL)
                .put("worker_pid", it.workerPid ?: JSONObject.NULL)
        } ?: JSONObject.NULL).put("operation_ids", JSONArray(operationIds))
        .put("artifacts", JSONArray(artifacts.map(ArtifactRef::toJson))).put("checked_stages", JSONArray(checkedStages))
        .put("record_ref", recordRef).put("error", error ?: JSONObject.NULL)
}

/** Executes repository versions through the same SDK and code-process slot as the parent. */
class ProgramRunner(
    private val taskId: Long,
    private val directory: File,
    private val programs: ProgramRepository,
    private val host: ExecutionHost,
    private val code: CodeExecutionManager,
) {
    suspend fun run(owner: ExecutionOwner, ref: ProgramRef, bindings: Map<String, ProgramBinding>,
        timeoutMs: Long, resume: ProgramResume? = null): ProgramRunResult {
        require(taskId > 0 && owner.runId.isNotBlank() && owner.step > 0 && timeoutMs > 0) {
            "Program execution needs a task, run, step and positive timeout"
        }
        val program = programs.get(ref)
        val capabilities = preflight(owner, program)
        val bound = programs.bind(ref, bindings)
        val parameterInputs = program.spec.parameters.filter { it.type == "artifact" && bound.params.has(it.name) }.map {
            val input = bound.params.getJSONObject(it.name)
            host.artifacts.get(input.getString("artifact_id"), input.getInt("version")).also { artifact ->
                require(owner.allowedArtifacts == null || artifact.key() in owner.allowedArtifacts) {
                    "Artifact ${artifact.key()} was not supplied to this caller"
                }
                host.artifacts.resolve(artifact)
            }
        }.distinctBy { it.key() }
        val recovery = resume?.let { recovery(it, ref, bound.params, capabilities) }
        val inputs = (parameterInputs + recovery?.artifacts.orEmpty()).distinctBy { it.key() }
        val executionId = UUID.randomUUID().toString()
        val runDirectory = runDirectory(executionId)
        check(runDirectory.mkdirs()) { "Cannot create program execution archive" }
        val workspace = File(runDirectory, "workspace")
        check(workspace.mkdir()) { "Cannot create program workspace" }
        val codeFile = File(runDirectory, "code.py")
        TaskRunStore.writeAtomic(codeFile, programs.codeFile(ref).readText())
        TaskRunStore.writeAtomic(File(runDirectory, "program.json"), program.toJson().toString())
        TaskRunStore.writeAtomic(File(runDirectory, "bindings.json"), JSONObject().put("params", bound.params)
            .put("sources", bound.sources).toString())
        val prefix = "program-runs/$executionId"
        val recordRef = "$prefix/execution.json"
        val record = JSONObject().put("execution_id", executionId).put("task_id", taskId)
            .put("root_run_id", owner.runId).put("step", owner.step).put("mode", program.spec.mode)
            .put("program_ref", ref.toJson()).put("program_snapshot_ref", "$prefix/program.json")
            .put("code_ref", "$prefix/code.py").put("bindings_ref", "$prefix/bindings.json")
            .put("workspace_ref", "$prefix/workspace").put("input_artifact_refs", JSONArray(inputs.map(ArtifactRef::toJson)))
            .put("started_at", System.currentTimeMillis()).put("status", "running")
            .put("operation_ids", JSONArray()).put("record_ref", recordRef)
        if (resume != null) record.put("previous_execution_id", resume.previousExecutionId).put("resume_after_stage", resume.afterStage)
        if (recovery != null && recovery.reviewOperations.isNotEmpty()) {
            val result = ProgramRunResult(executionId, "needs_review", ref, null, emptyList(), emptyList(), emptyList(), recordRef,
                "Review previous operations before resuming: ${recovery.reviewOperations.joinToString()}")
            record.put("review_operation_ids", JSONArray(recovery.reviewOperations))
            saveFinal(record, result)
            return result
        }
        writeRecord(record)
        val state = JSONObject(owner.executionState.toString())
        if (resume != null) {
            state.put("resume_stage", resume.afterStage).put("previous_execution_id", resume.previousExecutionId)
                .put("previous_checkpoints", JSONArray(requireNotNull(recovery).checkpoints))
        }
        val executionOwner = owner.copy(mode = program.spec.mode, executionId = executionId, workspace = workspace,
            program = program, allowedArtifacts = inputs.map { it.key() }.toSet(), executionState = state)
        var codeResult: CodeExecutionResult? = null
        var status = "failed"
        var error: String? = null
        var cancelled: CancellationException? = null
        var result: ProgramRunResult? = null
        try {
            codeResult = code.execute(executionId, codeFile, workspace, bound.params, timeoutMs) { name, args ->
                host.call(executionOwner, name, args)
            }
            status = codeResult.status
            error = codeResult.error
        } catch (failure: CancellationException) {
            status = "cancelled"
            error = failure.message ?: "Program execution cancelled"
            cancelled = failure
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            // CodeExecutionManager returns/throws only after its worker and host calls have exited.
            withContext(NonCancellable) {
                result = finish(record, program, executionOwner, bound.params, inputs, status, error, codeResult)
            }
        }
        cancelled?.let { throw it }
        return requireNotNull(result)
    }

    fun getRun(executionId: String): JSONObject {
        val file = File(runDirectory(executionId), "execution.json")
        require(file.isFile) { "Unknown program execution: $executionId" }
        return JSONObject(file.readText()).also {
            require(it.getString("execution_id") == executionId && it.getLong("task_id") == taskId) {
                "Program execution does not belong to this task"
            }
        }
    }

    private fun preflight(owner: ExecutionOwner, program: ProgramVersion): Map<String, JSONObject> {
        require(program.enabled) { "Program is disabled: ${program.disabledReason}" }
        require(owner.mode in setOf("data", "gui") && !(owner.mode == "data" && program.spec.mode == "gui")) {
            "A data execution cannot acquire GUI control"
        }
        require(program.spec.stages.all { it.procedureNodeIds.size <= 1 }) {
            "Each program stage currently supports at most one Procedure node"
        }
        val cards = host.describe(program.spec.mode).getJSONArray("capabilities")
        val capabilities = (0 until cards.length()).map(cards::getJSONObject).associateBy { it.getString("name") }
        program.spec.requiredCapabilities.forEach { name ->
            require(program.spec.mode != "data" || !name.startsWith("gui.")) { "Data program cannot require GUI capability: $name" }
            val card = capabilities[name]
            require(card?.optBoolean("available") == true) {
                "Required capability is unavailable: $name ${card?.optString("unavailable_reason").orEmpty()}"
            }
        }
        return capabilities
    }

    private data class Recovery(val checkpoints: List<JSONObject>, val reviewOperations: List<String>, val artifacts: List<ArtifactRef>)

    private fun recovery(resume: ProgramResume, ref: ProgramRef, params: JSONObject,
        capabilities: Map<String, JSONObject>): Recovery {
        val previous = getRun(resume.previousExecutionId)
        require(ProgramRef.fromJson(previous.getJSONObject("program_ref")) == ref) { "Resume requires the exact previous program revision" }
        val previousBindings = JSONObject(File(runDirectory(resume.previousExecutionId), "bindings.json").readText())
        require(MemoryBodies.sameJson(previousBindings.getJSONObject("params").toString(), params.toString())) {
            "Resume parameters differ from the previous execution"
        }
        val checkpoints = host.journal.checkpoints(resume.previousExecutionId)
        val last = checkpoints.lastOrNull()
        require(last != null && last.getString("stage") == resume.afterStage) {
            "Resume must follow the last completed stage checkpoint"
        }
        val boundary = last.getLong("sequence")
        val operations = host.journal.forExecution(resume.previousExecutionId)
        val review = operations.filter {
            val capability = it.getString("capability")
            val writesAfterBoundary = it.getLong("issued_sequence") > boundary &&
                (capability == "gui.action" || capabilities[capability]?.optBoolean("mutates") == true)
            it.getString("status") in setOf("issued", "unknown", "partial") || writesAfterBoundary
        }.map { it.getString("operation_id") }
        val artifacts = operations.filter { it.getLong("issued_sequence") < boundary && it.getString("status") == "succeeded" }
            .mapNotNull { it.optJSONObject("result")?.optJSONObject("artifact") }.map { ArtifactRef.fromJson(it) }
            .distinctBy { it.key() }.onEach { host.artifacts.resolve(it) }
        return Recovery(checkpoints, review, artifacts)
    }

    private fun finish(record: JSONObject, program: ProgramVersion, owner: ExecutionOwner, params: JSONObject,
        inputs: List<ArtifactRef>, initialStatus: String, initialError: String?, codeResult: CodeExecutionResult?): ProgramRunResult {
        val executionId = requireNotNull(owner.executionId)
        val operations = host.journal.forExecution(executionId)
        val operationIds = operations.map { it.getString("operation_id") }
        val uncertain = operations.filter { it.getString("status") in setOf("issued", "unknown", "partial") }
        var status = initialStatus
        var error = initialError
        if (status == "completed" && uncertain.isNotEmpty()) {
            status = "needs_review"
            error = "Program exited with unconfirmed operations: ${uncertain.joinToString { it.getString("operation_id") }}"
        }
        val outputs = mutableListOf<ArtifactRef>()
        val artifactErrors = mutableListOf<String>()
        operations.filter { it.getString("status") == "succeeded" }.forEach { operation ->
            val saved = operation.optJSONObject("result")?.optJSONObject("artifact") ?: return@forEach
            try {
                val artifact = ArtifactRef.fromJson(saved)
                if (inputs.none { it.key() == artifact.key() } && outputs.none { it.key() == artifact.key() }) {
                    // This verifies readability of the actual stored bytes, not their semantic correctness.
                    host.artifacts.resolve(artifact).inputStream().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (stream.read(buffer) != -1) { }
                    }
                    outputs += artifact
                }
            } catch (failure: Exception) {
                artifactErrors += "${operation.getString("operation_id")}: ${failure.message}"
            }
        }
        if (artifactErrors.isNotEmpty()) {
            record.put("artifact_read_errors", JSONArray(artifactErrors))
            if (status == "completed") { status = "failed"; error = "Archived output cannot be read: ${artifactErrors.joinToString()}" }
        }
        val byId = operations.associateBy { it.getString("operation_id") }
        val checkpoints = host.journal.checkpoints(executionId).filter { checkpoint ->
            val ids = checkpoint.getJSONArray("operation_ids")
            program.spec.stages.any { it.id == checkpoint.getString("stage") } && ids.length() > 0 &&
                (0 until ids.length()).all { index -> byId[ids.getString(index)]?.let {
                    it.getString("status") == "succeeded" && it.optJSONObject("result")?.optBoolean("verified") == true
                } == true }
        }
        val stages = checkpoints.map { it.getString("stage") }
        val result = ProgramRunResult(executionId, status, program.ref, codeResult, operationIds, outputs,
            stages, record.getString("record_ref"), error)
        val logs = JSONObject()
        val logDirectory = File(requireNotNull(owner.workspace), ".executions/$executionId")
        listOf("result.json", "stdout.txt", "stderr.txt").forEach { name ->
            val file = File(logDirectory, name)
            if (file.isFile) logs.put(name, file.relativeTo(directory).invariantSeparatorsPath)
        }
        record.put("logs", logs).put("stage_checkpoints", JSONArray(checkpoints))
        saveFinal(record, result)
        if (status == "completed") {
            val checks = mutableListOf(ProgramCheck("process_exit_completed", true, listOf("${result.recordRef}#code_result")))
            if (operations.isNotEmpty()) checks += ProgramCheck("host_operations_succeeded",
                operations.all { it.getString("status") == "succeeded" }, operationIds.map { "operation:$it" })
            outputs.forEach { checks += ProgramCheck("archived_artifact_readable", true, listOf("artifact:${it.key()}")) }
            checkpoints.forEach { checks += ProgramCheck("stage_operations_verified:${it.getString("stage")}", true,
                listOf("${result.recordRef}#stage_checkpoints:${it.getString("stage")}")) }
            val nodes = program.spec.stages.filter { it.id in stages }.flatMap { it.procedureNodeIds }.distinct()
            programs.recordValidation(program.ref, ProgramValidation(executionId, taskId, owner.runId,
                JSONObject(params.toString()), inputs.map(ArtifactRef::toJson), operationIds, stages, nodes, checks,
                JSONObject().put("mechanical_only", true).put("semantic_completion_verified", false)
                    .put("declared_preconditions", JSONArray(program.spec.preconditions))
                    .put("declared_completion_conditions", JSONArray(program.spec.completionConditions))
                    .put("resumed", record.has("previous_execution_id")),
                program.procedure?.procedureId, program.procedure?.procedureRevision))
            record.put("validation_ref", "${program.ref.programId}/${program.ref.revision}/validations/$executionId.json")
            writeRecord(record)
        }
        return result
    }

    private fun saveFinal(record: JSONObject, result: ProgramRunResult) {
        val summary = result.toJson()
        summary.keys().forEach { record.put(it, summary.get(it)) }
        record.put("finished_at", System.currentTimeMillis())
        writeRecord(record)
    }

    private fun writeRecord(record: JSONObject) = TaskRunStore.writeAtomic(
        File(runDirectory(record.getString("execution_id")), "execution.json"), record.toString())

    private fun runDirectory(executionId: String): File {
        require(executionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid program execution ID" }
        return File(directory, "program-runs/$executionId")
    }

    private fun ArtifactRef.key() = "$artifactId:$version"
}
