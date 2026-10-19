package ai.opencyvis.learning

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.util.UUID

@Entity(tableName = "learning_sessions", indices = [Index("status"), Index("createdAt")])
data class LearningSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val mode: String,
    val goal: String,
    val goalSource: String,
    val displayId: Int,
    val parentRunId: String? = null,
    val appScope: String? = null,
    val allowedPackagesJson: String = "[]",
    val sourceJson: String = "{}",
    val note: String = "",
    val status: String = "recording",
    val analysisJson: String = "{}",
    val proposalsJson: String = "[]",
    val questionsJson: String = "[]",
    val answersJson: String = "[]",
    val commitsJson: String = "[]",
    val error: String? = null,
    val eventCount: Int = 0,
    val frameCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val finishedAt: Long? = null
) {
    val sourceType: String get() = when (mode) {
        "demonstration" -> "human_demo"
        "correction" -> "human_correction"
        "observation" -> "human_observation"
        else -> error("Unknown learning mode: $mode")
    }
}

data class LearningFrame(
    val id: String,
    val sequence: Int,
    val timeMs: Long,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val packageName: String?,
    val imagePath: String,
    val treePath: String?,
    val evidenceId: String,
    val metadataJson: String = "{}"
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("sequence", sequence)
        .put("time_ms", timeMs).put("display_id", displayId).put("width", width).put("height", height)
        .put("package_name", packageName).put("image_path", imagePath).put("tree_path", treePath)
        .put("evidence_id", evidenceId).put("metadata", JSONObject(metadataJson))

    companion object {
        fun fromJson(value: JSONObject) = LearningFrame(
            value.getString("id"), value.getInt("sequence"), value.getLong("time_ms"),
            value.getInt("display_id"), value.getInt("width"), value.getInt("height"),
            value.optString("package_name").takeIf(String::isNotBlank), value.getString("image_path"),
            value.optString("tree_path").takeIf(String::isNotBlank), value.getString("evidence_id"),
            value.optJSONObject("metadata")?.toString() ?: "{}"
        )
    }
}
