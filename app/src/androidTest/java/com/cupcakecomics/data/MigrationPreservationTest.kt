package com.cupcakecomics.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device counterpart to the Robolectric migration test: creates a real schema-v7
 * cupcake.db via the exported schema JSONs (bundled as androidTest assets) and runs
 * the production migration path, verifying user data survives.
 */
@RunWith(AndroidJUnit4::class)
class MigrationPreservationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CupcakeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate7To8PreservesReminders() {
        val db = helper.createDatabase(TEST_DB, 7)
        db.execSQL(
            "INSERT INTO reminders VALUES " +
                "(1, 1, 'BOOK', 'DAILY', 20, 1, 1, 'PULL', 'Saga', 'smb:1:/saga.cbz', " +
                "0, NULL, 1, '/saga.cbz', 'RESUME', 1, 7, 0, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            CupcakeMigrations.MIGRATION_7_8,
        )
        migrated.query("SELECT title, trackedPage, dailyPageGoal, notifyEnabled FROM reminders WHERE id = 1")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Saga", cursor.getString(0))
                assertEquals(7, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
            }
        migrated.query("SELECT COUNT(*) FROM daily_reading_progress").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate8To9AddsDownloadQueue() {
        val db = helper.createDatabase(TEST_DB_8, 8)
        db.execSQL(
            "INSERT INTO daily_reading_progress VALUES ('smb:1:/saga.cbz', '2026-08-01', 4, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_8,
            9,
            true,
            CupcakeMigrations.MIGRATION_8_9,
        )
        migrated.query("SELECT COUNT(*) FROM download_jobs").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT pagesRead FROM daily_reading_progress WHERE bookKey = 'smb:1:/saga.cbz'")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
        migrated.close()
    }

    @Test
    fun migrate9To10FoldsLegacyProgressionIntoGoals() {
        val db = helper.createDatabase(TEST_DB_9, 9)
        // Page-a-day book reminder with notifications off — must become a 1 page/day
        // goal and regain notifications (page-a-day always notified).
        db.execSQL(
            "INSERT INTO reminders VALUES " +
                "(1, 1, 'BOOK', 'DAILY', 20, 1, 1, 'PULL', 'Page A Day', 'smb:1:/pad.cbz', " +
                "0, NULL, 1, '/pad.cbz', 'PAGE_A_DAY', 4, 2, 0, 0, 0, 0)",
        )
        // Daily habit goal keeps its page count.
        db.execSQL(
            "INSERT INTO reminders VALUES " +
                "(2, 1, 'BOOK', 'DAILY', 20, 1, 1, 'LOCAL', 'Habit', NULL, " +
                "0, '/books/habit.cbz', 0, NULL, 'RESUME', 1, 3, 5, 0, 0, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_9,
            10,
            true,
            CupcakeMigrations.MIGRATION_9_10,
        )
        migrated.query("SELECT goalPages, goalCadence, notifyEnabled, totalPages FROM reminders WHERE id = 1")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("DAILY", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }
        migrated.query("SELECT goalPages, goalCadence, notifyEnabled FROM reminders WHERE id = 2")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
                assertEquals("DAILY", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
        migrated.close()
    }

    @Test
    fun migrate10To11AddsIntervalColumns() {
        val db = helper.createDatabase(TEST_DB_10, 10)
        // v10 columns: …, dailyPageGoal, notifyEnabled, goalPages, goalCadence,
        // totalPages, lastFiredAt, nextFireAt
        db.execSQL(
            "INSERT INTO reminders VALUES " +
                "(1, 1, 'BOOK', 'DAILY', 20, 1, 1, 'PULL', 'Saga', 'smb:1:/saga.cbz', " +
                "0, NULL, 1, '/saga.cbz', 'RESUME', 1, 7, 0, 1, 0, 'DAILY', 22, 0, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_10,
            11,
            true,
            CupcakeMigrations.MIGRATION_10_11,
        )
        migrated.query("SELECT goalPages, totalPages, intervalDays, blockedWeekdays, blockedShift FROM reminders WHERE id = 1")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(22, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("LATER", cursor.getString(4))
            }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "cupcake-migration-test"
        private const val TEST_DB_8 = "cupcake-migration-test-8"
        private const val TEST_DB_9 = "cupcake-migration-test-9"
        private const val TEST_DB_10 = "cupcake-migration-test-10"
    }
}
