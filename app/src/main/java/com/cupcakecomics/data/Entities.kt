package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "smb_shares")
data class SmbShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val startPath: String = "",
    val domain: String = "",
    val username: String = "",
    /** Key into EncryptedSharedPreferences for the password; empty for guest. */
    val credentialKey: String = "",
    val useGuest: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Cached library card stats; -1 means not scanned yet. */
    val comicCount: Int = -1,
    val totalBytes: Long = -1L,
    val statsUpdatedAt: Long = 0L,
)

@Entity(tableName = "kapowarr_profiles")
data class KapowarrProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val baseUrl: String,
    /** Key into EncryptedSharedPreferences for the API key. */
    val apiKeyCredentialKey: String,
    val lanHttpAcknowledged: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
