package com.cupcakecomics.reminders

import com.cupcakecomics.data.ReminderFrequency
import com.nkanaev.comics.R
import java.util.Calendar

/**
 * Calendar math for goal windows (day / week / month). Pure and timezone-injectable
 * so unit tests can pin both wall-clock and locale week starts.
 */
object GoalWindow {

    /** First day (yyyy-MM-dd, local) of the window containing [nowMillis]. */
    fun windowStartDay(
        cadence: ReminderFrequency,
        nowMillis: Long,
        calendar: Calendar = Calendar.getInstance(),
    ): String {
        val cal = (calendar.clone() as Calendar).apply { timeInMillis = nowMillis }
        return when (cadence) {
            // Goal windows are day/week/month; INTERVAL only exists for fire schedules.
            ReminderFrequency.DAILY, ReminderFrequency.INTERVAL -> dayString(cal)
            ReminderFrequency.WEEKLY -> {
                val first = cal.firstDayOfWeek
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                while (cal.get(Calendar.DAY_OF_WEEK) != first) {
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                dayString(cal)
            }
            ReminderFrequency.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                dayString(cal)
            }
        }
    }

    /** String resource for the window word used in copy ("today" / "this week" / "this month"). */
    fun windowLabelRes(cadence: ReminderFrequency): Int = when (cadence) {
        ReminderFrequency.DAILY, ReminderFrequency.INTERVAL -> R.string.reminder_window_today
        ReminderFrequency.WEEKLY -> R.string.reminder_window_this_week
        ReminderFrequency.MONTHLY -> R.string.reminder_window_this_month
    }

    /** Short "per X" label for goal summaries and badges. */
    fun perLabelRes(cadence: ReminderFrequency): Int = when (cadence) {
        ReminderFrequency.DAILY, ReminderFrequency.INTERVAL -> R.string.reminder_goal_per_day
        ReminderFrequency.WEEKLY -> R.string.reminder_goal_per_week
        ReminderFrequency.MONTHLY -> R.string.reminder_goal_per_month
    }

    private fun dayString(cal: Calendar): String = String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}
