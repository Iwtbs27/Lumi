package ai.opencyvis.engine

import org.json.JSONObject
import java.io.File

data class ContextArtifactRef(
    val artifactRef: String,
    val charLength: Int,
    val sourceStep: Int,
    val label: String,
    val version: Int
) {
    fun toMap(): Map<String, Any> = mapOf(
        "artifact_ref" to artifactRef, "char_length" to charLength,
        "source_step" to sourceStep, "label" to label, "version" to version
    )
}

data class ContextTextPage(
    val reference: String,
    val text: String,
    val offset: Int,
    val nextOffset: Int?,
    val totalChars: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "reference" to reference, "text" to text, "offset" to offset,
        "next_offset" to nextOffset, "total_chars" to totalChars
    )

    companion object {
        fun from(reference: String, text: String, offset: Int, limit: Int): ContextTextPage {
            require(offset in 0..text.length) { "Text offset is outside this record" }
            require(limit > 0) { "Text page limit must be positive" }
            require(offset == 0 || offset == text.length ||
                !Character.isHighSurrogate(text[offset - 1]) || !Character.isLowSurrogate(text[offset])) {
                "Text offset must be at a complete character"
            }
            var end = offset + minOf(limit, text.length - offset)
            // Keep a supplementary character whole; a page may use one additional UTF-16 unit.
            if (end < text.length && end > offset && Character.isHighSurrogate(text[end - 1]) &&
                Character.isLowSurrogate(text[end])) end++
            return ContextTextPage(reference, text.substring(offset, end), offset,
                end.takeIf { it < text.length }, text.length)
        }
    }
}

/** Exact task text outside the prompt. Each saved record remains unchanged. */
internal class ContextArtifactStore(private val directory: File, private val runId: String) {
    private data class Entry(val sequence: Int, val reference: ContextArtifactRef, val textFile: File)

    private val entries: MutableList<Entry> = directory.listFiles().orEmpty()
        .filter { it.isDirectory }.map { folder ->
            val metadata = JSONObject(File(folder, "metadata.json").readText(Charsets.UTF_8))
            Entry(folder.name.toInt(), ContextArtifactRef(
                metadata.getString("artifact_ref"), metadata.getInt("char_length"),
                metadata.getInt("source_step"), metadata.getString("label"), metadata.getInt("version")
            ), File(folder, "text.txt"))
        }.sortedBy { it.sequence }.toMutableList()

    @Synchronized
    fun save(text: String, sourceStep: Int, label: String, version: Int = 1): ContextArtifactRef {
        require(sourceStep >= 0) { "Artifact source step must not be negative" }
        require(label.isNotBlank()) { "Artifact label is required" }
        require(version > 0) { "Artifact version must be positive" }
        entries.firstOrNull { entry ->
            val ref = entry.reference
            ref.sourceStep == sourceStep && ref.label == label && ref.version == version &&
                ref.charLength == text.length && entry.textFile.readText(Charsets.UTF_8) == text
        }?.let { return it.reference }

        val sequence = (entries.lastOrNull()?.sequence ?: 0) + 1
        val folder = File(directory, sequence.toString())
        check(folder.mkdirs()) { "Cannot create context artifact: $folder" }
        val reference = ContextArtifactRef("$runId:context:$sequence", text.length, sourceStep, label, version)
        val textFile = File(folder, "text.txt")
        textFile.writeText(text, Charsets.UTF_8)
        File(folder, "metadata.json").writeText(JSONObject(reference.toMap()).toString(), Charsets.UTF_8)
        entries += Entry(sequence, reference, textFile)
        return reference
    }

    @Synchronized
    fun reference(artifactRef: String): ContextArtifactRef = requireNotNull(
        entries.firstOrNull { it.reference.artifactRef == artifactRef }?.reference
    ) { "Context artifact does not exist in this run: $artifactRef" }

    @Synchronized
    fun read(artifactRef: String, offset: Int = 0, limit: Int = 4800): ContextTextPage {
        val entry = requireNotNull(entries.firstOrNull { it.reference.artifactRef == artifactRef }) {
            "Context artifact does not exist in this run: $artifactRef"
        }
        return ContextTextPage.from(artifactRef, entry.textFile.readText(Charsets.UTF_8), offset, limit)
    }
}
