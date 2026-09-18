package com.cupcakecomics.reminders

import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderShiftDirection
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

    @Test
    fun interval_firesNDaysLaterAtHour() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0) // Friday
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.INTERVAL,
            hourOfDay = 9,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = now.timeInMillis,
            intervalDays = 3,
        )
        // Friday + 3 days = Monday July 20 at 9:00.
        assertTrue(dayOfWeekOf(next) == Calendar.MONDAY)
        assertTrue(dayOf(next) == 20)
        assertTrue(hourOf(next) == 9)
    }

    @Test
    fun interval_blockedWeekdayShiftsLater() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0) // Friday
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.INTERVAL,
            hourOfDay = 9,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = now.timeInMillis,
            intervalDays = 3,
            blockedWeekdays = weekdayMask(Calendar.MONDAY),
            blockedShift = ReminderShiftDirection.LATER,
        )
        // Monday is blocked → Tuesday July 21.
        assertTrue(dayOfWeekOf(next) == Calendar.TUESDAY)
        assertTrue(dayOf(next) == 21)
    }

    @Test
    fun interval_blockedWeekdayShiftsEarlier() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0) // Friday
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.INTERVAL,
            hourOfDay = 9,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = now.timeInMillis,
            intervalDays = 3,
            blockedWeekdays = weekdayMask(Calendar.MONDAY),
            blockedShift = ReminderShiftDirection.EARLIER,
        )
        // Monday is blocked → Sunday July 19.
        assertTrue(dayOfWeekOf(next) == Calendar.SUNDAY)
        assertTrue(dayOf(next) == 19)
    }

    @Test
    fun interval_earlierShiftNeverLandsInThePast() {
        // Anchor Sunday 19:00; every 2 days → Tuesday 9:00. Block Tuesday and Monday:
        // earlier would be Sunday 9:00, which is before the anchor — must go later.
        val after = calendar(2026, Calendar.JULY, 19, 19, 0) // Sunday evening
        val next = ReminderSchedule.computeNextFire(
            afterMillis = after.timeInMillis,
            frequency = ReminderFrequency.INTERVAL,
            hourOfDay = 9,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = after.timeInMillis,
            intervalDays = 2,
            blockedWeekdays = weekdayMask(Calendar.TUESDAY, Calendar.MONDAY),
            blockedShift = ReminderShiftDirection.EARLIER,
        )
        assertTrue(dayOfWeekOf(next) == Calendar.WEDNESDAY)
        assertTrue(dayOf(next) == 22)
        assertTrue(next > after.timeInMillis)
    }

    @Test
    fun interval_allDaysBlockedLeavesCandidateAlone() {
        val now = calendar(2026, Calendar.JULY, 17, 10, 0) // Friday
        val next = ReminderSchedule.computeNextFire(
            afterMillis = now.timeInMillis,
            frequency = ReminderFrequency.INTERVAL,
            hourOfDay = 9,
            dayOfWeek = Calendar.SUNDAY,
            dayOfMonth = 1,
            settings = settings(),
            nowMillis = now.timeInMillis,
            intervalDays = 3,
            blockedWeekdays = 0x7F,
            blockedShift = ReminderShiftDirection.LATER,
        )
        // Nowhere to shift — the unshifted Monday fire stands.
        assertTrue(dayOfWeekOf(next) == Calendar.MONDAY)
        assertTrue(dayOf(next) == 20)
    }

    private fun weekdayMask(vararg days: Int): Int =
        days.fold(0) { mask, day -> mask or (1 shl (day - 1)) }

    private fun dayOfWeekOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)

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
