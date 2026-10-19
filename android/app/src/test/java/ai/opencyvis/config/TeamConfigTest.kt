package ai.opencyvis.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeamConfigTest {
    @Test fun explicitLimitsRoundTripWithoutNarrowingTheTaskBudget() {
        val config = TeamConfig(4_000_000_000L, 65_536L, maxActiveChildren = 2, childMaxSteps = 20)
        assertEquals(config, TeamConfig.fromJsonString(config.toJson().toString()))
        assertEquals(4_000_000_000L, config.toJson().getLong("total_token_budget"))
    }

    @Test fun invalidLimitsCannotEnableAnUnboundedTeam() {
        for (create in listOf<() -> TeamConfig>(
            { TeamConfig(0, 1) },
            { TeamConfig(100, 0) },
            { TeamConfig(100, 100) },
            { TeamConfig(100, 10, maxActiveChildren = 3) },
            { TeamConfig(100, 10, childMaxSteps = 0) }
        )) assertTrue(runCatching(create).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun incompleteOrBrokenStoredValuesDoNotSelectTheEditorPreset() {
        assertTrue(runCatching { TeamConfig.fromJson(JSONObject().put("total_token_budget", 1_000_000)) }.isFailure)
        assertTrue(runCatching { TeamConfig.fromJsonString("{broken") }.exceptionOrNull() is IllegalArgumentException)
    }
}
