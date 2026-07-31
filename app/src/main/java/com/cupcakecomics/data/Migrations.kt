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

    val ALL: Array<Migration> = arrayOf(MIGRATION_7_8)
}
