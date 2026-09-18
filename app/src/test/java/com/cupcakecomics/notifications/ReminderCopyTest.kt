package com.cupcakecomics.notifications

import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReminderCopyTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun bookReminder(
        title: String = "Saga",
        goalPages: Int = 0,
        goalCadence: ReminderFrequency = ReminderFrequency.DAILY,
        totalPages: Int = 0,
        id: Long = 1L,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.BOOK,
        bookSource = ReminderBookSource.PULL,
        title = title,
        identityKey = "smb:1:/saga.cbz",
        smbShareId = 1,
        smbRelativePath = "/saga.cbz",
        goalPages = goalPages,
        goalCadence = goalCadence,
        totalPages = totalPages,
    )

    @Test
    fun `goal body reports pages left in the window`() {
        val reminder = bookReminder(goalPages = 20, goalCadence = ReminderFrequency.WEEKLY)
        val body = CupcakeNotifications.bookReminderBody(app, reminder, page = 40, goalPagesRead = 8)
        assertEquals("12 pages left this week", body)
    }

    @Test
    fun `goal met body congratulates with window and count`() {
        val reminder = bookReminder(goalPages = 5)
        val body = CupcakeNotifications.bookReminderBody(app, reminder, page = 10, goalPagesRead = 7)
        assertEquals("Goal met today — 7 pages read", body)
    }

    @Test
    fun `no goal with known length reports page and pages left in book`() {
        val reminder = bookReminder(totalPages = 100)
        val body = CupcakeNotifications.bookReminderBody(app, reminder, page = 34, goalPagesRead = null)
        assertEquals("Continue at page 34 · 66 pages left", body)
    }

    @Test
    fun `no goal without length falls back to plain resume copy`() {
        val reminder = bookReminder()
        val body = CupcakeNotifications.bookReminderBody(app, reminder, page = 34, goalPagesRead = null)
        assertEquals("Continue at page 34", body)
    }

    @Test
    fun `titles never expose the raw file name`() {
        val reminder = bookReminder(title = "Absolute Batman 022 (2024) (Digital).cbz")
        val short = CupcakeNotifications.shortBookTitle(reminder)
        assertEquals("Absolute Batman - #22", short)
        assertFalse(short.contains(".cbz"))
    }

    @Test
    fun `already short titles pass through unchanged`() {
        val reminder = bookReminder(title = "Saga")
        assertEquals("Saga", CupcakeNotifications.shortBookTitle(reminder))
    }

    @Test
    fun `blank titles fall back to the relative path, shortened`() {
        val reminder = bookReminder(title = "").copy(smbRelativePath = "/Saga 022 (2022).cbz")
        assertEquals("Saga - #22", CupcakeNotifications.shortBookTitle(reminder))
    }

    @Test
    fun `notification ids never collide across reminder ids`() {
        assertNotEquals(
            CupcakeNotifications.bookNotifId(1),
            CupcakeNotifications.bookNotifId(1001),
        )
        assertTrue(CupcakeNotifications.bookNotifId(1) != CupcakeNotifications.bookNotifId(2))
    }
}
