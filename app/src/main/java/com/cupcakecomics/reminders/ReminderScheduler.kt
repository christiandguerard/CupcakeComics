package com.cupcakecomics.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cupcakecomics.data.CupcakeDatabase
import java.util.concurrent.TimeUnit
import kotlin.math.max

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ReminderScheduler {
    const val UNIQUE_WORK = "cupcake_reminders"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun schedule(context: Context) {
        scope.launch {
            scheduleInternal(context.applicationContext)
        }
    }

    private suspend fun scheduleInternal(app: Context) {
        val soonest = CupcakeDatabase.get(app).reminderDao().getSoonestEnabled()
        val workManager = WorkManager.getInstance(app)
        if (soonest == null || soonest.nextFireAt <= 0L) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val delayMs = max(0L, soonest.nextFireAt - System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
