package ai.opencyvis.team

/** References are supplied by the host from immutable, versioned task archives. */
data class MaterialRef(
    val kind: String,
    val reference: String,
    val version: Int,
    val sourceRunId: String,
    val sourceStep: Int? = null,
    val label: String = "",
) {
    init {
        require(kind in setOf("artifact", "evidence")) { "Unknown material kind: $kind" }
        require(reference.isNotBlank() && sourceRunId.isNotBlank() && version > 0) { "Material source and version are required" }
    }
}

data class MaterialPage(
    val reference: MaterialRef,
    val text: String,
    val offset: Int,
    val nextOffset: Int?,
    val totalChars: Int,
    val imageBase64: String? = null,
    val mimeType: String = "image/jpeg",
)

data class DelegationRequest(
    val childId: String,
    val rootTaskId: Long,
    val rootRunId: String,
    val goalRevision: String,
    val goal: String,
    val constraints: List<String>,
    val materials: List<MaterialRef>,
    val role: String = "research",
    val coding: CodingTaskSpec? = null,
    val sourceStep: Int? = null,
)

object DelegationStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val NEEDS_EVIDENCE = "needs_evidence"
    const val NEEDS_EXECUTION = "needs_execution"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
    val IN_FLIGHT = setOf(QUEUED, RUNNING)
}

data class ChildFact(val text: String, val refs: List<MaterialRef>)
data class ChildResult(
    val status: String,
    val facts: List<ChildFact>,
    val gaps: List<String>,
    val collectionRequests: List<String>,
    val outputs: Map<String, Any?> = emptyMap(),
)

/** Known subtotals stay separate from attempts whose usage is absent or partial. */
data class ChildUsage(
    val calls: Int = 0,
    val attempts: Int = 0,
    val knownInputTokens: Long = 0,
    val knownOutputTokens: Long = 0,
    val unknownUsageAttempts: Int = 0,
)

data class MaterialRead(val reference: MaterialRef, val offset: Int, val length: Int, val includedImage: Boolean)
data class DelegationEvent(val type: String, val at: Long, val data: Map<String, Any?>)

data class DelegationRecord(
    val request: DelegationRequest,
    val status: String,
    val result: ChildResult? = null,
    val stale: Boolean = false,
    val decision: String = "pending",
    val reviewReason: String? = null,
    val error: String? = null,
    val reads: List<MaterialRead> = emptyList(),
    val usage: ChildUsage = ChildUsage(),
    val resultRef: String,
    val createdAt: Long,
    val updatedAt: Long,
    val events: List<DelegationEvent> = emptyList(),
)
