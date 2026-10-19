package ai.opencyvis.memory

import org.json.JSONObject

/** Supplied by the execution host using the saved program's node mapping. */
internal data class ProcedureOperationMapping(val nodeId: String, val operationIds: List<String>)

/** Actual journal records accepted by the cursor, never model-reported verification. */
internal data class VerifiedProcedureNode(
    val nodeId: String,
    val operations: List<JSONObject>,
    val independentExecution: Boolean,
    val supportScope: JSONObject,
)
