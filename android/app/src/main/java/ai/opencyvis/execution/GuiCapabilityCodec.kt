package ai.opencyvis.execution

import ai.opencyvis.execution.gui.GuiBlockRequest
import ai.opencyvis.execution.gui.GuiBlockResult
import ai.opencyvis.execution.gui.GuiBlockAction
import ai.opencyvis.execution.gui.GuiExpectedState
import ai.opencyvis.execution.gui.GuiNodeSnapshot
import ai.opencyvis.execution.gui.GuiOperationEvidence
import ai.opencyvis.execution.gui.GuiPageIdentity
import ai.opencyvis.execution.gui.GuiQueryResult
import ai.opencyvis.execution.gui.GuiSelector
import org.json.JSONArray
import org.json.JSONObject

/** The parent and Python SDK share one GUI argument and evidence contract. */
object GuiCapabilityCodec {
    fun selector(json: JSONObject, displayId: Int): GuiSelector {
        require(!json.has("display_id") || json.getInt("display_id") == displayId) {
            "GUI selectors must use the current task display"
        }
        return GuiSelector(displayId, json.getString("package_name"), json.textOrNull("resource_id"),
            json.textOrNull("text"), json.textOrNull("content_description"), json.textOrNull("class_name"))
    }

    fun block(json: JSONObject, displayId: Int): GuiBlockRequest {
        val actions = json.getJSONArray("actions")
        require(actions.length() in 1..3) { "A GUI block contains one to three actions" }
        return GuiBlockRequest(selector(json.getJSONObject("page_anchor"), displayId),
            (0 until actions.length()).map { index ->
                val action = actions.getJSONObject(index)
                val target = selector(action.getJSONObject("selector"), displayId)
                when (action.getString("type")) {
                    "set_text" -> GuiBlockAction.SetText(target, action.getString("text"))
                    "tap" -> {
                        val expect = action.getJSONObject("expect")
                        val state = GuiExpectedState(selector(expect.getJSONObject("selector"), displayId),
                            expect.textOrNull("text"), expect.booleanOrNull("checked"), expect.booleanOrNull("selected"))
                        require(state.text != null || state.checked != null || state.selected != null) {
                            "A tap requires an explicit result condition"
                        }
                        GuiBlockAction.Tap(target, state)
                    }
                    else -> throw IllegalArgumentException("Unknown GUI action: ${action.getString("type")}")
                }
            })
    }

    fun toJson(result: GuiQueryResult): JSONObject = JSONObject().put("page", page(result.page))
        .put("nodes", JSONArray(result.matches.map(::node))).put("reason", result.reason ?: JSONObject.NULL)

    fun toJson(result: GuiBlockResult): JSONObject = JSONObject().put("status", result.status)
        .put("page", page(result.currentPage)).put("operations", JSONArray(result.operations.map(::toJson)))
        .put("delivered", result.operations.any { it.delivered })
        .put("verified", result.status == "completed" && result.operations.all { it.verified })
        .put("reason", result.reason ?: JSONObject.NULL)

    fun toJson(evidence: GuiOperationEvidence): JSONObject = JSONObject().put("index", evidence.index)
        .put("action", action(evidence.action)).put("page", page(evidence.after?.page ?: evidence.before?.page))
        .put("before", node(evidence.before)).put("after", node(evidence.after))
        .put("delivered", evidence.delivered).put("verified", evidence.verified)
        .put("reason", evidence.reason ?: JSONObject.NULL)

    fun cards(displayId: Int): JSONArray {
        require(displayId >= 0) { "A current task display is required" }
        val text = JSONObject().put("type", "string")
        val boolean = JSONObject().put("type", "boolean")
        val selector = objectSchema(mapOf(
            "package_name" to JSONObject().put("type", "string").put("minLength", 1),
            "display_id" to JSONObject().put("type", "integer").put("enum", JSONArray(listOf(displayId))),
            "resource_id" to text, "text" to text, "content_description" to text, "class_name" to text,
        ), listOf("package_name"))
        val expect = objectSchema(mapOf("selector" to selector, "text" to text,
            "checked" to boolean, "selected" to boolean), listOf("selector"))
            .put("anyOf", JSONArray(listOf("text", "checked", "selected").map {
                JSONObject().put("required", JSONArray(listOf(it)))
            }))
        val setText = objectSchema(mapOf("type" to JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("set_text"))), "selector" to selector, "text" to text),
            listOf("type", "selector", "text"))
        val tap = objectSchema(mapOf("type" to JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("tap"))), "selector" to selector, "expect" to expect),
            listOf("type", "selector", "expect"))
        val actions = JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 3)
            .put("items", JSONObject().put("oneOf", JSONArray(listOf(setText, tap))))
        return JSONArray().put(card("gui.query", false,
            "Read matching nodes on the current task display. All supplied selector fields match exactly. Returns actual page, bounds and full text.",
            objectSchema(mapOf("selector" to selector), listOf("selector"))))
            .put(card("gui.execute_block", true,
                "Execute 1–3 actions on a stable page. Every target must match uniquely and is located again before use. set_text replaces and reads back full text; tap requires a result condition. Page or control changes stop the remaining actions and return evidence. Use one action for a separate save stage.",
                objectSchema(mapOf("page_anchor" to selector, "actions" to actions), listOf("page_anchor", "actions"))))
    }

    private fun action(value: GuiBlockAction): JSONObject = JSONObject().put("selector", selectorJson(value.selector)).apply {
        when (value) {
            is GuiBlockAction.SetText -> put("type", "set_text").put("text", value.text)
            is GuiBlockAction.Tap -> put("type", "tap").put("expect", JSONObject()
                .put("selector", selectorJson(value.expect.selector)).put("text", value.expect.text)
                .put("checked", value.expect.checked).put("selected", value.expect.selected))
        }
    }

    private fun selectorJson(value: GuiSelector): JSONObject = JSONObject().put("display_id", value.displayId)
        .put("package_name", value.packageName).put("resource_id", value.resourceId).put("text", value.text)
        .put("content_description", value.contentDescription).put("class_name", value.className)

    private fun page(value: GuiPageIdentity?): Any = value?.let {
        JSONObject().put("display_id", it.displayId).put("package_name", it.packageName).put("window_id", it.windowId)
    } ?: JSONObject.NULL

    private fun node(value: GuiNodeSnapshot?): Any = value?.let {
        JSONObject().put("page", page(it.page)).put("resource_id", it.resourceId ?: JSONObject.NULL)
            .put("class_name", it.className ?: JSONObject.NULL).put("text", it.text ?: JSONObject.NULL)
            .put("content_description", it.contentDescription ?: JSONObject.NULL)
            .put("bounds", JSONObject().put("left", it.bounds.left).put("top", it.bounds.top)
                .put("right", it.bounds.right).put("bottom", it.bounds.bottom))
            .put("enabled", it.enabled).put("visible", it.visible).put("editable", it.editable)
            .put("clickable", it.clickable).put("checked", it.checked).put("selected", it.selected).put("password", it.password)
    } ?: JSONObject.NULL

    private fun card(name: String, mutates: Boolean, description: String, schema: JSONObject) = JSONObject()
        .put("name", name).put("description", description).put("available", true).put("mutates", mutates)
        .put("unavailable_reason", JSONObject.NULL).put("input_schema", schema)

    private fun objectSchema(properties: Map<String, Any>, required: List<String>) = JSONObject()
        .put("type", "object").put("properties", JSONObject(properties)).put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.booleanOrNull(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
}
