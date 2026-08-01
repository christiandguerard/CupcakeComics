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

    companion object {
        private const val TEST_DB = "cupcake-migration-test"
        private const val TEST_DB_8 = "cupcake-migration-test-8"
    }
}
