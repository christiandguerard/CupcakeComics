package com.cupcakecomics.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cupcakecomics.data.ReminderType
import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.settings.CupcakeSettings

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val settings = CupcakeSettings(applicationContext)
            val repo = ReminderRepository(applicationContext)
            val now = System.currentTimeMillis()

            // If still in quiet hours, reschedule to end of quiet window.
            if (settings.quietHoursEnabled &&
                CupcakeNotifications.isInQuietHours(settings)
            ) {
                val deferred = ReminderSchedule.applyQuietHoursDeferral(now, settings)
                scheduleDeferred(deferred)
                return Result.success()
            }

            val due = repo.getDue(now)
            for (reminder in due) {
                when (reminder.type) {
                    ReminderType.PULL_LIST -> handlePullList(repo, reminder)
                    ReminderType.BOOK -> handleBook(repo, reminder)
                }
            }

            ReminderScheduler.schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun handlePullList(repo: ReminderRepository, reminder: com.cupcakecomics.data.ReminderEntity) {
        val count = repo.unreadPullListCount()
        if (count > 0) {
            CupcakeNotifications.notifyPullListReminder(applicationContext, count)
        }
        repo.afterFired(reminder)
    }

    private suspend fun handleBook(repo: ReminderRepository, reminder: com.cupcakecomics.data.ReminderEntity) {
        if (reminder.title.isBlank()) {
            repo.afterFired(reminder)
            return
        }
        val page = repo.resolveResumePage(reminder)
        if (repo.isBookFinished(reminder, page)) {
            CupcakeNotifications.notifyBookFinished(applicationContext, reminder)
            repo.afterFired(reminder, disabled = true)
            return
        }
        val goalPagesRead = if (reminder.hasGoal()) repo.pagesReadInWindow(reminder) else null
        CupcakeNotifications.notifyBookReminder(
            applicationContext,
            reminder = reminder,
            page = page,
            goalPagesRead = goalPagesRead,
        )
        repo.afterFired(reminder)
    }

    private fun scheduleDeferred(deferredAt: Long) {
        val delayMs = maxOf(0L, deferredAt - System.currentTimeMillis())
        val request = androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag(ReminderScheduler.UNIQUE_WORK)
            .build()
        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ReminderScheduler.UNIQUE_WORK,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
