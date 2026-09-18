package com.cupcakecomics.reminders

import com.cupcakecomics.data.ReminderFrequency
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GoalWindowTest {

    private fun utcCalendar(firstDayOfWeek: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            this.firstDayOfWeek = firstDayOfWeek
        }

    private fun noonUtc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `daily window starts on the same day`() {
        val now = noonUtc(2026, Calendar.AUGUST, 5) // Wednesday
        assertEquals(
            "2026-08-05",
            GoalWindow.windowStartDay(ReminderFrequency.DAILY, now, utcCalendar(Calendar.SUNDAY)),
        )
    }

    @Test
    fun `weekly window starts on locale first day of week`() {
        val wednesday = noonUtc(2026, Calendar.AUGUST, 5)
        // US-style week: Sunday 2026-08-02
        assertEquals(
            "2026-08-02",
            GoalWindow.windowStartDay(ReminderFrequency.WEEKLY, wednesday, utcCalendar(Calendar.SUNDAY)),
        )
        // Monday-first week: Monday 2026-08-03
        assertEquals(
            "2026-08-03",
            GoalWindow.windowStartDay(ReminderFrequency.WEEKLY, wednesday, utcCalendar(Calendar.MONDAY)),
        )
    }

    @Test
    fun `weekly window on the first day itself starts that day`() {
        val sunday = noonUtc(2026, Calendar.AUGUST, 2)
        assertEquals(
            "2026-08-02",
            GoalWindow.windowStartDay(ReminderFrequency.WEEKLY, sunday, utcCalendar(Calendar.SUNDAY)),
        )
    }

    @Test
    fun `monthly window starts on the first of the month`() {
        val now = noonUtc(2026, Calendar.AUGUST, 18)
        assertEquals(
            "2026-08-01",
            GoalWindow.windowStartDay(ReminderFrequency.MONTHLY, now, utcCalendar(Calendar.SUNDAY)),
        )
    }

    @Test
    fun `window labels are distinct per cadence`() {
        val labels = ReminderFrequency.entries.map { GoalWindow.windowLabelRes(it) }
        assertEquals(labels.size, labels.distinct().size)
        val per = ReminderFrequency.entries.map { GoalWindow.perLabelRes(it) }
        assertEquals(per.size, per.distinct().size)
    }
}
