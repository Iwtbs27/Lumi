package ai.opencyvis.execution.gui

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Exact queries deliberately do not use the compact tree's text, depth or node truncation. */
internal object AccessibilityGuiTree {
    fun query(windows: List<AccessibilityWindowInfo>, selector: GuiSelector): GuiQueryResult {
        val current = currentRoot(windows, selector.displayId)
            ?: return GuiQueryResult(null, emptyList(), "application_window_unavailable")
        try {
            if (current.page.packageName != selector.packageName) return GuiQueryResult(current.page, emptyList(), "package_changed")
            val found = candidates(current.root, current.page, selector)
            try {
                val values = found.nodes.map { snapshot(it, current.page) }.filter(selector::matches)
                return GuiQueryResult(current.page, values, if (found.incomplete) "tree_incomplete" else null)
            } finally { found.nodes.forEach { it.recycle() } }
        } finally { current.root.recycle() }
    }

    fun setText(windows: List<AccessibilityWindowInfo>, selector: GuiSelector, expectedPage: GuiPageIdentity,
                text: String, isCurrent: () -> Boolean): GuiActionAttempt {
        val current = currentRoot(windows, selector.displayId)
            ?: return GuiActionAttempt(false, null, "application_window_unavailable")
        try {
            if (current.page != expectedPage) return GuiActionAttempt(false, null, "page_changed")
            val found = candidates(current.root, current.page, selector)
            try {
                if (found.incomplete) return GuiActionAttempt(false, null, "tree_incomplete")
                val matching = found.nodes.filter { selector.matches(snapshot(it, current.page)) }
                val node = matching.singleOrNull() ?: return GuiActionAttempt(false, null, "target_not_unique")
                val target = snapshot(node, current.page)
                val failure = when {
                    !target.enabled -> "target_disabled"
                    !target.editable -> "target_not_editable"
                    target.password -> "password_text_not_readable"
                    !isCurrent() -> "control_epoch_changed"
                    else -> null
                }
                if (failure != null) return GuiActionAttempt(false, target, failure)
                val delivered = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
                return GuiActionAttempt(delivered, target, if (delivered) null else "set_text_not_supported")
            } finally { found.nodes.forEach { it.recycle() } }
        } finally { current.root.recycle() }
    }

    private data class CurrentRoot(val page: GuiPageIdentity, val root: AccessibilityNodeInfo)
    private fun currentRoot(windows: List<AccessibilityWindowInfo>, displayId: Int): CurrentRoot? {
        for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            val packageName = root.packageName?.toString()
            if (root.isVisibleToUser && !packageName.isNullOrBlank()) {
                return CurrentRoot(GuiPageIdentity(displayId, packageName, window.id), root)
            }
            root.recycle()
        }
        return null
    }

    private data class Candidates(val nodes: List<AccessibilityNodeInfo>, val incomplete: Boolean)
    private fun candidates(root: AccessibilityNodeInfo, page: GuiPageIdentity, selector: GuiSelector): Candidates {
        if (selector.resourceId != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(selector.resourceId)
            val incomplete = nodes.map { it.refresh() }.any { !it }
            return Candidates(nodes, incomplete)
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var incomplete = false
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) { incomplete = true; return }
            if (selector.matches(snapshot(node, page))) nodes += AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) { incomplete = true; continue }
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(root)
        return Candidates(nodes, incomplete)
    }

    private fun snapshot(node: AccessibilityNodeInfo, page: GuiPageIdentity): GuiNodeSnapshot {
        val bounds = Rect().also(node::getBoundsInScreen)
        return GuiNodeSnapshot(page, node.viewIdResourceName, node.className?.toString(),
            if (node.isPassword) null else node.text?.toString(),
            if (node.isPassword) null else node.contentDescription?.toString(),
            GuiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), node.isEnabled, node.isVisibleToUser,
            node.isEditable, node.isClickable, node.isChecked, node.isSelected, node.isPassword)
    }
}
