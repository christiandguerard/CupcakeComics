package com.cupcakecomics.reminders

import androidx.room.Room
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class GoalProgressTrackerTest {
    private lateinit var db: CupcakeDatabase
    private lateinit var tracker: GoalProgressTracker

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US) // Sunday-first weeks
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CupcakeDatabase::class.java,
        ).allowMainThreadQueries().build()
        tracker = GoalProgressTracker(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `goal met fires once when crossing the goal`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 3)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE))
        val met = tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE)
        assertNotNull(met)
        assertEquals(3, met!!.goal)
        assertEquals(4, met.pagesRead)
        assertEquals(ReminderFrequency.DAILY, met.cadence)
        // Further reading the same window does not re-fire.
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 5, DAY_ONE))
        assertEquals(9, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `no event below the goal`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 5)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE))
        assertEquals(4, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_ONE))
        assertEquals(1, tracker.pagesLeftInWindow(getReminder("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `daily rollover resets counting and allows a new banner`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 3)
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 3, DAY_ONE))
        // Next local day: prior day still counts separately, banner can fire again.
        assertEquals(0, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_TWO))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_TWO))
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_TWO))
        assertEquals(3, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `weekly goal accumulates across days and fires once per week`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 5, cadence = ReminderFrequency.WEEKLY)
        // Friday and Saturday are the same Sunday-first week.
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 3, DAY_ONE)) // Fri Jul 31
        val met = tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_TWO) // Sat Aug 1
        assertNotNull(met)
        assertEquals(ReminderFrequency.WEEKLY, met!!.cadence)
        assertEquals(5, met.pagesRead)
        // Same week: no second banner.
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 4, DAY_TWO))
        // Sunday Aug 2 starts a new week: counting resets, banner can fire again.
        assertEquals(0, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_THREE))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 4, DAY_THREE))
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_THREE))
    }

    @Test
    fun `monthly goal spans the whole month`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 10, cadence = ReminderFrequency.MONTHLY)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 4, DAY_TWO)) // Aug 1
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 4, DAY_THREE)) // Aug 2
        assertEquals(8, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_THREE))
        assertEquals(2, tracker.pagesLeftInWindow(getReminder("smb:1:/saga.cbz"), DAY_THREE))
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_THREE))
    }

    @Test
    fun `disabled reminders are ignored`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 2, enabled = false)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 10, DAY_ONE))
    }

    @Test
    fun `reminders without a goal are ignored`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 0)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 10, DAY_ONE))
    }

    @Test
    fun `library books match by file path`() = runBlocking {
        insertGoalReminder(
            identityKey = null,
            localPath = "/storage/comics/saga.cbz",
            source = ReminderBookSource.LIBRARY,
            goal = 2,
        )
        val met = tracker.addPages(setOf("/storage/comics/saga.cbz"), 2, DAY_ONE)
        assertNotNull(met)
    }

    @Test
    fun `unrelated books are not tracked`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 2)
        assertNull(tracker.addPages(setOf("smb:9:/other.cbz"), 10, DAY_ONE))
        assertEquals(0, tracker.pagesReadInWindow(getReminder("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `clearing goal flags re-arms the banner within the same window`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 3)
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 3, DAY_ONE))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_ONE))

        // User raises the goal: the old "met" flag must not suppress a new banner.
        val before = getReminder("smb:1:/saga.cbz")
        db.reminderDao().upsert(before.copy(goalPages = 6))
        val after = getReminder("smb:1:/saga.cbz")
        tracker.clearGoalMetFlags(before, after, DAY_ONE)

        val met = tracker.addPages(setOf("smb:1:/saga.cbz"), 3, DAY_ONE)
        assertNotNull(met)
        assertEquals(6, met!!.goal)
        assertEquals(7, met.pagesRead)
    }

    @Test
    fun `clearing goal flags covers a cadence switch window`() = runBlocking {
        // Daily goal met Friday; user switches to a weekly goal the same day.
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 2, cadence = ReminderFrequency.DAILY)
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE)) // Fri Jul 31

        val before = getReminder("smb:1:/saga.cbz")
        db.reminderDao().upsert(before.copy(goalPages = 4, goalCadence = ReminderFrequency.WEEKLY))
        val after = getReminder("smb:1:/saga.cbz")
        tracker.clearGoalMetFlags(before, after, DAY_ONE)

        // Weekly window (Sun Jul 26 – Sat Aug 1) includes Friday's 2 pages.
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_TWO))
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_TWO))
    }

    private suspend fun insertGoalReminder(
        identityKey: String?,
        localPath: String? = null,
        source: ReminderBookSource = ReminderBookSource.PULL,
        goal: Int,
        cadence: ReminderFrequency = ReminderFrequency.DAILY,
        enabled: Boolean = true,
    ) {
        db.reminderDao().upsert(
            ReminderEntity(
                enabled = enabled,
                type = ReminderType.BOOK,
                bookSource = source,
                title = "Test Book",
                identityKey = identityKey,
                localPath = localPath,
                goalPages = goal,
                goalCadence = cadence,
            ),
        )
    }

    private suspend fun getReminder(identityKey: String): ReminderEntity =
        db.reminderDao().getAll().first { it.identityKey == identityKey }

    companion object {
        private fun noonUtc(year: Int, month: Int, day: Int): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(year, month, day, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private val DAY_ONE = noonUtc(2026, Calendar.JULY, 31) // Friday
        private val DAY_TWO = noonUtc(2026, Calendar.AUGUST, 1) // Saturday
        private val DAY_THREE = noonUtc(2026, Calendar.AUGUST, 2) // Sunday (new week)
    }
}
