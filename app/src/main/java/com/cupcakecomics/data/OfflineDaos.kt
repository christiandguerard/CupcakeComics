package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineComicDao {
    @Query("SELECT * FROM offline_comics ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<OfflineComicEntity>>

    @Query("SELECT * FROM offline_comics ORDER BY downloadedAt DESC")
    suspend fun getAll(): List<OfflineComicEntity>

    @Query("SELECT * FROM offline_comics WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): OfflineComicEntity?

    @Query("SELECT * FROM offline_comics WHERE id = :id")
    suspend fun getById(id: Long): OfflineComicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OfflineComicEntity): Long

    @Query("DELETE FROM offline_comics WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}

@Dao
interface ReadMarkDao {
    @Query("SELECT * FROM read_marks ORDER BY markedReadAt DESC")
    fun observeAll(): Flow<List<ReadMarkEntity>>

    @Query("SELECT * FROM read_marks ORDER BY markedReadAt DESC")
    suspend fun getAll(): List<ReadMarkEntity>

    @Query("SELECT identityKey FROM read_marks")
    suspend fun getAllKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadMarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ReadMarkEntity>)

    @Query("DELETE FROM read_marks WHERE identityKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)
}
