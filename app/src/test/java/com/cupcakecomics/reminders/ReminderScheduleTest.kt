package com.cupcakecomics.reminders

import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.settings.CupcakeSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReminderScheduleTest {
    private fun settings(
        quietEnabled: Boolean = false,
        quietStart: Int = 22,
        quietEnd: Int = 8,
    ): CupcakeSettings {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        return CupcakeSettings(ctx).apply {
            quietHoursEnabled = quietEnabled
            quietHoursStartHour = quietStart
            quietHoursEndHour = quietEnd
        }
    }

    @Test
    fun daily_nextFire_isAfterNow() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0)
        val after = now.timeInMillis
        val next = ReminderSchedule.computeNextFire(
            afterMillis = after,
            frequency = ReminderFrequency.DAILY,
            hourOfDay = 20,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = after,
        )
        assertTrue(next > after)
        assertTrue(hourOf(next) == 20)
    }

    @Test
    fun weekly_nextFire_matchesWeekday() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0) // Friday
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.WEEKLY,
            hourOfDay = 9,
            dayOfWeek = Calendar.MONDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = now.timeInMillis,
        )
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertTrue(cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY)
        assertTrue(hourOf(next) == 9)
    }

    @Test
    fun monthly_clampsDayAtMonthEnd() {
        val now = calendar(2026, Calendar.JANUARY, 31, 12, 0)
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.MONTHLY,
            hourOfDay = 18,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 31,
            settings = settings(),
            nowMillis = now.timeInMillis,
        )
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertTrue(cal.get(Calendar.MONTH) == Calendar.FEBRUARY)
        assertTrue(cal.get(Calendar.DAY_OF_MONTH) == 28)
    }

    @Test
    fun quietHours_deferEveningFireToNextMorning() {
        val settings = settings(quietEnabled = true, quietStart = 22, quietEnd = 8)
        val fire = calendar(2026, Calendar.JULY, 17, 23, 0).timeInMillis
        val deferred = ReminderSchedule.applyQuietHoursDeferral(fire, settings)
        val cal = Calendar.getInstance().apply { timeInMillis = deferred }
        assertTrue(hourOf(deferred) == 8)
        assertTrue(cal.get(Calendar.DAY_OF_MONTH) == 18)
    }

    @Test
    fun quietHours_deferEarlyMorningToSameMorningEnd() {
        val settings = settings(quietEnabled = true, quietStart = 22, quietEnd = 8)
        val fire = calendar(2026, Calendar.JULY, 17, 2, 0).timeInMillis
        val deferred = ReminderSchedule.applyQuietHoursDeferral(fire, settings)
        assertTrue(hourOf(deferred) == 8)
        assertTrue(dayOf(deferred) == 17)
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun dayOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)
}
