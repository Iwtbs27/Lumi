package ai.opencyvis.execution.api

import ai.opencyvis.engine.TaskRunStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

/** A fixed binary version; localCopy is relative to the owning task directory. */
data class ArtifactRef(
    val artifactId: String,
    val version: Int,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sourceUri: String?,
    val localCopy: String,
) {
    fun toJson(): JSONObject = JSONObject().put("artifact_id", artifactId).put("version", version)
        .put("name", name).put("mime_type", mimeType).put("size", size)
        .put("source_uri", sourceUri ?: JSONObject.NULL).put("local_copy", localCopy)

    companion object {
        fun fromJson(json: JSONObject) = ArtifactRef(json.getString("artifact_id"), json.getInt("version"),
            json.getString("name"), json.getString("mime_type"), json.getLong("size"),
            if (json.isNull("source_uri")) null else json.getString("source_uri"), json.getString("local_copy"))
    }
}

/** Immutable inputs/outputs shared by SAF and the code workspace. */
class ArtifactRepository(val taskDirectory: File) {
    fun importFile(file: File, name: String, mimeType: String, sourceUri: String? = null,
        artifactId: String? = null): ArtifactRef {
        require(file.isFile) { "Artifact input is not a file: $file" }
        return importStream(file.inputStream(), name, mimeType, sourceUri, artifactId)
    }

    @Synchronized
    fun importStream(input: InputStream, name: String, mimeType: String, sourceUri: String? = null,
        artifactId: String? = null): ArtifactRef = input.use { source ->
        require(name.isNotBlank() && mimeType.isNotBlank()) { "Artifact name and MIME type are required" }
        val id = artifactId ?: UUID.randomUUID().toString()
        val versions = artifactDirectory(id)
        if (artifactId != null) require(versions.isDirectory) { "Unknown artifact: $id" }
        val version = (versions.listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val directory = File(versions, version.toString())
        check(directory.mkdirs()) { "Cannot create immutable artifact version: $id v$version" }
        val payload = File(directory, "payload")
        val size = FileOutputStream(payload).use { output ->
            val copied = source.copyTo(output)
            output.fd.sync()
            copied
        }
        val ref = ArtifactRef(id, version, name, mimeType, size, sourceUri, "artifacts/$id/$version/payload")
        // The published metadata appears only after the complete binary copy is durable.
        TaskRunStore.writeAtomic(File(directory, "metadata.json"), ref.toJson().toString())
        ref
    }

    fun get(artifactId: String, version: Int): ArtifactRef {
        require(version > 0) { "Artifact version must be positive" }
        val file = File(artifactDirectory(artifactId), "$version/metadata.json")
        require(file.isFile) { "Unknown artifact version: $artifactId v$version" }
        return ArtifactRef.fromJson(JSONObject(file.readText())).also {
            require(it.artifactId == artifactId && it.version == version &&
                it.localCopy == "artifacts/$artifactId/$version/payload") { "Artifact metadata does not match its version" }
        }
    }

    fun resolve(ref: ArtifactRef): File {
        require(ref == get(ref.artifactId, ref.version)) { "Artifact reference does not match the saved version" }
        return inside(taskDirectory, ref.localCopy).also {
            require(it.isFile && it.length() == ref.size) { "Artifact binary copy is unavailable: ${ref.artifactId} v${ref.version}" }
        }
    }

    fun stage(ref: ArtifactRef, workspace: File, relativePath: String): File {
        val destination = inside(workspace, relativePath)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "Cannot create workspace input directory" }
        resolve(ref).copyTo(destination, overwrite = true)
        return destination
    }

    fun captureOutput(workspace: File, relativePath: String, name: String, mimeType: String,
        artifactId: String? = null): ArtifactRef = importFile(inside(workspace, relativePath), name, mimeType, artifactId = artifactId)

    private fun artifactDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid artifact ID" }
        return File(taskDirectory, "artifacts/$id")
    }

    private fun inside(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "A relative workspace path is required" }
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Path leaves its owning directory" }
        return file
    }
}
