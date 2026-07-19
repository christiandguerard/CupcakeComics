package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Comics the user added via the system file picker (SAF).
 * Files are copied into app-private storage so covers and reading work offline.
 */
@Entity(
    tableName = "local_files",
    indices = [Index(value = ["sourceKey"], unique = true)],
)
data class LocalFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Absolute path under filesDir/local-comics/… */
    val localPath: String,
    /** Stable key, e.g. local:content://… or local:file://… */
    val sourceKey: String,
    /** Original SAF URI string when picked from content provider (may be blank). */
    val contentUri: String = "",
    val sizeBytes: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
)
