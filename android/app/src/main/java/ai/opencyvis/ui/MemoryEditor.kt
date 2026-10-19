package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class MemoryEditor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: MemoryRepository,
    private val onSaved: () -> Unit
) {
    fun show(memory: MemoryItem?) {
        val body = memory?.let { JSONObject(it.bodyJson) } ?: JSONObject()
        if (memory?.type == "procedure" && (body.optJSONArray("nodes")?.length() ?: 0) == 0) {
            Toast.makeText(context, R.string.mem_method_content_missing, Toast.LENGTH_LONG).show()
            return
        }
        val column = MemoryUi.column(context)
        fun field(label: Int, value: String = "") = MemoryUi.field(column, context.getString(label), value)
        val title = field(R.string.mem_title, memory?.title.orEmpty())
        val summary = field(R.string.mem_summary, memory?.summary.orEmpty())
        val app = field(R.string.mem_app, memory?.appScope.orEmpty())
        val procedure = memory?.type == "procedure"
        val domain = if (procedure) null else Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf(context.getString(R.string.mem_user_preference), context.getString(R.string.mem_app_knowledge)))
            setSelection(if (body.optString("domain") == "app_knowledge") 1 else 0)
            column.addView(this)
        }
        val content = field(if (procedure) R.string.mem_goal else R.string.mem_value,
            body.optString(if (procedure) "goal" else "value", memory?.summary.orEmpty()))
        val conditionKey = if (procedure) "preconditions" else "conditions"
        val conditions = field(R.string.mem_conditions, MemoryUi.arrayText(body.optJSONArray(conditionKey)))
        val completion = if (procedure) field(R.string.mem_completion, body.optString("completion")) else null
        val nodeInputs = mutableListOf<Triple<JSONObject, EditText, EditText>>()
        body.optJSONArray("nodes")?.let { nodes ->
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                MemoryUi.text(column, "${context.getString(R.string.mem_steps)} ${i + 1} · ${node.getString("node_id")}")
                val intent = field(R.string.mem_intent, node.optString("intent"))
                val expected = field(R.string.mem_expected, node.optString("expected"))
                nodeInputs += Triple(node, intent, expected)
                val branches = node.optJSONArray("branches")
                if (branches != null && branches.length() > 0) MemoryUi.text(column,
                    (0 until branches.length()).joinToString("\n") { index ->
                        val branch = branches.getJSONObject(index)
                        "${branch.optString("condition")} → ${branch.optString("next")}"
                    })
            }
        }
        val parameterInputs = mutableListOf<Triple<JSONObject, EditText, CheckBox>>()
        val parameters = body.optJSONArray("parameters")
        if (parameters != null && parameters.length() > 0) {
            MemoryUi.text(column, context.getString(R.string.mem_parameters))
            for (i in 0 until parameters.length()) {
                val parameter = parameters.getJSONObject(i)
                val description = MemoryUi.field(column, parameter.getString("name"), parameter.optString("description"))
                val required = CheckBox(context).apply {
                    setText(R.string.mem_parameter_required)
                    isChecked = parameter.optBoolean("required", true)
                    column.addView(this)
                }
                parameterInputs += Triple(parameter, description, required)
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (memory == null) R.string.memory_dialog_add_title else R.string.memory_dialog_edit_title)
            .setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.memory_dialog_save, null)
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val required = listOfNotNull(title, content, completion) + nodeInputs.flatMap { listOf(it.second, it.third) }
                val missing = required.firstOrNull { it.text.isBlank() }
                if (missing != null) {
                    missing.error = context.getString(R.string.mem_required)
                    missing.requestFocus()
                    return@setOnClickListener
                }
                body.put(if (procedure) "goal" else "value", content.text.toString().trim())
                body.put(conditionKey, MemoryUi.lines(conditions))
                val appScope = app.text.toString().trim().takeIf(String::isNotEmpty)
                if (domain != null) body.put("domain", if (domain.selectedItemPosition == 1) "app_knowledge" else "user_preference")
                completion?.let { body.put("completion", it.text.toString().trim()) }
                nodeInputs.forEach { (node, intent, expected) ->
                    node.put("intent", intent.text.toString().trim()).put("expected", expected.text.toString().trim())
                }
                parameterInputs.forEach { (parameter, description, required) ->
                    parameter.put("description", description.text.toString().trim()).put("required", required.isChecked)
                }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                scope.launch {
                    try {
                        val summaryText = summary.text.toString().trim().ifEmpty { content.text.toString().trim() }
                        if (memory == null) {
                            repository.saveExplicit(title.text.toString().trim(), summaryText, appScope, bodyJson = body.toString())
                        } else repository.edit(memory.id, title.text.toString().trim(), summaryText, body.toString(), memory.revision, appScope)
                        dialog.dismiss()
                        onSaved()
                    } catch (error: Exception) {
                        MemoryUi.error(context, error)
                    } finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
