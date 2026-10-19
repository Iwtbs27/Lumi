package ai.opencyvis.db

import android.content.Context
import ai.opencyvis.memory.MemoryBodies
import ai.opencyvis.memory.MemoryItem
import ai.opencyvis.memory.MemoryRepository
import java.util.UUID

/** Compatibility for older callers; every read/write uses the new memory store. */
class GlobalMemoryRepository(context: Context) {
    private val repository = MemoryRepository(context)

    suspend fun upsert(key: String, value: String, category: String = "",
        source: String = GlobalMemoryEntity.SOURCE_USER): Long {
        require(source == GlobalMemoryEntity.SOURCE_USER) {
            "Agent writes must use the explicit-memory or consolidation API with source evidence"
        }
        return legacyId(repository.saveExplicit(key, value, appScope = category.takeIf { it.isNotBlank() }))
    }

    suspend fun update(memory: GlobalMemoryEntity) {
        val old = requireNotNull(repository.list().firstOrNull { legacyId(it) == memory.id })
        repository.edit(old.id, memory.key, memory.value, MemoryBodies.fact(memory.value), old.revision, old.appScope)
        if ((old.status == "active") != memory.enabled) repository.setEnabled(old.id, memory.enabled)
    }
    suspend fun getAll(): List<GlobalMemoryEntity> = repository.list().map(::legacy)
    suspend fun getEnabled(): List<GlobalMemoryEntity> = repository.list().filter { it.status == "active" }.map(::legacy)
    suspend fun search(query: String): List<GlobalMemoryEntity> = repository.list(query).map(::legacy)
    suspend fun disable(id: Long) { repository.list().firstOrNull { legacyId(it) == id }?.let { repository.setEnabled(it.id, false) } }
    suspend fun delete(id: Long) { repository.list().firstOrNull { legacyId(it) == id }?.let { repository.delete(it.id) } }

    private fun legacyId(item: MemoryItem): Long = if (item.id.startsWith("legacy-")) item.id.removePrefix("legacy-").toLong()
        else UUID.fromString(item.id).mostSignificantBits
    private fun legacy(item: MemoryItem) = GlobalMemoryEntity(id = legacyId(item), key = item.title,
        value = item.summary, category = item.appScope.orEmpty(),
        source = if (item.origin in setOf("explicit_user", "user_edit")) "user" else "ai",
        createdAt = item.createdAt, updatedAt = item.updatedAt, enabled = item.status == "active")
}
