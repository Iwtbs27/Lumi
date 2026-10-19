package ai.opencyvis.memory

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryMigrationTest {
    @Test fun upgradeKeepsUserContentAndDisabledStateWithoutInventingAiEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE global_memories(id INTEGER PRIMARY KEY,`key` TEXT,value TEXT,category TEXT,source TEXT,createdAt INTEGER,updatedAt INTEGER,enabled INTEGER)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO global_memories VALUES(1,'笔记应用','原子笔记','notes','user',100,200,1)")
            db.execSQL("INSERT INTO global_memories VALUES(2,'旧建议','点击保存','','ai',100,200,1)")
            db.execSQL("INSERT INTO global_memories VALUES(3,'停用偏好','不用','','user',100,200,0)")
            MemoryMigration.migrate(db)
            db.query("SELECT status,origin,evidenceLevel,summary FROM memory_items WHERE id='legacy-1'").use {
                assertTrue(it.moveToFirst()); assertEquals("active", it.getString(0)); assertEquals("user_stated", it.getString(2)); assertEquals("原子笔记", it.getString(3))
            }
            db.query("SELECT status,origin,evidenceLevel FROM memory_items WHERE id='legacy-2'").use {
                assertTrue(it.moveToFirst()); assertEquals("candidate", it.getString(0)); assertEquals("legacy", it.getString(1)); assertEquals("unverified", it.getString(2))
            }
            db.query("SELECT status FROM memory_items WHERE id='legacy-3'").use {
                assertTrue(it.moveToFirst()); assertEquals("disabled", it.getString(0))
            }
            db.query("SELECT itemId FROM memory_fts WHERE memory_fts MATCH ?", arrayOf(MemorySearch.query("原子笔记"))).use {
                assertTrue(it.moveToFirst()); assertEquals("legacy-1", it.getString(0))
            }
        } finally { helper.close() }
    }
}
