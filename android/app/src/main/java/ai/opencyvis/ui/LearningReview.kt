package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import ai.opencyvis.R
import ai.opencyvis.learning.LearningRepository
import ai.opencyvis.learning.LearningSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Human-readable proposals and evidence, backed by the same durable session as the worker. */
internal class LearningReview(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: LearningRepository,
    private val refresh: () -> Unit
) {
    companion object {
        fun modeLabel(context: Context, session: LearningSession): String = context.getString(when (session.mode) {
            "demonstration" -> R.string.learning_teach
            "correction" -> if (JSONObject(session.sourceJson).optBoolean("automatic_review")) {
                R.string.learning_task_review
            } else R.string.learning_correct
            else -> R.string.learning_observe
        })
        fun statusLabel(context: Context, status: String): String = context.getString(when (status) {
            "recording" -> R.string.learning_recording
            "paused" -> R.string.learning_paused
            "awaiting_task" -> R.string.learning_awaiting_task
            "queued" -> R.string.learning_queued
            "reviewing" -> R.string.learning_reviewing
            "parsing" -> R.string.learning_parsing
            "compiling" -> R.string.learning_compiling
            "ready" -> R.string.learning_ready
            "awaiting_input" -> R.string.learning_awaiting_input
            "saved" -> R.string.learning_saved
            "failed" -> R.string.learning_failed
            "discarded" -> R.string.learning_discarded
            "skipped" -> R.string.learning_skipped
            else -> R.string.learning_interrupted
        })

        fun statusBadge(column: LinearLayout, status: String) {
            val context = column.context
            val (foreground, background) = when (status) {
                "saved" -> R.color.color_success to R.color.success_container
                "failed" -> R.color.color_danger to R.color.danger_container
                "skipped", "discarded", "paused", "interrupted" -> R.color.text_caption to R.color.surface_container
                else -> R.color.on_brand_container to R.color.brand_container
            }
            MemoryUi.caption(column, statusLabel(context, status)).apply {
                setTextColor(context.getColor(foreground))
                setBackgroundResource(R.drawable.bg_ui_pill)
                backgroundTintList = ColorStateList.valueOf(context.getColor(background))
                setPadding(MemoryUi.dp(context, 10), MemoryUi.dp(context, 6), MemoryUi.dp(context, 10), MemoryUi.dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = MemoryUi.dp(context, 6) }
            }
        }

        fun statusHelp(context: Context, status: String): String? {
            val resource = when (status) {
                "awaiting_task" -> R.string.learning_awaiting_task_help
                "reviewing" -> R.string.learning_reviewing_help
                "skipped" -> R.string.learning_skipped_help
                "queued", "parsing", "compiling" -> R.string.learning_pending_foreground
                "ready" -> R.string.lumi_learning_ready_help
                "awaiting_input" -> R.string.lumi_learning_input_help
                "saved" -> R.string.lumi_learning_saved_help
                else -> return null
            }
            return context.getString(resource)
        }
    }

    fun render(column: LinearLayout, session: LearningSession) {
        val taskReview = JSONObject(session.sourceJson).optJSONObject("task_review")
        taskReview?.optString("reason")?.takeIf(String::isNotBlank)?.let {
            val result = MemoryUi.card(column, tonal = true)
            MemoryUi.heading(result, context.getString(R.string.learning_task_review_result))
            MemoryUi.text(result, it)
        }
        if (session.status in setOf("awaiting_task", "reviewing", "skipped")) {
            return
        }
        session.error?.let {
            val error = MemoryUi.card(column)
            MemoryUi.heading(error, context.getString(R.string.mui_review_error)).setTextColor(context.getColor(R.color.color_danger))
            MemoryUi.text(error, it)
        }
        if (session.status in setOf("queued", "parsing", "compiling")) {
            MemoryUi.heading(column, context.getString(R.string.mui_review_processing))
        }
        val analysis = JSONObject(session.analysisJson)
        analysis.optString("summary").takeIf(String::isNotBlank)?.let {
            MemoryUi.heading(column, context.getString(R.string.mui_review_summary))
            MemoryUi.text(column, it)
        }
        val editable = session.status in setOf("ready", "awaiting_input")
        val questions = JSONArray(session.questionsJson)
        if (editable && questions.length() > 0) {
            val question = questions.getJSONObject(0)
            val questionCard = MemoryUi.card(column, tonal = true)
            MemoryUi.heading(questionCard, context.getString(R.string.learning_question))
            MemoryUi.text(questionCard, question.getString("question"))
            question.optString("reason").takeIf(String::isNotBlank)?.let { MemoryUi.caption(questionCard, it) }
            val answer = MemoryUi.field(questionCard, context.getString(R.string.learning_answer_label))
            button(questionCard, R.string.learning_answer) {
                if (answer.text.isBlank()) answer.error = context.getString(R.string.learning_answer_required)
                else run { repository.answer(session.id, question.getString("id"), answer.text.toString()); refresh() }
            }
        }
        val proposals = JSONArray(session.proposalsJson)
        if (proposals.length() == 0 && editable) MemoryUi.text(column, context.getString(R.string.learning_no_proposals))
        if (proposals.length() > 0) MemoryUi.heading(column, context.getString(R.string.learning_result))
        for (i in 0 until proposals.length()) {
            val proposal = proposals.getJSONObject(i)
            val card = MemoryUi.card(column)
            MemoryUi.caption(card, MemoryUi.label(context, proposal.getString("type")))
            MemoryUi.heading(card, proposal.getString("title")).setPadding(0, 0, 0, MemoryUi.dp(context, 8))
            MemoryUi.text(card, proposal.getString("summary"))
            val details = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val expand = MemoryUi.action(card, R.string.mui_view_details) {}
            expand.setOnClickListener {
                val expanded = details.visibility != View.VISIBLE
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                expand.setText(if (expanded) R.string.mui_hide_details else R.string.mui_view_details)
            }
            card.addView(details)
            val body = proposal.getJSONObject("body")
            if (proposal.getString("type") == "procedure") {
                val nodes = body.getJSONArray("nodes")
                MemoryUi.text(details, MemoryUi.arrayText(body.optJSONArray("preconditions")))
                for (n in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(n)
                    MemoryUi.text(details, "${n + 1}. ${node.getString("intent")}\n${node.getString("expected")}")
                    node.optJSONArray("branches")?.let { branches ->
                        for (b in 0 until branches.length()) {
                            val branch = branches.getJSONObject(b)
                            val next = (0 until nodes.length()).map(nodes::getJSONObject)
                                .firstOrNull { it.getString("node_id") == branch.getString("next") }?.getString("intent").orEmpty()
                            MemoryUi.text(details, "${branch.getString("condition")} → $next")
                        }
                    }
                }
                MemoryUi.text(details, "${context.getString(R.string.learning_edit_completion)}：${body.getString("completion")}")
                body.optJSONArray("parameters")?.let { parameters ->
                    for (p in 0 until parameters.length()) {
                        val parameter = parameters.getJSONObject(p)
                        MemoryUi.text(details, "${context.getString(R.string.learning_edit_parameters)} · ${parameter.getString("name")}：${parameter.getString("description")}")
                    }
                }
                body.optJSONArray("data_dependencies")?.let { dependencies ->
                    for (d in 0 until dependencies.length()) {
                        val dependency = dependencies.getJSONObject(d)
                        MemoryUi.text(details, "${dependency.getString("source")} → ${dependency.getString("transform")} → ${dependency.getString("target_field")}")
                    }
                }
            } else {
                MemoryUi.text(details, body.getString("value"))
                MemoryUi.text(details, MemoryUi.arrayText(body.optJSONArray("conditions")))
            }
            if (proposal.optString("target_id").isNotBlank()) {
                MemoryUi.text(details, "${context.getString(R.string.learning_result_updates)}：${proposal.getString("reason")}")
            }
            if (editable) button(card, R.string.learning_edit) { editProposal(session, i) }
        }
        if (editable && proposals.length() > 0) {
            MemoryUi.heading(column, context.getString(R.string.mui_review_save))
            MemoryUi.caption(column, context.getString(R.string.learning_demo_evidence))
            button(column, R.string.learning_save_enable) { save(session, true) }
            button(column, R.string.learning_save) { save(session, false) }
        }
        if (session.status in setOf("failed", "interrupted", "ready", "awaiting_input")) {
            MemoryUi.heading(column, context.getString(R.string.mui_review_adjust))
            button(column, R.string.learning_supplement) { supplement(session) }
            button(column, R.string.learning_retry) { run { repository.requestLearning(session.id); refresh() } }
        }
    }

    private fun save(session: LearningSession, enabled: Boolean) = run {
        val results = repository.accept(session.id, enabled)
        val errors = results.filter { it.status == "conflict" }.map { it.reason }
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(context).setTitle(R.string.learning_result).setMessage(errors.joinToString("\n"))
                .setPositiveButton(R.string.mem_close, null).show()
        } else Toast.makeText(context, R.string.learning_save_success, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun supplement(session: LearningSession) {
        val column = MemoryUi.column(context)
        val note = MemoryUi.field(column, context.getString(R.string.learning_supplement)).apply { setHint(R.string.learning_note_hint) }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.learning_supplement).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.learning_retry, null).setNegativeButton(R.string.memory_dialog_cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (note.text.isBlank()) note.error = context.getString(R.string.learning_answer_required)
            else run { repository.supplement(session.id, note.text.toString()); dialog.dismiss(); refresh() }
        } }
        dialog.show()
    }

    private fun editProposal(session: LearningSession, index: Int) {
        val proposals = JSONArray(session.proposalsJson)
        val proposal = proposals.getJSONObject(index)
        val body = proposal.getJSONObject("body")
        val column = MemoryUi.column(context)
        val updates = mutableListOf<() -> Unit>()
        fun field(target: JSONObject, key: String, label: Int) {
            val input = MemoryUi.field(column, context.getString(label), target.optString(key))
            updates += { target.put(key, input.text.toString().trim()) }
        }
        fun lines(target: JSONObject, key: String, label: Int) {
            val input = MemoryUi.field(column, context.getString(label), MemoryUi.arrayText(target.optJSONArray(key)))
            updates += { target.put(key, MemoryUi.lines(input)) }
        }
        field(proposal, "title", R.string.learning_edit_title)
        field(proposal, "summary", R.string.learning_edit_summary)
        if (proposal.getString("type") == "fact") {
            field(body, "value", R.string.learning_edit_value)
            lines(body, "conditions", R.string.learning_edit_conditions)
        } else {
            field(body, "goal", R.string.learning_edit_goal)
            lines(body, "preconditions", R.string.learning_edit_conditions)
            val nodes = body.getJSONArray("nodes")
            for (n in 0 until nodes.length()) {
                val node = nodes.getJSONObject(n)
                MemoryUi.text(column, "${n + 1}. ${node.getString("intent")}")
                field(node, "intent", R.string.learning_edit_intent)
                field(node, "expected", R.string.learning_edit_expected)
            }
            field(body, "completion", R.string.learning_edit_completion)
            body.optJSONArray("data_dependencies")?.let { dependencies ->
                for (d in 0 until dependencies.length()) {
                    val dependency = dependencies.getJSONObject(d)
                    field(dependency, "source", R.string.learning_edit_dependency)
                    field(dependency, "transform", R.string.learning_edit_transform)
                    field(dependency, "target_field", R.string.learning_edit_target)
                }
            }
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.learning_edit).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.learning_save_changes, null).setNegativeButton(R.string.memory_dialog_cancel, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { run {
            updates.forEach { it() }
            repository.updateDraft(session.id, proposals.toString())
            dialog.dismiss()
            refresh()
        } } }
        dialog.show()
    }

    fun showFrames(session: LearningSession) = run {
        val frames = repository.frames(session.id)
        if (frames.isEmpty()) {
            AlertDialog.Builder(context).setMessage(R.string.learning_no_frames).setPositiveButton(R.string.mem_close, null).show()
            return@run
        }
        fun timeLabel(time: Long) = "+${(time - frames.first().timeMs) / 1000}s"
        AlertDialog.Builder(context).setTitle(R.string.learning_evidence)
            .setItems(frames.map { "${it.sequence} · ${timeLabel(it.timeMs)} · ${it.packageName.orEmpty()}" }.toTypedArray()) { _, index -> run {
                val frame = frames[index]
                val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(frame.imagePath).absolutePath) }
                val column = MemoryUi.column(context)
                val image = ImageView(context).apply {
                    adjustViewBounds = true
                    contentDescription = "${session.goal} · ${frame.sequence}"
                    setImageBitmap(bitmap)
                }
                column.addView(image)
                if (bitmap == null) MemoryUi.text(column, context.getString(R.string.mem_image_unavailable))
                val tree = withContext(Dispatchers.IO) { frame.treePath?.let(::File)?.takeIf(File::isFile)?.readText() }
                tree?.let { MemoryUi.text(column, it) }
                AlertDialog.Builder(context).setTitle("${frame.sequence} · ${timeLabel(frame.timeMs)}")
                    .setView(MemoryUi.scroll(column)).setPositiveButton(R.string.mem_close, null)
                    .setOnDismissListener { image.setImageDrawable(null); bitmap?.recycle() }.show()
            } }.setNegativeButton(R.string.mem_close, null).show()
    }

    fun showEvents(session: LearningSession) = run {
        val events = repository.events(session.id)
        val column = MemoryUi.column(context)
        events.forEach { event ->
            MemoryUi.text(column, "${event.optInt("sequence")} · ${event.optString("kind")}\n${MemoryUi.describe(event.optJSONObject("details") ?: event)}")
        }
        AlertDialog.Builder(context).setTitle(R.string.learning_events).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.mem_close, null).show()
    }

    fun export(session: LearningSession) = run {
        val file = repository.export(session.id)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.run_exports", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.learning_export)))
    }

    private fun button(column: LinearLayout, label: Int, action: () -> Unit) {
        MemoryUi.action(column, label,
            primary = label == R.string.learning_save_enable || label == R.string.learning_answer, onClick = action)
    }
    private fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (error: Exception) { MemoryUi.error(context, error) }
    }
}
