package com.cupcakecomics.downloads

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.SmbStageManager
import com.nkanaev.comics.R
import com.nkanaev.comics.managers.Utils

/**
 * Downloads SMB comics for offline use without blocking the UI.
 */
class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1L)
        val paths = inputData.getStringArray(KEY_PATHS)?.toList().orEmpty()
        if (shareId < 0L || paths.isEmpty()) return Result.failure()

        val connections = ConnectionRepository(applicationContext)
        val share = connections.getSmbShare(shareId) ?: return Result.failure()
        val stage = SmbStageManager(applicationContext, connections.credentialStore())
        val okTitles = mutableListOf<String>()
        val total = paths.size

        setForegroundSafe(progressNotification(0, total, paths.firstOrNull().orEmpty()))

        for ((index, path) in paths.withIndex()) {
            if (isStopped) break
            val name = path.substringAfterLast('/').ifBlank { path }
            setForegroundSafe(progressNotification(index, total, name))
            val result = stage.stage(
                share = share,
                relativePath = path,
                keepOffline = true,
                isCancelled = { isStopped },
                onProgress = null,
            )
            if (result.isSuccess) {
                okTitles.add(name)
            }
        }

        if (okTitles.isNotEmpty() && CupcakeSettings(applicationContext).notifyDownloads) {
            CupcakeNotifications.onDownloadsComplete(applicationContext, okTitles)
        }
        return Result.success(
            Data.Builder().putInt(KEY_DONE_COUNT, okTitles.size).build(),
        )
    }

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
        const val UNIQUE_PREFIX = "cupcake_offline_dl_"
        const val KEY_SHARE_ID = "shareId"
        const val KEY_PATHS = "paths"
        const val KEY_DONE_COUNT = "doneCount"
        private const val NOTIF_PROGRESS_ID = 44021

        fun enqueue(context: Context, shareId: Long, relativePaths: List<String>) {
            if (relativePaths.isEmpty()) return
            val data = Data.Builder()
                .putLong(KEY_SHARE_ID, shareId)
                .putStringArray(KEY_PATHS, relativePaths.toTypedArray())
                .build()
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + shareId + "_" + System.currentTimeMillis(),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
