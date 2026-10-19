package ai.opencyvis.learning

import android.content.Context
import ai.opencyvis.memory.MemoryEvidence
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Raw capture is written once. Episodes and memory evidence reference the same files. */
internal class LearningFiles(context: Context) {
    private val root = File(context.applicationContext.filesDir, "learning")
    private val evidenceDirectory = File(context.applicationContext.filesDir, "memory/evidence")

    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid learning session id" }
        return File(root, id)
    }

    fun create(id: String): File = directory(id).also {
        check(it.isDirectory || it.mkdirs()) { "无法创建学习记录目录" }
    }

    fun append(id: String, name: String, value: JSONObject) {
        File(directory(id), name).appendText(value.toString() + "\n", Charsets.UTF_8)
    }

    fun events(id: String): List<JSONObject> = lines(id, "events.jsonl")
    fun frames(id: String): List<LearningFrame> = lines(id, "frames.jsonl").map(LearningFrame::fromJson)

    fun hasMaterial(session: LearningSession): Boolean = session.frameCount > 0 || events(session.id).any {
        it.getString("kind") !in setOf("capture_unavailable", "capture_failed", "password_input_omitted")
    }

    /** Saved memory takes ownership of its source files before the capture folder is removed. */
    fun retainEvidence(id: String, evidence: List<MemoryEvidence>): List<MemoryEvidence> {
        val capture = directory(id).canonicalFile.toPath()
        val copies = mutableMapOf<String, String>()
        fun retain(path: String?): String? {
            if (path == null) return null
            val source = File(path).canonicalFile
            if (!source.toPath().startsWith(capture)) return path
            return copies.getOrPut(path) {
                check(evidenceDirectory.isDirectory || evidenceDirectory.mkdirs()) { "无法创建记忆证据目录" }
                source.copyTo(File(evidenceDirectory, "${UUID.randomUUID()}-${source.name}")).absolutePath
            }
        }
        return evidence.map { entry ->
            val image = retain(entry.imagePath)
            val tree = retain(entry.treePath)
            val text = copies.entries.fold(entry.text) { text, (before, after) ->
                text.replace(JSONObject.quote(before), JSONObject.quote(after))
            }
            entry.copy(imagePath = image, treePath = tree, text = text)
        }
    }

    private fun lines(id: String, name: String): List<JSONObject> {
        val file = File(directory(id), name)
        if (!file.exists()) return emptyList()
        // A killed process may leave the last append incomplete. Earlier complete records remain usable.
        val committed = file.readText(Charsets.UTF_8).substringBeforeLast('\n', "")
        return committed.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
    }
}
