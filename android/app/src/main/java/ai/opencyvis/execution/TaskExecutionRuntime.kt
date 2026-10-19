package ai.opencyvis.execution

import android.content.Context
import ai.opencyvis.engine.TaskRunStore
import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.execution.api.AndroidApiCapabilities
import ai.opencyvis.execution.api.ArtifactRef
import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.code.CodeExecutionManager
import ai.opencyvis.execution.coding.CodingEnvironment
import ai.opencyvis.execution.coding.CodingExecution
import ai.opencyvis.execution.coding.CodingWorker
import ai.opencyvis.execution.program.ProgramBinding
import ai.opencyvis.execution.program.ProgramRef
import ai.opencyvis.execution.program.ProgramRepository
import ai.opencyvis.execution.program.ProgramResume
import ai.opencyvis.execution.program.ProgramRunResult
import ai.opencyvis.execution.program.ProgramRunner
import ai.opencyvis.execution.program.ProgramVersion
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryRepository
import ai.opencyvis.memory.MemorySession
import ai.opencyvis.memory.ProcedureOperationMapping
import ai.opencyvis.team.ChildWorker
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.MaterialPage
import ai.opencyvis.team.MaterialRef
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Task-owned files and execution state, with reusable code kept alongside Procedure memory. */
class TaskExecutionRuntime internal constructor(
    private val context: Context,
    private val store: TaskRunStore,
    private val gui: TaskGuiAccess?,
    private val repository: MemoryRepository?,
) {
    private val taskId = requireNotNull(store.taskId)
    private val directory = File(context.filesDir, "task_execution/$taskId")
    val artifacts = ArtifactRepository(directory)
    private val journal = OperationJournal(File(directory, "journal")).also { it.recover() }
    private val api = AndroidApiCapabilities(context, artifacts)
    private val host = ExecutionHost(artifacts, journal, api::describe, api::call, gui)
    private val programs = ProgramRepository(File(context.filesDir, "programs"))
    private val code = CodeExecutionManager(context)
    private val runner = ProgramRunner(taskId, directory, programs, host, code)
    @Volatile private var guiWork: Deferred<ProgramRunResult>? = null
    val codingWorker: ChildWorker = CodingWorker(programs, ::codingEnvironment)

    fun material(reference: String, step: Int): MaterialRef {
        val artifact = artifact(reference)
        return MaterialRef("artifact", reference, artifact.version, store.runId, step, artifact.name)
    }

    suspend fun readMaterial(reference: MaterialRef, offset: Int, limit: Int): MaterialPage = withContext(Dispatchers.IO) {
        val artifact = artifact(reference.reference)
        require(reference.kind == "artifact" && artifact.version == reference.version) { "Artifact reference/version mismatch" }
        val page = readArtifact(artifact, offset, limit)
        MaterialPage(reference, page.text, page.offset, page.nextOffset, page.totalChars)
    }

    suspend fun context(procedures: List<MemoryItem>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val capabilities = host.describe(if (gui == null) "data" else "gui").getJSONArray("capabilities")
        val cards = procedures.filter { it.type == "procedure" }.flatMap(programs::listForProcedure).distinctBy { it.ref }
        mapOf("enabled" to true, "display_id" to gui?.displayId,
            "capabilities" to (0 until capabilities.length()).map { index ->
                val card = capabilities.getJSONObject(index)
                mapOf("name" to card.getString("name"), "available" to card.getBoolean("available"),
                    "unavailable_reason" to card.opt("unavailable_reason").takeUnless { it == JSONObject.NULL })
            }, "programs" to cards.map { programs.card(it.ref) },
            "uncertain_operations" to journal.unresolved().map { operation ->
                mapOf("operation_id" to operation.getString("operation_id"), "capability" to operation.getString("capability"),
                    "execution_id" to operation.opt("execution_id"), "effect" to operation.opt("effect"))
            })
    }

    internal suspend fun tool(action: Map<String, Any?>, step: Int, epoch: Long, memory: MemorySession?): JSONObject =
        withContext(Dispatchers.IO) {
            val input = JSONObject(action)
            val owner = ExecutionOwner(store.runId, step, if (gui == null) "data" else "gui", epoch)
            val result = when (input.getString("action_type")) {
                "describe_capabilities" -> host.describe(owner.mode)
                "call_capability" -> host.call(owner, input.getString("capability_name"), input.getJSONObject("arguments"))
                "run_program" -> {
                    val ref = ProgramRef.fromJson(input)
                    val program = programs.get(ref)
                    validateProcedure(program)
                    val binding = bindings(input.getJSONObject("params"), source(step),
                        input.optJSONArray("parse_text_parameters")?.strings().orEmpty().toSet())
                    val resume = input.optJSONObject("resume")?.let {
                        ProgramResume(it.getString("previous_execution_id"), it.getString("after_stage"))
                    }
                    val timeout = input.getLong("execution_timeout_ms")
                    require(timeout in 1..300_000)
                    val run = if (program.spec.mode == "gui") guiRun {
                        runner.run(owner, ref, binding, timeout, resume)
                    } else runner.run(owner, ref, binding, timeout, resume)
                    if (run == null) JSONObject().put("status", "needs_agent").put("reason", "GUI program stopped for user control; inspect recorded operations before continuing")
                    else run.toJson().also { result ->
                        applyProcedure(memory, step, program, run, result)
                    }
                }
                "save_program" -> saveProgram(input)
                "read_execution" -> readExecution(input)
                else -> throw IllegalArgumentException("Unknown execution action")
            }
            addArtifactReferences(result)
            store.recordEvent(step, "hybrid_execution", mapOf("action_type" to action["action_type"], "result" to result))
            result
        }

    fun interruptGui() { guiWork?.cancel(CancellationException("User took GUI control")) }
    suspend fun awaitGuiIdle() { guiWork?.join() }

    private suspend fun guiRun(block: suspend () -> ProgramRunResult): ProgramRunResult? = coroutineScope {
        val work = async { block() }
        guiWork = work
        try { work.await() }
        catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        } finally { guiWork = null }
    }

    private fun codingEnvironment(request: DelegationRequest): CodingEnvironment {
        require(request.rootTaskId == taskId && request.rootRunId == store.runId)
        val step = requireNotNull(request.sourceStep) { "Coding delegation must identify its parent step" }
        val assignment = requireNotNull(request.coding)
        val supplied = request.materials.filter { isArtifactReference(it.reference) }.map { artifact(it.reference) }
        return object : CodingEnvironment {
            private val outputs = linkedSetOf<String>()
            override suspend fun describeCapabilities(): JSONObject = host.describe(assignment.mode)

            override suspend fun execute(program: ProgramVersion, params: JSONObject, timeoutMs: Long): CodingExecution {
                require(assignment.mode == "data" && program.spec.mode == "data") { "GUI code is executed by the parent" }
                val parameterInputs = program.spec.parameters.filter { it.type == "artifact" && params.has(it.name) }.map {
                    val ref = params.getJSONObject(it.name)
                    artifacts.get(ref.getString("artifact_id"), ref.getInt("version"))
                }
                val owner = ExecutionOwner(store.runId, step, "data", 0,
                    allowedArtifacts = (supplied + parameterInputs).map { "${it.artifactId}:${it.version}" }.toSet())
                val run = runner.run(owner, program.ref, bindings(params, source(step).put("child_id", request.childId)), timeoutMs)
                val execution = requireNotNull(run.codeResult) { run.error ?: "Program did not start" }
                val artifactRefs = run.artifacts.map(::reference)
                outputs += listOf(execution.resultRef, execution.stdoutRef, execution.stderrRef) + artifactRefs
                return CodingExecution(if (run.status == "completed") execution else execution.copy(status = run.status, error = run.error),
                    artifactRefs, run.operationIds)
            }

            override suspend fun readOutput(reference: String, offset: Int, limit: Int): ContextTextPage = withContext(Dispatchers.IO) {
                require(reference in outputs) { "Output was not produced by this child" }
                if (isArtifactReference(reference)) readArtifact(artifact(reference), offset, limit)
                else readFile(File(reference), reference, offset, limit)
            }
        }
    }

    private suspend fun validateProcedure(program: ProgramVersion) {
        program.procedure?.let { link ->
            val current = requireNotNull(repository?.get(link.procedureId)) { "Linked Procedure is unavailable" }
            require(current.status == "active" && current.revision == link.procedureRevision) { "Linked Procedure changed; review this program before reusing it" }
        }
    }

    private suspend fun applyProcedure(memory: MemorySession?, step: Int, program: ProgramVersion,
        run: ProgramRunResult, result: JSONObject) {
        val link = program.procedure ?: return
        val current = memory?.currentProcedure()
        if (current?.id != link.procedureId || current.revision != link.procedureRevision) return
        val mappings = journal.checkpoints(run.executionId).mapNotNull { checkpoint ->
            val stage = program.spec.stages.single { it.id == checkpoint.getString("stage") }
            stage.procedureNodeIds.singleOrNull()?.let { node ->
                ProcedureOperationMapping(node, checkpoint.getJSONArray("operation_ids").strings())
            }
        }
        if (mappings.isEmpty()) return
        try {
            memory.applyVerifiedOperations(step, run.executionId, mappings, journal)
            result.put("procedure_progress", "applied_verified_operations")
        } catch (invalid: IllegalArgumentException) {
            result.put("procedure_progress", "not_applied").put("procedure_progress_reason", invalid.message)
        }
    }

    private suspend fun saveProgram(input: JSONObject): JSONObject {
        val ref = ProgramRef.fromJson(input)
        val reason = input.getString("reason")
        if (input.has("enabled")) return programs.setEnabled(ref, input.getBoolean("enabled"), reason).toJson()
        val current = requireNotNull(repository?.get(input.getString("procedure_id"))) { "Procedure is unavailable" }
        require(current.revision == input.getInt("procedure_revision")) { "Procedure revision changed" }
        programs.linkProcedure(ref, current, input.getJSONArray("covered_nodes").strings(), reason)
        return programs.card(ref)
    }

    private fun readExecution(input: JSONObject): JSONObject {
        val record = runner.getRun(input.getString("execution_id"))
        val kind = input.getString("output_kind")
        val offset = input.optInt("recall_offset", 0)
        val limit = input.optInt("recall_limit", 4800)
        require(limit in 1..4800)
        val page = if (kind == "record") ContextTextPage.from(record.getString("record_ref"), record.toString(), offset, limit)
        else {
            require(kind in setOf("result", "stdout", "stderr")) { "Invalid execution output kind" }
            val name = if (kind == "result") "result.json" else "$kind.txt"
            val file = File(directory, record.getJSONObject("logs").getString(name))
            readFile(file, "${record.getString("execution_id")}:$kind", offset, limit)
        }
        return JSONObject(page.toMap())
    }

    private fun bindings(params: JSONObject, source: JSONObject, parseText: Set<String> = emptySet()): Map<String, ProgramBinding> =
        params.keys().asSequence().associateWith { ProgramBinding(params.get(it), source, it in parseText) }

    private fun source(step: Int) = JSONObject().put("task_id", taskId).put("run_id", store.runId).put("step", step)
    private fun reference(ref: ArtifactRef) = "artifact:${ref.artifactId}:${ref.version}"
    private fun artifact(reference: String): ArtifactRef {
        val parts = reference.split(':')
        require(parts.size == 3 && parts[0] == "artifact") { "Expected a task artifact reference" }
        return artifacts.get(parts[1], parts[2].toInt())
    }

    private fun readArtifact(ref: ArtifactRef, offset: Int, limit: Int): ContextTextPage {
        require(limit in 1..4800)
        return if (ExecutionHost.isText(ref)) readFile(artifacts.resolve(ref), reference(ref), offset, limit)
        else ContextTextPage.from(reference(ref), ref.toJson().put("usage", "Use artifacts.stage then a Python library to read this binary artifact; metadata alone does not prove its contents.").toString(), offset, limit)
    }

    private fun readFile(file: File, reference: String, offset: Int, limit: Int): ContextTextPage {
        require(limit in 1..4800)
        val page = code.readLog(file.absolutePath, offset, limit)
        var total = 0
        file.reader().use { reader ->
            val buffer = CharArray(8192)
            while (true) { val count = reader.read(buffer); if (count < 0) break; total += count }
        }
        return ContextTextPage(reference, page.text, page.offset, page.nextOffset, total)
    }

    private fun addArtifactReferences(value: Any) {
        when (value) {
            is JSONObject -> {
                if (value.opt("artifact_id") is String && value.opt("version") is Number) {
                    value.put("artifact_ref", "artifact:${value.getString("artifact_id")}:${value.getInt("version")}")
                }
                value.keys().asSequence().toList().forEach { addArtifactReferences(value.get(it)) }
            }
            is JSONArray -> for (index in 0 until value.length()) addArtifactReferences(value.get(index))
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    companion object {
        val ACTIONS = setOf("describe_capabilities", "call_capability", "run_program", "save_program", "read_execution")
        fun isArtifactReference(reference: String): Boolean = reference.startsWith("artifact:")

        internal fun delete(context: Context, taskId: Long) {
            require(taskId > 0)
            File(context.filesDir, "task_execution/$taskId").deleteRecursively()
        }
    }
}
