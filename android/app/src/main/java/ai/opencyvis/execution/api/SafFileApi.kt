package ai.opencyvis.execution.api

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Access is limited to the directory grants actually retained by Android's picker. */
internal class SafFileApi(private val context: Context, private val artifacts: ArtifactRepository) {
    private val resolver get() = context.contentResolver

    fun call(name: String, args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject = when (name) {
        "files.roots" -> roots()
        "files.list" -> list(args)
        "files.import" -> importDocument(args)
        "files.export" -> export(args, onEffect)
        "files.rename" -> rename(args, onEffect)
        else -> throw IllegalArgumentException("Unknown file capability: $name")
    }

    private fun roots(): JSONObject {
        val roots = JSONArray()
        resolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }.forEach { grant ->
            val entry = JSONObject().put("tree_uri", grant.uri.toString()).put("can_read", grant.isReadPermission)
                .put("can_write", grant.isWritePermission)
            try {
                val actual = document(rootDocument(grant.uri))
                actual.keys().forEach { key -> entry.put(key, actual.get(key)) }
                entry.put("available", true)
            } catch (failure: Exception) {
                entry.put("available", false).put("error", failure.message)
            }
            roots.put(entry)
        }
        return success().put("roots", roots)
    }

    private fun list(args: JSONObject): JSONObject {
        val tree = grantedTree(args)
        val parent = target(tree, args.optString("parent_uri").takeIf(String::isNotBlank))
        require(document(parent).getString("mime_type") == Document.MIME_TYPE_DIR) { "parent_uri must identify a directory" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val offset = args.pageOffset()
        val limit = args.pageLimit()
        requireNotNull(resolver.query(children, COLUMNS, null, null, "${Document.COLUMN_DISPLAY_NAME} ASC")) {
            "Document provider is unavailable"
        }.use { cursor ->
            val documents = JSONArray()
            var position = offset
            while (documents.length() < limit && cursor.moveToPosition(position)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)))
                documents.put(document(cursor, uri))
                position++
            }
            return success().put("documents", documents).put("offset", offset).put("total", cursor.count)
                .put("next_offset", if (position < cursor.count) position else JSONObject.NULL)
                .put("loading", cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
        }
    }

    private fun importDocument(args: JSONObject): JSONObject {
        val tree = grantedTree(args)
        val uri = target(tree, args.requiredText("document_uri"))
        val metadata = document(uri)
        val ref = snapshot(uri, metadata)
        return success().put("document", metadata).put("artifact", ref.toJson())
    }

    private fun export(args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject {
        val tree = grantedTree(args, write = true)
        val parent = target(tree, args.optString("parent_uri").takeIf(String::isNotBlank))
        val metadata = document(parent)
        require(metadata.getString("mime_type") == Document.MIME_TYPE_DIR &&
            metadata.getLong("flags") and Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L) {
            "This document provider cannot create files in the selected directory"
        }
        val ref = artifacts.get(args.requiredText("artifact_id"), args.positiveId("version").toInt())
        val source = artifacts.resolve(ref)
        val name = validName(args.requiredText("name"))
        val mime = if (args.has("mime_type")) args.requiredText("mime_type") else ref.mimeType
        require(mime != Document.MIME_TYPE_DIR) { "Export an artifact as a file" }
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, name)) {
            "Document provider did not create the file"
        }
        onEffect(JSONObject().put("operation", "files.export").put("uri", uri.toString()).put("tree_uri", tree.toString()))
        return try {
            requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open the created file for writing" }.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            val actual = document(uri)
            val readback = snapshot(uri, actual)
            val verified = sameBytes(source, artifacts.resolve(readback))
            JSONObject().put("status", if (verified) "succeeded" else "failed").put("verified", verified)
                .put("uri", uri.toString()).put("document", actual).put("artifact", readback.toJson())
                .put("source_artifact", ref.toJson()).put("requested_name", name)
                .put("verification", "provider_content_readback")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            JSONObject().put("status", "unknown").put("verified", false).put("uri", uri.toString())
                .put("error", "File was created; writing or readback failed: ${failure.message}")
        }
    }

    private fun rename(args: JSONObject, onEffect: (JSONObject) -> Unit): JSONObject {
        val tree = grantedTree(args, write = true)
        val original = target(tree, args.requiredText("document_uri"))
        val before = document(original)
        require(before.getLong("flags") and Document.FLAG_SUPPORTS_RENAME.toLong() != 0L) {
            "This document provider does not support renaming this document"
        }
        val name = validName(args.requiredText("name"))
        val uri = requireNotNull(DocumentsContract.renameDocument(resolver, original, name)) {
            "Document provider did not rename the document"
        }
        onEffect(JSONObject().put("operation", "files.rename").put("uri", uri.toString()).put("previous_uri", original.toString()))
        return try {
            val actual = document(uri)
            val verified = actual.getString("name") == name
            JSONObject().put("status", if (verified) "succeeded" else "failed").put("verified", verified)
                .put("uri", uri.toString()).put("previous_uri", original.toString()).put("document", actual)
                .put("verification", "provider_metadata_readback")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            JSONObject().put("status", "unknown").put("verified", false).put("uri", uri.toString())
                .put("error", "Rename returned a URI; readback failed: ${failure.message}")
        }
    }

    private fun snapshot(uri: Uri, metadata: JSONObject): ArtifactRef {
        require(metadata.getString("mime_type") != Document.MIME_TYPE_DIR) { "Import a file, not a directory" }
        val input = requireNotNull(resolver.openInputStream(uri)) { "Document content is unavailable" }
        return artifacts.importStream(input, metadata.getString("name"), metadata.getString("mime_type"), uri.toString())
    }

    private fun grantedTree(args: JSONObject, write: Boolean = false): Uri {
        val tree = Uri.parse(args.requiredText("tree_uri"))
        require(DocumentsContract.isTreeUri(tree)) { "tree_uri must come from files.roots or the directory picker" }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        require(grant?.isReadPermission == true && (!write || grant.isWritePermission)) {
            "The selected directory has no retained ${if (write) "read/write" else "read"} permission; select it in Settings"
        }
        return tree
    }

    private fun target(tree: Uri, value: String?): Uri {
        val root = rootDocument(tree)
        if (value == null) return root
        val requested = Uri.parse(value)
        require(requested.authority == tree.authority && DocumentsContract.isDocumentUri(context, requested)) {
            "document_uri must belong to the selected document provider"
        }
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(requested))
        require(document == root || DocumentsContract.isChildDocument(resolver, root, document)) {
            "Document is outside the granted directory"
        }
        return document
    }

    private fun rootDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun document(uri: Uri): JSONObject =
        requireNotNull(resolver.query(uri, COLUMNS, null, null, null)) { "Document provider is unavailable" }.use {
            require(it.moveToFirst()) { "Document is unavailable: $uri" }
            document(it, uri)
        }

    private fun document(cursor: android.database.Cursor, uri: Uri): JSONObject = JSONObject()
        .put("uri", uri.toString()).put("document_id", cursor.string(Document.COLUMN_DOCUMENT_ID))
        .put("name", cursor.string(Document.COLUMN_DISPLAY_NAME)).put("mime_type", cursor.string(Document.COLUMN_MIME_TYPE))
        .put("size", cursor.number(Document.COLUMN_SIZE)).put("last_modified", cursor.number(Document.COLUMN_LAST_MODIFIED))
        .put("flags", cursor.long(Document.COLUMN_FLAGS))

    private fun validName(name: String): String = name.also {
        require(it != "." && it != ".." && '/' !in it && '\\' !in it) { "name must be a document name, not a path" }
    }

    private fun sameBytes(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        return first.inputStream().buffered().use { left -> second.inputStream().buffered().use { right ->
            val a = ByteArray(DEFAULT_BUFFER_SIZE)
            val b = ByteArray(DEFAULT_BUFFER_SIZE)
            var equal = true
            while (equal) {
                val count = readBlock(left, a)
                if (count != readBlock(right, b)) { equal = false; break }
                if (count == 0) break
                equal = (0 until count).all { a[it] == b[it] }
            }
            equal
        } }
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Int {
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        return length
    }

    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
