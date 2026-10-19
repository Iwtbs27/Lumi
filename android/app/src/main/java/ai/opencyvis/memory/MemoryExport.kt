package ai.opencyvis.memory

import android.content.Context
import ai.opencyvis.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Adds persistent memory diagnostics to the existing user-requested conversation export. */
object MemoryExport {
    fun hasRecords(context: Context, conversationId: Long): Boolean =
        AppDatabase.getInstance(context).memoryDao().conversationEpisodes(conversationId).isNotEmpty()

    fun write(context: Context, conversationId: Long, zip: ZipOutputStream) {
        val dao = AppDatabase.getInstance(context).memoryDao()
        fun json(path: String, value: JSONObject) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(value.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        for (episode in dao.conversationEpisodes(conversationId)) {
            val prefix = "memory/${episode.runId}"
            json("$prefix/episode.json", JSONObject().put("run_id", episode.runId)
                .put("instruction", episode.instruction).put("summary", episode.summary)
                .put("run_status", episode.status).put("result_status", episode.resultStatus)
                .put("source_type", episode.sourceType)
                .put("created_at", episode.createdAt).put("segments", JSONArray(episode.segmentsJson)))
            val usages = dao.runUsages(episode.runId)
            json("$prefix/usage.json", JSONObject().put("events", JSONArray(usages.map { usage ->
                mapOf("memory_id" to usage.memoryId, "revision" to usage.revision,
                    "step" to usage.step, "event" to usage.event, "outcome" to usage.outcome,
                    "evidence_step" to usage.evidenceStep, "details" to JSONObject(usage.detailsJson))
            })))
            val snapshots = JSONArray()
            usages.map { it.memoryId to it.revision }.distinct().forEach { (id, revision) ->
                val item = dao.get(id)
                val snapshot = if (item?.revision == revision) MemoryBodies.snapshot(item)
                    else dao.revisions(id).firstOrNull { it.revision == revision }?.snapshotJson
                snapshots.put(JSONObject().put("id", id).put("revision", revision)
                    .put("snapshot", snapshot?.let(::JSONObject) ?: JSONObject.NULL))
            }
            json("$prefix/memories.json", JSONObject().put("revisions_used", snapshots))
            dao.jobs().filter { it.runId == episode.runId }.forEachIndexed { index, job ->
                val name = if (job.id == "${episode.runId}:v1") "consolidation" else "${job.kind}_$index"
                json("$prefix/$name.json", JSONObject().put("id", job.id).put("kind", job.kind).put("status", job.status)
                    .put("attempts", job.attempts).put("error", job.error)
                    .put("checkpoint", JSONObject(job.checkpointJson)))
            }
            val sources = JSONArray()
            dao.episodeEvidence(episode.id).forEachIndexed { index, evidence ->
                val record = JSONObject().put("run_id", evidence.runId).put("step", evidence.step)
                    .put("id", evidence.id).put("memory_id", evidence.memoryId).put("revision", evidence.revision)
                    .put("actor", evidence.actor).put("source_type", evidence.sourceType)
                    .put("observation_type", evidence.observationType).put("support_scope", JSONObject(evidence.supportScopeJson))
                    .put("author", evidence.author).put("observed_at", evidence.observedAt).put("text", evidence.text)
                for ((kind, source) in listOf("image" to evidence.imagePath, "tree" to evidence.treePath)) {
                    val file = source?.let(::File)?.takeIf { it.isFile } ?: continue
                    val relative = "evidence/${index + 1}.${if (kind == "image") "jpg" else "txt"}"
                    zip.putNextEntry(ZipEntry("$prefix/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    record.put(kind, relative)
                }
                sources.put(record)
            }
            json("$prefix/evidence.json", JSONObject().put("sources", sources))
        }
    }
}
