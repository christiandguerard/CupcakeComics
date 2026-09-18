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
     * Next fire strictly after [afterMillis]. Blocked-weekday shifting and then
     * quiet-hours deferral are applied to the candidate.
     */
    fun computeNextFire(
        afterMillis: Long,
        frequency: com.cupcakecomics.data.ReminderFrequency,
        hourOfDay: Int,
        dayOfWeek: Int,
        dayOfMonth: Int,
        settings: CupcakeSettings,
        nowMillis: Long = System.currentTimeMillis(),
        intervalDays: Int = 0,
        blockedWeekdays: Int = 0,
        blockedShift: com.cupcakecomics.data.ReminderShiftDirection =
            com.cupcakecomics.data.ReminderShiftDirection.LATER,
    ): Long {
        val hour = hourOfDay.coerceIn(0, 23)
        var candidate = rawNext(afterMillis, frequency, hour, dayOfWeek, dayOfMonth, intervalDays, nowMillis)
        candidate = adjust(candidate, afterMillis, blockedWeekdays, blockedShift, settings)
        // Ensure strictly after afterMillis
        if (candidate <= afterMillis) {
            candidate = rawNext(candidate + 1, frequency, hour, dayOfWeek, dayOfMonth, intervalDays, candidate + 1)
            candidate = adjust(candidate, afterMillis, blockedWeekdays, blockedShift, settings)
        }
        return candidate
    }

    private fun rawNext(
        afterMillis: Long,
        frequency: com.cupcakecomics.data.ReminderFrequency,
        hour: Int,
        dayOfWeek: Int,
        dayOfMonth: Int,
        intervalDays: Int,
        nowMillis: Long,
    ): Long = when (frequency) {
        com.cupcakecomics.data.ReminderFrequency.DAILY ->
            nextDaily(afterMillis, hour, nowMillis)
        com.cupcakecomics.data.ReminderFrequency.WEEKLY ->
            nextWeekly(afterMillis, hour, dayOfWeek.coerceIn(Calendar.SUNDAY, Calendar.SATURDAY), nowMillis)
        com.cupcakecomics.data.ReminderFrequency.MONTHLY ->
            nextMonthly(afterMillis, hour, dayOfMonth.coerceIn(1, 28), nowMillis)
        com.cupcakecomics.data.ReminderFrequency.INTERVAL ->
            nextInterval(afterMillis, hour, intervalDays.coerceAtLeast(2), nowMillis)
    }

    private fun adjust(
        candidate: Long,
        afterMillis: Long,
        blockedWeekdays: Int,
        blockedShift: com.cupcakecomics.data.ReminderShiftDirection,
        settings: CupcakeSettings,
    ): Long {
        val shifted = applyBlockedWeekdayShift(candidate, blockedWeekdays, blockedShift, afterMillis)
        return applyQuietHoursDeferral(shifted, settings)
    }

    /**
     * Moves [timeMillis] off blocked weekdays in the preferred direction. An EARLIER
     * shift that would land in the past (<= [afterMillis]) falls back to shifting
     * later. If every day is blocked the candidate is left untouched.
     */
    fun applyBlockedWeekdayShift(
        timeMillis: Long,
        blockedWeekdays: Int,
        shift: com.cupcakecomics.data.ReminderShiftDirection,
        afterMillis: Long,
    ): Long {
        if (blockedWeekdays == 0 || !isBlocked(timeMillis, blockedWeekdays)) return timeMillis

        fun shiftDays(from: Long, deltaDays: Int): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = from }
            cal.add(Calendar.DAY_OF_MONTH, deltaDays)
            return cal.timeInMillis
        }

        fun sweep(direction: Int): Long? {
            var candidate = timeMillis
            repeat(6) {
                candidate = shiftDays(candidate, direction)
                if (!isBlocked(candidate, blockedWeekdays)) return candidate
            }
            return null
        }

        val preferredDir = if (shift == com.cupcakecomics.data.ReminderShiftDirection.EARLIER) -1 else 1
        val preferred = sweep(preferredDir)
        if (preferred != null && preferred > afterMillis) return preferred
        // Preferred direction failed or landed in the past — try the other way.
        return sweep(-preferredDir) ?: timeMillis
    }

    private fun isBlocked(timeMillis: Long, blockedWeekdays: Int): Boolean {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val bit = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
        return blockedWeekdays and bit != 0
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

    private fun nextInterval(afterMillis: Long, hour: Int, intervalDays: Int, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = maxOf(afterMillis, nowMillis) }
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.add(Calendar.DAY_OF_MONTH, intervalDays)
        while (cal.timeInMillis <= afterMillis) {
            cal.add(Calendar.DAY_OF_MONTH, intervalDays)
        }
        return cal.timeInMillis
    }
}
