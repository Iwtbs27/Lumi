package ai.opencyvis.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.engine.TaskRunStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only ADB bridge. It exposes task archives, never app configuration or the database. */
class TraceExportProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val appContext = requireNotNull(context)
        val path = uri.pathSegments
        val result = when {
            path == listOf("runs") -> listRuns(appContext, AppDatabase.getInstance(appContext))
            path.size == 2 && path[0] == "export" -> {
                val conversationId = requireNotNull(path[1].toLongOrNull()) { "Invalid conversation ID" }
                val archive = requireNotNull(TaskRunStore.exportConversation(appContext, conversationId)) {
                    "No task records for conversation $conversationId"
                }
                JSONObject().put("conversation_id", conversationId)
                    .put("path", "cache/run_exports/${archive.name}")
                    .put("size", archive.length())
            }
            else -> throw IllegalArgumentException("Unknown trace URI: $uri")
        }
        return MatrixCursor(arrayOf("json"), 1).apply { addRow(arrayOf(result.toString())) }
    }

    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri = readOnly()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()

    private fun readOnly(): Nothing = throw UnsupportedOperationException("Trace export is read-only")

    internal fun listRuns(context: Context, database: AppDatabase): JSONObject {
        val jobs = mutableMapOf<String, Pair<String, Long>>()
        database.openHelper.readableDatabase.query(
            "SELECT runId, status, updatedAt FROM memory_jobs ORDER BY updatedAt"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                jobs[cursor.getString(0)] = cursor.getString(1) to cursor.getLong(2)
            }
        }
        val runs = File(context.filesDir, "agent_runs").listFiles().orEmpty().mapNotNull { folder ->
            val metadata = File(folder, "run.json").takeIf { it.isFile } ?: return@mapNotNull null
            val run = JSONObject(metadata.readText())
            val job = jobs[run.getString("run_id")]
            val updatedAt = folder.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() }
                ?: metadata.lastModified()
            JSONObject().put("run_id", run.getString("run_id"))
                .put("conversation_id", run.opt("conversation_id") ?: JSONObject.NULL)
                .put("instruction", run.getString("instruction"))
                .put("status", run.getString("status"))
                .put("created_at", run.getLong("created_at"))
                .put("finished_at", run.opt("finished_at") ?: JSONObject.NULL)
                .put("step_count", File(folder, "steps").listFiles().orEmpty().count {
                    it.isDirectory && it.name.toIntOrNull() != null
                })
                .put("updated_at", maxOf(updatedAt, job?.second ?: 0L))
                .put("memory_status", job?.first ?: JSONObject.NULL)
                .put("memory_updated_at", job?.second ?: JSONObject.NULL)
        }.sortedByDescending { it.getLong("created_at") }
        return JSONObject().put("runs", JSONArray(runs))
    }
}
