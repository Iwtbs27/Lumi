package ai.opencyvis.execution.gui

/** All supplied fields match exactly within one display and package. */
data class GuiSelector(
    val displayId: Int,
    val packageName: String,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
) {
    init {
        require(displayId >= 0 && packageName.isNotBlank()) { "A display and package are required" }
    }

    fun matches(node: GuiNodeSnapshot): Boolean = node.visible &&
        node.page.displayId == displayId && node.page.packageName == packageName &&
        (resourceId == null || resourceId == node.resourceId) &&
        (text == null || text == node.text) &&
        (contentDescription == null || contentDescription == node.contentDescription) &&
        (className == null || className == node.className)
}

data class GuiPageIdentity(val displayId: Int, val packageName: String, val windowId: Int)
data class GuiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Values only: no live node handle or reusable action coordinate crosses a query. */
data class GuiNodeSnapshot(
    val page: GuiPageIdentity,
    val resourceId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: GuiBounds,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val clickable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val password: Boolean = false,
)

data class GuiQueryResult(
    val page: GuiPageIdentity?,
    val matches: List<GuiNodeSnapshot>,
    val reason: String? = null,
)

data class GuiExpectedState(
    val selector: GuiSelector,
    val text: String? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
)

sealed interface GuiBlockAction {
    val selector: GuiSelector
    data class SetText(override val selector: GuiSelector, val text: String) : GuiBlockAction
    data class Tap(override val selector: GuiSelector, val expect: GuiExpectedState) : GuiBlockAction
}

data class GuiBlockRequest(val pageAnchor: GuiSelector, val actions: List<GuiBlockAction>)
data class GuiActionAttempt(val delivered: Boolean, val target: GuiNodeSnapshot?, val reason: String? = null)

/** The caller holds the task's existing GUI lock throughout a block. */
interface GuiAccess {
    suspend fun query(selector: GuiSelector): GuiQueryResult
    suspend fun perform(action: GuiBlockAction, expectedPage: GuiPageIdentity, isCurrent: () -> Boolean): GuiActionAttempt
}

data class GuiOperationEvidence(
    val index: Int,
    val action: GuiBlockAction,
    val before: GuiNodeSnapshot?,
    val after: GuiNodeSnapshot?,
    val delivered: Boolean,
    val verified: Boolean,
    val reason: String? = null,
)

data class GuiBlockResult(
    val status: String,
    val operations: List<GuiOperationEvidence>,
    val currentPage: GuiPageIdentity?,
    val reason: String? = null,
)
