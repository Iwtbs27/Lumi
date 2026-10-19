package ai.opencyvis.execution

import ai.opencyvis.execution.gui.GuiBlockAction
import ai.opencyvis.execution.gui.GuiBlockResult
import ai.opencyvis.execution.gui.GuiBounds
import ai.opencyvis.execution.gui.GuiNodeSnapshot
import ai.opencyvis.execution.gui.GuiOperationEvidence
import ai.opencyvis.execution.gui.GuiPageIdentity
import ai.opencyvis.execution.gui.GuiQueryResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiCapabilityCodecTest {
    private fun selector(id: String) = JSONObject().put("package_name", "notes.fixture")
        .put("resource_id", "notes.fixture:id/$id")
    private val fullText = "完整内容\n" + "Read every character. ".repeat(30)
    private fun input() = JSONObject().put("page_anchor", selector("page").put("text", "Editor"))
        .put("actions", JSONArray()
            .put(JSONObject().put("type", "set_text").put("selector", selector("body")).put("text", fullText))
            .put(JSONObject().put("type", "tap").put("selector", selector("save"))
                .put("expect", JSONObject().put("selector", selector("status")).put("text", "Saved")
                    .put("checked", false).put("selected", true))))

    @Test fun selectorsKeepExactFieldsAndCannotSwitchTheTaskDisplay() {
        val value = selector("body").put("display_id", 8).put("text", "Old text")
            .put("content_description", "Note body").put("class_name", "android.widget.EditText")
        val result = GuiCapabilityCodec.selector(value, 8)
        assertEquals(8, result.displayId)
        assertEquals("notes.fixture", result.packageName)
        assertEquals("notes.fixture:id/body", result.resourceId)
        assertEquals("Old text", result.text)
        assertEquals("Note body", result.contentDescription)
        assertEquals("android.widget.EditText", result.className)
        assertThrows(IllegalArgumentException::class.java) { GuiCapabilityCodec.selector(value, 0) }
        assertEquals(8, GuiCapabilityCodec.selector(selector("body"), 8).displayId)
    }

    @Test fun blockDecodesFullTextAndExplicitFalseInTheTapCheck() {
        val result = GuiCapabilityCodec.block(input(), 8)
        assertEquals("Editor", result.pageAnchor.text)
        assertEquals(fullText, (result.actions[0] as GuiBlockAction.SetText).text)
        val save = result.actions[1] as GuiBlockAction.Tap
        assertEquals("notes.fixture:id/status", save.expect.selector.resourceId)
        assertEquals("Saved", save.expect.text)
        assertEquals(false, save.expect.checked)
        assertEquals(true, save.expect.selected)
        assertEquals(8, save.selector.displayId)
    }

    @Test fun outputKeepsActualPageBoundsFullReadbackAndPartialEvidence() {
        val request = GuiCapabilityCodec.block(input(), 8)
        val page = GuiPageIdentity(8, "notes.fixture", 42)
        val before = GuiNodeSnapshot(page, "notes.fixture:id/body", "android.widget.EditText", "Old text", null,
            GuiBounds(1, 2, 300, 400), true, true, true, false, false, false)
        val after = before.copy(text = fullText)
        val query = GuiCapabilityCodec.toJson(GuiQueryResult(page, listOf(after)))
        assertEquals(42, query.getJSONObject("page").getInt("window_id"))
        val found = query.getJSONArray("nodes").getJSONObject(0)
        assertEquals(fullText, found.getString("text"))
        assertEquals(400, found.getJSONObject("bounds").getInt("bottom"))
        assertTrue(found.getBoolean("editable"))
        val operation = GuiOperationEvidence(1, request.actions[0], before, after, true, true)
        val encoded = GuiCapabilityCodec.toJson(operation)
        assertEquals(fullText, encoded.getJSONObject("action").getString("text"))
        assertEquals("Old text", encoded.getJSONObject("before").getString("text"))
        assertEquals(fullText, encoded.getJSONObject("after").getString("text"))
        assertTrue(encoded.getBoolean("delivered"))
        assertTrue(encoded.getBoolean("verified"))
        val partial = GuiCapabilityCodec.toJson(GuiBlockResult("partial", listOf(operation), page, "page_changed"))
        assertEquals("partial", partial.getString("status"))
        assertEquals("page_changed", partial.getString("reason"))
        assertEquals(1, partial.getJSONArray("operations").length())
        val unavailable = GuiCapabilityCodec.toJson(GuiQueryResult(null, emptyList(), "accessibility_unavailable"))
        assertTrue(unavailable.isNull("page"))
        assertEquals(0, unavailable.getJSONArray("nodes").length())
    }

    @Test fun capabilityCardsDescribeTheSelectorsAndBothActionShapes() {
        val cards = GuiCapabilityCodec.cards(8)
        assertEquals(listOf("gui.query", "gui.execute_block"), (0 until cards.length())
            .map { cards.getJSONObject(it).getString("name") })
        val query = cards.getJSONObject(0)
        assertTrue(query.getBoolean("available"))
        assertFalse(query.getBoolean("mutates"))
        val selector = query.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("selector")
        assertEquals("package_name", selector.getJSONArray("required").getString(0))
        assertEquals(8, selector.getJSONObject("properties").getJSONObject("display_id").getJSONArray("enum").getInt(0))
        val block = cards.getJSONObject(1)
        assertTrue(block.getBoolean("mutates"))
        val actions = block.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("actions")
        assertEquals(1, actions.getInt("minItems"))
        assertEquals(3, actions.getInt("maxItems"))
        val shapes = actions.getJSONObject("items").getJSONArray("oneOf")
        assertEquals(listOf("set_text", "tap"), (0 until shapes.length()).map {
            shapes.getJSONObject(it).getJSONObject("properties").getJSONObject("type").getJSONArray("enum").getString(0)
        })
        val expect = shapes.getJSONObject(1).getJSONObject("properties").getJSONObject("expect")
        assertEquals(3, expect.getJSONArray("anyOf").length())
    }
}
