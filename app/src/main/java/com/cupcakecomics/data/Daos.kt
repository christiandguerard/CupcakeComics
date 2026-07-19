package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmbShareDao {
    @Query("SELECT * FROM smb_shares ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<SmbShareEntity>>

    @Query("SELECT * FROM smb_shares ORDER BY displayName COLLATE NOCASE")
    suspend fun getAll(): List<SmbShareEntity>

    @Query("SELECT * FROM smb_shares WHERE id = :id")
    suspend fun getById(id: Long): SmbShareEntity?

    @Query(
        """
        SELECT * FROM smb_shares
        WHERE lower(host) = lower(:host)
          AND port = :port
          AND lower(shareName) = lower(:shareName)
          AND lower(startPath) = lower(:startPath)
        LIMIT 1
        """,
    )
    suspend fun findByIdentity(
        host: String,
        port: Int,
        shareName: String,
        startPath: String,
    ): SmbShareEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SmbShareEntity): Long

    @Update
    suspend fun update(entity: SmbShareEntity)

    @Query("DELETE FROM smb_shares WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE smb_shares
        SET comicCount = :comicCount, totalBytes = :totalBytes, statsUpdatedAt = :statsUpdatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStats(id: Long, comicCount: Int, totalBytes: Long, statsUpdatedAt: Long)
}

@Dao
interface KapowarrProfileDao {
    @Query("SELECT * FROM kapowarr_profiles ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<KapowarrProfileEntity>>

    @Query("SELECT * FROM kapowarr_profiles ORDER BY displayName COLLATE NOCASE")
    suspend fun getAll(): List<KapowarrProfileEntity>

    @Query("SELECT * FROM kapowarr_profiles WHERE id = :id")
    suspend fun getById(id: Long): KapowarrProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KapowarrProfileEntity): Long

    @Update
    suspend fun update(entity: KapowarrProfileEntity)

    @Query("DELETE FROM kapowarr_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
