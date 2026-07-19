package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_comics")
data class OfflineComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val localPath: String,
    val sourceKey: String,
    val sizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
)

/**
 * Exportable read-history rows. identityKey is stable across sessions
 * (e.g. smb:<shareId>:<relativePath> or offline:<id>).
 */
@Entity(tableName = "read_marks")
data class ReadMarkEntity(
    @PrimaryKey val identityKey: String,
    val displayName: String,
    val sourceType: String,
    val sourceDetail: String,
    val markedReadAt: Long,
)
