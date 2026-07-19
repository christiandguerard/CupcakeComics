package com.cupcakecomics.pulllist

import android.content.Context
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.MonitoredFolderEntity
import com.cupcakecomics.data.PullComicEntity
import com.cupcakecomics.data.PullPathSql
import com.cupcakecomics.data.pullIdentityKey
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.ComicFileNames
import com.cupcakecomics.smb.SmbBrowser
import com.cupcakecomics.smb.SmbStageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

data class PullScanResult(
    val newUnreadItems: Int,
    val scannedComics: Int,
    val folders: Int,
    val downloaded: Int = 0,
    val newTitles: List<String> = emptyList(),
    val downloadedTitles: List<String> = emptyList(),
)

private data class FolderScan(
    val newUnread: Int,
    val scanned: Int,
    val freshKeys: List<String>,
    /** False when listing failed mid-walk or hit the visit cap with work remaining. */
    val complete: Boolean,
)

/**
 * Pull List = unread comics under enrolled monitored folders.
 * Enroll baselines existing files as read (so the whole share is not dumped).
 * Later appearances are unread and show on the Pull List.
 */
class PullListRepository(context: Context) {
    private val app = context.applicationContext
    private val db = CupcakeDatabase.get(app)
    private val connections = ConnectionRepository(app)
    private val settings = CupcakeSettings(app)
    private val browser = SmbBrowser(connections.credentialStore())

    val pullList: Flow<List<PullComicEntity>> = db.pullComicDao().observePullList()
    val monitoredFolders: Flow<List<MonitoredFolderEntity>> = db.monitoredFolderDao().observeAll()

    suspend fun getPull(key: String): PullComicEntity? = withContext(Dispatchers.IO) {
        db.pullComicDao().getByKey(key)
    }

    suspend fun enrollFolder(
        shareId: Long,
        relativePath: String,
        displayName: String,
        comicvineId: Int? = null,
        kapowarrVolumeId: Int? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            val path = SmbBrowser.normalizePath(relativePath)
            val existing = db.monitoredFolderDao().find(shareId, path)
            if (existing != null) {
                if ((comicvineId != null && existing.comicvineId == null) ||
                    (kapowarrVolumeId != null && existing.kapowarrVolumeId == null)
                ) {
                    db.monitoredFolderDao().update(
                        existing.copy(
                            comicvineId = comicvineId ?: existing.comicvineId,
                            kapowarrVolumeId = kapowarrVolumeId ?: existing.kapowarrVolumeId,
                            accentColor = if (existing.accentColor != 0) {
                                existing.accentColor
                            } else {
                                PullEstimateRepository.randomAccent(existing.id)
                            },
                        ),
                    )
                }
                return@withContext existing.id
            }
            val tempId = System.currentTimeMillis()
            val id = db.monitoredFolderDao().insert(
                MonitoredFolderEntity(
                    shareId = shareId,
                    relativePath = path,
                    displayName = displayName.ifBlank { path.ifBlank { "/" } },
                    comicvineId = comicvineId,
                    kapowarrVolumeId = kapowarrVolumeId,
                    accentColor = PullEstimateRepository.randomAccent(tempId),
                ),
            )
            // Existing comics = known + read (not on Pull List). Only later arrivals are unread.
            baselineFolder(id)
            id
        }

    suspend fun unenrollFolder(folderId: Long) = withContext(Dispatchers.IO) {
        val folder = db.monitoredFolderDao().getById(folderId) ?: return@withContext
        db.pullComicDao().deleteUnderFolder(
            shareId = folder.shareId,
            folderPath = folder.relativePath,
            childPrefix = PullPathSql.childPrefix(folder.relativePath),
            isRoot = if (folder.relativePath.isEmpty()) 1 else 0,
        )
        db.monitoredFolderDao().delete(folderId)
    }

    suspend fun markRead(identityKey: String) = withContext(Dispatchers.IO) {
        db.pullComicDao().setPullMembership(identityKey, inPullList = false, markedRead = true)
    }

    suspend fun markUnread(identityKey: String) = withContext(Dispatchers.IO) {
        val row = db.pullComicDao().getByKey(identityKey) ?: return@withContext
        db.pullComicDao().upsert(
            row.copy(inPullList = true, markedReadManually = false, missing = false),
        )
    }

    suspend fun ignoreFromPull(identityKey: String) = markRead(identityKey)

    suspend fun updateReadingProgress(identityKey: String, highestPage: Int, pageCount: Int) =
        withContext(Dispatchers.IO) {
            if (identityKey.isBlank() || highestPage < 1) return@withContext
            db.pullComicDao().updateProgress(
                identityKey,
                highestPage,
                pageCount.coerceAtLeast(0),
                settings.pullListLeaveThreshold,
            )
        }

    fun updateReadingProgressSync(identityKey: String, highestPage: Int, pageCount: Int) {
        kotlinx.coroutines.runBlocking {
            updateReadingProgress(identityKey, highestPage, pageCount)
        }
    }

    suspend fun scanAll(): PullScanResult = withContext(Dispatchers.IO) {
        val folders = db.monitoredFolderDao().getAll()
        var newUnread = 0
        var scanned = 0
        val freshKeys = mutableListOf<String>()
        for (folder in folders) {
            if (!folder.baselined) {
                baselineFolder(folder.id)
            }
            // Re-read — baseline may still be incomplete after a failed walk.
            val current = db.monitoredFolderDao().getById(folder.id) ?: continue
            if (!current.baselined) continue
            val result = scanFolder(current, markNewAsUnread = true)
            newUnread += result.newUnread
            scanned += result.scanned
            freshKeys.addAll(result.freshKeys)
        }
        val newTitles = freshKeys.mapNotNull { key ->
            db.pullComicDao().getByKey(key)?.title
        }
        val downloadedTitles = if (settings.canAutoDownloadNow(app) && freshKeys.isNotEmpty()) {
            autoDownload(freshKeys)
        } else {
            emptyList()
        }
        PullScanResult(
            newUnreadItems = newUnread,
            scannedComics = scanned,
            folders = folders.size,
            downloaded = downloadedTitles.size,
            newTitles = newTitles,
            downloadedTitles = downloadedTitles,
        )
    }

    private suspend fun autoDownload(keys: List<String>): List<String> {
        val stage = SmbStageManager(app, connections.credentialStore())
        val ok = mutableListOf<String>()
        for (key in keys) {
            val row = db.pullComicDao().getByKey(key) ?: continue
            val share = connections.getSmbShare(row.shareId) ?: continue
            val result = stage.stage(share, row.relativePath, keepOffline = true, onProgress = null)
            if (result.isSuccess) ok.add(row.title)
        }
        return ok
    }

    private suspend fun baselineFolder(folderId: Long) {
        val folder = db.monitoredFolderDao().getById(folderId) ?: return
        val result = scanFolder(folder, markNewAsUnread = false)
        if (result.complete) {
            db.monitoredFolderDao().markBaselined(folderId)
        }
    }

    private suspend fun scanFolder(
        folder: MonitoredFolderEntity,
        markNewAsUnread: Boolean,
    ): FolderScan {
        val share = connections.getSmbShare(folder.shareId)
            ?: return FolderScan(0, 0, emptyList(), complete = false)
        val seen = linkedSetOf<String>()
        val freshKeys = mutableListOf<String>()
        var newUnread = 0
        var scanned = 0
        val queue = ArrayDeque<String>()
        queue.add(folder.relativePath)
        var visited = 0
        val max = 50_000

        while (queue.isNotEmpty() && visited < max) {
            val path = queue.removeFirst()
            val listing = browser.list(share, path).getOrElse {
                return FolderScan(newUnread, scanned, freshKeys, complete = false)
            }
            for (entry in listing) {
                visited++
                if (entry.isDirectory) {
                    queue.addLast(entry.relativePath)
                } else if (ComicFileNames.isComicArchive(entry.name)) {
                    scanned++
                    val key = pullIdentityKey(share.id, entry.relativePath)
                    seen.add(key)
                    val existing = db.pullComicDao().getByKey(key)
                    if (existing == null) {
                        val unread = markNewAsUnread
                        val added = db.pullComicDao().insertIgnore(
                            PullComicEntity(
                                identityKey = key,
                                shareId = share.id,
                                relativePath = SmbBrowser.normalizePath(entry.relativePath),
                                title = entry.name,
                                sizeBytes = entry.size.coerceAtLeast(0L),
                                inPullList = unread,
                                markedReadManually = !unread,
                                missing = false,
                            ),
                        )
                        if (added != -1L && unread) {
                            newUnread++
                            freshKeys.add(key)
                        }
                    } else if (existing.missing) {
                        db.pullComicDao().update(
                            existing.copy(missing = false, sizeBytes = entry.size.coerceAtLeast(0L)),
                        )
                    }
                }
            }
        }

        if (queue.isNotEmpty()) {
            // Hit visit cap with directories still pending — incomplete baseline.
            return FolderScan(newUnread, scanned, freshKeys, complete = false)
        }

        val prefix = folder.relativePath
        val known = db.pullComicDao().keysUnderFolder(
            shareId = folder.shareId,
            folderPath = prefix,
            childPrefix = PullPathSql.childPrefix(prefix),
            isRoot = if (prefix.isEmpty()) 1 else 0,
        )
        val gone = known.filter { it !in seen }
        if (gone.isNotEmpty()) {
            gone.chunked(200).forEach { chunk ->
                db.pullComicDao().setMissing(chunk, true)
            }
        }
        return FolderScan(newUnread, scanned, freshKeys, complete = true)
    }
}
