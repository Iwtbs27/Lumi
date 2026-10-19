package ai.opencyvis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryItem
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject

internal class MemoryDetails(private val context: Context) {
    private val display = MemoryPresentation(context)

    fun populate(column: LinearLayout, memory: MemoryItem, body: JSONObject, confirmed: Boolean) {
        val badges = ChipGroup(context).apply {
            isSingleLine = false
            chipSpacingHorizontal = MemoryUi.dp(context, 8)
            chipSpacingVertical = MemoryUi.dp(context, 4)
        }
        column.addView(badges)
        fun badge(label: String, foreground: Int, background: Int) = Chip(context).apply {
            text = label
            textSize = 13f
            isCheckable = false
            isClickable = false
            isFocusable = false
            chipStrokeWidth = 0f
            setTextColor(context.getColor(foreground))
            chipBackgroundColor = ColorStateList.valueOf(context.getColor(background))
            badges.addView(this)
        }
        badge(display.type(memory), R.color.on_brand_container,
            if (memory.type == "procedure") R.color.brand_container else R.color.memory_knowledge_background)
        val (foreground, background) = when (memory.status) {
            "active" -> R.color.color_success to R.color.success_container
            "candidate", "needs_review" -> R.color.on_brand_container to R.color.brand_container
            else -> R.color.text_caption to R.color.surface_container
        }
        badge(MemoryUi.label(context, memory.status), foreground, background)
        MemoryUi.caption(column, display.classification(memory, body))
        section(column, R.string.mp_summary)
        content(column, memory.summary)

        section(column, R.string.mp_status_heading)
        val statusHelp = when (memory.status) {
            "active" -> R.string.mp_status_active
            "candidate" -> R.string.mp_status_candidate
            "needs_review" -> R.string.mp_status_review
            "disabled" -> R.string.mp_status_disabled
            "superseded" -> R.string.mp_status_superseded
            else -> null
        }
        statusHelp?.let { MemoryUi.caption(column, context.getString(it)) }
        property(column, R.string.mp_source, display.source(memory))
        property(column, R.string.mp_confirmation, display.confirmation(memory, confirmed))
        property(column, R.string.mp_run_verification, display.observation(memory))
        MemoryUi.caption(column, context.getString(R.string.mp_status_help))

        if (memory.type == "procedure") procedure(column, body) else {
            section(column, R.string.mp_knowledge_content)
            content(column, value(body, "value"))
            section(column, R.string.mp_conditions)
            list(column, body.optJSONArray("conditions"), R.string.mp_no_conditions)
        }
        optionalList(column, R.string.mp_failure_notes, body.optJSONArray("failure_notes"))
        optionalList(column, R.string.mp_aliases, body.optJSONArray("aliases"))

        section(column, R.string.mp_properties)
        property(column, R.string.mp_scope, display.appScope(memory, includePackage = true))
        memory.taskFamily?.takeIf(String::isNotBlank)?.let { property(column, R.string.mp_task_family, it) }
        property(column, R.string.mp_revision, "v${memory.revision}")
        property(column, R.string.mp_created, MemoryUi.date(memory.createdAt))
        property(column, R.string.mp_updated, MemoryUi.date(memory.updatedAt))
        memory.lastVerifiedAt?.let {
            property(column, if (display.hasObservation(memory)) R.string.mp_support_date
                else R.string.mp_historical_date, MemoryUi.date(it))
        }
        body.optString("maturity").takeIf(String::isNotBlank)?.let { maturity ->
            property(column, R.string.mp_maturity, when (maturity) {
                "observed" -> context.getString(R.string.mp_maturity_observed)
                "validated" -> context.getString(R.string.mp_maturity_validated)
                else -> maturity
            })
        }
        body.optString("source_ref").takeIf(String::isNotBlank)?.let {
            property(column, R.string.mp_source_reference, it)
        }
    }

    private fun procedure(column: LinearLayout, body: JSONObject) {
        section(column, R.string.mp_method_goal)
        content(column, value(body, "goal"))
        section(column, R.string.mp_conditions)
        list(column, body.optJSONArray("preconditions"), R.string.mp_no_conditions)

        section(column, R.string.mp_method_steps)
        val nodes = body.optJSONArray("nodes") ?: JSONArray()
        fun nodeLabel(id: String): String {
            val index = (0 until nodes.length()).firstOrNull { nodes.getJSONObject(it).optString("node_id") == id }
            return if (index == null) id else "${context.getString(R.string.mp_step_number, index + 1)} · $id"
        }
        if (nodes.length() == 0) MemoryUi.text(column, context.getString(R.string.mem_method_content_missing))
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val step = MemoryUi.card(column, tonal = true)
            MemoryUi.text(step, context.getString(R.string.mp_step_number, index + 1)).apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.on_surface))
                isAccessibilityHeading = true
            }
            content(step, value(node, "intent"))
            property(step, R.string.mp_expected, value(node, "expected"))
            node.optString("next").takeIf(String::isNotBlank)?.let {
                property(step, R.string.mp_next, nodeLabel(it))
            }
            node.optJSONArray("branches")?.let { branches ->
                for (branchIndex in 0 until branches.length()) {
                    val branch = branches.getJSONObject(branchIndex)
                    MemoryUi.text(step, context.getString(R.string.mp_branch,
                        value(branch, "condition"), nodeLabel(branch.optString("next"))))
                }
            }
            node.optString("node_id").takeIf(String::isNotBlank)?.let {
                MemoryUi.caption(step, context.getString(R.string.mp_step_reference, it))
            }
        }

        section(column, R.string.mp_completion)
        content(column, value(body, "completion"))
        section(column, R.string.mp_parameters)
        val parameters = body.optJSONArray("parameters") ?: JSONArray()
        if (parameters.length() == 0) MemoryUi.caption(column, context.getString(R.string.mp_no_parameters))
        else MemoryUi.caption(column, context.getString(R.string.mp_parameter_help))
        for (index in 0 until parameters.length()) {
            val parameter = parameters.getJSONObject(index)
            val required = if (!parameter.has("required")) context.getString(R.string.mp_no_value)
                else context.getString(if (parameter.getBoolean("required")) R.string.mp_parameter_required else R.string.mp_parameter_optional)
            MemoryUi.text(column, "${value(parameter, "name")} · $required").apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.on_surface))
            }
            content(column, value(parameter, "description"))
        }
        body.optJSONArray("data_dependencies")?.takeIf { it.length() > 0 }?.let { dependencies ->
            section(column, R.string.mp_data_dependencies)
            for (index in 0 until dependencies.length()) {
                val dependency = dependencies.getJSONObject(index)
                property(column, R.string.mp_dependency_source, value(dependency, "source"))
                property(column, R.string.mp_dependency_transform, value(dependency, "transform"))
                property(column, R.string.mp_dependency_target,
                    "${nodeLabel(dependency.optString("target_node"))} · ${value(dependency, "target_field")}")
                if (index < dependencies.length() - 1) divider(column)
            }
        }
    }

    private fun value(body: JSONObject, key: String): String = body.optString(key).ifBlank {
        context.getString(R.string.mp_no_value)
    }

    private fun section(column: LinearLayout, title: Int) {
        divider(column)
        MemoryUi.heading(column, context.getString(title))
    }

    private fun divider(column: LinearLayout) {
        column.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.outline)) },
            LinearLayout.LayoutParams(-1, MemoryUi.dp(context, 1)).apply { topMargin = MemoryUi.dp(context, 14) })
    }

    private fun content(column: LinearLayout, text: String) = MemoryUi.text(column, text).apply {
        textSize = 16f
        setTextColor(context.getColor(R.color.on_surface))
    }

    private fun property(column: LinearLayout, label: Int, value: String) {
        MemoryUi.text(column, "").text = display.row(label, value)
    }

    private fun list(column: LinearLayout, values: JSONArray?, empty: Int) {
        if (values == null || values.length() == 0) MemoryUi.caption(column, context.getString(empty))
        else for (index in 0 until values.length()) content(column, "• ${values.getString(index)}")
    }

    private fun optionalList(column: LinearLayout, title: Int, values: JSONArray?) {
        if (values == null || values.length() == 0) return
        section(column, title)
        list(column, values, R.string.mp_no_value)
    }
}
