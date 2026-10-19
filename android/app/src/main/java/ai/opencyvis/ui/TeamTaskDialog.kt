package ai.opencyvis.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.team.ChildFact
import ai.opencyvis.team.DelegationRecord
import ai.opencyvis.team.DelegationStatus
import ai.opencyvis.team.MaterialRef
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton

/** Presents live helpers belonging to one run; the host owns cancellation and run changes. */
internal class TeamTaskDialog(private val context: Context, private val cancel: (String) -> Unit) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }
    private val scroll = ScrollView(context).apply { addView(content) }
    private val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.team_task_title)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .create()
    private var shown = emptyList<Details>()

    fun show(records: List<DelegationRecord>) {
        update(records)
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    fun update(records: List<DelegationRecord>) {
        val details = records.map { record ->
            Details(record.request.childId, record.request.goal, record.request.role, record.status, record.stale, record.decision, record.reviewReason,
                record.result?.facts.orEmpty(), record.result?.gaps.orEmpty(),
                record.result?.collectionRequests.orEmpty(), record.result?.outputs?.get("summary") as? String,
                (record.result?.outputs?.get("artifact_refs") as? List<*>)?.size ?: 0,
                (record.result?.outputs?.get("revision") as? Number)?.toInt(),
                record.result?.outputs?.get("verification_scope") == "child_review")
        }
        if (details == shown) return
        shown = details
        val position = scroll.scrollY
        content.removeAllViews()
        text(context.getString(R.string.team_task_help))
        details.forEach { item ->
            text(item.goal, heading = true, spaceAbove = 24)
            val role = context.getString(if (item.role == "coding") R.string.team_task_role_coding else R.string.team_task_role_research)
            text(context.getString(R.string.team_task_role_status, role, context.getString(statusLabel(item.status))), spaceAbove = 4)
            if (item.stale) text(context.getString(R.string.team_task_stale), spaceAbove = 4)
            text(context.getString(reviewLabel(item.decision)), spaceAbove = 4)
            item.reviewReason?.takeIf { it.isNotBlank() }?.let {
                text(context.getString(R.string.team_task_review_reason, it), spaceAbove = 4, selectable = true)
            }
            item.outputSummary?.takeIf(String::isNotBlank)?.let { text(it, spaceAbove = 12, selectable = true) }
            if (item.outputCount > 0) text(context.getString(R.string.team_task_outputs_count, item.outputCount), spaceAbove = 4)
            item.programRevision?.let { text(context.getString(R.string.team_task_program_revision, it), spaceAbove = 4) }
            if (item.childReview) text(context.getString(R.string.team_task_child_review), spaceAbove = 4)
            if (item.facts.isNotEmpty()) {
                text(context.getString(R.string.team_task_facts), heading = true, spaceAbove = 16, section = true)
                item.facts.forEach { fact ->
                    text(fact.text, spaceAbove = 8, selectable = true)
                    fact.refs.map(::sourceLabel).distinct().forEach { text(it, spaceAbove = 4) }
                }
            }
            section(R.string.team_task_gaps, item.gaps)
            section(R.string.team_task_collect, item.collectionRequests)
            if (item.status in DelegationStatus.IN_FLIGHT) {
                content.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    tag = "team_cancel_${item.childId}"
                    setText(R.string.team_task_cancel)
                    contentDescription = context.getString(R.string.team_task_cancel_named, item.goal)
                    minHeight = dp(48)
                    setOnClickListener { isEnabled = false; cancel(item.childId) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) })
            }
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun section(label: Int, values: List<String>) {
        if (values.isEmpty()) return
        text(context.getString(label), heading = true, spaceAbove = 16, section = true)
        values.forEach { text(it, spaceAbove = 6, selectable = true) }
    }

    private fun text(value: String, heading: Boolean = false, spaceAbove: Int = 0, section: Boolean = false, selectable: Boolean = false) {
        content.addView(TextView(context).apply {
            setTextAppearance(when {
                section -> com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                heading -> R.style.OpenCyvis_Ui_Section
                else -> com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            })
            text = value
            ViewCompat.setAccessibilityHeading(this, heading)
            if (selectable) setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(spaceAbove) })
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun sourceLabel(ref: MaterialRef): String = when {
        ref.sourceStep != null && ref.label.isNotBlank() ->
            context.getString(R.string.team_task_source_step_label, ref.sourceStep, ref.label)
        ref.sourceStep != null -> context.getString(R.string.team_task_source_step, ref.sourceStep)
        ref.label.isNotBlank() -> context.getString(R.string.team_task_source_label, ref.label)
        else -> context.getString(R.string.team_task_source_material)
    }

    private fun reviewLabel(decision: String): Int = when (decision) {
        "pending" -> R.string.team_task_review_pending
        "accepted" -> R.string.team_task_review_accepted
        "rejected" -> R.string.team_task_review_rejected
        else -> error("Unknown helper review: $decision")
    }

    private fun statusLabel(status: String): Int = when (status) {
        DelegationStatus.QUEUED -> R.string.team_task_queued
        DelegationStatus.RUNNING -> R.string.team_task_running
        DelegationStatus.COMPLETED -> R.string.team_task_completed
        DelegationStatus.NEEDS_EVIDENCE -> R.string.team_task_needs_evidence
        DelegationStatus.NEEDS_EXECUTION -> R.string.team_task_needs_execution
        DelegationStatus.FAILED -> R.string.team_task_failed
        DelegationStatus.CANCELLED -> R.string.team_task_cancelled
        DelegationStatus.INTERRUPTED -> R.string.team_task_interrupted
        else -> error("Unknown helper status: $status")
    }

    private data class Details(
        val childId: String, val goal: String, val role: String, val status: String, val stale: Boolean,
        val decision: String, val reviewReason: String?,
        val facts: List<ChildFact>, val gaps: List<String>, val collectionRequests: List<String>,
        val outputSummary: String?, val outputCount: Int, val programRevision: Int?, val childReview: Boolean,
    )
}
