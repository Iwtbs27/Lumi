package ai.opencyvis.learning

import org.json.JSONArray
import org.json.JSONObject

/** Recording eligibility is deliberately broader than eligibility to learn a correction. */
object TaskReviewPolicy {
    fun shouldCapture(trigger: String, kind: String, reason: String): Boolean =
        trigger in setOf("agent_handoff", "manual_takeover") && excludedCategory(kind, reason) == null

    internal fun excludedCategory(kind: String, reason: String): String? {
        if (kind in setOf("captcha", "account_access", "protected_app", "protected_screen"))
            return if (kind == "protected_app") "protected_screen" else kind
        val text = reason.lowercase()
        if (listOf("captcha", "验证码", "人机验证", "滑块验证", "拼图验证", "拖动滑块", "安全验证")
                .any(text::contains)) return "captcha"
        if (listOf("password", "passcode", "密码", "扫码验证", "扫码登录", "身份验证", "短信验证")
                .any(text::contains)) return "account_access"
        return null
    }

    internal fun isAutomatic(session: LearningSession) = session.mode == "correction" &&
        JSONObject(session.sourceJson).optBoolean("automatic_review")

    internal fun isHumanAction(event: JSONObject): Boolean {
        val kind = event.optString("kind")
        if (kind !in setOf("gesture", "key", "text_edit", "open_app")) return false
        val details = event.optJSONObject("details") ?: return false
        if (!details.optBoolean("injected") || details.optBoolean("cancelled")) return false
        return kind != "text_edit" || details.optString("inserted").isNotBlank() || details.optInt("deleted") > 0
    }

    internal fun localSkip(source: JSONObject, events: List<JSONObject>): TaskReviewDecision? {
        val category = excludedCategory(source.optString("handoff_kind"), source.optString("handoff_reason"))
        if (category != null) return TaskReviewDecision("skip", category, "本次接管用于验证、账户访问或受保护页面，不提炼为通用 GUI 方法。")
        if (events.any { it.optString("kind") == "password_input_omitted" })
            return TaskReviewDecision("skip", "account_access", "接管期间涉及密码输入，保留记录但不整理为操作方法。")
        if (events.none(::isHumanAction)) return TaskReviewDecision("skip", "no_human_action", "没有采集到有效的人工操作，无法判断人如何解决困难。")
        return null
    }
}

internal data class TaskReviewDecision(
    val decision: String, val category: String, val reason: String, val evidenceIds: List<String> = emptyList()
) {
    fun json() = JSONObject().put("decision", decision).put("category", category)
        .put("reason", reason).put("evidence_ids", JSONArray(evidenceIds))

    companion object {
        val categories = setOf("gui_difficulty", "captcha", "account_access", "protected_screen",
            "task_change", "routine_takeover", "no_human_action", "insufficient_evidence")

        fun parse(value: JSONObject, seenIds: Set<String>, actionIds: Set<String>, beforeIds: Set<String>): TaskReviewDecision {
            val decision = value.getString("decision")
            val category = value.getString("category")
            val reason = value.getString("reason")
            val refs = value.getJSONArray("evidence_ids")
            val ids = (0 until refs.length()).map(refs::getString)
            require(decision in setOf("learn", "skip") && category in categories && reason.isNotBlank()) { "复盘结论、分类或理由不完整" }
            require(ids.all { it in seenIds }) { "复盘只能引用本次已经提供内容的证据" }
            if (decision == "learn") {
                require(category == "gui_difficulty") { "只有有依据的 GUI 困难纠正进入经验整理" }
                require(ids.any { it in actionIds } && ids.any { it in beforeIds }) { "学习判断需要同时引用接管前的困难与真实人工操作" }
            }
            return TaskReviewDecision(decision, category, reason, ids)
        }
    }
}
