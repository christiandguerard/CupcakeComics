package com.cupcakecomics.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadJobDao {
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadJobEntity?

    @Query("SELECT * FROM download_jobs WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): DownloadJobEntity?

    @Query(
        """
        SELECT * FROM download_jobs
        WHERE status = 'QUEUED'
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun nextQueued(): DownloadJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: DownloadJobEntity): Long

    @Query("UPDATE download_jobs SET bytesDone = :done, bytesTotal = :total, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, done: Long, total: Long, now: Long)

    @Query(
        """
        UPDATE download_jobs
        SET status = 'QUEUED', error = NULL, attempts = 0, bytesDone = 0, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun requeue(id: Long, now: Long)

    @Query(
        """
        UPDATE download_jobs
        SET status = 'QUEUED', error = NULL, attempts = 0, bytesDone = 0, updatedAt = :now
        WHERE status = 'FAILED'
        """,
    )
    suspend fun requeueFailed(now: Long): Int

    /** Crash/stop recovery: a job left RUNNING never finished, so queue it again. */
    @Query("UPDATE download_jobs SET status = 'QUEUED', updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun requeueRunning(now: Long): Int

    @Query("SELECT COUNT(*) FROM download_jobs WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM download_jobs WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("DELETE FROM download_jobs WHERE status = 'SUCCEEDED'")
    suspend fun clearSucceeded()

    @Query("DELETE FROM download_jobs WHERE status = 'SUCCEEDED' AND updatedAt < :before")
    suspend fun pruneSucceeded(before: Long)
}
