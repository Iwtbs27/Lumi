package ai.opencyvis.execution

import org.json.JSONArray
import org.json.JSONObject

/** Documentation is returned on demand; it never changes the model's tool prefix. */
internal object ExecutionSdk {
    fun cards(): List<JSONObject> {
        val string = JSONObject().put("type", "string")
        val integer = JSONObject().put("type", "integer")
        val artifact = mapOf("artifact_id" to string, "version" to integer)
        fun card(name: String, description: String, fields: Map<String, Any>, vararg required: String) = JSONObject()
            .put("name", name).put("description", description).put("available", true).put("mutates", false)
            .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject(fields))
                .put("required", JSONArray(required.toList())).put("additionalProperties", false))
        return listOf(
            card("artifacts.read_text", "Read a page of a fixed UTF-8 text artifact. For XLSX or other binary inputs, stage and use Python. Offsets count UTF-16 characters; limit is 1..4800.",
                artifact + mapOf("offset" to integer, "limit" to integer), "artifact_id", "version"),
            card("artifacts.stage", "Copy an exact input artifact version to a relative code-workspace path. Use ctx.path(path) to open the copy.",
                artifact + ("path" to string), "artifact_id", "version", "path"),
            card("artifacts.capture_output", "Archive a workspace output as a new immutable artifact; return its real ID/version. This proves the copy exists, not semantic task completion.",
                mapOf("path" to string, "name" to string, "mime_type" to string), "path", "name", "mime_type"),
            card("execution.state", "Read explicit resume_stage, previous_execution_id, previous_checkpoints and this execution's checkpoints. Resume only after the supplied completed stage; do not repeat uncertain writes.", emptyMap()),
            card("execution.operation", "Read the actual recorded operation by ID; a code execution may read only its own operations.",
                mapOf("operation_id" to string), "operation_id"),
            card("execution.checkpoint", "Mark a declared stage complete using actual succeeded, verified operation IDs from this execution. Preserve the fields required to enter the next stage in state.",
                mapOf("stage" to string, "operation_ids" to JSONObject().put("type", "array").put("items", string),
                    "state" to JSONObject().put("type", "object")), "stage", "operation_ids", "state"),
        )
    }
}
