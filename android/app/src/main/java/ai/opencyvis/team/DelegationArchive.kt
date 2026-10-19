package ai.opencyvis.team

import ai.opencyvis.llm.ResponseParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** A small per-parent archive; the manager serializes all access with its existing mutex. */
internal class DelegationArchive(private val directory: File, private val taskId: Long) {
    private val file get() = File(directory, "delegations.json")

    fun load(): List<DelegationRecord> {
        if (!file.exists()) return emptyList()
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.getInt("format_version") == 1) { "Unsupported delegation archive version" }
        require(json.getLong("root_task_id") == taskId) { "Delegation archive belongs to another task" }
        return json.getJSONArray("children").objects().map(DelegationJson::record)
    }

    fun save(records: Collection<DelegationRecord>) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create delegation archive directory" }
        val text = JSONObject(mapOf("format_version" to 1, "root_task_id" to taskId,
            "children" to records.map { DelegationJson.recordMap(it, includeEvents = true) })).toString()
        write(file, text)
    }

    /** Exact provider payloads can contain images; the main archive retains only this file reference. */
    fun saveRequest(request: Map<String, Any?>): String {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create delegation archive directory" }
        val target = File.createTempFile("model-request-", ".json", directory)
        write(target, JSONObject(request).toString())
        return target.name
    }

    private fun write(target: File, text: String) {
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal object DelegationJson {
    fun materialMap(ref: MaterialRef): Map<String, Any?> = mapOf(
        "kind" to ref.kind, "reference" to ref.reference, "version" to ref.version,
        "source_run_id" to ref.sourceRunId, "source_step" to ref.sourceStep, "label" to ref.label)

    fun requestMap(request: DelegationRequest): Map<String, Any?> = mapOf(
        "child_id" to request.childId, "root_task_id" to request.rootTaskId, "root_run_id" to request.rootRunId,
        "goal_revision" to request.goalRevision, "goal" to request.goal, "constraints" to request.constraints,
        "materials" to request.materials.map(::materialMap), "role" to request.role, "source_step" to request.sourceStep,
        "coding" to request.coding?.let { mapOf("mode" to it.mode, "parameters" to it.parameters,
            "completion_conditions" to it.completionConditions, "max_executions" to it.maxExecutions,
            "timeout_ms" to it.timeoutMs, "source_program" to it.sourceProgram) })

    fun usageMap(usage: ChildUsage): Map<String, Any?> = mapOf(
        "calls" to usage.calls, "attempts" to usage.attempts, "known_input_tokens" to usage.knownInputTokens,
        "known_output_tokens" to usage.knownOutputTokens, "unknown_usage_attempts" to usage.unknownUsageAttempts)

    fun resultMap(result: ChildResult): Map<String, Any?> = mapOf(
        "status" to result.status, "facts" to result.facts.map { fact ->
            mapOf("text" to fact.text, "refs" to fact.refs.map(::materialMap))
        }, "gaps" to result.gaps, "collection_requests" to result.collectionRequests, "outputs" to result.outputs)

    fun recordMap(record: DelegationRecord, includeEvents: Boolean = false): Map<String, Any?> = buildMap {
        put("request", requestMap(record.request))
        put("status", record.status)
        put("result", record.result?.let(::resultMap))
        put("stale", record.stale)
        put("decision", record.decision)
        put("review_reason", record.reviewReason)
        put("error", record.error)
        put("reads", record.reads.map { mapOf("reference" to materialMap(it.reference), "offset" to it.offset,
            "length" to it.length, "included_image" to it.includedImage) })
        put("usage", usageMap(record.usage))
        put("result_ref", record.resultRef)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
        if (includeEvents) put("events", record.events.map { mapOf("type" to it.type, "at" to it.at, "data" to it.data) })
    }

    private fun material(json: JSONObject) = MaterialRef(json.getString("kind"), json.getString("reference"),
        json.getInt("version"), json.getString("source_run_id"),
        if (json.isNull("source_step")) null else json.getInt("source_step"), json.getString("label"))

    fun record(json: JSONObject): DelegationRecord {
        val request = json.getJSONObject("request")
        val usage = json.getJSONObject("usage")
        val result = json.optJSONObject("result")?.let { result ->
            ChildResult(result.getString("status"), result.getJSONArray("facts").objects().map { fact ->
                ChildFact(fact.getString("text"), fact.getJSONArray("refs").objects().map(::material))
            }, result.getJSONArray("gaps").strings(), result.getJSONArray("collection_requests").strings(),
                result.optJSONObject("outputs")?.let(ResponseParser::jsonObjectToMap).orEmpty())
        }
        return DelegationRecord(
            DelegationRequest(request.getString("child_id"), request.getLong("root_task_id"),
                request.getString("root_run_id"), request.getString("goal_revision"), request.getString("goal"),
                request.getJSONArray("constraints").strings(), request.getJSONArray("materials").objects().map(::material),
                request.optString("role", "research"), request.optJSONObject("coding")?.let { coding ->
                    CodingTaskSpec(coding.getString("mode"), ResponseParser.jsonObjectToMap(coding.getJSONObject("parameters")),
                        coding.getJSONArray("completion_conditions").strings(), coding.getInt("max_executions"),
                        coding.getLong("timeout_ms"), coding.optJSONObject("source_program")?.let(ResponseParser::jsonObjectToMap))
                }, if (request.isNull("source_step")) null else request.getInt("source_step")),
            json.getString("status"), result, json.getBoolean("stale"), json.getString("decision"),
            json.nullableString("review_reason"), json.nullableString("error"),
            json.getJSONArray("reads").objects().map { read ->
                MaterialRead(material(read.getJSONObject("reference")), read.getInt("offset"), read.getInt("length"),
                    read.getBoolean("included_image"))
            }, ChildUsage(usage.getInt("calls"), usage.getInt("attempts"), usage.getLong("known_input_tokens"),
                usage.getLong("known_output_tokens"), usage.getInt("unknown_usage_attempts")),
            json.getString("result_ref"), json.getLong("created_at"), json.getLong("updated_at"),
            json.getJSONArray("events").objects().map { event -> DelegationEvent(event.getString("type"),
                event.getLong("at"), ResponseParser.jsonObjectToMap(event.getJSONObject("data"))) })
    }
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
