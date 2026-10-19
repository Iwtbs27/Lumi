package ai.opencyvis.execution.gui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Executes a short block under the GUI lease already held by the main task. */
class GuiBlockExecutor(private val gui: GuiAccess, private val currentEpoch: () -> Long) {
    suspend fun execute(
        request: GuiBlockRequest,
        expectedEpoch: Long,
        onOperation: (index: Int, phase: String, evidence: GuiOperationEvidence) -> Unit = { _, _, _ -> },
    ): GuiBlockResult {
        require(request.actions.size in 1..3) { "A GUI block contains one to three actions" }
        request.actions.forEach { action ->
            requireSameDisplayAndPackage(request.pageAnchor, action.selector)
            if (action is GuiBlockAction.Tap) {
                requireSameDisplayAndPackage(request.pageAnchor, action.expect.selector)
                require(action.expect.text != null || action.expect.checked != null || action.expect.selected != null) {
                    "A tap requires an explicit result condition"
                }
            }
        }
        val operations = mutableListOf<GuiOperationEvidence>()
        var currentPage: GuiPageIdentity? = null
        var outstanding: GuiOperationEvidence? = null
        fun result(reason: String? = null) = GuiBlockResult(
            if (reason == null) "completed" else if (operations.any { it.delivered }) "partial" else "needs_agent",
            operations.toList(), currentPage, reason)
        fun isCurrent() = currentEpoch() == expectedEpoch
        suspend fun pageCheck(expectedPage: GuiPageIdentity?): String? {
            if (!isCurrent()) return "control_epoch_changed"
            val observation = gui.query(request.pageAnchor)
            currentPage = observation.page
            if (observation.reason != null) return observation.reason
            if (expectedPage != null && observation.page != expectedPage) return "page_changed"
            if (observation.page == null || observation.matches.size != 1) return "page_anchor_not_unique"
            return null
        }
        pageCheck(null)?.let { return result(it) }
        val initialPage = requireNotNull(currentPage)
        try {
            for ((offset, action) in request.actions.withIndex()) {
                currentCoroutineContext().ensureActive()
                pageCheck(initialPage)?.let { return result(it) }
                val located = gui.query(action.selector)
                currentPage = located.page
                val target = located.matches.singleOrNull()
                val locateFailure = when {
                    located.reason != null -> located.reason
                    located.page != initialPage -> "page_changed"
                    target == null -> "target_not_unique"
                    !target.enabled -> "target_disabled"
                    action is GuiBlockAction.SetText && !target.editable -> "target_not_editable"
                    else -> null
                }
                if (locateFailure != null) return result(locateFailure)
                if (!isCurrent()) return result("control_epoch_changed")
                val index = offset + 1
                val issued = GuiOperationEvidence(index, action, target, null, delivered = false, verified = false)
                onOperation(index, "issued", issued)
                outstanding = issued
                val attempt = gui.perform(action, initialPage, ::isCurrent)
                val sent = issued.copy(before = attempt.target ?: target, delivered = attempt.delivered, reason = attempt.reason)
                outstanding = sent
                var after: GuiNodeSnapshot? = null
                var failure = attempt.reason
                var verified = false
                if (attempt.delivered) {
                    val expected = when (action) {
                        is GuiBlockAction.SetText -> GuiExpectedState(action.selector.copy(
                            text = null, resourceId = action.selector.resourceId ?: target?.resourceId), text = action.text)
                        is GuiBlockAction.Tap -> action.expect
                    }
                    // Accessibility updates can follow input delivery. Poll reads only, never repeat the action.
                    for (poll in 0 until 10) {
                        failure = pageCheck(initialPage)
                        if (failure != null) break
                        val readback = gui.query(expected.selector)
                        currentPage = readback.page
                        after = readback.matches.singleOrNull()
                        failure = when {
                            readback.reason != null -> readback.reason
                            readback.page != initialPage -> "page_changed"
                            after == null -> "readback_not_unique"
                            !expected.matches(after) -> "readback_mismatch"
                            !isCurrent() -> "control_epoch_changed"
                            else -> null
                        }
                        if (failure == null) {
                            verified = true
                            break
                        }
                        if (readback.page != initialPage) break
                        if (poll < 9) delay(100)
                    }
                } else if (failure == null) failure = "input_not_delivered"
                val checked = sent.copy(after = after, verified = verified, reason = failure)
                operations += checked
                onOperation(index, "checked", checked)
                outstanding = null
                if (failure != null) return result(failure)
            }
            return result()
        } catch (cancelled: CancellationException) {
            outstanding?.let { pending ->
                withContext(NonCancellable) {
                    onOperation(pending.index, "unknown", pending.copy(reason = "cancelled_before_confirmation"))
                }
            }
            throw cancelled
        }
    }

    private fun requireSameDisplayAndPackage(page: GuiSelector, target: GuiSelector) {
        require(page.displayId == target.displayId && page.packageName == target.packageName) {
            "Every action and check must belong to the block display and package"
        }
    }

    private fun GuiExpectedState.matches(node: GuiNodeSnapshot): Boolean =
        (text == null || text == node.text) && (checked == null || checked == node.checked) &&
            (selected == null || selected == node.selected)
}
