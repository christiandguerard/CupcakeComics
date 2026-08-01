package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadJobStatus { QUEUED, RUNNING, FAILED, SUCCEEDED }

/**
 * One offline-download request for an SMB comic. Rows persist across app restarts
 * so the queue survives process death and failed jobs can be retried from the
 * Downloads screen. One row per source (unique sourceKey).
 */
@Entity(
    tableName = "download_jobs",
    indices = [Index(value = ["sourceKey"], unique = true)],
)
data class DownloadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    val relativePath: String,
    val title: String,
    val sourceKey: String,
    val status: DownloadJobStatus = DownloadJobStatus.QUEUED,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val error: String? = null,
    val attempts: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
