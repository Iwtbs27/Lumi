package ai.opencyvis.task

import android.content.Context
import ai.opencyvis.db.AppDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskMigrationDeviceTest {
    @Test fun upgradeValidatesRoomSchemaAndKeepsExistingUserData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task_migration_${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_7_SCHEMA.forEach(db::execSQL)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        var migrated: AppDatabase? = null
        try {
            val old = helper.writableDatabase
            assertEquals(7, old.version)
            old.execSQL("INSERT INTO conversations(id,title,status,createdAt,updatedAt) VALUES(21,'旧对话','completed',100,200)")
            old.execSQL("INSERT INTO chat_messages(id,conversationId,type,text,timestamp,source,remoteChatId) VALUES(31,21,'USER','保留的聊天内容',100,'telegram','chat-3')")
            old.execSQL("INSERT INTO global_memories(id,`key`,`value`,category,source,createdAt,updatedAt,enabled) VALUES(41,'偏好','保留的记忆','notes','user',100,200,0)")
            helper.close()

            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(TaskMigration).build()
            migrated = database
            // Opening through Room executes 7 -> 8 and validates every entity table.
            assertEquals(8, database.openHelper.writableDatabase.version)
            assertEquals("旧对话", database.chatDao().getAllConversations().single().title)
            val message = database.chatDao().getMessages(21L).single()
            assertEquals("保留的聊天内容", message.text)
            assertEquals("chat-3", message.remoteChatId)
            database.openHelper.readableDatabase.query("SELECT `value`,enabled FROM global_memories WHERE id=41").use {
                assertTrue(it.moveToFirst())
                assertEquals("保留的记忆", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            val repository = TaskRepository(database.taskDao()) { 500L }
            val task = repository.enqueue(TaskRequest("新队列任务", conversationId = 21L))
            assertEquals(task, repository.get(task.id))
            database.close()

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(TaskMigration).build()
            migrated = reopened
            assertEquals(task, TaskRepository(reopened.taskDao()).get(task.id))
            assertEquals(1, reopened.chatDao().getMessages(21L).size)
        } finally {
            migrated?.close()
            helper.close()
            context.deleteDatabase(name)
        }
    }

    companion object {
        // Frozen from the existing v7 generated CREATE statements before adding task storage.
        // Room's own metadata is deliberately absent; opening v8 must validate the real schema.
        private val VERSION_7_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `type` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `source` TEXT NOT NULL, `remoteChatId` TEXT, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            """CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)""",
            """CREATE TABLE IF NOT EXISTS `global_memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, `value` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)""",
            """CREATE UNIQUE INDEX IF NOT EXISTS `index_global_memories_key` ON `global_memories` (`key`)""",
            """CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `instruction` TEXT NOT NULL, `description` TEXT, `category` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, `lastUsedAt` INTEGER, `createdAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `scheduleEnabled` INTEGER NOT NULL, `triggerType` TEXT, `scheduleHour` INTEGER, `scheduleMinute` INTEGER, `scheduleRepeatDays` TEXT, `intervalMinutes` INTEGER, `intervalStartHour` INTEGER, `intervalEndHour` INTEGER, `geoLatitude` REAL, `geoLongitude` REAL, `geoRadiusMeters` REAL, `geoTriggerOnEnter` INTEGER, `geoLocationName` TEXT, `lastTriggeredAt` INTEGER, `nextTriggerAt` INTEGER)""",
            """CREATE TABLE IF NOT EXISTS `memory_items` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `appScope` TEXT, `taskFamily` TEXT, `status` TEXT NOT NULL, `origin` TEXT NOT NULL, `evidenceLevel` TEXT NOT NULL, `revision` INTEGER NOT NULL, `bodyJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastVerifiedAt` INTEGER, PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_memory_items_status_type_appScope` ON `memory_items` (`status`, `type`, `appScope`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_items_updatedAt` ON `memory_items` (`updatedAt`)""",
            """CREATE TABLE IF NOT EXISTS `memory_revisions` (`memoryId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`memoryId`, `revision`))""",
            """CREATE TABLE IF NOT EXISTS `memory_episodes` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `conversationId` INTEGER, `instruction` TEXT NOT NULL, `summary` TEXT NOT NULL, `status` TEXT NOT NULL, `resultStatus` TEXT NOT NULL, `segmentsJson` TEXT NOT NULL, `evidenceRefsJson` TEXT NOT NULL, `appScope` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `sourceType` TEXT NOT NULL DEFAULT 'agent_run', PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_memory_episodes_conversationId` ON `memory_episodes` (`conversationId`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_episodes_updatedAt` ON `memory_episodes` (`updatedAt`)""",
            """CREATE TABLE IF NOT EXISTS `memory_evidence` (`id` TEXT NOT NULL, `memoryId` TEXT, `revision` INTEGER, `episodeId` TEXT NOT NULL, `runId` TEXT NOT NULL, `step` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `imagePath` TEXT, `treePath` TEXT, `author` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, `actor` TEXT NOT NULL DEFAULT 'unknown', `sourceType` TEXT NOT NULL DEFAULT 'agent_run', `observationType` TEXT NOT NULL DEFAULT 'direct', `supportScopeJson` TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_memory_evidence_memoryId` ON `memory_evidence` (`memoryId`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_evidence_runId` ON `memory_evidence` (`runId`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_evidence_episodeId` ON `memory_evidence` (`episodeId`)""",
            """CREATE TABLE IF NOT EXISTS `memory_usages` (`id` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `runId` TEXT NOT NULL, `step` INTEGER NOT NULL, `event` TEXT NOT NULL, `goalId` TEXT, `outcome` TEXT NOT NULL, `evidenceStep` INTEGER, `detailsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_memory_usages_memoryId_revision` ON `memory_usages` (`memoryId`, `revision`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_usages_runId_step` ON `memory_usages` (`runId`, `step`)""",
            """CREATE TABLE IF NOT EXISTS `memory_jobs` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `status` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `checkpointJson` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `kind` TEXT NOT NULL DEFAULT 'memory_consolidate', PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_memory_jobs_status_createdAt` ON `memory_jobs` (`status`, `createdAt`)""",
            """CREATE INDEX IF NOT EXISTS `index_memory_jobs_runId` ON `memory_jobs` (`runId`)""",
            """CREATE TABLE IF NOT EXISTS `memory_blocks` (`identity` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`identity`))""",
            """CREATE VIRTUAL TABLE IF NOT EXISTS `memory_fts` USING FTS4(`itemId` TEXT NOT NULL, `terms` TEXT NOT NULL)""",
            """CREATE VIRTUAL TABLE IF NOT EXISTS `episode_fts` USING FTS4(`episodeId` TEXT NOT NULL, `terms` TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS `learning_sessions` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `goal` TEXT NOT NULL, `goalSource` TEXT NOT NULL, `displayId` INTEGER NOT NULL, `parentRunId` TEXT, `appScope` TEXT, `allowedPackagesJson` TEXT NOT NULL, `sourceJson` TEXT NOT NULL, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `analysisJson` TEXT NOT NULL, `proposalsJson` TEXT NOT NULL, `questionsJson` TEXT NOT NULL, `answersJson` TEXT NOT NULL, `commitsJson` TEXT NOT NULL, `error` TEXT, `eventCount` INTEGER NOT NULL, `frameCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`id`))""",
            """CREATE INDEX IF NOT EXISTS `index_learning_sessions_status` ON `learning_sessions` (`status`)""",
            """CREATE INDEX IF NOT EXISTS `index_learning_sessions_createdAt` ON `learning_sessions` (`createdAt`)"""
        )
    }
}
