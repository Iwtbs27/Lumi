package ai.opencyvis.execution.coding

import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.execution.code.CodeExecutionStatus
import ai.opencyvis.execution.program.ProgramBinding
import ai.opencyvis.execution.program.ProgramRef
import ai.opencyvis.execution.program.ProgramRepository
import ai.opencyvis.execution.program.ProgramSpec
import ai.opencyvis.execution.program.ProgramVersion
import ai.opencyvis.llm.ResponseParser
import ai.opencyvis.team.ChildResult
import ai.opencyvis.team.ChildWorker
import ai.opencyvis.team.ChildWorkerSession
import ai.opencyvis.team.CodingTaskSpec
import ai.opencyvis.team.DelegationJson
import ai.opencyvis.team.DelegationRequest
import ai.opencyvis.team.DelegationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** A bounded coding role using the existing team's model accounting and lifecycle. */
class CodingWorker(
    private val programs: ProgramRepository,
    private val createEnvironment: (DelegationRequest) -> CodingEnvironment,
) : ChildWorker {
    override suspend fun execute(request: DelegationRequest, session: ChildWorkerSession): ChildResult {
        require(request.role == "coding") { "CodingWorker requires a coding assignment" }
        val assignment = requireNotNull(request.coding) { "Coding assignment is required" }
        require(assignment.mode in setOf("data", "gui") && assignment.maxExecutions > 0 && assignment.timeoutMs > 0) {
            "Coding mode and execution limits are required"
        }
        require(assignment.completionConditions.isNotEmpty()) { "Coding completion conditions are required" }
        val environment = createEnvironment(request)
        val state = State(assignment.sourceProgram?.let { programs.get(ProgramRef.fromJson(JSONObject(it))) })
        state.current?.let { require(it.spec.mode == assignment.mode) { "Source program has a different execution mode" } }
        val schema = CodingTool.schema(session.contextBudget)
        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to CodingTool.SYSTEM),
            mapOf("role" to "user", "content" to JSONObject(DelegationJson.requestMap(request)).toString()))
        state.current?.let { feedback(messages, mapOf("source_program" to jsonMap(it.toJson()))) }
        repeat(session.maxSteps) { index ->
            currentCoroutineContext().ensureActive()
            // Model errors and shared budget exhaustion leave this loop; only tool errors are repairable.
            val action = session.chat(index + 1, messages.toList(), schema)
            currentCoroutineContext().ensureActive()
            messages += mapOf("role" to "assistant", "content" to JSONObject(action).toString())
            try {
                when (action["action_type"]) {
                    "read_material" -> {
                        val kind = text(action, "kind")
                        val reference = text(action, "reference")
                        val version = integer(action, "version")
                        val ref = requireNotNull(request.materials.firstOrNull {
                            it.kind == kind && it.reference == reference && it.version == version
                        }) { "Reference/version is not in the delegated materials" }
                        val (offset, limit) = pageArguments(action)
                        val page = session.read(ref, offset, limit)
                        val content = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to JSONObject(mapOf(
                            "material" to DelegationJson.materialMap(ref), "text" to page.text,
                            "offset" to page.offset, "next_offset" to page.nextOffset, "total_chars" to page.totalChars)).toString()))
                        page.imageBase64?.let { image -> content += mapOf("type" to "image_url",
                            "image_url" to mapOf("url" to "data:${page.mimeType};base64,$image")) }
                        messages += mapOf("role" to "user", "content" to content)
                    }
                    "inspect_sdk" -> feedback(messages, mapOf("sdk" to jsonMap(environment.describeCapabilities())))
                    "write_code" -> {
                        val code = text(action, "code")
                        val rawSpec = action["spec"] as? Map<*, *> ?: error("spec must be an object")
                        // The host owns assignment mode, completion criteria and source attribution.
                        val spec = ProgramSpec.fromJson(JSONObject(rawSpec).put("mode", assignment.mode)
                            .put("completion_conditions", JSONArray(assignment.completionConditions))
                            .put("sources", JSONArray().put(source(request).put("coding_step", index + 1))))
                        val written = programs.saveCode(code, spec, state.current?.ref?.programId)
                        state.current = written
                        state.latest = null
                        val data = jsonMap(written.toJson())
                        session.event("program_written", data)
                        feedback(messages, mapOf("program" to data, "execution_required" to true))
                    }
                    "read_code" -> {
                        val program = requireNotNull(state.current) { "Write code or supply a source program first" }
                        val (offset, limit) = pageArguments(action)
                        val page = ContextTextPage.from(program.codeRef, programs.codeFile(program.ref).readText(), offset, limit)
                        session.event("code_read", mapOf("program_id" to program.ref.programId, "revision" to program.ref.revision,
                            "offset" to page.offset, "length" to page.text.length))
                        feedback(messages, page.toMap())
                    }
                    "run_code" -> {
                        require(assignment.mode == "data") { "GUI programs must be executed by the parent" }
                        require(state.executions < assignment.maxExecutions) { "Code execution limit reached" }
                        val program = requireNotNull(state.current) { "Write code or supply a source program first" }
                        val params = bind(request, program)
                        state.latest = null
                        state.readOutputs.clear()
                        state.executions++
                        session.event("code_execution_started", mapOf("program_id" to program.ref.programId,
                            "revision" to program.ref.revision, "execution_attempt" to state.executions))
                        val execution = environment.execute(program, params, assignment.timeoutMs)
                        currentCoroutineContext().ensureActive()
                        state.latest = Executed(program.ref, execution)
                        state.outputRefs += outputReferences(execution)
                        val data = executionMap(execution) + mapOf("program_id" to program.ref.programId,
                            "revision" to program.ref.revision, "execution_attempt" to state.executions)
                        session.event("code_execution", data)
                        feedback(messages, data)
                    }
                    "read_output" -> {
                        val reference = text(action, "reference")
                        require(reference in state.outputRefs) { "Output reference was not returned by this coding session" }
                        val (offset, limit) = pageArguments(action)
                        val page = environment.readOutput(reference, offset, limit)
                        require(page.reference == reference && page.offset == offset && page.text.length <= limit + 1) {
                            "Output reader returned a different or unbounded page"
                        }
                        state.readOutputs += reference
                        session.event("code_output_read", mapOf("reference" to reference, "offset" to offset,
                            "length" to page.text.length, "next_offset" to page.nextOffset))
                        feedback(messages, page.toMap())
                    }
                    "submit_result" -> return submit(request, assignment, action, state)
                    else -> error("Unknown coding action; use the fixed coding_action schema")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val data = mapOf("tool_error" to (failure.message ?: failure.javaClass.simpleName))
                session.event("tool_error", data)
                feedback(messages, data)
            }
        }
        error("Coding child reached the model step limit (${session.maxSteps}) without a valid result")
    }

    private fun submit(request: DelegationRequest, assignment: CodingTaskSpec, action: Map<String, Any?>,
        state: State): ChildResult {
        val status = text(action, "status")
        require(status in setOf(DelegationStatus.COMPLETED, DelegationStatus.NEEDS_EVIDENCE, DelegationStatus.NEEDS_EXECUTION)) {
            "Coding status must be completed, needs_evidence or needs_execution"
        }
        val summary = text(action, "summary")
        val gaps = strings(action, "gaps")
        var collectionRequests = strings(action, "collection_requests")
        val output = linkedMapOf<String, Any?>("summary" to summary, "mode" to assignment.mode,
            "execution_attempts" to state.executions)
        state.current?.let { program ->
            output += mapOf("program_id" to program.ref.programId, "revision" to program.ref.revision,
                "code_ref" to program.codeRef)
        }
        state.latest?.takeIf { it.ref == state.current?.ref }?.let { output += executionMap(it.value) }
        when (status) {
            DelegationStatus.COMPLETED -> {
                require(assignment.mode == "data") { "GUI programs must be submitted as needs_execution" }
                val latest = requireNotNull(state.latest?.takeIf {
                    it.ref == state.current?.ref && it.value.execution.status == CodeExecutionStatus.COMPLETED
                }) { "Current code revision has no successful execution" }
                val checks = action["checks"] as? List<*> ?: error("checks must be an array")
                val validRefs = (listOf(latest.value.execution.resultRef) + latest.value.artifactRefs).toSet()
                    .intersect(state.readOutputs)
                val reviewed = checks.map { item ->
                    val check = item as? Map<*, *> ?: error("Each check must contain name, passed and evidence_refs")
                    val name = text(check, "name")
                    require(check["passed"] == true) { "Completed coding checks must all pass" }
                    val refs = strings(check, "evidence_refs")
                    require(refs.isNotEmpty() && refs.all { it in validRefs }) {
                        "Completion checks must cite read-back results from the current execution"
                    }
                    mapOf("name" to name, "passed" to true, "evidence_refs" to refs)
                }
                require(assignment.completionConditions.all { condition -> reviewed.any { it["name"] == condition } }) {
                    "Check every delegated completion condition by its exact name"
                }
                output += executionMap(latest.value)
                output += mapOf("params" to jsonMap(bind(request, requireNotNull(state.current))),
                    "verification_scope" to "child_review", "checks" to reviewed)
            }
            DelegationStatus.NEEDS_EXECUTION -> {
                require(assignment.mode == "gui") { "Only GUI candidates use needs_execution" }
                val program = requireNotNull(state.current) { "A GUI program must be saved before requesting execution" }
                val params = jsonMap(bind(request, program))
                output["params"] = params
                output["run_request"] = mapOf("action_type" to "run_program", "program_id" to program.ref.programId,
                    "revision" to program.ref.revision, "params" to params, "execution_timeout_ms" to assignment.timeoutMs)
                if (collectionRequests.isEmpty()) collectionRequests = listOf("请父任务执行此版本程序并读回实际界面结果")
            }
            DelegationStatus.NEEDS_EVIDENCE -> require(collectionRequests.isNotEmpty()) {
                "needs_evidence must specify what the parent should provide or inspect"
            }
        }
        return ChildResult(status, emptyList(), gaps, collectionRequests, output)
    }

    private fun bind(request: DelegationRequest, program: ProgramVersion): JSONObject {
        // JSONObject preserves arrays, objects and artifact references for the existing typed binder.
        val parameters = JSONObject(requireNotNull(request.coding).parameters)
        val bindings = parameters.keys().asSequence().associateWith { name ->
            val value = parameters.get(name)
            require(value != JSONObject.NULL) { "Program parameter $name cannot be null" }
            ProgramBinding(value, source(request).put("parameter", name))
        }
        return programs.bind(program.ref, bindings).params
    }

    private fun source(request: DelegationRequest) = JSONObject().put("root_task_id", request.rootTaskId)
        .put("run_id", request.rootRunId).put("child_id", request.childId)
        .put("source_step", request.sourceStep ?: JSONObject.NULL)

    private fun outputReferences(value: CodingExecution): Set<String> = with(value.execution) {
        (listOf(resultRef, stdoutRef, stderrRef) + value.artifactRefs).toSet()
    }

    private fun executionMap(value: CodingExecution): Map<String, Any?> = with(value.execution) {
        mapOf("execution_id" to executionId, "execution_status" to status, "result_ref" to resultRef,
            "stdout_ref" to stdoutRef, "stderr_ref" to stderrRef, "error" to error,
            "artifact_refs" to value.artifactRefs, "operation_ids" to value.operationIds)
    }

    private fun feedback(messages: MutableList<Map<String, Any>>, data: Map<String, Any?>) {
        messages += mapOf("role" to "user", "content" to JSONObject(data).toString())
    }

    private fun pageArguments(action: Map<String, Any?>): Pair<Int, Int> {
        val offset = integer(action, "offset", 0)
        val limit = integer(action, "limit", CodingTool.PAGE_LIMIT)
        require(offset >= 0 && limit in 1..CodingTool.PAGE_LIMIT) { "Read offset must be nonnegative and limit 1..4800" }
        return offset to limit
    }

    private fun integer(value: Map<*, *>, key: String, default: Int? = null): Int {
        val number = value[key] ?: return requireNotNull(default) { "$key is required" }
        require(number is Number && number.toDouble() == number.toInt().toDouble()) { "$key must be an integer" }
        return number.toInt()
    }

    private fun text(value: Map<*, *>, key: String): String =
        (value[key] as? String)?.takeIf(String::isNotBlank) ?: error("$key must be a nonblank string")

    private fun strings(value: Map<*, *>, key: String): List<String> {
        val items = value[key] as? List<*> ?: error("$key must be an array")
        require(items.all { it is String && it.isNotBlank() }) { "$key must contain nonblank strings" }
        return items.map { it as String }
    }

    private fun jsonMap(value: JSONObject): Map<String, Any?> = ResponseParser.jsonObjectToMap(value)
    private data class Executed(val ref: ProgramRef, val value: CodingExecution)
    private class State(var current: ProgramVersion?) {
        var executions = 0
        var latest: Executed? = null
        val outputRefs = linkedSetOf<String>()
        val readOutputs = linkedSetOf<String>()
    }
}
