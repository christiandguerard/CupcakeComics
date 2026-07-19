package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalFileDao {
    @Query("SELECT * FROM local_files ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM local_files ORDER BY addedAt DESC")
    suspend fun getAll(): List<LocalFileEntity>

    @Query("SELECT * FROM local_files WHERE id = :id")
    suspend fun getById(id: Long): LocalFileEntity?

    @Query("SELECT * FROM local_files WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): LocalFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalFileEntity): Long

    @Query("DELETE FROM local_files WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}
