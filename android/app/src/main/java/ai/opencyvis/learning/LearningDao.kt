package ai.opencyvis.learning

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningDao {
    @Query("SELECT * FROM learning_sessions WHERE id=:id") fun get(id: String): LearningSession?
    @Query("SELECT * FROM learning_sessions ORDER BY createdAt DESC") fun all(): List<LearningSession>
    @Query("SELECT * FROM learning_sessions ORDER BY createdAt DESC") fun observeAll(): Flow<List<LearningSession>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(session: LearningSession)
    @Query("DELETE FROM learning_sessions WHERE id=:id") fun delete(id: String)
}
