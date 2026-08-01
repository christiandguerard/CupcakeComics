package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ReminderType { PULL_LIST, BOOK }

enum class ReminderFrequency { DAILY, WEEKLY, MONTHLY }

enum class ReminderBookSource { LIBRARY, PULL, LOCAL }

/** Page-a-day advances each fire; Resume opens at stored reading progress. */
enum class ReminderPageMode { PAGE_A_DAY, RESUME }

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    val type: ReminderType,
    val frequency: ReminderFrequency = ReminderFrequency.DAILY,
    /** Hour of day 0–23 when the reminder should fire. */
    val hourOfDay: Int = 20,
    /** [Calendar.DAY_OF_WEEK] for weekly reminders (1 = Sunday … 7 = Saturday). */
    val dayOfWeek: Int = CalendarCompat.SUNDAY,
    /** Day of month 1–28 for monthly reminders. */
    val dayOfMonth: Int = 1,
    // Book reminder fields (ignored for PULL_LIST)
    val bookSource: ReminderBookSource? = null,
    val title: String = "",
    val identityKey: String? = null,
    val libraryComicId: Int = 0,
    val localPath: String? = null,
    val smbShareId: Long = 0,
    val smbRelativePath: String? = null,
    val pageMode: ReminderPageMode = ReminderPageMode.RESUME,
    /** 1-based page index for page-a-day mode. */
    val pageADayIndex: Int = 1,
    /** 1-based tracked progress for local-file resume mode. */
    val trackedPage: Int = 1,
    /**
     * Optional daily habit goal in pages. Values >= 2 enable per-day page counting
     * and a one-time in-reader banner when the goal is met. 0/1 = no goal.
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val dailyPageGoal: Int = 0,
    /** When false no status-bar reminder is scheduled; goal tracking still applies. */
    @androidx.room.ColumnInfo(defaultValue = "1")
    val notifyEnabled: Boolean = true,
    val lastFiredAt: Long = 0L,
    val nextFireAt: Long = 0L,
) {
    /** Page-a-day reminders need their fire to advance the page, so they always notify. */
    fun effectiveNotify(): Boolean = notifyEnabled || pageMode == ReminderPageMode.PAGE_A_DAY
}

@Entity(tableName = "daily_reading_progress", primaryKeys = ["bookKey", "day"])
data class DailyReadingProgressEntity(
    /** Canonical book key: reminder identityKey, else localPath, else "reminder:{id}". */
    val bookKey: String,
    /** Local calendar day, yyyy-MM-dd. */
    val day: String,
    val pagesRead: Int = 0,
    /** Epoch millis when the daily goal banner fired; 0 = not yet met today. */
    val goalMetAt: Long = 0L,
)

/** Calendar constants without importing java.util.Calendar in entity defaults at compile time issues. */
object CalendarCompat {
    const val SUNDAY = 1
    const val MONDAY = 2
    const val TUESDAY = 3
    const val WEDNESDAY = 4
    const val THURSDAY = 5
    const val FRIDAY = 6
    const val SATURDAY = 7
}
