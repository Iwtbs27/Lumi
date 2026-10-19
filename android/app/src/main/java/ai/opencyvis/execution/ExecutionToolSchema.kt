package ai.opencyvis.execution

import org.json.JSONArray
import org.json.JSONObject

/** Stable phone_action fields; capability and program details are discovered on demand. */
object ExecutionToolSchema {
    val actions = TaskExecutionRuntime.ACTIONS + "delegate_coding"

    fun addTo(properties: JSONObject) {
        properties.put("capability_name", field("string", "Exact capability name from describe_capabilities"))
        properties.put("arguments", field("object", "Capability arguments matching its discovered schema; use {} when empty"))
        properties.put("program_id", field("string", "Saved program ID returned by a coding child or program card"))
        properties.put("revision", integer("Exact immutable program revision", 1))
        properties.put("params", field("object", "Explicit typed program parameters; artifact inputs contain artifact_id and version. Use {} when empty"))
        properties.put("parse_text_parameters", strings("Only these parameter names may be parsed from textual values"))
        properties.put("execution_timeout_ms", integer("Maximum duration of one execution in milliseconds", 1, 300000))
        properties.put("coding_mode", field("string", "data: child may execute on supplied data; gui: child prepares code for parent execution")
            .put("enum", JSONArray(listOf("data", "gui"))))
        properties.put("max_executions", integer("Maximum child execution attempts, including repairs", 1, 10))
        properties.put("completion_conditions", strings("Concrete conditions the coding child must check before submitting").put("minItems", 1))
        properties.put("source_program", obj(JSONObject()
            .put("program_id", field("string", "Existing program to revise"))
            .put("revision", integer("Existing program revision", 1)), "program_id", "revision"))
        properties.put("resume", obj(JSONObject()
            .put("previous_execution_id", field("string", "Execution whose verified checkpoint should be used"))
            .put("after_stage", field("string", "Last verified stage; program and parameters must remain identical")),
            "previous_execution_id", "after_stage"))
        properties.put("procedure_id", field("string", "Existing Procedure to link with save_program"))
        properties.put("procedure_revision", integer("Current Procedure revision", 1))
        properties.put("covered_nodes", strings("Procedure node IDs covered by the program stages").put("minItems", 1))
        properties.put("enabled", field("boolean", "save_program: enable or disable this version, with reason; omit when linking a Procedure"))
        properties.put("execution_id", field("string", "Actual execution ID returned by run_program or a coding child"))
        properties.put("output_kind", field("string", "read_execution output, paged using recall_offset and recall_limit")
            .put("enum", JSONArray(listOf("record", "result", "stdout", "stderr"))))
    }

    fun validate(data: Map<String, Any?>) {
        fun text(key: String) = require((data[key] as? String)?.isNotBlank() == true) { "$key is required" }
        fun number(key: String, min: Int, max: Int = Int.MAX_VALUE) {
            val value = data[key]
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in min..max) {
                "$key must be an integer in $min..$max"
            }
        }
        fun list(key: String) {
            val value = data[key]
            require(value is List<*> && value.isNotEmpty() && value.all { it is String && it.isNotBlank() }) {
                "$key must be a nonempty array of strings"
            }
        }
        fun parameters() = require(data["params"] is Map<*, *>) { "params must be an explicit object" }
        when (data["action_type"]) {
            "describe_capabilities" -> Unit
            "call_capability" -> {
                text("capability_name")
                require(data["arguments"] is Map<*, *>) { "arguments must be an explicit object" }
            }
            "run_program" -> {
                text("program_id"); number("revision", 1); parameters()
                number("execution_timeout_ms", 1, 300000)
            }
            "delegate_coding" -> {
                text("child_goal"); parameters(); list("completion_conditions")
                require(data["coding_mode"] in setOf("data", "gui")) { "coding_mode must be data or gui" }
                number("max_executions", 1, 10); number("execution_timeout_ms", 1, 300000)
            }
            "save_program" -> {
                text("program_id"); number("revision", 1); text("reason")
                if (data.containsKey("enabled")) require(data["enabled"] is Boolean) { "enabled must be boolean" }
                else { text("procedure_id"); number("procedure_revision", 1); list("covered_nodes") }
            }
            "read_execution" -> {
                text("execution_id")
                require(data["output_kind"] in setOf("record", "result", "stdout", "stderr")) { "Invalid output_kind" }
                if (data.containsKey("recall_offset")) number("recall_offset", 0)
                if (data.containsKey("recall_limit")) number("recall_limit", 1, 4800)
            }
            else -> throw IllegalArgumentException("Unknown execution action")
        }
    }

    private fun field(type: String, description: String) = JSONObject().put("type", type).put("description", description)
    private fun integer(description: String, min: Int, max: Int? = null) = field("integer", description)
        .put("minimum", min).apply { if (max != null) put("maximum", max) }
    private fun strings(description: String) = field("array", description).put("items", JSONObject().put("type", "string"))
    private fun obj(properties: JSONObject, vararg required: String) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required.toList()))
}
