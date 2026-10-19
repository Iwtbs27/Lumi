package ai.opencyvis.execution.gui

import ai.opencyvis.accessibility.VdAccessibilityService

/** The tap callback uses the caller's existing input backend and task GUI lock. */
class AccessibilityGuiAccess(private val tap: suspend (GuiNodeSnapshot) -> Boolean) : GuiAccess {
    override suspend fun query(selector: GuiSelector): GuiQueryResult = VdAccessibilityService.queryGui(selector)

    override suspend fun perform(action: GuiBlockAction, expectedPage: GuiPageIdentity, isCurrent: () -> Boolean): GuiActionAttempt {
        if (action is GuiBlockAction.SetText) {
            return VdAccessibilityService.setGuiText(action.selector, expectedPage, action.text, isCurrent)
        }
        // Re-query immediately before injecting; coordinates are never carried over from the block's first screen.
        val located = query(action.selector)
        val target = located.matches.singleOrNull()
        val failure = when {
            located.reason != null -> located.reason
            located.page != expectedPage -> "page_changed"
            target == null -> "target_not_unique"
            !target.enabled -> "target_disabled"
            target.bounds.right <= target.bounds.left || target.bounds.bottom <= target.bounds.top -> "target_has_no_bounds"
            !isCurrent() -> "control_epoch_changed"
            else -> null
        }
        if (failure != null) return GuiActionAttempt(false, target, failure)
        val delivered = tap(requireNotNull(target))
        return GuiActionAttempt(delivered, target, if (delivered) null else "input_not_delivered")
    }
}
