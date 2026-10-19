package ai.opencyvis.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "memory_items", indices = [Index("status", "type", "appScope"), Index("updatedAt")])
data class MemoryItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String = "fact",
    val title: String,
    val summary: String,
    val appScope: String? = null,
    val taskFamily: String? = null,
    val status: String = "candidate",
    val origin: String = "inferred",
    val evidenceLevel: String = "unverified",
    val revision: Int = 1,
    val bodyJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastVerifiedAt: Long? = null
)

@Entity(tableName = "memory_revisions", primaryKeys = ["memoryId", "revision"])
data class MemoryRevision(
    val memoryId: String, val revision: Int, val snapshotJson: String,
    val reason: String, val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_episodes", indices = [Index("conversationId"), Index("updatedAt")])
data class MemoryEpisode(
    @PrimaryKey val id: String,
    val runId: String = id,
    val conversationId: Long? = null,
    val instruction: String,
    val summary: String = "",
    val status: String = "running",
    val resultStatus: String = "unknown",
    val segmentsJson: String = "[]",
    val evidenceRefsJson: String = "[]",
    val appScope: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "'agent_run'") val sourceType: String = "agent_run"
)

@Entity(tableName = "memory_evidence", indices = [Index("memoryId"), Index("runId"), Index("episodeId")])
data class MemoryEvidence(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val memoryId: String? = null,
    val revision: Int? = null,
    val episodeId: String,
    val runId: String,
    val step: Int,
    val kind: String = "observation",
    val text: String,
    val imagePath: String? = null,
    val treePath: String? = null,
    val author: String = "model_observed",
    val observedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'unknown'") val actor: String = "unknown",
    @ColumnInfo(defaultValue = "'agent_run'") val sourceType: String = "agent_run",
    @ColumnInfo(defaultValue = "'direct'") val observationType: String = "direct",
    @ColumnInfo(defaultValue = "'{}'") val supportScopeJson: String = "{}"
)

@Entity(tableName = "memory_usages", indices = [Index("memoryId", "revision"), Index("runId", "step")])
data class MemoryUsage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val memoryId: String, val revision: Int, val runId: String, val step: Int,
    val event: String,
    val goalId: String? = null,
    val outcome: String = "unknown",
    val evidenceStep: Int? = null,
    val detailsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_jobs", indices = [Index("status", "createdAt"), Index("runId")])
data class MemoryJob(
    @PrimaryKey val id: String,
    val runId: String,
    val status: String = "pending",
    val payloadJson: String = "{}",
    val checkpointJson: String = "{}",
    val attempts: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "'memory_consolidate'") val kind: String = "memory_consolidate"
)

/** Tombstones contain identity only; deleted content is never retained here. */
@Entity(tableName = "memory_blocks")
data class MemoryBlock(@PrimaryKey val identity: String, val createdAt: Long = System.currentTimeMillis())

@Fts4
@Entity(tableName = "memory_fts")
data class MemoryFts(val itemId: String, val terms: String)

@Fts4
@Entity(tableName = "episode_fts")
data class EpisodeFts(val episodeId: String, val terms: String)
