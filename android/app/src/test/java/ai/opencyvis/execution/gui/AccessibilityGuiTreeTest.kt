package ai.opencyvis.execution.gui

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityGuiTreeTest {
    private val page = GuiPageIdentity(8, "fixture.notes", 12)
    private val selector = GuiSelector(8, "fixture.notes", "fixture.notes:id/body")
    private fun target(text: String = "Existing") = mockk<AccessibilityNodeInfo>(relaxed = true).also { node ->
        every { node.viewIdResourceName } returns selector.resourceId
        every { node.className } returns "android.widget.EditText"
        every { node.text } returns text
        every { node.isVisibleToUser } returns true
        every { node.isEnabled } returns true
        every { node.isEditable } returns true
        every { node.refresh() } returns true
        every { node.getBoundsInScreen(any()) } answers { firstArg<Rect>().set(12, 30, 400, 500) }
    }

    private fun window(nodes: List<AccessibilityNodeInfo>, packageName: String = page.packageName, layer: Int = 1): AccessibilityWindowInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns packageName
        every { root.isVisibleToUser } returns true
        every { root.findAccessibilityNodeInfosByViewId(selector.resourceId!!) } returns nodes
        return mockk<AccessibilityWindowInfo>(relaxed = true).also {
            every { it.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { it.id } returns page.windowId
            every { it.layer } returns layer
            every { it.root } returns root
        }
    }

    @Test fun resourceIdQueryReadsTheEntireValueAndKeepsConcreteControlAttributes() {
        val text = "长正文\n" + "原始字符不能截断。".repeat(40)
        val result = AccessibilityGuiTree.query(listOf(window(listOf(target(text)))),
            selector.copy(className = "android.widget.EditText", text = text))
        assertNull(result.reason)
        assertEquals(page, result.page)
        assertEquals(text, result.matches.single().text)
        assertEquals(GuiBounds(12, 30, 400, 500), result.matches.single().bounds)
        assertTrue(result.matches.single().editable)
    }

    @Test fun aDifferentTopApplicationCannotMatchAnUnderlyingApplicationsControl() {
        val result = AccessibilityGuiTree.query(listOf(window(listOf(target())),
            window(emptyList(), packageName = "other.application", layer = 2)), selector)
        assertEquals("package_changed", result.reason)
        assertEquals("other.application", result.page!!.packageName)
        assertTrue(result.matches.isEmpty())
    }

    @Test fun setTextRequiresOneCurrentEditableMatchAndPassesTheEntireReplacementValue() {
        val node = target()
        val duplicate = target()
        val ambiguous = AccessibilityGuiTree.setText(listOf(window(listOf(node, duplicate))), selector, page, "New", { true })
        assertFalse(ambiguous.delivered)
        verify(exactly = 0) { node.performAction(any(), any()) }
        val changed = AccessibilityGuiTree.setText(listOf(window(listOf(node))), selector, page, "New", { false })
        assertFalse(changed.delivered)
        verify(exactly = 0) { node.performAction(any(), any()) }

        var replacement: String? = null
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } answers {
            replacement = secondArg<Bundle>().getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()
            true
        }
        val full = "New title\n" + "完整正文".repeat(30)
        assertTrue(AccessibilityGuiTree.setText(listOf(window(listOf(node))), selector, page, full, { true }).delivered)
        assertEquals(full, replacement)
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
    }
}
