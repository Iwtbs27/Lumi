package ai.opencyvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.Display
import ai.opencyvis.learning.LearningObservationService
import ai.opencyvis.execution.gui.AccessibilityGuiTree
import ai.opencyvis.execution.gui.GuiActionAttempt
import ai.opencyvis.execution.gui.GuiPageIdentity
import ai.opencyvis.execution.gui.GuiQueryResult
import ai.opencyvis.execution.gui.GuiSelector

class VdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VdA11yService"
        private const val MAX_DEPTH = 15
        private const val MAX_NODES = 200

        @Volatile
        var instance: VdAccessibilityService? = null
            private set

        fun captureViewTree(displayId: Int, displayWidth: Int, displayHeight: Int): String? {
            return instance?.captureViewTreeInternal(displayId, displayWidth, displayHeight)
        }

        fun queryGui(selector: GuiSelector): GuiQueryResult {
            val service = instance ?: return GuiQueryResult(null, emptyList(), "accessibility_unavailable")
            return AccessibilityGuiTree.query(service.windowsOnAllDisplays.get(selector.displayId).orEmpty(), selector)
        }

        fun setGuiText(selector: GuiSelector, expectedPage: GuiPageIdentity, text: String,
                       isCurrent: () -> Boolean): GuiActionAttempt {
            val service = instance ?: return GuiActionAttempt(false, null, "accessibility_unavailable")
            return AccessibilityGuiTree.setText(service.windowsOnAllDisplays.get(selector.displayId).orEmpty(),
                selector, expectedPage, text, isCurrent)
        }

        data class ObservationWindow(val packageName: String, val bounds: Rect, val passwordFocused: Boolean)

        fun observationWindow(displayId: Int = Display.DEFAULT_DISPLAY): ObservationWindow? = instance?.let { service ->
            service.windowsOnAllDisplays.get(displayId)
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                ?.sortedByDescending { it.layer }?.firstNotNullOfOrNull { window ->
                    val root = window.root ?: return@firstNotNullOfOrNull null
                    try {
                        val packageName = root.packageName?.toString() ?: return@firstNotNullOfOrNull null
                        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val passwordFocused = try { focus?.isPassword == true } finally { focus?.recycle() }
                        ObservationWindow(packageName, Rect().also(window::getBoundsInScreen), passwordFocused)
                    } finally { root.recycle() }
                }
        }

        fun captureObservationTree(packageName: String, width: Int, height: Int): String? =
            instance?.captureViewTreeInternal(Display.DEFAULT_DISPLAY, width, height, packageName)

        fun getTopPackageOnDisplay(displayId: Int): String? {
            val service = instance ?: return null
            return try {
                service.windowsOnAllDisplays.get(displayId)?.firstNotNullOfOrNull { window ->
                    val root = window.root ?: return@firstNotNullOfOrNull null
                    try {
                        root.packageName?.toString()?.takeIf { it != "com.android.systemui" }
                    } finally {
                        root.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getTopPackageOnDisplay failed", e)
                null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !LearningObservationService.isObserving) return
        val displayId = windowsOnAllDisplays.let { displays ->
            (0 until displays.size()).firstNotNullOfOrNull { index ->
                displays.valueAt(index).firstOrNull { it.id == event.windowId }?.let { displays.keyAt(index) }
            }
        }
        if (displayId == Display.DEFAULT_DISPLAY) LearningObservationService.recordAccessibilityEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun captureViewTreeInternal(
        displayId: Int, displayWidth: Int, displayHeight: Int, onlyPackage: String? = null,
    ): String? {
        return try {
            // This API is available since our minimum SDK (30). Never substitute another display.
            val displayWindows = windowsOnAllDisplays.get(displayId)
            if (displayWindows.isNullOrEmpty()) {
                Log.w(TAG, "No windows found for displayId=$displayId")
                return null
            }

            val tree = TreeWriter(displayWidth, displayHeight)
            for (window in displayWindows) {
                // The no-argument getter also works on Android 11 and 12.
                val root = window.root ?: continue
                try {
                    val packageName = root.packageName?.toString().orEmpty()
                    if (packageName == "com.android.systemui") continue
                    if (onlyPackage != null && packageName != onlyPackage) continue

                    val start = tree.text.length
                    val countBeforeWindow = tree.nodeCount
                    tree.text.append("Window(package=").append(packageName)
                        .append(",display=").append(displayId).append(")\n")
                    tree.appendNode(root)
                    if (tree.nodeCount == countBeforeWindow) tree.text.setLength(start)
                } finally {
                    root.recycle()
                }
                if (tree.nodeLimitReached) break
            }

            tree.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture view tree", e)
            null
        }
    }

    private class TreeWriter(
        private val displayWidth: Int,
        private val displayHeight: Int,
    ) {
        val text = StringBuilder()
        var nodeCount = 0
            private set
        var nodeLimitReached = false
            private set
        private var depthLimitReached = false
        private var missingChildren = false

        fun appendNode(
            node: AccessibilityNodeInfo,
            depth: Int = 0,
            outputDepth: Int = 0,
            isCollectionChild: Boolean = false,
        ) {
            if (nodeLimitReached || !node.isVisibleToUser) return
            if (depth > MAX_DEPTH) {
                depthLimitReached = true
                return
            }

            val label = if (node.isPassword) "[password]" else compactLabel(node.text)
            val description = if (node.isPassword) null else compactLabel(node.contentDescription)
            val collection = node.collectionInfo
            val item = node.collectionItemInfo
            val hasContent = !label.isNullOrBlank() || !description.isNullOrBlank()
            val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            // Keep list item boundaries and clickable parents, while flattening empty layout wrappers.
            val emit = hasContent || isInteractive || node.isSelected || node.isFocused ||
                collection != null || item != null || isCollectionChild

            if (emit) {
                if (nodeCount >= MAX_NODES) {
                    nodeLimitReached = true
                    return
                }
                nodeCount++
                val className = node.className?.toString()?.substringAfterLast('.').orEmpty()
                text.append("  ".repeat(outputDepth)).append(className)
                if (!label.isNullOrBlank()) text.append(" \"").append(label).append('"')
                if (!description.isNullOrBlank() && description != label) {
                    text.append(" (").append(description).append(')')
                }

                val attrs = mutableListOf(if (node.isEnabled) "enabled" else "disabled")
                if (node.isSelected) attrs.add("selected")
                if (node.isFocused) attrs.add("focused")
                if (node.isEditable) attrs.add("editable")
                if (node.isScrollable) attrs.add("scrollable")
                if (node.isCheckable) attrs.add(if (node.isChecked) "checked" else "unchecked")
                if (node.isClickable && !node.isEditable) attrs.add("clickable")
                if (collection != null) attrs.add("collection")
                if (item != null) {
                    attrs.add("item(row=${item.rowIndex},column=${item.columnIndex})")
                } else if (isCollectionChild) {
                    attrs.add("item")
                }
                text.append(' ').append(attrs.joinToString(","))

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val x1 = (bounds.left * 1000 / displayWidth).coerceIn(0, 1000)
                val y1 = (bounds.top * 1000 / displayHeight).coerceIn(0, 1000)
                val x2 = (bounds.right * 1000 / displayWidth).coerceIn(0, 1000)
                val y2 = (bounds.bottom * 1000 / displayHeight).coerceIn(0, 1000)
                text.append(" ($x1,$y1)-($x2,$y2)\n")
            }

            for (i in 0 until node.childCount) {
                if (nodeLimitReached) break
                val child = node.getChild(i)
                if (child == null) {
                    missingChildren = true
                    continue
                }
                try {
                    appendNode(child, depth + 1, outputDepth + (if (emit) 1 else 0), collection != null)
                } finally {
                    child.recycle()
                }
            }
        }

        fun finish(): String? {
            if (text.isEmpty()) return null
            if (nodeLimitReached) text.append("[tree truncated: output node limit=$MAX_NODES]\n")
            if (depthLimitReached) text.append("[tree truncated: depth limit=$MAX_DEPTH]\n")
            if (missingChildren) text.append("[tree incomplete: some child nodes unavailable]\n")
            return text.toString().trimEnd()
        }

        private fun compactLabel(value: CharSequence?): String? {
            val label = value?.toString()?.replace('\n', ' ')?.replace('\r', ' ') ?: return null
            return if (label.length > 50) label.take(50) + "…[truncated]" else label
        }
    }
}
