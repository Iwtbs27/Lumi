package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.opencyvis.R
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object MemoryUi {
    fun label(context: Context, value: String): String {
        val resource = when (value) {
            "fact" -> R.string.mem_fact
            "procedure" -> R.string.mem_procedure
            "active" -> R.string.mem_active
            "candidate" -> R.string.mem_candidate
            "needs_review" -> R.string.mem_needs_review
            "superseded" -> R.string.mem_superseded
            "disabled" -> R.string.mem_disabled
            "explicit_user" -> R.string.mem_explicit_user
            "user_edit" -> R.string.mem_user_edit
            "observed" -> R.string.mem_observed
            "inferred" -> R.string.mem_inferred
            "learned" -> R.string.learning_learned
            "human_demo" -> R.string.learning_teach
            "human_correction" -> R.string.learning_correct
            "human_observation" -> R.string.learning_observe
            "agent_run" -> R.string.learning_agent_run
            "human" -> R.string.learning_actor_human
            "agent" -> R.string.learning_actor_agent
            "mixed" -> R.string.learning_actor_mixed
            "unknown" -> R.string.learning_actor_unknown
            "legacy" -> R.string.mem_legacy
            "user_stated" -> R.string.mem_user_stated
            "user_review" -> R.string.mem_human_confirmed
            "observed_once" -> R.string.mem_observed_once
            "observed_repeatedly" -> R.string.mem_observed_repeatedly
            "unverified" -> R.string.mem_unverified
            "pending" -> R.string.mem_pending
            "running" -> R.string.mem_running
            "completed" -> R.string.mem_completed
            "failed" -> R.string.mem_failed
            "cancelled" -> R.string.mem_cancelled
            else -> return value
        }
        return context.getString(resource)
    }

    fun date(time: Long): String = DateFormat.format("yyyy-MM-dd HH:mm", time).toString()

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 24))
    }

    fun scroll(column: LinearLayout): ScrollView = ScrollView(column.context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(column)
    }

    fun text(column: LinearLayout, content: String): TextView = TextView(column.context).apply {
        text = content
        textSize = 15f
        setTextColor(context.getColor(R.color.on_surface_medium))
        setTextIsSelectable(true)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        column.addView(this)
    }

    fun heading(column: LinearLayout, content: String): TextView = text(column, content).apply {
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(context.getColor(R.color.on_surface))
        setPadding(0, dp(context, 20), 0, dp(context, 8))
        isAccessibilityHeading = true
    }

    fun caption(column: LinearLayout, content: String): TextView = text(column, content).apply {
        textSize = 13f
        setTextColor(context.getColor(R.color.text_caption))
    }

    fun card(column: LinearLayout, tonal: Boolean = false): LinearLayout = LinearLayout(column.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
        setBackgroundResource(if (tonal) R.drawable.bg_ui_tonal else R.drawable.bg_ui_card)
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) })
    }

    fun action(column: LinearLayout, label: Int, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(column.context).apply {
            setText(label)
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            setBackgroundResource(if (danger) R.drawable.bg_ui_danger else R.drawable.bg_ui_tonal)
            backgroundTintList = null
            if (primary) backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.color_accent))
            setTextColor(context.getColor(when {
                primary -> R.color.on_accent
                danger -> R.color.color_danger
                else -> R.color.on_brand_container
            }))
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = context.getDrawable(ripple.resourceId)
            setOnClickListener { onClick() }
            column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8) })
        }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun field(column: LinearLayout, label: String, value: String = ""): EditText {
        val caption = text(column, label)
        return EditText(column.context).apply {
            id = android.view.View.generateViewId()
            caption.labelFor = id
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(value)
            textSize = 16f
            setTextColor(context.getColor(R.color.on_surface))
            setHintTextColor(context.getColor(R.color.on_surface_hint))
            setBackgroundResource(R.drawable.bg_ui_tonal)
            minHeight = dp(context, 52)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) })
        }
    }

    fun arrayText(array: JSONArray?): String =
        if (array == null) "" else (0 until array.length()).joinToString("\n") { array.getString(it) }

    fun lines(input: EditText): JSONArray = JSONArray(input.text.lines().map(String::trim).filter(String::isNotEmpty))

    fun error(context: Context, error: Exception) {
        if (error is CancellationException) throw error
        Toast.makeText(context, context.getString(R.string.mem_error, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    fun deleteConversation(context: Context, onDelete: (Boolean) -> Unit) {
        val column = column(context)
        text(column, context.getString(R.string.mem_delete_chat_body))
        val related = CheckBox(context).apply {
            text = context.getString(R.string.mem_delete_related)
            column.addView(this)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mem_delete_chat_title)
            .setView(scroll(column))
            .setPositiveButton(R.string.memory_action_delete) { _, _ -> onDelete(related.isChecked) }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    fun describe(body: JSONObject): String = buildString {
        body.keys().forEach { key ->
            val value = body.get(key)
            append(key).append(": ")
            append(if (value is JSONArray) (0 until value.length()).joinToString("\n") { i ->
                val entry = value.get(i)
                if (entry is JSONObject) entry.keys().asSequence().joinToString(" · ") { k -> "$k: ${entry.get(k)}" } else entry.toString()
            } else value.toString())
            append("\n\n")
        }
    }
}
