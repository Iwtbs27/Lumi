package ai.opencyvis.execution.program

import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryBodies
import ai.opencyvis.engine.TaskRunStore
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Small metadata plus versioned code and per-execution validation files. */
class ProgramRepository(val directory: File) {
    @Synchronized
    fun saveCode(code: String, spec: ProgramSpec, programId: String? = null): ProgramVersion {
        require(code.isNotBlank()) { "Program code is required" }
        validateSpec(spec)
        val id = programId ?: UUID.randomUUID().toString()
        val versions = programDirectory(id)
        if (programId != null) require(versions.isDirectory) { "Unknown program: $id" }
        val revision = (versions.listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val ref = ProgramRef(id, revision)
        val destination = versionDirectory(ref)
        check(destination.mkdirs()) { "Cannot create program version: $id r$revision" }
        TaskRunStore.writeAtomic(File(destination, "main.py"), code)
        val value = ProgramVersion(ref, ProgramSpec.fromJson(spec.toJson()), "$id/$revision/main.py")
        write(value)
        return value
    }

    @Synchronized
    fun get(ref: ProgramRef): ProgramVersion {
        val metadata = File(versionDirectory(ref), "metadata.json")
        require(metadata.isFile) { "Unknown program version: ${ref.programId} r${ref.revision}" }
        return ProgramVersion.fromJson(JSONObject(metadata.readText())).also {
            require(it.ref == ref && it.codeRef == "${ref.programId}/${ref.revision}/main.py") {
                "Program metadata does not match the requested version"
            }
        }
    }

    fun codeFile(ref: ProgramRef): File = File(directory, get(ref).codeRef).also {
        require(it.isFile) { "Program code is unavailable: ${ref.programId} r${ref.revision}" }
    }

    fun bind(ref: ProgramRef, bindings: Map<String, ProgramBinding>): BoundParameters {
        val parameters = get(ref).spec.parameters
        val names = parameters.map { it.name }.toSet()
        bindings.keys.forEach { require(it in names) { "Unknown program parameter: $it" } }
        val params = JSONObject()
        val sources = JSONObject()
        parameters.forEach { parameter ->
            val binding = bindings[parameter.name]
            require(binding != null || !parameter.required) { "Missing program parameter: ${parameter.name}" }
            if (binding != null) {
                params.put(parameter.name, convert(parameter, binding))
                sources.put(parameter.name, JSONObject(binding.source.toString()))
            }
        }
        return BoundParameters(params, sources)
    }

    @Synchronized
    fun linkProcedure(ref: ProgramRef, current: MemoryItem, coveredNodes: List<String>, reason: String): ProgramVersion {
        require(current.type == "procedure" && current.status == "active") { "An active Procedure is required" }
        require(reason.isNotBlank()) { "Procedure link reason is required" }
        val body = JSONObject(current.bodyJson)
        MemoryBodies.validate("procedure", body)
        val nodes = body.getJSONArray("nodes")
        val nodeIds = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("node_id") }.toSet()
        val program = get(ref)
        require(coveredNodes.isNotEmpty() && coveredNodes.distinct().size == coveredNodes.size && coveredNodes.all { it in nodeIds }) {
            "Program covered nodes must exist in Procedure ${current.id} r${current.revision}"
        }
        val mapped = program.spec.stages.flatMap { it.procedureNodeIds }
        require(mapped.all { it in nodeIds } && mapped.toSet() == coveredNodes.toSet()) {
            "Program stages must map exactly to the covered Procedure nodes"
        }
        val parameters = body.getJSONArray("parameters")
        val parameterNames = (0 until parameters.length()).map { parameters.getJSONObject(it).getString("name") }.toSet()
        program.spec.parameters.forEach { parameter ->
            require(parameter.procedureParameter == null || parameter.procedureParameter in parameterNames) {
                "Program parameter ${parameter.name} refers to an unknown Procedure parameter: ${parameter.procedureParameter}"
            }
        }
        val updated = program.copy(procedure = ProgramProcedureLink(current.id, current.revision, coveredNodes.toList(), reason))
        write(updated)
        return updated
    }

    @Synchronized
    fun listForProcedure(current: MemoryItem): List<ProgramVersion> {
        if (current.type != "procedure" || current.status != "active") return emptyList()
        return directory.listFiles().orEmpty().filter { it.isDirectory }.flatMap { program ->
            program.listFiles().orEmpty().filter { it.isDirectory && it.name.toIntOrNull() != null && File(it, "metadata.json").isFile }
                .map { get(ProgramRef(program.name, it.name.toInt())) }
        }.filter { it.enabled && it.procedure?.let { link ->
            link.procedureId == current.id && link.procedureRevision == current.revision } == true }
            .sortedWith(compareBy<ProgramVersion> { it.spec.name }.thenByDescending { it.ref.revision })
    }

    @Synchronized
    fun setEnabled(ref: ProgramRef, enabled: Boolean, reason: String): ProgramVersion {
        require(reason.isNotBlank()) { "Program status change reason is required" }
        val updated = get(ref).copy(enabled = enabled, disabledReason = if (enabled) null else reason)
        write(updated)
        return updated
    }

    @Synchronized
    fun recordValidation(ref: ProgramRef, validation: ProgramValidation) {
        val program = get(ref)
        require(validation.rootTaskId > 0 && validation.rootRunId.isNotBlank()) { "Validation must identify its actual root task and run" }
        require((validation.procedureId == null) == (validation.procedureRevision == null)) { "Validation Procedure ID and revision must be supplied together" }
        if (validation.procedureId != null && program.procedure != null) {
            require(validation.procedureId == program.procedure.procedureId && validation.procedureRevision == program.procedure.procedureRevision) {
                "Validation refers to a different Procedure revision"
            }
        }
        val checkedStages = validation.checkedStageIds.toSet()
        require(checkedStages.all { id -> program.spec.stages.any { it.id == id } }) { "Validation contains an unknown program stage" }
        val checkedNodes = program.spec.stages.filter { it.id in checkedStages }.flatMap { it.procedureNodeIds }.toSet()
        require(validation.coveredNodes.all { it in checkedNodes }) { "Validation nodes must belong to the stages actually checked" }
        val file = validationFile(ref, validation.executionId)
        require(!file.exists()) { "Execution validation is already recorded: ${validation.executionId}" }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create validation archive" }
        TaskRunStore.writeAtomic(file, validation.toJson().put("program_ref", ref.toJson()).toString())
    }

    @Synchronized
    fun validations(ref: ProgramRef): List<ProgramValidation> {
        get(ref)
        return File(versionDirectory(ref), "validations").listFiles().orEmpty().filter { it.extension == "json" }.map {
            val json = JSONObject(it.readText())
            require(ProgramRef.fromJson(json.getJSONObject("program_ref")) == ref) { "Validation belongs to another program version" }
            ProgramValidation.fromJson(json)
        }.sortedWith(compareBy<ProgramValidation> { it.createdAt }.thenBy { it.executionId })
    }

    fun card(ref: ProgramRef): JSONObject {
        val program = get(ref)
        val history = validations(ref)
        val passed = history.filter { it.checks.isNotEmpty() && it.checks.all(ProgramCheck::passed) }
        val varied = passed.any { first -> passed.any { second -> !MemoryBodies.sameJson(first.params.toString(), second.params.toString()) } }
        return program.toJson().put("validation_count", history.size).put("passed_validation_count", passed.size)
            .put("parameter_variation_observed", varied)
            .put("current_procedure_validation_count", history.count { program.procedure != null &&
                it.procedureId == program.procedure.procedureId && it.procedureRevision == program.procedure.procedureRevision })
            .put("validation_scope", "仅表示此代码版本已记录的实际样例，不表示所有参数范围均已验证")
            .put("recent_validations", JSONArray(history.takeLast(10).map { value ->
                JSONObject().put("execution_id", value.executionId).put("root_run_id", value.rootRunId)
                    .put("validation_ref", "${ref.programId}/${ref.revision}/validations/${value.executionId}.json")
                    .put("passed", value.checks.isNotEmpty() && value.checks.all(ProgramCheck::passed))
                    .put("checked_stage_ids", JSONArray(value.checkedStageIds)).put("covered_nodes", JSONArray(value.coveredNodes))
                    .put("procedure_id", value.procedureId ?: JSONObject.NULL)
                    .put("procedure_revision", value.procedureRevision ?: JSONObject.NULL)
            }))
    }

    private fun validateSpec(spec: ProgramSpec) {
        require(spec.name.isNotBlank() && spec.mode in setOf("data", "gui")) { "Program needs a name and data/gui mode" }
        val names = spec.parameters.map { it.name }
        require(names.all { it.isNotBlank() } && names.distinct().size == names.size) { "Program parameter names must be nonempty and unique" }
        spec.parameters.forEach {
            require(it.type in setOf("string", "integer", "number", "boolean", "array", "object", "artifact")) {
                "Unknown type for program parameter ${it.name}: ${it.type}"
            }
        }
        val stages = spec.stages.map { it.id }
        require(stages.all { it.isNotBlank() } && stages.distinct().size == stages.size) { "Program stage IDs must be nonempty and unique" }
        require(spec.stages.all { it.entryCondition.isNotBlank() && it.doneCondition.isNotBlank() }) { "Program stages need entry and completion conditions" }
        require(spec.requiredCapabilities.all { it.isNotBlank() }) { "Program capability names cannot be empty" }
    }

    private fun convert(parameter: ProgramParameter, binding: ProgramBinding): Any {
        val value = binding.value
        val text = if (binding.parseText && value is String) value.trim() else null
        try {
            return when (parameter.type) {
                "string" -> { require(value is String); value }
                "integer" -> when {
                    text != null -> text.toLong()
                    value is Number -> BigDecimal(value.toString()).longValueExact()
                    else -> throw IllegalArgumentException("expected integer")
                }
                "number" -> {
                    val number = if (text != null) text.toDouble() else { require(value is Number); value.toDouble() }
                    require(number.isFinite()); number
                }
                "boolean" -> when {
                    value is Boolean -> value
                    text == "true" -> true
                    text == "false" -> false
                    else -> throw IllegalArgumentException("expected true or false")
                }
                "array" -> if (text != null) JSONArray(text) else { require(value is JSONArray); JSONArray(value.toString()) }
                "object" -> if (text != null) JSONObject(text) else { require(value is JSONObject); JSONObject(value.toString()) }
                "artifact" -> {
                    val artifact = if (text != null) JSONObject(text) else { require(value is JSONObject); value }
                    require(artifact.keys().asSequence().toSet() == setOf("artifact_id", "version"))
                    val id = artifact.get("artifact_id")
                    require(id is String && id.isNotBlank())
                    val version = artifact.get("version")
                    require(version is Number)
                    val versionNumber = BigDecimal(version.toString()).intValueExact()
                    require(versionNumber > 0)
                    JSONObject().put("artifact_id", id).put("version", versionNumber)
                }
                else -> throw IllegalArgumentException("unknown type")
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid program parameter ${parameter.name}: expected ${parameter.type}", error)
        }
    }

    private fun programDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid program ID" }
        return File(directory, id)
    }

    private fun versionDirectory(ref: ProgramRef): File {
        require(ref.revision > 0) { "Program revision must be positive" }
        return File(programDirectory(ref.programId), ref.revision.toString())
    }

    private fun validationFile(ref: ProgramRef, executionId: String): File {
        require(executionId.matches(Regex("[A-Za-z0-9._-]+")) && executionId != "." && executionId != "..") { "Invalid execution ID" }
        return File(versionDirectory(ref), "validations/$executionId.json")
    }

    private fun write(version: ProgramVersion) = TaskRunStore.writeAtomic(File(versionDirectory(version.ref), "metadata.json"), version.toJson().toString())
}
