package com.cupcakecomics.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Builds a faithful schema-v7 database (DDL copied from the committed schema export
 * `app/schemas/com.cupcakecomics.data.CupcakeDatabase/7.json`), seeds user data, then
 * opens it through Room. Room runs [CupcakeMigrations.MIGRATION_7_8] and its own
 * structural validation, so this fails if the migration drops data or produces a
 * schema Room does not expect.
 */
@RunWith(RobolectricTestRunner::class)
class CupcakeMigrationTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("cupcake-v7", ".db")
        dbFile.deleteOnExit()
        createV7Database(dbFile)
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `migration 7 to 8 preserves user data and passes Room validation`() = runBlocking {
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            CupcakeDatabase::class.java,
            dbFile.absolutePath,
        )
            .addMigrations(*CupcakeMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        // Forces open: runs the migration chain and Room's schema validation.
        db.openHelper.writableDatabase

        val reminders = db.reminderDao().getAll()
        assertEquals(2, reminders.size)
        val book = reminders.first { it.type == ReminderType.BOOK }
        assertEquals("Saga", book.title)
        assertEquals("smb:1:/saga.cbz", book.identityKey)
        assertEquals(7, book.trackedPage)
        assertEquals(20, book.hourOfDay)
        // New columns get safe defaults on existing rows.
        assertEquals(0, book.dailyPageGoal)
        assertTrue(book.notifyEnabled)

        // The new table exists and works.
        db.dailyReadingProgressDao().upsert(
            DailyReadingProgressEntity("smb:1:/saga.cbz", "2026-07-31", pagesRead = 3, goalMetAt = 0L),
        )
        assertEquals(
            3,
            db.dailyReadingProgressDao().get("smb:1:/saga.cbz", "2026-07-31")?.pagesRead,
        )
        db.close()
    }

    private fun createV7Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            V7_DDL.forEach { db.execSQL(it) }
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(1, 1, 'BOOK', 'DAILY', 20, 1, 1, 'PULL', 'Saga', 'smb:1:/saga.cbz', " +
                    "0, NULL, 1, '/saga.cbz', 'RESUME', 1, 7, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO reminders VALUES " +
                    "(2, 1, 'PULL_LIST', 'WEEKLY', 9, 2, 1, NULL, '', NULL, 0, NULL, 0, NULL, " +
                    "'RESUME', 1, 1, 0, 0)",
            )
            db.version = 7
        } finally {
            db.close()
        }
    }

    companion object {
        private val V7_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `smb_shares` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `shareName` TEXT NOT NULL, `startPath` TEXT NOT NULL, `domain` TEXT NOT NULL, `username` TEXT NOT NULL, `credentialKey` TEXT NOT NULL, `useGuest` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `comicCount` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `statsUpdatedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `kapowarr_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiKeyCredentialKey` TEXT NOT NULL, `lanHttpAcknowledged` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `offline_comics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `downloadedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `read_marks` (`identityKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceDetail` TEXT NOT NULL, `markedReadAt` INTEGER NOT NULL, PRIMARY KEY(`identityKey`))",
            "CREATE TABLE IF NOT EXISTS `monitored_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shareId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `enrolledAt` INTEGER NOT NULL, `baselined` INTEGER NOT NULL, `comicvineId` INTEGER, `kapowarrVolumeId` INTEGER, `seriesStatus` TEXT NOT NULL, `lastReleaseAt` INTEGER, `nextReleaseAt` INTEGER, `typicalGapDays` INTEGER, `accentColor` INTEGER NOT NULL, `metadataUpdatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_monitored_folders_shareId_relativePath` ON `monitored_folders` (`shareId`, `relativePath`)",
            "CREATE TABLE IF NOT EXISTS `pull_comics` (`identityKey` TEXT NOT NULL, `shareId` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `title` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `inPullList` INTEGER NOT NULL, `missing` INTEGER NOT NULL, `highestPage` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `markedReadManually` INTEGER NOT NULL, PRIMARY KEY(`identityKey`))",
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `enabled` INTEGER NOT NULL, `type` TEXT NOT NULL, `frequency` TEXT NOT NULL, `hourOfDay` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `bookSource` TEXT, `title` TEXT NOT NULL, `identityKey` TEXT, `libraryComicId` INTEGER NOT NULL, `localPath` TEXT, `smbShareId` INTEGER NOT NULL, `smbRelativePath` TEXT, `pageMode` TEXT NOT NULL, `pageADayIndex` INTEGER NOT NULL, `trackedPage` INTEGER NOT NULL, `lastFiredAt` INTEGER NOT NULL, `nextFireAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `local_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `localPath` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `contentUri` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_files_sourceKey` ON `local_files` (`sourceKey`)",
        )
    }
}
