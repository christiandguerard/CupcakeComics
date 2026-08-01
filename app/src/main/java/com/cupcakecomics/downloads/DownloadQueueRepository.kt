package com.cupcakecomics.downloads

import android.content.Context
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.DownloadJobEntity
import com.cupcakecomics.data.DownloadJobStatus
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.smb.SmbBrowser
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Single write path for the persistent download queue (Room `download_jobs`).
 * The queue survives process death; the worker drives it, the Downloads screen
 * observes it, and both SMB-browse and pull-list auto-downloads feed it.
 */
class DownloadQueueRepository internal constructor(
    private val app: Context,
    private val db: CupcakeDatabase,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        CupcakeDatabase.get(context.applicationContext),
    )

    private val dao = db.downloadJobDao()
    private val libraryRepo = LibraryRepository(app)

    /** Test hook: WorkManager is not initialized under Robolectric. */
    internal var kicker: (Context) -> Unit = { OfflineDownloadWorker.kick(it) }

    fun observeAll(): Flow<List<DownloadJobEntity>> = dao.observeAll()

    fun observeFailedCount(): Flow<Int> = dao.observeFailedCount()

    /**
     * Queues comics for download, skipping anything already downloaded, queued,
     * or running. Failed/succeeded rows for the same source are refreshed into a
     * new queued job. Returns how many jobs were newly queued.
     */
    suspend fun enqueue(shareId: Long, comics: List<Pair<String, String>>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for ((relativePath, title) in comics) {
            val rel = SmbBrowser.normalizePath(relativePath)
            if (rel.isBlank()) continue
            val key = sourceKey(shareId, rel)
            if (isAlreadyDownloaded(key)) continue
            val existing = dao.getBySourceKey(key)
            if (existing != null &&
                (existing.status == DownloadJobStatus.QUEUED ||
                    existing.status == DownloadJobStatus.RUNNING)
            ) {
                continue
            }
            dao.upsert(
                DownloadJobEntity(
                    id = existing?.id ?: 0,
                    shareId = shareId,
                    relativePath = rel,
                    title = title.ifBlank { rel.substringAfterLast('/') },
                    sourceKey = key,
                    status = DownloadJobStatus.QUEUED,
                    bytesDone = 0L,
                    bytesTotal = 0L,
                    error = null,
                    attempts = 0,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            added++
        }
        return added
    }

    suspend fun nextQueued(): DownloadJobEntity? = dao.nextQueued()

    suspend fun queuedCount(): Int = dao.queuedCount()

    suspend fun markRunning(id: Long, attempt: Int) {
        val job = getById(id) ?: return
        dao.upsert(
            job.copy(
                status = DownloadJobStatus.RUNNING,
                attempts = attempt,
                error = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun setProgress(id: Long, done: Long, total: Long) {
        dao.setProgress(id, done, total, System.currentTimeMillis())
    }

    suspend fun markSucceeded(id: Long) {
        val job = getById(id) ?: return
        dao.upsert(
            job.copy(
                status = DownloadJobStatus.SUCCEEDED,
                error = null,
                bytesDone = job.bytesTotal.takeIf { it > 0 } ?: job.bytesDone,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markFailed(id: Long, error: String?) {
        val job = getById(id) ?: return
        dao.upsert(
            job.copy(
                status = DownloadJobStatus.FAILED,
                error = error?.take(300),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Worker stopped mid-job: leave it queued so the next run picks it up. */
    suspend fun requeue(id: Long) = dao.requeue(id, System.currentTimeMillis())

    /** Crash recovery at worker start: jobs stuck RUNNING never completed. */
    suspend fun requeueRunningLeftovers(): Int = dao.requeueRunning(System.currentTimeMillis())

    suspend fun retry(id: Long) {
        dao.requeue(id, System.currentTimeMillis())
        kicker(app)
    }

    suspend fun retryFailed(): Int {
        val n = dao.requeueFailed(System.currentTimeMillis())
        if (n > 0) kicker(app)
        return n
    }

    suspend fun clearFinished() = dao.clearSucceeded()

    suspend fun pruneSucceeded(olderThanMs: Long) = dao.pruneSucceeded(olderThanMs)

    private suspend fun getById(id: Long): DownloadJobEntity? = dao.getById(id)

    private suspend fun isAlreadyDownloaded(sourceKey: String): Boolean {
        val row = libraryRepo.getOfflineBySource(sourceKey) ?: return false
        val f = File(row.localPath)
        return f.isFile && f.length() > 0
    }

    companion object {
        fun sourceKey(shareId: Long, relativePath: String): String =
            "smb:$shareId:${SmbBrowser.normalizePath(relativePath)}"
    }
}
