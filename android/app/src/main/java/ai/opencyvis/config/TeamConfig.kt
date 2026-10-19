package ai.opencyvis.config

import org.json.JSONObject

/** Cumulative input + output limits for one task, independent of the model context window. */
data class TeamConfig(
    val totalTokenBudget: Long,
    val finalizationReserveTokens: Long,
    val maxActiveChildren: Int = 1,
    val childMaxSteps: Int = 12
) {
    init {
        require(totalTokenBudget > 0) { "Team token budget must be positive" }
        require(finalizationReserveTokens > 0 && finalizationReserveTokens < totalTokenBudget) {
            "Finalization reserve must be positive and less than the team token budget"
        }
        require(maxActiveChildren in 1..2) { "Active child limit must be 1 or 2" }
        require(childMaxSteps > 0) { "Child step limit must be positive" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("total_token_budget", totalTokenBudget)
        .put("finalization_reserve_tokens", finalizationReserveTokens)
        .put("max_active_children", maxActiveChildren)
        .put("child_max_steps", childMaxSteps)

    companion object {
        /** Visible editor starting values. Missing configuration never enables a team. */
        val EDITOR_PRESET = TeamConfig(1_000_000L, 65_536L, 1, 12)

        fun fromJson(json: JSONObject): TeamConfig = TeamConfig(
            json.getLong("total_token_budget"),
            json.getLong("finalization_reserve_tokens"),
            json.getInt("max_active_children"),
            json.getInt("child_max_steps")
        )

        fun fromJsonString(value: String): TeamConfig = try {
            fromJson(JSONObject(value))
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid team configuration", failure)
        }
    }
}
