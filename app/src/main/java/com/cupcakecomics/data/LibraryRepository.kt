package com.cupcakecomics.data

import android.content.Context
import android.net.Uri
import com.cupcakecomics.cover.FileCoverHandler
import com.nkanaev.comics.managers.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = CupcakeDatabase.get(appContext)

    val offlineComics: Flow<List<OfflineComicEntity>> = db.offlineComicDao().observeAll()
    val localFiles: Flow<List<LocalFileEntity>> = db.localFileDao().observeAll()
    val readMarks: Flow<List<ReadMarkEntity>> = db.readMarkDao().observeAll()

    fun downloadsDir(): File = File(appContext.filesDir, "offline-comics").also { it.mkdirs() }

    fun localFilesDir(): File = File(appContext.filesDir, "local-comics").also { it.mkdirs() }

    suspend fun getOffline(id: Long): OfflineComicEntity? = withContext(Dispatchers.IO) {
        db.offlineComicDao().getById(id)
    }

    suspend fun getOfflineBySource(sourceKey: String): OfflineComicEntity? = withContext(Dispatchers.IO) {
        db.offlineComicDao().getBySourceKey(sourceKey)
    }

    suspend fun upsertOffline(
        title: String,
        localPath: String,
        sourceKey: String,
        sizeBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        val existing = db.offlineComicDao().getBySourceKey(sourceKey)
        if (existing != null) {
            // Replace file path / metadata
            db.offlineComicDao().deleteIds(listOf(existing.id))
            if (existing.localPath != localPath) {
                File(existing.localPath).delete()
            }
        }
        db.offlineComicDao().insert(
            OfflineComicEntity(
                title = title,
                localPath = localPath,
                sourceKey = sourceKey,
                sizeBytes = sizeBytes,
            ),
        )
    }

    suspend fun deleteOffline(ids: List<Long>) = withContext(Dispatchers.IO) {
        val all = db.offlineComicDao().getAll().filter { it.id in ids }
        all.forEach { f ->
            val file = File(f.localPath)
            file.delete()
            file.parentFile?.takeIf { it.exists() && it.list()?.isEmpty() == true }?.delete()
        }
        db.offlineComicDao().deleteIds(ids)
    }

    /**
     * LRU-evict offline comics until total size + [reserveBytes] fits in [budgetBytes].
     * Oldest [OfflineComicEntity.downloadedAt] first (list is DESC — evict from the end).
     */
    suspend fun enforceOfflineBudget(budgetBytes: Long, reserveBytes: Long = 0L) =
        withContext(Dispatchers.IO) {
            val all = db.offlineComicDao().getAll().toMutableList() // newest first
            fun total(): Long = all.sumOf { it.sizeBytes.coerceAtLeast(0L) } + reserveBytes
            while (total() > budgetBytes && all.isNotEmpty()) {
                val victim = all.removeAt(all.lastIndex) // oldest
                val file = File(victim.localPath)
                file.delete()
                file.parentFile?.takeIf { it.exists() && it.list()?.isEmpty() == true }?.delete()
                db.offlineComicDao().deleteIds(listOf(victim.id))
            }
        }

    suspend fun markRead(entries: List<ReadMarkEntity>) = withContext(Dispatchers.IO) {
        db.readMarkDao().upsertAll(entries)
    }

    suspend fun unmarkRead(keys: List<String>) = withContext(Dispatchers.IO) {
        db.readMarkDao().deleteKeys(keys)
    }

    suspend fun readKeys(): Set<String> = withContext(Dispatchers.IO) {
        db.readMarkDao().getAllKeys().toSet()
    }

    suspend fun exportReadMarksJson(): String = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val arr = JSONArray()
        db.readMarkDao().getAll().forEach { mark ->
            arr.put(
                JSONObject()
                    .put("identityKey", mark.identityKey)
                    .put("displayName", mark.displayName)
                    .put("sourceType", mark.sourceType)
                    .put("sourceDetail", mark.sourceDetail)
                    .put("markedReadAt", mark.markedReadAt)
                    .put("markedReadAtIso", fmt.format(Date(mark.markedReadAt))),
            )
        }
        JSONObject()
            .put("exportedAt", fmt.format(Date()))
            .put("app", "Cupcake Comics")
            .put("marks", arr)
            .toString(2)
    }

    /**
     * Copy a SAF / file URI into app storage, warm its cover, and index it under Local files.
     * Returns the new entity id, or existing id if already imported.
     */
    suspend fun importLocalFromUri(uri: Uri): Long = withContext(Dispatchers.IO) {
        val sourceKey = "local:$uri"
        db.localFileDao().getBySourceKey(sourceKey)?.let { return@withContext it.id }

        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }

        val displayName = resolveDisplayName(uri)
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val destDir = File(localFilesDir(), token).also { it.mkdirs() }
        val dest = File(destDir, sanitizeFileName(displayName))

        appContext.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open $uri")

        FileCoverHandler.warmCache(dest.absolutePath)

        db.localFileDao().insert(
            LocalFileEntity(
                title = displayName,
                localPath = dest.absolutePath,
                sourceKey = sourceKey,
                contentUri = uri.toString(),
                sizeBytes = dest.length(),
            ),
        )
    }

    suspend fun getLocalFile(id: Long): LocalFileEntity? = withContext(Dispatchers.IO) {
        db.localFileDao().getById(id)
    }

    suspend fun deleteLocalFiles(ids: List<Long>) = withContext(Dispatchers.IO) {
        val all = db.localFileDao().getAll().filter { it.id in ids }
        all.forEach { item ->
            Utils.deleteCoverCacheFile(item.localPath)
            val file = File(item.localPath)
            file.delete()
            file.parentFile?.takeIf { it.exists() && it.list()?.isEmpty() == true }?.delete()
        }
        db.localFileDao().deleteIds(ids)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val fromQuery = runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        if (!fromQuery.isNullOrBlank()) return fromQuery
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return segment?.takeIf { it.isNotBlank() } ?: "comic"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "comic" }
    }
}
