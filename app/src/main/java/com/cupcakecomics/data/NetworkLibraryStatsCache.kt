package com.cupcakecomics.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Survives Room wipes / share re-add / re-pairing by keying stats to share identity
 * (host + port + shareName + startPath), not Room row id.
 */
class NetworkLibraryStatsCache(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun identityKey(host: String, port: Int, shareName: String, startPath: String): String {
        val path = startPath.trim().trim('/').replace('\\', '/')
        return listOf(host.trim().lowercase(), port.toString(), shareName.trim().lowercase(), path.lowercase())
            .joinToString("|")
    }

    fun identityKey(share: SmbShareEntity): String =
        identityKey(share.host, share.port, share.shareName, share.startPath)

    fun save(share: SmbShareEntity, comicCount: Int, totalBytes: Long, statsUpdatedAt: Long) {
        if (comicCount < 0) return
        val key = identityKey(share)
        val json = JSONObject()
            .put("comicCount", comicCount)
            .put("totalBytes", totalBytes)
            .put("statsUpdatedAt", statsUpdatedAt)
            .toString()
        prefs.edit().putString(KEY_PREFIX + key, json).apply()
    }

    fun load(host: String, port: Int, shareName: String, startPath: String): CachedStats? {
        val raw = prefs.getString(KEY_PREFIX + identityKey(host, port, shareName, startPath), null)
            ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CachedStats(
                comicCount = o.getInt("comicCount"),
                totalBytes = o.getLong("totalBytes"),
                statsUpdatedAt = o.optLong("statsUpdatedAt", 0L),
            )
        }.getOrNull()
    }

    data class CachedStats(
        val comicCount: Int,
        val totalBytes: Long,
        val statsUpdatedAt: Long,
    )

    companion object {
        private const val PREFS = "cupcake_network_library_stats"
        private const val KEY_PREFIX = "stats:"
    }
}
