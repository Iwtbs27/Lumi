package ai.opencyvis.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MemoryMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_items (id TEXT NOT NULL PRIMARY KEY,type TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,appScope TEXT,taskFamily TEXT,status TEXT NOT NULL,origin TEXT NOT NULL,evidenceLevel TEXT NOT NULL,revision INTEGER NOT NULL,bodyJson TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,lastVerifiedAt INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_status_type_appScope ON memory_items(status,type,appScope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_updatedAt ON memory_items(updatedAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_revisions (memoryId TEXT NOT NULL,revision INTEGER NOT NULL,snapshotJson TEXT NOT NULL,reason TEXT NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(memoryId,revision))")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_episodes (id TEXT NOT NULL PRIMARY KEY,runId TEXT NOT NULL,conversationId INTEGER,instruction TEXT NOT NULL,summary TEXT NOT NULL,status TEXT NOT NULL,resultStatus TEXT NOT NULL,segmentsJson TEXT NOT NULL,evidenceRefsJson TEXT NOT NULL,appScope TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_episodes_conversationId ON memory_episodes(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_episodes_updatedAt ON memory_episodes(updatedAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_evidence (id TEXT NOT NULL PRIMARY KEY,memoryId TEXT,revision INTEGER,episodeId TEXT NOT NULL,runId TEXT NOT NULL,step INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,imagePath TEXT,treePath TEXT,author TEXT NOT NULL,observedAt INTEGER NOT NULL)")
        listOf("memoryId", "runId", "episodeId").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_evidence_$it ON memory_evidence($it)") }
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_usages (id TEXT NOT NULL PRIMARY KEY,memoryId TEXT NOT NULL,revision INTEGER NOT NULL,runId TEXT NOT NULL,step INTEGER NOT NULL,event TEXT NOT NULL,goalId TEXT,outcome TEXT NOT NULL,evidenceStep INTEGER,detailsJson TEXT NOT NULL,createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_usages_memoryId_revision ON memory_usages(memoryId,revision)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_usages_runId_step ON memory_usages(runId,step)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_jobs (id TEXT NOT NULL PRIMARY KEY,runId TEXT NOT NULL,status TEXT NOT NULL,payloadJson TEXT NOT NULL,checkpointJson TEXT NOT NULL,attempts INTEGER NOT NULL,error TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_jobs_status_createdAt ON memory_jobs(status,createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_jobs_runId ON memory_jobs(runId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_blocks (identity TEXT NOT NULL PRIMARY KEY,createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING FTS4(itemId TEXT NOT NULL,terms TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS episode_fts USING FTS4(episodeId TEXT NOT NULL,terms TEXT NOT NULL)")
        db.query("SELECT * FROM global_memories").use { cursor ->
            while (cursor.moveToNext()) {
                fun str(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                val user = str("source") == "user"
                val item = MemoryItem(id = "legacy-${number("id")}", title = str("key"), summary = str("value"),
                    status = if (number("enabled") == 0L) "disabled" else if (user) "active" else "candidate",
                    origin = if (user) "explicit_user" else "legacy", evidenceLevel = if (user) "user_stated" else "unverified",
                    bodyJson = org.json.JSONObject(MemoryBodies.fact(str("value"), if (user) "user_preference" else "app_knowledge"))
                        .put("legacy_category", str("category")).put("legacy_source", str("source"))
                        .put("legacy_enabled", number("enabled") != 0L).toString(),
                    createdAt = number("createdAt"), updatedAt = number("updatedAt"))
                db.execSQL("INSERT INTO memory_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(item.id,item.type,item.title,item.summary,item.appScope,item.taskFamily,item.status,item.origin,item.evidenceLevel,item.revision,item.bodyJson,item.createdAt,item.updatedAt,item.lastVerifiedAt))
                db.execSQL("INSERT INTO memory_fts(itemId,terms) VALUES(?,?)", arrayOf(item.id,MemorySearch.index(item.title + " " + item.summary + " " + str("category"))))
            }
        }
        // Legacy table remains for Room schema compatibility, but contains no second source of truth.
        db.execSQL("DELETE FROM global_memories")
    }
}
