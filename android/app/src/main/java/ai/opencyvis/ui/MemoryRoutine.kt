package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import ai.opencyvis.R
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.RoutineEntity
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Routines store a reference; each launch binds fresh values through the existing Agent entrypoint. */
internal object MemoryRoutine {
    const val PREFIX = "memory_procedure:"

    fun save(context: Context, scope: CoroutineScope, memory: MemoryItem) = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(context).routineDao()
                val instruction = PREFIX + memory.id
                if (dao.getAllRoutines().none { it.instruction == instruction }) {
                    dao.insertRoutine(RoutineEntity(
                        name = memory.title, icon = "▶", instruction = instruction, description = memory.summary,
                        category = "custom", isPinned = false, useCount = 0, lastUsedAt = null,
                        createdAt = System.currentTimeMillis(), sortOrder = dao.getCount()
                    ))
                }
            }
            Toast.makeText(context, R.string.mem_routine_saved, Toast.LENGTH_LONG).show()
        } catch (error: Exception) { MemoryUi.error(context, error) }
    }

    fun prepare(context: Context, scope: CoroutineScope, reference: String, onReady: (String) -> Unit) = scope.launch {
        try {
            val memory = MemoryRepository(context).get(reference.removePrefix(PREFIX))
            if (memory == null || memory.type != "procedure" || memory.status != "active" ||
                JSONObject(memory.bodyJson).optString("maturity") != "validated") {
                Toast.makeText(context, R.string.mem_routine_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            val body = JSONObject(memory.bodyJson)
            val column = MemoryUi.column(context)
            MemoryUi.text(column, body.getString("goal"))
            MemoryUi.text(column, context.getString(R.string.mem_parameter_hint))
            val fields = mutableListOf<Pair<JSONObject, EditText>>()
            val parameters = body.optJSONArray("parameters") ?: JSONArray()
            for (i in 0 until parameters.length()) {
                val parameter = parameters.getJSONObject(i)
                val label = "${parameter.getString("name")} · ${parameter.optString("description")}" +
                    if (parameter.optBoolean("required", true)) " *" else ""
                fields += parameter to MemoryUi.field(column, label)
            }
            val dialog = AlertDialog.Builder(context).setTitle(memory.title).setView(MemoryUi.scroll(column))
                .setPositiveButton(R.string.mem_run, null).setNegativeButton(R.string.memory_dialog_cancel, null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val missing = fields.firstOrNull { it.first.optBoolean("required", true) && it.second.text.isBlank() }
                    if (missing != null) {
                        missing.second.error = context.getString(R.string.mem_required)
                        missing.second.requestFocus()
                        return@setOnClickListener
                    }
                    val inputs = JSONObject()
                    fields.forEach { (parameter, input) ->
                        if (input.text.isNotBlank()) inputs.put(parameter.getString("name"), input.text.toString().trim())
                    }
                    val ref = JSONObject().put("id", memory.id).put("revision", memory.revision).put("parameters", inputs)
                    val description = inputs.keys().asSequence().joinToString("\n") { "$it: ${inputs.getString(it)}" }
                    val instruction = context.getString(R.string.mem_routine_task, body.getString("goal"), description) +
                        "\n[procedure_reference]\n$ref\n[/procedure_reference]"
                    dialog.dismiss()
                    onReady(instruction)
                }
            }
            dialog.show()
        } catch (error: Exception) { MemoryUi.error(context, error) }
    }
}
