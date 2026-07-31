package com.cupcakecomics.reminders

import androidx.room.Room
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
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
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class DailyReadingTrackerTest {
    private lateinit var db: CupcakeDatabase
    private lateinit var tracker: DailyReadingTracker

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CupcakeDatabase::class.java,
        ).allowMainThreadQueries().build()
        tracker = DailyReadingTracker(db)
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
        // Further reading the same day does not re-fire.
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 5, DAY_ONE))
        assertEquals(9, tracker.pagesReadToday(setOf("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `no event below the goal`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 5)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_ONE))
        assertEquals(4, tracker.pagesReadToday(setOf("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `day rollover resets counting and allows a new banner`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 3)
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 3, DAY_ONE))
        // Next local day: prior day still counts separately, banner can fire again.
        assertEquals(0, tracker.pagesReadToday(setOf("smb:1:/saga.cbz"), DAY_TWO))
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 1, DAY_TWO))
        assertNotNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 2, DAY_TWO))
        assertEquals(3, tracker.pagesReadToday(setOf("smb:1:/saga.cbz"), DAY_ONE))
    }

    @Test
    fun `disabled reminders are ignored`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 2, enabled = false)
        assertNull(tracker.addPages(setOf("smb:1:/saga.cbz"), 10, DAY_ONE))
    }

    @Test
    fun `goals below the minimum are ignored`() = runBlocking {
        insertGoalReminder(identityKey = "smb:1:/saga.cbz", goal = 1)
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
        assertEquals(0, tracker.pagesReadToday(setOf("smb:9:/other.cbz"), DAY_ONE))
    }

    private suspend fun insertGoalReminder(
        identityKey: String?,
        localPath: String? = null,
        source: ReminderBookSource = ReminderBookSource.PULL,
        goal: Int,
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
                dailyPageGoal = goal,
            ),
        )
    }

    companion object {
        private fun noonUtc(year: Int, month: Int, day: Int): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(year, month, day, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private val DAY_ONE = noonUtc(2026, Calendar.JULY, 31)
        private val DAY_TWO = noonUtc(2026, Calendar.AUGUST, 1)
    }
}
