package com.cupcakecomics.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Secrets only — share metadata lives in Room.
 * Never log values returned from here.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            "cupcake_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putSecret(value: String): String {
        val key = "cred_" + UUID.randomUUID().toString()
        prefs.edit().putString(key, value).apply()
        return key
    }

    fun getSecret(key: String): String? {
        if (key.isBlank()) return null
        return prefs.getString(key, null)
    }

    fun deleteSecret(key: String) {
        if (key.isBlank()) return
        prefs.edit().remove(key).apply()
    }
}
