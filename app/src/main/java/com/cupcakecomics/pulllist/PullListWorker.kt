package com.cupcakecomics.pulllist

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.settings.CupcakeSettings
import java.util.concurrent.TimeUnit

class PullListWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val settings = CupcakeSettings(applicationContext)
            val repo = PullListRepository(applicationContext)
            val result = repo.scanAll()
            if (result.newUnreadItems > 0 && settings.pullListNotify) {
                CupcakeNotifications.onNewPullItems(applicationContext, result.newTitles)
            } else {
                // Still try to flush a quiet-hours digest if the window ended.
                CupcakeNotifications.flushPendingPullList(applicationContext)
            }
            if (result.downloadedTitles.isNotEmpty()) {
                CupcakeNotifications.onDownloadsComplete(applicationContext, result.downloadedTitles)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK = "cupcake_pull_list_scan"
        @Deprecated("Use CupcakeNotifications.CHANNEL_PULL_LIST", ReplaceWith("CupcakeNotifications.CHANNEL_PULL_LIST"))
        const val CHANNEL_ID = CupcakeNotifications.CHANNEL_PULL_LIST

        @JvmStatic
        fun schedule(context: Context) {
            val settings = CupcakeSettings(context)
            val minutes = settings.pullListScanMinutes.toLong().coerceAtLeast(15L)
            val network = if (settings.pullListScanWifiOnly) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(network)
                .build()
            val request = PeriodicWorkRequestBuilder<PullListWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }

        @JvmStatic
        fun ensureChannel(context: Context) {
            CupcakeNotifications.ensureChannels(context)
        }

        @JvmStatic
        fun areNotificationsAllowed(context: Context): Boolean =
            CupcakeNotifications.areNotificationsAllowed(context)

        @JvmStatic
        fun notifyNewIssues(context: Context, count: Int) {
            // Legacy entry: count-only callers get a generic title list.
            val titles = List(count.coerceAtLeast(0)) { "Comic ${it + 1}" }
            CupcakeNotifications.onNewPullItems(context, titles)
        }

        @JvmStatic
        fun notifyNewIssues(context: Context, titles: List<String>) {
            CupcakeNotifications.onNewPullItems(context, titles)
        }

        /** @deprecated use CupcakeSettings */
        @JvmStatic
        fun notificationsEnabled(context: Context): Boolean =
            CupcakeSettings(context).pullListNotify

        @JvmStatic
        fun setNotificationsEnabled(context: Context, enabled: Boolean) {
            CupcakeSettings(context).pullListNotify = enabled
        }
    }
}
