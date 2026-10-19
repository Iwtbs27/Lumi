package ai.opencyvis.execution

import ai.opencyvis.execution.api.ArtifactRepository
import ai.opencyvis.execution.api.ArtifactRef
import ai.opencyvis.engine.ContextTextPage
import ai.opencyvis.execution.gui.GuiBlockRequest
import ai.opencyvis.execution.gui.GuiBlockResult
import ai.opencyvis.execution.gui.GuiOperationEvidence
import ai.opencyvis.execution.gui.GuiQueryResult
import ai.opencyvis.execution.gui.GuiSelector
import ai.opencyvis.execution.program.ProgramVersion
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Implemented by the main engine using its existing action mutex and control epoch. */
interface TaskGuiAccess {
    val displayId: Int
    suspend fun query(selector: GuiSelector, epoch: Long): GuiQueryResult
    suspend fun execute(request: GuiBlockRequest, epoch: Long,
        onOperation: (Int, String, GuiOperationEvidence) -> Unit): GuiBlockResult
}

data class ExecutionOwner(
    val runId: String,
    val step: Int,
    val mode: String,
    val epoch: Long,
    val executionId: String? = null,
    val workspace: File? = null,
    val program: ProgramVersion? = null,
    val allowedArtifacts: Set<String>? = null,
    val executionState: JSONObject = JSONObject(),
)

/** One capability entry point for direct parent calls and Python's ctx.call. */
class ExecutionHost(
    val artifacts: ArtifactRepository,
    val journal: OperationJournal,
    private val apiCards: () -> JSONArray,
    private val apiCall: suspend (String, JSONObject, (JSONObject) -> Unit) -> JSONObject,
    private val gui: TaskGuiAccess?,
) {
    fun describe(mode: String): JSONObject {
        require(mode in setOf("data", "gui"))
        val capabilities = apiCards()
        ExecutionSdk.cards().forEach(capabilities::put)
        if (mode == "gui" && gui != null) {
            val cards = GuiCapabilityCodec.cards(gui.displayId)
            for (index in 0 until cards.length()) capabilities.put(cards.getJSONObject(index))
        }
        return JSONObject().put("mode", mode).put("capabilities", capabilities)
            .put("python_entry", "def main(ctx, params): ...")
            .put("python_sdk", "ctx.call(name, arguments) calls these capabilities; ctx.path(relative_path) locates a workspace file. Return JSON-compatible values. Capture outputs as immutable artifacts before returning. No GUI is available in data mode.")
    }

    suspend fun call(owner: ExecutionOwner, name: String, args: JSONObject): JSONObject {
        require(owner.mode in setOf("data", "gui") && owner.runId.isNotBlank() && owner.step > 0)
        when (name) {
            "execution.state" -> return JSONObject(owner.executionState.toString())
                .put("execution_id", owner.executionId ?: JSONObject.NULL)
                .put("checkpoints", JSONArray(owner.executionId?.let(journal::checkpoints).orEmpty()))
            "execution.checkpoint" -> {
                val id = requireNotNull(owner.executionId) { "A program execution is required" }
                val stage = args.getString("stage")
                val program = requireNotNull(owner.program) { "A declared program is required" }
                require(program.spec.stages.any { it.id == stage }) { "Stage is not declared by this program: $stage" }
                val operations = args.getJSONArray("operation_ids")
                return journal.checkpoint(id, stage, (0 until operations.length()).map(operations::getString),
                    args.getJSONObject("state"))
            }
            "execution.operation" -> {
                val operation = journal.get(args.getString("operation_id"))
                require(owner.executionId == null || operation.optString("execution_id") == owner.executionId) {
                    "Operation belongs to another execution"
                }
                return operation
            }
        }
        if (name.startsWith("gui.")) return guiCall(owner, name, args)
        if (name.startsWith("artifacts.")) return recorded(owner, name, args, mutates = false) { _ ->
            artifactCall(owner, name, args)
        }
        val cards = apiCards()
        val card = (0 until cards.length()).map(cards::getJSONObject).singleOrNull { it.getString("name") == name }
        requireNotNull(card) { "Unknown capability: $name" }
        require(card.getBoolean("available")) { "$name is unavailable: ${card.optString("unavailable_reason")}" }
        val mutates = card.getBoolean("mutates")
        suspend fun dispatch() = recorded(owner, name, args, mutates) { operationId ->
            apiCall(name, args) { effect -> journal.effect(operationId, effect) }
        }
        return if (mutates) writeSlot.withLock { dispatch() } else dispatch()
    }

    private suspend fun recorded(owner: ExecutionOwner, name: String, args: JSONObject, mutates: Boolean,
        block: suspend (String) -> JSONObject): JSONObject {
        val id = journal.begin(owner.runId, owner.step, owner.executionId, name, args)
        try {
            val result = block(id).put("operation_id", id)
            val status = result.getString("status")
            journal.finish(id, status, result)
            return result
        } catch (cancelled: CancellationException) {
            journal.finish(id, if (mutates) "unknown" else "failed", JSONObject().put("verified", false)
                .put("reason", "cancelled_before_confirmation"))
            throw cancelled
        } catch (failure: Exception) {
            journal.finish(id, if (mutates) "unknown" else "failed", JSONObject().put("verified", false)
                .put("reason", failure.message ?: failure.javaClass.simpleName))
            throw failure
        }
    }

    private fun artifactCall(owner: ExecutionOwner, name: String, args: JSONObject): JSONObject {
        fun input(): ArtifactRef {
            val ref = artifacts.get(args.getString("artifact_id"), args.getInt("version"))
            require(owner.allowedArtifacts == null || "${ref.artifactId}:${ref.version}" in owner.allowedArtifacts ||
                owner.executionId?.let { execution -> journal.forExecution(execution).any {
                    val saved = it.optJSONObject("result")?.optJSONObject("artifact")
                    saved?.optString("artifact_id") == ref.artifactId && saved.optInt("version") == ref.version
                } } == true) { "Artifact version was not supplied to this execution" }
            return ref
        }
        val result = JSONObject().put("status", "succeeded")
        return when (name) {
            "artifacts.read_text" -> {
                val ref = input()
                require(isText(ref)) { "Binary artifact: stage it and read it with a Python library" }
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 4800)
                require(limit in 1..4800)
                val page = ContextTextPage.from("artifact:${ref.artifactId}:${ref.version}",
                    artifacts.resolve(ref).readText(), offset, limit)
                page.toMap().forEach { (key, value) -> result.put(key, value ?: JSONObject.NULL) }
                result.put("artifact", ref.toJson()).put("verified", true).put("verification_scope", "exact_text_read")
            }
            "artifacts.stage" -> {
                val ref = input()
                val workspace = requireNotNull(owner.workspace) { "Staging requires a code workspace" }
                artifacts.stage(ref, workspace, args.getString("path"))
                result.put("path", args.getString("path")).put("artifact", ref.toJson())
                    .put("verified", true).put("verification_scope", "fixed_input_copy")
            }
            "artifacts.capture_output" -> {
                val workspace = requireNotNull(owner.workspace) { "Capturing requires a code workspace" }
                val ref = artifacts.captureOutput(workspace, args.getString("path"), args.getString("name"), args.getString("mime_type"))
                artifacts.resolve(ref)
                result.put("artifact", ref.toJson()).put("verified", true).put("verification_scope", "artifact_archived")
            }
            else -> throw IllegalArgumentException("Unknown artifact capability: $name")
        }
    }

    private suspend fun guiCall(owner: ExecutionOwner, name: String, args: JSONObject): JSONObject {
        require(owner.mode == "gui") { "Data code cannot operate or observe the phone display" }
        val access = requireNotNull(gui) { "Task GUI is unavailable" }
        if (name == "gui.query") return GuiCapabilityCodec.toJson(access.query(
            GuiCapabilityCodec.selector(args.getJSONObject("selector"), access.displayId), owner.epoch))
        require(name == "gui.execute_block") { "Unknown GUI capability: $name" }
        val request = GuiCapabilityCodec.block(args, access.displayId)
        val operationIds = linkedMapOf<Int, String>()
        try {
            val result = access.execute(request, owner.epoch) { index, phase, evidence ->
                val value = GuiCapabilityCodec.toJson(evidence)
                if (phase == "issued") {
                    operationIds[index] = journal.begin(owner.runId, owner.step, owner.executionId, "gui.action", value)
                } else {
                    val id = operationIds.getValue(index)
                    journal.finish(id, if (evidence.verified) "succeeded" else if (evidence.delivered || phase == "unknown") "unknown" else "failed", value)
                }
            }
            return GuiCapabilityCodec.toJson(result).put("operation_ids", JSONArray(operationIds.values.toList()))
        } finally {
            // An exception between input and readback leaves the actual operation visible for recovery.
            operationIds.values.forEach { id ->
                if (journal.get(id).getString("status") == "issued") {
                    journal.finish(id, "unknown", JSONObject().put("verified", false).put("reason", "block_stopped_before_confirmation"))
                }
            }
        }
    }

    companion object {
        private val writeSlot = Mutex()
        fun isText(ref: ArtifactRef): Boolean = ref.mimeType.startsWith("text/") ||
            ref.mimeType in setOf("application/json", "application/xml") ||
            ref.name.substringAfterLast('.', "").lowercase() in setOf("txt", "csv", "tsv", "json", "md", "log", "xml")
    }
}
