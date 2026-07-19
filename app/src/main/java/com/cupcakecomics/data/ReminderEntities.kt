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
    val lastFiredAt: Long = 0L,
    val nextFireAt: Long = 0L,
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
