package ai.opencyvis.execution

import java.io.File
import java.util.UUID
import ai.opencyvis.engine.TaskRunStore
import org.json.JSONArray
import org.json.JSONObject

/** Durable API/GUI sub-operations; model steps and code executions keep their own identities. */
class OperationJournal(private val directory: File) {
    private val operations = File(directory, "operations")
    private val sequenceFile = File(directory, "sequence.json")

    @Synchronized
    fun begin(runId: String, step: Int, executionId: String?, capability: String, arguments: JSONObject): String {
        require(runId.isNotBlank() && step > 0 && capability.isNotBlank())
        val id = UUID.randomUUID().toString()
        write(operationFile(id), JSONObject().put("operation_id", id).put("run_id", runId).put("step", step)
            .put("execution_id", executionId ?: JSONObject.NULL).put("capability", capability)
            .put("arguments", JSONObject(arguments.toString())).put("status", "issued")
            .put("issued_sequence", nextSequence()).put("issued_at", System.currentTimeMillis()))
        return id
    }

    @Synchronized
    fun effect(operationId: String, effect: JSONObject) {
        val saved = get(operationId).put("effect", JSONObject(effect.toString()))
        write(operationFile(operationId), saved)
    }

    @Synchronized
    fun finish(operationId: String, status: String, result: JSONObject) {
        require(status in setOf("succeeded", "failed", "unknown", "partial")) { "Unknown operation status" }
        val saved = get(operationId).put("status", status).put("result", JSONObject(result.toString()))
            .put("finished_sequence", nextSequence()).put("finished_at", System.currentTimeMillis())
        write(operationFile(operationId), saved)
    }

    @Synchronized
    fun get(operationId: String): JSONObject = JSONObject(operationFile(operationId).readText())

    /** Called once when opening a new run, before admitting any current operation. */
    @Synchronized
    fun recover(): List<JSONObject> = all().map { saved ->
        if (saved.getString("status") == "issued") {
            saved.put("status", "unknown").put("interruption", "Host stopped before confirming this operation")
            write(operationFile(saved.getString("operation_id")), saved)
        }
        saved
    }.filter { it.getString("status") == "unknown" }

    @Synchronized
    fun forExecution(executionId: String): List<JSONObject> = all().filter { it.optString("execution_id") == executionId }

    @Synchronized
    fun unresolved(): List<JSONObject> = all().filter { it.getString("status") == "unknown" }

    @Synchronized
    fun checkpoint(executionId: String, stage: String, operationIds: List<String>, state: JSONObject): JSONObject {
        require(stage.isNotBlank() && operationIds.distinct().size == operationIds.size)
        operationIds.forEach { id ->
            val operation = get(id)
            require(operation.optString("execution_id") == executionId && operation.getString("status") == "succeeded" &&
                operation.optJSONObject("result")?.optBoolean("verified") == true) {
                "Stage $stage contains an unverified or foreign operation: $id"
            }
        }
        val previous = checkpoints(executionId)
        require(previous.none { it.getString("stage") == stage }) { "Stage $stage already has a checkpoint" }
        val value = JSONObject().put("execution_id", executionId).put("stage", stage)
            .put("operation_ids", JSONArray(operationIds)).put("state", JSONObject(state.toString()))
            .put("sequence", nextSequence())
        write(stageFile(executionId), JSONObject().put("stages", JSONArray(previous + value)))
        return JSONObject(value.toString())
    }

    @Synchronized
    fun checkpoints(executionId: String): List<JSONObject> {
        val file = stageFile(executionId)
        if (!file.exists()) return emptyList()
        val values = JSONObject(file.readText()).getJSONArray("stages")
        return (0 until values.length()).map(values::getJSONObject)
    }

    private fun all(): List<JSONObject> = operations.listFiles().orEmpty().filter { it.extension == "json" }
        .map { JSONObject(it.readText()) }.sortedBy { it.getLong("issued_sequence") }

    private fun nextSequence(): Long {
        val next = if (sequenceFile.exists()) JSONObject(sequenceFile.readText()).getLong("value") + 1 else 1L
        write(sequenceFile, JSONObject().put("value", next))
        return next
    }

    private fun operationFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && id != "." && id != "..") { "Invalid operation ID" }
        return File(operations, "$id.json")
    }

    private fun stageFile(executionId: String): File {
        require(executionId.matches(Regex("[A-Za-z0-9._-]+")) && executionId != "." && executionId != "..") { "Invalid execution ID" }
        return File(directory, "stages/$executionId.json")
    }

    private fun write(file: File, value: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create operation archive" }
        TaskRunStore.writeAtomic(file, value.toString())
    }
}
