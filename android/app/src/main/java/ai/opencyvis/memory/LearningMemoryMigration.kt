package ai.opencyvis.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing memories keep their content and identity; legacy evidence has no invented actor. */
object LearningMemoryMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory_episodes ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'agent_run'")
        db.execSQL("ALTER TABLE memory_jobs ADD COLUMN kind TEXT NOT NULL DEFAULT 'memory_consolidate'")
        db.execSQL("ALTER TABLE memory_evidence ADD COLUMN actor TEXT NOT NULL DEFAULT 'unknown'")
        db.execSQL("ALTER TABLE memory_evidence ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'agent_run'")
        db.execSQL("ALTER TABLE memory_evidence ADD COLUMN observationType TEXT NOT NULL DEFAULT 'direct'")
        db.execSQL("ALTER TABLE memory_evidence ADD COLUMN supportScopeJson TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS learning_sessions (
                id TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, goal TEXT NOT NULL,
                goalSource TEXT NOT NULL, displayId INTEGER NOT NULL, parentRunId TEXT, appScope TEXT,
                allowedPackagesJson TEXT NOT NULL, sourceJson TEXT NOT NULL, note TEXT NOT NULL,
                status TEXT NOT NULL, analysisJson TEXT NOT NULL, proposalsJson TEXT NOT NULL,
                questionsJson TEXT NOT NULL, answersJson TEXT NOT NULL, commitsJson TEXT NOT NULL,
                error TEXT, eventCount INTEGER NOT NULL, frameCount INTEGER NOT NULL,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, finishedAt INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_sessions_status ON learning_sessions(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_sessions_createdAt ON learning_sessions(createdAt)")
    }
}
