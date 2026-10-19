package ai.opencyvis.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetConfigTest {
    @Test fun presetsUseTheAgreedDecimalTokenBudgets() {
        val small = ContextBudgetConfig.preset(128_000)
        assertEquals(105_600, small.usableInputTokens)
        assertEquals(79_200, small.triggerTokens)
        assertEquals(52_800, small.targetTokens)
        assertEquals(170_400, ContextBudgetConfig.preset(256_000).triggerTokens)
        assertEquals(688_500, ContextBudgetConfig.preset(1_000_000).triggerTokens)
    }

    @Test fun workingInputLimitIsAnExplicitLowerInputBudget() {
        val budget = ContextBudgetConfig(1_000_000, 32_000, workingInputLimitTokens = 100_000)
        assertEquals(100_000, budget.usableInputTokens)
        assertEquals(75_000, budget.triggerTokens)
        assertEquals(50_000, budget.targetTokens)
        assertEquals(budget, ContextBudgetConfig.fromJson(budget.toJson()))
    }

    @Test fun invalidBudgetsFailInsteadOfSelectingAnotherWindow() {
        for (create in listOf<() -> ContextBudgetConfig>(
            { ContextBudgetConfig(0, 100) },
            { ContextBudgetConfig(100, 100) },
            { ContextBudgetConfig(100, 1, 100) },
            { ContextBudgetConfig(100, 1, workingInputLimitTokens = 100) }
        )) assertTrue(runCatching(create).isFailure)
        assertTrue(runCatching { ContextBudgetConfig.fromJson(JSONObject().put("context_window_tokens", 128_000)) }.isFailure)
    }

    @Test fun oldProfilesHaveNoInventedWindowAndNewProfilesRoundTrip() {
        val old = ProviderProfile.fromJson(JSONObject().put("name", "old"))
        assertNull(old.contextBudget)
        val profile = old.copy(contextBudget = ContextBudgetConfig.preset(256_000))
        assertEquals(profile, ProviderProfile.fromJson(profile.toJson()))
    }

    @Test fun invalidProfileBudgetReportsItsProfileInsteadOfDiscardingTheList() {
        val json = org.json.JSONArray().put(JSONObject().put("name", "broken-budget")
            .put("context_budget", JSONObject().put("context_window_tokens", 128_000)))
        val error = runCatching { ProviderProfile.fromJsonArray(json.toString()) }.exceptionOrNull()
        assertTrue(error is InvalidContextBudgetException)
        assertTrue(error?.message.orEmpty().contains("broken-budget"))
    }

    @Test fun configurationLinksRoundTripAndRejectIncompleteBudgets() {
        val budget = ContextBudgetConfig(256_000, 16_000, 5, 100_000)
        val uri = ConfigDeepLink.buildUri("openai", "test-key", "test-model", "https://example.com", 100,
            contextBudget = budget)
        val params = uri.substringAfter('?').split('&').associate {
            val pair = it.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(budget, ConfigDeepLink.parse(params).getOrThrow().contextBudget)
        assertTrue(ConfigDeepLink.parse(params - "output_reserve_tokens").isFailure)
        assertNull(ConfigDeepLink.parse(params.filterKeys { it !in ContextBudgetConfig.PARAMETER_KEYS }).getOrThrow().contextBudget)
    }
}
