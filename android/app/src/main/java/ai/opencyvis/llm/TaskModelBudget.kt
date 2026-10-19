package ai.opencyvis.llm

import org.json.JSONObject

enum class LlmOwnerRole { PARENT, CHILD, BACKGROUND }
enum class LlmCallPurpose { GENERAL, CONTEXT_SUMMARY, CHILD_ANALYSIS, FINALIZATION }

class ModelBudgetExceededException(message: String) : LLMException(message)

data class TaskModelBudgetSnapshot(
    val limitTokens: Long,
    val finalizationReserveTokens: Long,
    val knownInputTokens: Long = 0,
    val knownOutputTokens: Long = 0,
    val pendingReservedTokens: Long = 0,
    val unknownReservedTokens: Long = 0,
    val finalizationUsed: Boolean = false,
    val nextReservationId: Long = 1
) {
    val accountedTokens: Long get() = knownInputTokens + knownOutputTokens + pendingReservedTokens + unknownReservedTokens
    val remainingTokens: Long get() = (limitTokens - accountedTokens).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject()
        .put("limit_tokens", limitTokens).put("finalization_reserve_tokens", finalizationReserveTokens)
        .put("known_input_tokens", knownInputTokens).put("known_output_tokens", knownOutputTokens)
        .put("pending_reserved_tokens", pendingReservedTokens).put("unknown_reserved_tokens", unknownReservedTokens)
        .put("finalization_used", finalizationUsed).put("next_reservation_id", nextReservationId)

    companion object {
        fun fromJson(json: JSONObject) = TaskModelBudgetSnapshot(
            json.getLong("limit_tokens"), json.getLong("finalization_reserve_tokens"),
            json.getLong("known_input_tokens"), json.getLong("known_output_tokens"),
            json.getLong("pending_reserved_tokens"), json.getLong("unknown_reserved_tokens"),
            json.getBoolean("finalization_used"), json.getLong("next_reservation_id")
        )
    }
}

data class ModelBudgetReservation(
    val id: Long,
    val ownerId: String,
    val purpose: LlmCallPurpose,
    val estimatedInputTokens: Long,
    val outputLimitTokens: Long
) {
    val reservedTokens: Long get() = estimatedInputTokens + outputLimitTokens
}

/** One root task's ledger. The synchronous callback persists each change before it is committed. */
class TaskModelBudget(
    limitTokens: Long,
    finalizationReserveTokens: Long,
    restored: TaskModelBudgetSnapshot? = null,
    private val onChanged: (TaskModelBudgetSnapshot) -> Unit = {}
) {
    private var state = restored?.copy(
        pendingReservedTokens = 0,
        unknownReservedTokens = restored.unknownReservedTokens + restored.pendingReservedTokens
    ) ?: TaskModelBudgetSnapshot(limitTokens, finalizationReserveTokens)
    private val pending = mutableMapOf<Long, ModelBudgetReservation>()

    init {
        require(state.limitTokens > 0 && state.finalizationReserveTokens in 0 until state.limitTokens) {
            "任务模型额度必须为正，汇总预留必须小于总额度"
        }
    }

    @Synchronized fun snapshot(): TaskModelBudgetSnapshot = state

    @Synchronized fun reserve(ownerId: String, role: LlmOwnerRole, purpose: LlmCallPurpose,
        estimatedInputTokens: Long, outputLimitTokens: Long): ModelBudgetReservation {
        require(ownerId.isNotBlank() && estimatedInputTokens >= 0 && outputLimitTokens > 0)
        val amount = estimatedInputTokens + outputLimitTokens
        val finalization = purpose == LlmCallPurpose.FINALIZATION
        if (finalization) {
            require(role == LlmOwnerRole.PARENT) { "只有父任务可以使用最终汇总额度" }
            if (state.finalizationUsed || amount > state.finalizationReserveTokens || amount > state.remainingTokens) {
                throw ModelBudgetExceededException("最终汇总额度不足或已使用")
            }
        } else if (state.finalizationUsed || amount > state.limitTokens - state.finalizationReserveTokens - state.accountedTokens) {
            throw ModelBudgetExceededException("任务模型额度不足，已保留最终汇总额度")
        }
        val reservation = ModelBudgetReservation(state.nextReservationId, ownerId, purpose, estimatedInputTokens, outputLimitTokens)
        val next = state.copy(pendingReservedTokens = state.pendingReservedTokens + amount,
            finalizationUsed = state.finalizationUsed || finalization, nextReservationId = state.nextReservationId + 1)
        onChanged(next)
        pending[reservation.id] = reservation
        state = next
        return reservation
    }

    @Synchronized fun settle(reservationId: Long, usage: LlmUsage?, usageComplete: Boolean): Boolean {
        val reservation = pending[reservationId] ?: return false
        val input = usage?.inputTokens
        val output = usage?.outputTokens
        val complete = usageComplete && input != null && output != null
        val next = if (complete) state.copy(
            knownInputTokens = state.knownInputTokens + input!!,
            knownOutputTokens = state.knownOutputTokens + output!!,
            pendingReservedTokens = state.pendingReservedTokens - reservation.reservedTokens
        ) else state.copy(
            pendingReservedTokens = state.pendingReservedTokens - reservation.reservedTokens,
            unknownReservedTokens = state.unknownReservedTokens + maxOf(reservation.reservedTokens, (input ?: 0) + (output ?: 0))
        )
        onChanged(next)
        pending.remove(reservationId)
        state = next
        return true
    }

    /** Only used when no HTTP attempt was started, including cancellation while awaiting a slot. */
    @Synchronized fun releaseUnsent(reservationId: Long): Boolean {
        val reservation = pending[reservationId] ?: return false
        val next = state.copy(pendingReservedTokens = state.pendingReservedTokens - reservation.reservedTokens,
            finalizationUsed = state.finalizationUsed && reservation.purpose != LlmCallPurpose.FINALIZATION)
        onChanged(next)
        pending.remove(reservationId)
        state = next
        return true
    }
}
