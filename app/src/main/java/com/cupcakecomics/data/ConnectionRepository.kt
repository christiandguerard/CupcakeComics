package com.cupcakecomics.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ConnectionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = CupcakeDatabase.get(appContext)
    private val credentials = CredentialStore(appContext)
    private val statsCache = NetworkLibraryStatsCache(appContext)
    val smbShares: Flow<List<SmbShareEntity>> = db.smbShareDao().observeAll()
    val kapowarrProfiles: Flow<List<KapowarrProfileEntity>> = db.kapowarrProfileDao().observeAll()

    fun credentialStore(): CredentialStore = credentials

    suspend fun getSmbShare(id: Long): SmbShareEntity? = withContext(Dispatchers.IO) {
        db.smbShareDao().getById(id)
    }

    suspend fun addSmbShare(
        displayName: String,
        host: String,
        port: Int,
        shareName: String,
        startPath: String,
        domain: String,
        username: String,
        password: String,
        useGuest: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        val hostTrim = host.trim()
        val shareTrim = shareName.trim().trim('/')
        val pathNorm = SmbPath.normalize(startPath)
        val existing = db.smbShareDao().findByIdentity(hostTrim, port, shareTrim, pathNorm)
        val cached = statsCache.load(hostTrim, port, shareTrim, pathNorm)

        val credKey = if (!useGuest && password.isNotEmpty()) {
            credentials.putSecret(password)
        } else {
            ""
        }

        if (existing != null) {
            if (existing.credentialKey.isNotEmpty() && existing.credentialKey != credKey) {
                credentials.deleteSecret(existing.credentialKey)
            }
            val updated = existing.copy(
                displayName = displayName.ifBlank { "$hostTrim/$shareTrim" },
                domain = domain.trim(),
                username = username.trim(),
                credentialKey = credKey,
                useGuest = useGuest || username.isBlank(),
                comicCount = if (existing.comicCount >= 0) existing.comicCount else (cached?.comicCount ?: -1),
                totalBytes = if (existing.totalBytes >= 0) existing.totalBytes else (cached?.totalBytes ?: -1L),
                statsUpdatedAt = maxOf(existing.statsUpdatedAt, cached?.statsUpdatedAt ?: 0L),
            )
            db.smbShareDao().update(updated)
            return@withContext existing.id
        }

        db.smbShareDao().insert(
            SmbShareEntity(
                displayName = displayName.ifBlank { "$hostTrim/$shareTrim" },
                host = hostTrim,
                port = port,
                shareName = shareTrim,
                startPath = pathNorm,
                domain = domain.trim(),
                username = username.trim(),
                credentialKey = credKey,
                useGuest = useGuest || username.isBlank(),
                comicCount = cached?.comicCount ?: -1,
                totalBytes = cached?.totalBytes ?: -1L,
                statsUpdatedAt = cached?.statsUpdatedAt ?: 0L,
            ),
        )
    }

    suspend fun deleteSmbShare(id: Long) = withContext(Dispatchers.IO) {
        val existing = db.smbShareDao().getById(id) ?: return@withContext
        // Keep NetworkLibraryStatsCache so re-adding the same share restores counts.
        credentials.deleteSecret(existing.credentialKey)
        db.smbShareDao().delete(id)
    }

    suspend fun updateSmbStats(id: Long, comicCount: Int, totalBytes: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.smbShareDao().updateStats(id, comicCount, totalBytes, now)
        val share = db.smbShareDao().getById(id) ?: return@withContext
        statsCache.save(share, comicCount, totalBytes, now)
    }

    suspend fun getAllSmbShares(): List<SmbShareEntity> = withContext(Dispatchers.IO) {
        db.smbShareDao().getAll()
    }

    /** Rehydrate Room rows that lost stats (e.g. after destructive migration) from durable cache. */
    suspend fun restoreCachedStatsIfNeeded() = withContext(Dispatchers.IO) {
        db.smbShareDao().getAll().forEach { share ->
            if (share.comicCount >= 0) return@forEach
            val cached = statsCache.load(share.host, share.port, share.shareName, share.startPath) ?: return@forEach
            db.smbShareDao().updateStats(
                share.id,
                cached.comicCount,
                cached.totalBytes,
                cached.statsUpdatedAt,
            )
        }
    }

    suspend fun addKapowarrProfile(
        displayName: String,
        baseUrl: String,
        apiKey: String,
        lanHttpAcknowledged: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        val key = credentials.putSecret(apiKey)
        db.kapowarrProfileDao().insert(
            KapowarrProfileEntity(
                displayName = displayName.ifBlank { baseUrl },
                baseUrl = baseUrl.trim().trimEnd('/'),
                apiKeyCredentialKey = key,
                lanHttpAcknowledged = lanHttpAcknowledged,
            ),
        )
    }

    suspend fun getKapowarrProfile(id: Long): KapowarrProfileEntity? = withContext(Dispatchers.IO) {
        db.kapowarrProfileDao().getById(id)
    }

    suspend fun getAllKapowarrProfiles(): List<KapowarrProfileEntity> = withContext(Dispatchers.IO) {
        db.kapowarrProfileDao().getAll()
    }

    suspend fun deleteKapowarrProfile(id: Long) = withContext(Dispatchers.IO) {
        val existing = db.kapowarrProfileDao().getById(id) ?: return@withContext
        credentials.deleteSecret(existing.apiKeyCredentialKey)
        db.kapowarrProfileDao().delete(id)
    }
}

/** Tiny path helper so repository does not depend on smbj package. */
internal object SmbPath {
    fun normalize(path: String): String = path.trim().trim('/').replace('\\', '/')
}
