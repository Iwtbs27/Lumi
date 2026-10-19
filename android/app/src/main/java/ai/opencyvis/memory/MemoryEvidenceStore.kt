package ai.opencyvis.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import ai.opencyvis.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Small evidence copies belong to memory; source run retention cannot remove them. */
class MemoryEvidenceStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "memory/evidence")
    private val database = AppDatabase.getInstance(context)
    private val dao = database.memoryDao()

    suspend fun save(runId: String, step: Int, text: String, imageBase64: String? = null,
        tree: String? = null, author: String = "model_observed",
        observedAt: Long = System.currentTimeMillis(), actor: String = "agent",
        sourceType: String = "agent_run", observationType: String = "direct",
        supportScopeJson: String = "{}"): MemoryEvidence = withContext(Dispatchers.IO) {
        require(step > 0)
        check(dao.blocked("run:$runId") == 0) { "Memory source was deleted" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create memory evidence directory" }
        val existing = dao.runEvidence(runId).firstOrNull { it.step == step && it.memoryId == null }
        val imagePath = if (imageBase64 != null && existing?.imagePath == null) {
            val bytes = Base64.decode(imageBase64.substringAfter("base64,", imageBase64), Base64.DEFAULT)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Invalid evidence image" }
            val scale = minOf(1.0, 960.0 / maxOf(bitmap.width, bitmap.height))
            val small = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val file = File(directory, "${UUID.randomUUID()}.jpg")
            try { file.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 78, it)) } }
            finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
            file.absolutePath
        } else existing?.imagePath
        val treePath = if (tree != null) {
            val file = existing?.treePath?.let(::File) ?: File(directory, "${UUID.randomUUID()}.txt")
            file.writeText(tree.take(12000), Charsets.UTF_8)
            file.absolutePath
        } else existing?.treePath
        val evidence = MemoryEvidence(id = existing?.id ?: UUID.randomUUID().toString(),
            episodeId = runId, runId = runId, step = step, text = text,
            imagePath = imagePath, treePath = treePath, author = author, observedAt = observedAt,
            actor = actor, sourceType = sourceType, observationType = observationType,
            supportScopeJson = supportScopeJson)
        database.runInTransaction {
            check(dao.blocked("run:$runId") == 0) { "Memory source was deleted" }
            dao.putEvidence(evidence)
        }
        pruneImages()
        dao.runEvidence(runId).first { it.id == evidence.id }
    }

    suspend fun readImage(evidence: MemoryEvidence): String? = withContext(Dispatchers.IO) {
        evidence.imagePath?.let(::File)?.takeIf { it.isFile }?.readBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    suspend fun readTree(evidence: MemoryEvidence): String? = withContext(Dispatchers.IO) {
        evidence.treePath?.let(::File)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    private fun pruneImages() = database.runInTransaction {
        val evidence = dao.allEvidence()
        // Learning owns its source frames and retention; do not prune a pending parser's input.
        val images = evidence.mapNotNull { it.imagePath }.distinct().map(::File)
            .filter { it.parentFile == directory }
        var total = images.sumOf { it.length() }
        if (total <= IMAGE_BUDGET) return@runInTransaction
        val active = dao.all().filter { it.status == "active" }.map { it.id }.toSet()
        val ordered = images.sortedWith(compareBy<File> { file ->
            evidence.any { it.imagePath == file.absolutePath && it.memoryId in active }
        }.thenBy { it.lastModified() })
        for (file in ordered) {
            if (total <= IMAGE_BUDGET) break
            val size = file.length()
            if (!file.exists() || file.delete()) {
                total -= size
                evidence.filter { it.imagePath == file.absolutePath }.forEach { dao.putEvidence(it.copy(imagePath = null)) }
            }
        }
    }

    companion object { private const val IMAGE_BUDGET = 50L * 1024 * 1024 }
}
