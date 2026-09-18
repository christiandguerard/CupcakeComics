package com.cupcakecomics.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Builds a faithful schema-v9 database (DDL copied from the committed schema export
 * `app/schemas/com.cupcakecomics.data.CupcakeDatabase/9.json`), seeds reminders that
 * exercise every legacy progression mode, then opens it through Room so
 * [CupcakeMigrations.MIGRATION_9_10] runs under Room's structural validation.
 */
@RunWith(RobolectricTestRunner::class)
class Migration9to10Test {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("cupcake-v9", ".db")
        dbFile.deleteOnExit()
        createV9Database(dbFile)
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `migration 9 to 10 folds legacy progression into the goal model`() = runBlocking {
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            CupcakeDatabase::class.java,
            dbFile.absolutePath,
        )
            .addMigrations(*CupcakeMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        val reminders = db.reminderDao().getAll()
        assertEquals(4, reminders.size)

        // Page-a-day becomes a 1 page/day goal and is forced back to notifying.
        val pageADay = reminders.first { it.title == "Page A Day" }
        assertEquals(1, pageADay.goalPages)
        assertEquals(ReminderFrequency.DAILY, pageADay.goalCadence)
        assertTrue(pageADay.notifyEnabled)
        assertEquals(0, pageADay.totalPages)

        // A daily habit goal keeps its page count as a daily goal.
        val habit = reminders.first { it.title == "Habit" }
        assertEquals(5, habit.goalPages)
        assertEquals(ReminderFrequency.DAILY, habit.goalCadence)
        assertFalse(habit.notifyEnabled)

        // Plain resume reminders stay goal-less.
        val plain = reminders.first { it.title == "Plain" }
        assertEquals(0, plain.goalPages)
        assertEquals(ReminderFrequency.DAILY, plain.goalCadence)
        assertEquals(0, plain.totalPages)

        // Pull List reminders are untouched.
        val pull = reminders.first { it.type == ReminderType.PULL_LIST }
        assertEquals(0, pull.goalPages)
        db.close()
    }

    private fun createV9Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            V9_DDL.forEach { db.execSQL(it) }
            // Columns: id, enabled, type, frequency, hourOfDay, dayOfWeek, dayOfMonth,
            // bookSource, title, identityKey, libraryComicId, localPath, smbShareId,
            // smbRelativePath, pageMode, pageADayIndex, trackedPage, dailyPageGoal,
            // notifyEnabled, lastFiredAt, nextFireAt
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(1, 1, 'BOOK', 'DAILY', 20, 1, 1, 'PULL', 'Page A Day', 'smb:1:/pad.cbz', " +
                    "0, NULL, 1, '/pad.cbz', 'PAGE_A_DAY', 4, 2, 0, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(2, 1, 'BOOK', 'DAILY', 20, 1, 1, 'LOCAL', 'Habit', NULL, " +
                    "0, '/books/habit.cbz', 0, NULL, 'RESUME', 1, 3, 5, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(3, 1, 'BOOK', 'WEEKLY', 9, 2, 1, 'LIBRARY', 'Plain', NULL, " +
                    "7, NULL, 0, NULL, 'RESUME', 1, 1, 0, 1, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(4, 1, 'PULL_LIST', 'WEEKLY', 9, 2, 1, NULL, '', NULL, 0, NULL, 0, NULL, " +
                    "'RESUME', 1, 1, 0, 1, 0, 0)",
            )
            db.version = 9
        } finally {
            db.close()
        }
    }

    companion object {
        private val V9_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `smb_shares` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `shareName` TEXT NOT NULL, `startPath` TEXT NOT NULL, `domain` TEXT NOT NULL, `username` TEXT NOT NULL, `credentialKey` TEXT NOT NULL, `useGuest` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `comicCount` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `statsUpdatedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `kapowarr_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiKeyCredentialKey` TEXT NOT NULL, `lanHttpAcknowledged` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `offline_comics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `downloadedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `read_marks` (`identityKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceDetail` TEXT NOT NULL, `markedReadAt` INTEGER NOT NULL, PRIMARY KEY(`identityKey`))",
            "CREATE TABLE IF NOT EXISTS `monitored_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shareId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `enrolledAt` INTEGER NOT NULL, `baselined` INTEGER NOT NULL, `comicvineId` INTEGER, `kapowarrVolumeId` INTEGER, `seriesStatus` TEXT NOT NULL, `lastReleaseAt` INTEGER, `nextReleaseAt` INTEGER, `typicalGapDays` INTEGER, `accentColor` INTEGER NOT NULL, `metadataUpdatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_monitored_folders_shareId_relativePath` ON `monitored_folders` (`shareId`, `relativePath`)",
            "CREATE TABLE IF NOT EXISTS `pull_comics` (`identityKey` TEXT NOT NULL, `shareId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `title` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `inPullList` INTEGER NOT NULL, `missing` INTEGER NOT NULL, `highestPage` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `markedReadManually` INTEGER NOT NULL, PRIMARY KEY(`identityKey`))",
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `enabled` INTEGER NOT NULL, `type` TEXT NOT NULL, `frequency` TEXT NOT NULL, `hourOfDay` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `bookSource` TEXT, `title` TEXT NOT NULL, `identityKey` TEXT, `libraryComicId` INTEGER NOT NULL, `localPath` TEXT, `smbShareId` INTEGER NOT NULL, `smbRelativePath` TEXT, `pageMode` TEXT NOT NULL, `pageADayIndex` INTEGER NOT NULL, `trackedPage` INTEGER NOT NULL, `dailyPageGoal` INTEGER NOT NULL DEFAULT 0, `notifyEnabled` INTEGER NOT NULL DEFAULT 1, `lastFiredAt` INTEGER NOT NULL, `nextFireAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `local_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `contentUri` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_files_sourceKey` ON `local_files` (`sourceKey`)",
            "CREATE TABLE IF NOT EXISTS `daily_reading_progress` (`bookKey` TEXT NOT NULL, `day` TEXT NOT NULL, `pagesRead` INTEGER NOT NULL, `goalMetAt` INTEGER NOT NULL, PRIMARY KEY(`bookKey`, `day`))",
            "CREATE TABLE IF NOT EXISTS `download_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shareId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `title` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `status` TEXT NOT NULL, `bytesDone` INTEGER NOT NULL, `bytesTotal` INTEGER NOT NULL, `error` TEXT, `attempts` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_jobs_sourceKey` ON `download_jobs` (`sourceKey`)",
        )
    }
}
