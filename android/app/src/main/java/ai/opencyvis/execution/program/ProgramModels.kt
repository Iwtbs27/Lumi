package ai.opencyvis.execution.program

import org.json.JSONArray
import org.json.JSONObject

/** A concrete Python implementation version, independent from Procedure maturity. */
data class ProgramRef(val programId: String, val revision: Int) {
    fun toJson(): JSONObject = JSONObject().put("program_id", programId).put("revision", revision)

    companion object {
        fun fromJson(json: JSONObject) = ProgramRef(json.getString("program_id"), json.getInt("revision"))
    }
}

data class ProgramParameter(
    val name: String,
    val type: String,
    val required: Boolean = true,
    val procedureParameter: String? = null,
    val description: String = "",
)

data class ProgramStage(
    val id: String,
    val entryCondition: String,
    val doneCondition: String,
    val needsAgent: Boolean = false,
    val procedureNodeIds: List<String> = emptyList(),
)

/** Sources are supplied by the host from actual run/step/child records. */
data class ProgramSpec(
    val name: String,
    val mode: String,
    val parameters: List<ProgramParameter> = emptyList(),
    val requiredCapabilities: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val completionConditions: List<String> = emptyList(),
    val stages: List<ProgramStage> = emptyList(),
    val sources: List<JSONObject> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("mode", mode)
        .put("parameters", JSONArray(parameters.map { value -> JSONObject().put("name", value.name)
            .put("type", value.type).put("required", value.required).put("description", value.description)
            .put("procedure_parameter", value.procedureParameter ?: JSONObject.NULL) }))
        .put("required_capabilities", JSONArray(requiredCapabilities)).put("preconditions", JSONArray(preconditions))
        .put("completion_conditions", JSONArray(completionConditions))
        .put("stages", JSONArray(stages.map { value -> JSONObject().put("id", value.id)
            .put("entry_condition", value.entryCondition).put("done_condition", value.doneCondition)
            .put("needs_agent", value.needsAgent).put("procedure_node_ids", JSONArray(value.procedureNodeIds)) }))
        .put("sources", JSONArray(sources.map { JSONObject(it.toString()) }))

    companion object {
        fun fromJson(json: JSONObject) = ProgramSpec(json.getString("name"), json.getString("mode"),
            json.array("parameters").objects().map { ProgramParameter(it.getString("name"), it.getString("type"),
                if (it.has("required")) it.getBoolean("required") else true, it.nullableString("procedure_parameter"),
                if (it.has("description")) it.getString("description") else "") },
            json.array("required_capabilities").strings(), json.array("preconditions").strings(),
            json.array("completion_conditions").strings(), json.array("stages").objects().map {
                ProgramStage(it.getString("id"), it.getString("entry_condition"), it.getString("done_condition"),
                    if (it.has("needs_agent")) it.getBoolean("needs_agent") else false,
                    it.array("procedure_node_ids").strings()) },
            json.array("sources").objects().map { JSONObject(it.toString()) })
    }
}

data class ProgramProcedureLink(
    val procedureId: String,
    val procedureRevision: Int,
    val coveredNodes: List<String>,
    val reason: String,
) {
    internal fun toJson() = JSONObject().put("procedure_id", procedureId).put("procedure_revision", procedureRevision)
        .put("covered_nodes", JSONArray(coveredNodes)).put("reason", reason)

    companion object {
        internal fun fromJson(json: JSONObject) = ProgramProcedureLink(json.getString("procedure_id"),
            json.getInt("procedure_revision"), json.getJSONArray("covered_nodes").strings(), json.getString("reason"))
    }
}

data class ProgramVersion(
    val ref: ProgramRef,
    val spec: ProgramSpec,
    val codeRef: String,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val procedure: ProgramProcedureLink? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = ref.toJson().put("spec", spec.toJson()).put("code_ref", codeRef)
        .put("enabled", enabled).put("disabled_reason", disabledReason ?: JSONObject.NULL)
        .put("procedure", procedure?.toJson() ?: JSONObject.NULL).put("created_at", createdAt)

    companion object {
        internal fun fromJson(json: JSONObject) = ProgramVersion(ProgramRef.fromJson(json),
            ProgramSpec.fromJson(json.getJSONObject("spec")), json.getString("code_ref"), json.getBoolean("enabled"),
            json.nullableString("disabled_reason"), if (json.isNull("procedure")) null else
                ProgramProcedureLink.fromJson(json.getJSONObject("procedure")), json.getLong("created_at"))
    }
}

/** parseText is explicit when adapting existing Procedure string bindings. */
data class ProgramBinding(val value: Any, val source: JSONObject, val parseText: Boolean = false)
data class BoundParameters(val params: JSONObject, val sources: JSONObject)

data class ProgramCheck(val name: String, val passed: Boolean, val evidenceRefs: List<String>)

/** Written only by the execution host after checking actual execution evidence. */
data class ProgramValidation(
    val executionId: String,
    val rootTaskId: Long,
    val rootRunId: String,
    val params: JSONObject,
    val inputArtifactRefs: List<JSONObject> = emptyList(),
    val operationIds: List<String> = emptyList(),
    val checkedStageIds: List<String> = emptyList(),
    val coveredNodes: List<String> = emptyList(),
    val checks: List<ProgramCheck> = emptyList(),
    val scope: JSONObject = JSONObject(),
    val procedureId: String? = null,
    val procedureRevision: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    internal fun toJson(): JSONObject = JSONObject().put("execution_id", executionId).put("root_task_id", rootTaskId)
        .put("root_run_id", rootRunId).put("params", JSONObject(params.toString()))
        .put("input_artifact_refs", JSONArray(inputArtifactRefs.map { JSONObject(it.toString()) }))
        .put("operation_ids", JSONArray(operationIds)).put("checked_stage_ids", JSONArray(checkedStageIds))
        .put("covered_nodes", JSONArray(coveredNodes)).put("checks", JSONArray(checks.map {
            JSONObject().put("name", it.name).put("passed", it.passed).put("evidence_refs", JSONArray(it.evidenceRefs)) }))
        .put("scope", JSONObject(scope.toString())).put("procedure_id", procedureId ?: JSONObject.NULL)
        .put("procedure_revision", procedureRevision ?: JSONObject.NULL).put("created_at", createdAt)

    companion object {
        internal fun fromJson(json: JSONObject) = ProgramValidation(json.getString("execution_id"),
            json.getLong("root_task_id"), json.getString("root_run_id"), json.getJSONObject("params"),
            json.getJSONArray("input_artifact_refs").objects(), json.getJSONArray("operation_ids").strings(),
            json.getJSONArray("checked_stage_ids").strings(), json.getJSONArray("covered_nodes").strings(),
            json.getJSONArray("checks").objects().map { ProgramCheck(it.getString("name"), it.getBoolean("passed"),
                it.getJSONArray("evidence_refs").strings()) }, json.getJSONObject("scope"), json.nullableString("procedure_id"),
            if (json.isNull("procedure_revision")) null else json.getInt("procedure_revision"), json.getLong("created_at"))
    }
}

private fun JSONObject.array(name: String): JSONArray = if (has(name)) getJSONArray(name) else JSONArray()
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
