package com.cupcakecomics.reminders

import com.cupcakecomics.notifications.CupcakeNotifications
import com.cupcakecomics.settings.CupcakeSettings
import java.util.Calendar

/**
 * Computes next reminder fire times with quiet-hours deferral.
 * Hour granularity matches existing quiet-hours UI.
 */
object ReminderSchedule {
    /**
     * Next fire strictly after [afterMillis]. Applies quiet-hours deferral to the candidate.
     */
    fun computeNextFire(
        afterMillis: Long,
        frequency: com.cupcakecomics.data.ReminderFrequency,
        hourOfDay: Int,
        dayOfWeek: Int,
        dayOfMonth: Int,
        settings: CupcakeSettings,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val hour = hourOfDay.coerceIn(0, 23)
        var candidate = when (frequency) {
            com.cupcakecomics.data.ReminderFrequency.DAILY ->
                nextDaily(afterMillis, hour, nowMillis)
            com.cupcakecomics.data.ReminderFrequency.WEEKLY ->
                nextWeekly(afterMillis, hour, dayOfWeek.coerceIn(Calendar.SUNDAY, Calendar.SATURDAY), nowMillis)
            com.cupcakecomics.data.ReminderFrequency.MONTHLY ->
                nextMonthly(afterMillis, hour, dayOfMonth.coerceIn(1, 28), nowMillis)
        }
        candidate = applyQuietHoursDeferral(candidate, settings)
        // Ensure strictly after afterMillis
        if (candidate <= afterMillis) {
            candidate = when (frequency) {
                com.cupcakecomics.data.ReminderFrequency.DAILY ->
                    nextDaily(candidate, hour, candidate + 1)
                com.cupcakecomics.data.ReminderFrequency.WEEKLY ->
                    nextWeekly(candidate, hour, dayOfWeek.coerceIn(Calendar.SUNDAY, Calendar.SATURDAY), candidate + 1)
                com.cupcakecomics.data.ReminderFrequency.MONTHLY ->
                    nextMonthly(candidate, hour, dayOfMonth.coerceIn(1, 28), candidate + 1)
            }
            candidate = applyQuietHoursDeferral(candidate, settings)
        }
        return candidate
    }

    /** If [timeMillis] falls in quiet hours, defer to the next [quietHoursEndHour]. */
    fun applyQuietHoursDeferral(timeMillis: Long, settings: CupcakeSettings): Long {
        if (!settings.quietHoursEnabled) return timeMillis
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (!CupcakeNotifications.isInQuietHours(settings, hour)) return timeMillis

        val endHour = settings.quietHoursEndHour.coerceIn(0, 23)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, endHour)
        if (cal.timeInMillis <= timeMillis) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun nextDaily(afterMillis: Long, hour: Int, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = maxOf(afterMillis, nowMillis) }
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        if (cal.timeInMillis <= afterMillis) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun nextWeekly(afterMillis: Long, hour: Int, dayOfWeek: Int, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = maxOf(afterMillis, nowMillis) }
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        val currentDow = cal.get(Calendar.DAY_OF_WEEK)
        var delta = dayOfWeek - currentDow
        if (delta < 0) delta += 7
        if (delta == 0 && cal.timeInMillis <= afterMillis) delta = 7
        if (delta > 0) cal.add(Calendar.DAY_OF_MONTH, delta)
        return cal.timeInMillis
    }

    private fun nextMonthly(afterMillis: Long, hour: Int, dayOfMonth: Int, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = maxOf(afterMillis, nowMillis) }
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(maxDay))
        if (cal.timeInMillis <= afterMillis) {
            cal.add(Calendar.MONTH, 1)
            val maxNext = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(maxNext))
        }
        return cal.timeInMillis
    }
}
