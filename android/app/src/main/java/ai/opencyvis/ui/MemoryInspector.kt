package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
import ai.opencyvis.R
import ai.opencyvis.memory.MemoryEvidence
import ai.opencyvis.memory.MemoryEvidenceStore
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryJobs
import ai.opencyvis.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class MemoryInspector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: MemoryRepository,
    private val onChanged: () -> Unit = {}
) {
    private fun launch(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (error: Exception) { MemoryUi.error(context, error) }
    }

    fun showSources(memory: MemoryItem) = launch {
        showEvidence(memory.title, repository.evidence(memory.id), JSONObject(memory.bodyJson).optString("source_ref"))
    }

    private fun showEvidence(title: String, evidence: List<MemoryEvidence>, sourceRef: String = "") {
        val column = MemoryUi.column(context)
        if (sourceRef.isNotBlank()) MemoryUi.text(column, sourceRef)
        if (evidence.isEmpty() && sourceRef.isBlank()) MemoryUi.text(column, context.getString(R.string.mem_no_sources))
        evidence.forEach { source ->
            if (source.kind == "user_confirmation") {
                MemoryUi.text(column, context.getString(R.string.mem_confirmation_source, source.revision,
                    MemoryUi.date(source.observedAt)))
            } else {
                MemoryUi.text(column, context.getString(R.string.learning_evidence_source,
                    MemoryUi.label(context, source.sourceType), MemoryUi.label(context, source.actor)))
                MemoryUi.text(column, context.getString(R.string.mem_source_location, source.runId, source.step))
                MemoryUi.text(column, "${MemoryUi.date(source.observedAt)} · ${source.author}\n${source.text}")
            }
            if (source.imagePath != null) column.addView(Button(context).apply {
                setText(R.string.mem_view_image)
                setOnClickListener { showImage(source) }
            })
        }
        AlertDialog.Builder(context).setTitle(title).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.mem_close, null).show()
    }

    private fun showImage(source: MemoryEvidence) = launch {
        val bitmap = withContext(Dispatchers.IO) {
            val encoded = MemoryEvidenceStore(context).readImage(source)
            encoded?.let { Base64.decode(it, Base64.DEFAULT) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        if (bitmap == null) {
            AlertDialog.Builder(context).setMessage(R.string.mem_image_unavailable)
                .setPositiveButton(R.string.mem_close, null).show()
        } else {
            val image = ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                contentDescription = source.text
            }
            AlertDialog.Builder(context).setTitle(MemoryUi.date(source.observedAt))
                .setView(image).setPositiveButton(R.string.mem_close, null).show()
        }
    }

    fun showRevisions(memory: MemoryItem): Job = launch {
        val current = repository.get(memory.id) ?: return@launch
        val revisions = repository.revisions(memory.id)
        if (revisions.isEmpty()) {
            AlertDialog.Builder(context).setMessage(R.string.mem_no_revisions).setPositiveButton(R.string.mem_close, null).show()
            return@launch
        }
        AlertDialog.Builder(context).setTitle(R.string.mem_revisions)
            .setItems(revisions.map { "v${it.revision} · ${MemoryUi.date(it.createdAt)}\n${it.reason}" }.toTypedArray()) { _, index ->
                val revision = revisions[index]
                val column = MemoryUi.column(context)
                val snapshot = JSONObject(revision.snapshotJson)
                MemoryUi.text(column, snapshot.optString("title", memory.title))
                MemoryUi.text(column, snapshot.optString("summary"))
                val body = snapshot.optJSONObject("body") ?: snapshot.optString("bodyJson").takeIf(String::isNotBlank)?.let(::JSONObject)
                if (body != null) MemoryUi.text(column, MemoryUi.describe(body))
                var detail: AlertDialog? = null
                if (revision.revision != current.revision) column.addView(Button(context).apply {
                    setText(R.string.mem_restore_revision)
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.mem_restore_revision_title, revision.revision))
                            .setMessage(R.string.mem_restore_revision_body)
                            .setPositiveButton(R.string.mem_restore_revision) { _, _ -> launch {
                                val restored = repository.restoreRevision(current.id, revision.revision, current.revision)
                                detail?.dismiss()
                                onChanged()
                                showRevisions(restored)
                            } }
                            .setNegativeButton(R.string.memory_dialog_cancel, null).show()
                    }
                })
                detail = AlertDialog.Builder(context).setTitle("v${revision.revision}")
                    .setView(MemoryUi.scroll(column)).setPositiveButton(R.string.mem_close, null).show()
            }.setNegativeButton(R.string.mem_close, null).show()
    }

    fun showEpisodes(query: String) = launch {
        val episodes = repository.listEpisodes(query)
        if (episodes.isEmpty()) {
            AlertDialog.Builder(context).setMessage(R.string.mem_no_episodes).setPositiveButton(R.string.mem_close, null).show()
            return@launch
        }
        AlertDialog.Builder(context).setTitle(R.string.mem_episodes)
            .setItems(episodes.map { "${MemoryUi.date(it.createdAt)} · ${it.instruction}\n${it.summary}" }.toTypedArray()) { _, index ->
                val episode = episodes[index]
                val column = MemoryUi.column(context)
                MemoryUi.text(column, MemoryUi.label(context, episode.sourceType))
                MemoryUi.text(column, "${MemoryUi.date(episode.createdAt)} · ${MemoryUi.label(context, episode.status)} · ${episode.resultStatus}")
                MemoryUi.text(column, episode.summary)
                val segments = JSONArray(episode.segmentsJson)
                for (i in 0 until segments.length()) {
                    val segment = segments.optJSONObject(i)
                    MemoryUi.text(column, if (segment == null) segments.get(i).toString() else MemoryUi.describe(segment))
                }
                AlertDialog.Builder(context).setTitle(episode.instruction).setView(MemoryUi.scroll(column))
                    .setPositiveButton(R.string.mem_sources) { _, _ -> launch { showEvidence(episode.instruction, repository.episodeEvidence(episode.id)) } }
                    .setNegativeButton(R.string.mem_close, null).show()
            }.setNegativeButton(R.string.mem_close, null).show()
    }

    fun showJobs(): Job = launch {
        val jobs = MemoryJobs(context).status()
        val column = MemoryUi.column(context)
        var dialog: AlertDialog? = null
        if (jobs.isEmpty()) MemoryUi.text(column, context.getString(R.string.mem_no_jobs))
        jobs.forEach { job ->
            MemoryUi.text(column, "${MemoryUi.label(context, job.status)} · ${MemoryUi.date(job.updatedAt)}\n${job.runId}")
            val progress = JSONObject(job.checkpointJson)
            if (progress.has("batch_count")) MemoryUi.text(column, context.getString(R.string.mem_job_progress,
                progress.optJSONArray("completed_batches")?.length() ?: 0, progress.optInt("batch_count")))
            if (progress.has("consolidation_calls")) MemoryUi.text(column, context.getString(R.string.mem_job_calls,
                progress.optInt("consolidation_calls"), progress.optLong("latency_ms") / 1000.0))
            if (progress.optBoolean("usage_known")) MemoryUi.text(column, context.getString(R.string.mem_job_tokens,
                progress.optLong("input_tokens"), progress.optLong("output_tokens")))
            if (progress.has("format_repairs")) MemoryUi.text(column, context.getString(R.string.mem_job_format_repairs,
                progress.optInt("format_repairs")))
            progress.optString("last_validation_error").takeIf(String::isNotBlank)?.let {
                MemoryUi.text(column, context.getString(R.string.mem_job_validation_error, it))
            }
            progress.optJSONArray("conflicts")?.let { conflicts ->
                if (conflicts.length() > 0) {
                    MemoryUi.text(column, context.getString(R.string.mem_job_conflicts))
                    for (i in 0 until conflicts.length()) {
                        val conflict = conflicts.get(i)
                        MemoryUi.text(column, if (conflict is JSONObject) conflict.optString("reason", conflict.toString()) else conflict.toString())
                    }
                }
            }
            job.error?.takeIf(String::isNotBlank)?.let { MemoryUi.text(column, it) }
            if (job.status == "failed") column.addView(Button(context).apply {
                setText(R.string.mem_retry)
                setOnClickListener {
                    isEnabled = false
                    launch {
                        try { MemoryJobs(context).retry(job.id); dialog?.dismiss(); showJobs() }
                        finally { isEnabled = true }
                    }
                }
            })
        }
        dialog = AlertDialog.Builder(context).setTitle(R.string.mem_jobs).setView(MemoryUi.scroll(column))
            .setPositiveButton(R.string.mem_refresh) { _, _ -> showJobs() }
            .setNegativeButton(R.string.mem_close, null).show()
    }
}
