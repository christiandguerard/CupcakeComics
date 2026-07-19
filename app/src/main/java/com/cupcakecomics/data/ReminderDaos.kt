package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY type, title, id")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY type, title, id")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReminderEntity?

    @Query(
        """
        SELECT * FROM reminders
        WHERE enabled = 1 AND nextFireAt > 0 AND nextFireAt <= :nowMillis
        ORDER BY nextFireAt ASC
        """,
    )
    suspend fun getDue(nowMillis: Long): List<ReminderEntity>

    @Query(
        """
        SELECT * FROM reminders
        WHERE enabled = 1 AND nextFireAt > 0
        ORDER BY nextFireAt ASC
        LIMIT 1
        """,
    )
    suspend fun getSoonestEnabled(): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity): Long

    @Update
    suspend fun update(entity: ReminderEntity)

    @Delete
    suspend fun delete(entity: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        UPDATE reminders SET trackedPage = :page
        WHERE type = 'BOOK' AND bookSource = 'LOCAL'
          AND localPath = :localPath AND trackedPage < :page
        """,
    )
    suspend fun updateTrackedPageForLocalPath(localPath: String, page: Int)

    @Query(
        """
        UPDATE reminders SET trackedPage = :page
        WHERE type = 'BOOK' AND identityKey = :identityKey AND trackedPage < :page
        """,
    )
    suspend fun updateTrackedPageForIdentity(identityKey: String, page: Int)
}
