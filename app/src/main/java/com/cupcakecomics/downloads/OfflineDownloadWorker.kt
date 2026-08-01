package com.cupcakecomics.downloads

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.DownloadJobEntity
import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.smb.SmbStageManager
import com.nkanaev.comics.R
import com.nkanaev.comics.managers.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Drains the persistent download queue (Room `download_jobs`) one job at a time.
 *
 * Reliability design:
 * - Queue state lives in the database, so process death loses nothing; jobs left
 *   RUNNING are re-queued at the next start.
 * - Each job gets up to [MAX_ATTEMPTS] attempts with backoff before it is marked
 *   FAILED with the real error; failures are retryable from the Downloads screen.
 * - Work is constrained to a connected network, so WorkManager pauses it when
 *   connectivity drops and resumes it when it returns.
 * - The stage routine writes to a temp file and atomically renames after size
 *   validation, so a dropped connection can never register a truncated comic.
 */
class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val queue = DownloadQueueRepository(applicationContext)

    override suspend fun doWork(): Result {
        val connections = ConnectionRepository(applicationContext)
        val stage = SmbStageManager(applicationContext, connections.credentialStore())
        val okTitles = mutableListOf<String>()
        var failed = 0

        queue.requeueRunningLeftovers()
        queue.pruneSucceeded(System.currentTimeMillis() - KEEP_SUCCEEDED_MS)

        setForegroundSafe(progressNotification(0, 1, ""))

        var doneCount = 0
        var totalCount = 1
        while (!isStopped) {
            val job = queue.nextQueued() ?: break
            totalCount = doneCount + failed + remainingEstimate() + 1
            setForegroundSafe(progressNotification(doneCount, totalCount, job.title))
            when (runJob(job, stage, connections)) {
                JobOutcome.SUCCEEDED -> {
                    okTitles.add(job.title.ifBlank { job.relativePath.substringAfterLast('/') })
                    doneCount++
                }
                JobOutcome.FAILED -> failed++
                JobOutcome.STOPPED -> break
            }
        }

        if (!isStopped && (okTitles.isNotEmpty() || failed > 0)) {
            CupcakeNotifications.onDownloadsFinished(applicationContext, okTitles, failed)
        }
        return Result.success()
    }

    private suspend fun remainingEstimate(): Int =
        runCatching { queue.queuedCount() }.getOrDefault(0)

    private enum class JobOutcome { SUCCEEDED, FAILED, STOPPED }

    private suspend fun runJob(
        job: DownloadJobEntity,
        stage: SmbStageManager,
        connections: ConnectionRepository,
    ): JobOutcome {
        val share = connections.getSmbShare(job.shareId)
        if (share == null) {
            queue.markFailed(job.id, "Connection no longer exists")
            return JobOutcome.FAILED
        }
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_ATTEMPTS) {
            if (isStopped) {
                queue.requeue(job.id)
                return JobOutcome.STOPPED
            }
            attempt++
            lastProgressWrite = 0L
            queue.markRunning(job.id, attempt)
            val result = withContext(Dispatchers.IO) {
                stage.stage(
                    share = share,
                    relativePath = job.relativePath,
                    keepOffline = true,
                    isCancelled = { isStopped },
                    onProgress = { done, total ->
                        // Throttled by the stage manager; keep DB writes light too.
                        if (done == total || done - lastProgressWrite > PROGRESS_WRITE_STEP) {
                            lastProgressWrite = done
                            kotlinx.coroutines.runBlocking {
                                runCatching { queue.setProgress(job.id, done, total) }
                            }
                        }
                    },
                )
            }
            if (result.isSuccess) {
                queue.markSucceeded(job.id)
                return JobOutcome.SUCCEEDED
            }
            val err = result.exceptionOrNull()
            if (isStopped || err is CancellationException || err?.cause is CancellationException) {
                queue.requeue(job.id)
                return JobOutcome.STOPPED
            }
            lastError = err?.message ?: "Download failed"
            queue.setProgress(job.id, 0L, 0L)
            if (attempt < MAX_ATTEMPTS) delay(backoffMs(attempt))
        }
        queue.markFailed(job.id, lastError)
        return JobOutcome.FAILED
    }

    @Volatile
    private var lastProgressWrite = 0L

    private suspend fun setForegroundSafe(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: Throwable) {
            // Older devices / missing permission — progress notification still helps.
            CupcakeNotifications.ensureChannels(applicationContext)
            val nm = androidx.core.app.NotificationManagerCompat.from(applicationContext)
            nm.notify(NOTIF_PROGRESS_ID, info.notification)
        }
    }

    private fun progressNotification(done: Int, total: Int, currentName: String): ForegroundInfo {
        CupcakeNotifications.ensureChannels(applicationContext)
        val title = applicationContext.getString(R.string.offline_download_progress_title, done, total)
        val body = Utils.removeExtensionIfAny(currentName)
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            CupcakeNotifications.CHANNEL_DOWNLOADS,
        )
            .setSmallIcon(R.drawable.ic_download_18)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total), total <= 0)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIF_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIF_PROGRESS_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "cupcake_offline_downloads"
        private const val NOTIF_PROGRESS_ID = 44021
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_WRITE_STEP = 512L * 1024L
        private const val KEEP_SUCCEEDED_MS = 7L * 24L * 60L * 60L * 1000L

        private fun backoffMs(attempt: Int): Long = when (attempt) {
            1 -> 2_000L
            2 -> 8_000L
            else -> 20_000L
        }

        /**
         * Queues comics and starts the queue worker. New work chains behind any
         * in-flight run (APPEND_OR_REPLACE keeps one active drain at a time).
         * Safe to call from the UI thread.
         */
        fun enqueue(context: Context, shareId: Long, relativePaths: List<String>) {
            if (relativePaths.isEmpty()) return
            val app = context.applicationContext
            enqueueScope.launch {
                val added = DownloadQueueRepository(app)
                    .enqueue(shareId, relativePaths.map { it to it.substringAfterLast('/') })
                if (added > 0) kick(app)
            }
        }

        private val enqueueScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )

        /** Starts the queue drain if it is not already running. */
        fun kick(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
