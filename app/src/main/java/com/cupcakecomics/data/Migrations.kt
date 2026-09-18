package com.cupcakecomics.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every Room schema change MUST be paired with a migration here and registered in
 * [CupcakeDatabase]. There is intentionally no destructive-migration fallback: losing
 * connections, pull list entries, or reminders on an app update is not acceptable.
 * See docs/DATABASE_MIGRATIONS.md.
 */
object CupcakeMigrations {
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reminders ADD COLUMN dailyPageGoal INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE reminders ADD COLUMN notifyEnabled INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `daily_reading_progress` (
                    `bookKey` TEXT NOT NULL,
                    `day` TEXT NOT NULL,
                    `pagesRead` INTEGER NOT NULL,
                    `goalMetAt` INTEGER NOT NULL,
                    PRIMARY KEY(`bookKey`, `day`)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `download_jobs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `shareId` INTEGER NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `sourceKey` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `bytesDone` INTEGER NOT NULL,
                    `bytesTotal` INTEGER NOT NULL,
                    `error` TEXT,
                    `attempts` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_jobs_sourceKey` " +
                    "ON `download_jobs` (`sourceKey`)",
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reminders ADD COLUMN goalPages INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE reminders ADD COLUMN goalCadence TEXT NOT NULL DEFAULT 'DAILY'",
            )
            db.execSQL(
                "ALTER TABLE reminders ADD COLUMN totalPages INTEGER NOT NULL DEFAULT 0",
            )
            // Fold legacy progression modes into the goal model: page-a-day becomes
            // a 1 page/day goal, and per-day habit goals keep their page count.
            db.execSQL(
                "UPDATE reminders SET goalPages = 1, goalCadence = 'DAILY' " +
                    "WHERE type = 'BOOK' AND pageMode = 'PAGE_A_DAY'",
            )
            db.execSQL(
                "UPDATE reminders SET goalPages = dailyPageGoal, goalCadence = 'DAILY' " +
                    "WHERE type = 'BOOK' AND pageMode != 'PAGE_A_DAY' AND dailyPageGoal >= 2",
            )
            // Page-a-day always notified; preserve that now that notifyEnabled is sole switch.
            db.execSQL(
                "UPDATE reminders SET notifyEnabled = 1 " +
                    "WHERE type = 'BOOK' AND pageMode = 'PAGE_A_DAY'",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
}
