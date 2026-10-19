package ai.opencyvis.task

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the queue without rewriting existing chats, memories, routines or learning records. */
object TaskMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                instruction TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL,
                remoteChatId TEXT, routineId INTEGER, conversationId INTEGER, runId TEXT,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, error TEXT,
                guiHeld INTEGER NOT NULL, queueOrder INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_status_queueOrder ON agent_tasks(status, queueOrder)")
    }
}
