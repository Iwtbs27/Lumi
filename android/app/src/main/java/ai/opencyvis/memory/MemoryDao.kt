package ai.opencyvis.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_items WHERE id = :id") fun get(id: String): MemoryItem?
    @Query("SELECT * FROM memory_items ORDER BY updatedAt DESC") fun all(): List<MemoryItem>
    @Query("SELECT * FROM memory_items WHERE title = :title AND type = :type AND COALESCE(appScope,'') = :app AND COALESCE(taskFamily,'') = :family")
    fun sameIdentity(title: String, type: String, app: String, family: String): List<MemoryItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(item: MemoryItem)
    @Query("DELETE FROM memory_items WHERE id = :id") fun delete(id: String)
    @Query("SELECT COALESCE(SUM(revision),0) + COALESCE(MAX(updatedAt),0) FROM memory_items") fun revisionToken(): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putRevision(revision: MemoryRevision)
    @Query("SELECT * FROM memory_revisions WHERE memoryId = :id ORDER BY revision DESC") fun revisions(id: String): List<MemoryRevision>
    @Query("DELETE FROM memory_revisions WHERE memoryId = :id") fun deleteRevisions(id: String)
    @Insert fun putFts(fts: MemoryFts)
    @Query("DELETE FROM memory_fts WHERE itemId = :id") fun deleteFts(id: String)
    @Query("SELECT m.* FROM memory_items m JOIN memory_fts f ON m.id = f.itemId WHERE memory_fts MATCH :query AND m.status = 'active' LIMIT :limit")
    fun match(query: String, limit: Int): List<MemoryItem>
    @Query("SELECT m.* FROM memory_items m JOIN memory_fts f ON m.id = f.itemId WHERE memory_fts MATCH :query LIMIT :limit")
    fun matchAll(query: String, limit: Int): List<MemoryItem>
    @Query("SELECT * FROM memory_items WHERE status = 'active' AND appScope = :app ORDER BY updatedAt DESC LIMIT :limit")
    fun byApp(app: String, limit: Int): List<MemoryItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putBlock(block: MemoryBlock)
    @Query("SELECT COUNT(*) FROM memory_blocks WHERE identity = :identity") fun blocked(identity: String): Int
    @Query("DELETE FROM memory_blocks WHERE identity = :identity") fun clearBlock(identity: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putEpisode(episode: MemoryEpisode)
    @Query("SELECT * FROM memory_episodes WHERE id = :id") fun episode(id: String): MemoryEpisode?
    @Query("SELECT * FROM memory_episodes ORDER BY createdAt DESC LIMIT :limit") fun episodes(limit: Int): List<MemoryEpisode>
    @Query("SELECT * FROM memory_episodes WHERE conversationId = :conversationId") fun conversationEpisodes(conversationId: Long): List<MemoryEpisode>
    @Query("DELETE FROM memory_episodes WHERE id = :id") fun deleteEpisode(id: String)
    @Insert fun putEpisodeFts(fts: EpisodeFts)
    @Query("DELETE FROM episode_fts WHERE episodeId = :id") fun deleteEpisodeFts(id: String)
    @Query("SELECT e.* FROM memory_episodes e JOIN episode_fts f ON e.id = f.episodeId WHERE episode_fts MATCH :query ORDER BY e.createdAt DESC LIMIT :limit")
    fun matchEpisodes(query: String, limit: Int): List<MemoryEpisode>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putEvidence(evidence: MemoryEvidence)
    @Query("SELECT * FROM memory_evidence WHERE memoryId = :id ORDER BY observedAt DESC") fun evidence(id: String): List<MemoryEvidence>
    @Query("SELECT e.* FROM memory_evidence e JOIN memory_items m ON e.memoryId = m.id AND e.revision = m.revision WHERE e.kind = 'user_confirmation' AND e.author = 'user_review'")
    fun userConfirmations(): List<MemoryEvidence>
    @Query("SELECT * FROM memory_evidence WHERE episodeId = :id ORDER BY step") fun episodeEvidence(id: String): List<MemoryEvidence>
    @Query("SELECT * FROM memory_evidence WHERE runId = :runId ORDER BY step") fun runEvidence(runId: String): List<MemoryEvidence>
    @Query("SELECT * FROM memory_evidence ORDER BY observedAt") fun allEvidence(): List<MemoryEvidence>
    @Query("DELETE FROM memory_evidence WHERE id = :id") fun deleteEvidence(id: String)
    @Query("DELETE FROM memory_evidence WHERE memoryId = :id") fun deleteItemEvidence(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putUsage(usage: MemoryUsage)
    @Query("SELECT * FROM memory_usages WHERE memoryId = :id ORDER BY createdAt DESC") fun usages(id: String): List<MemoryUsage>
    @Query("SELECT * FROM memory_usages WHERE runId = :runId ORDER BY step") fun runUsages(runId: String): List<MemoryUsage>
    @Query("DELETE FROM memory_usages WHERE memoryId = :id") fun deleteUsages(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun enqueue(job: MemoryJob): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putJob(job: MemoryJob)
    @Query("SELECT * FROM memory_jobs WHERE id = :id") fun job(id: String): MemoryJob?
    @Query("SELECT * FROM memory_jobs WHERE status = 'pending' ORDER BY createdAt LIMIT 1") fun nextPendingJob(): MemoryJob?
    @Query("SELECT * FROM memory_jobs ORDER BY createdAt DESC") fun jobs(): List<MemoryJob>
    @Query("UPDATE memory_jobs SET status='cancelled',payloadJson='{}',checkpointJson='{}',error='Source deleted',updatedAt=:now WHERE runId=:runId")
    fun cancelJobs(runId: String, now: Long)
}
