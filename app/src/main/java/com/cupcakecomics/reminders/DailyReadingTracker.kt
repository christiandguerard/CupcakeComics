package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.DailyReadingProgressEntity
import com.cupcakecomics.data.ReminderEntity
import java.util.Calendar
import java.util.Locale

/**
 * Counts forward-reading progress per book per local day for enabled book reminders
 * that carry a daily page goal (dailyPageGoal >= [MIN_GOAL]). When the goal is first
 * reached on a given day, [addPages] returns a [GoalMet] so the reader can show a
 * one-time, non-invasive banner. State lives in Room so progress survives restarts.
 */
class DailyReadingTracker internal constructor(
    private val db: CupcakeDatabase,
) {
    constructor(context: Context) : this(CupcakeDatabase.get(context.applicationContext))

    data class GoalMet(
        val title: String,
        val goal: Int,
        val pagesRead: Int,
    )

    /**
     * Adds [pages] newly-read pages for the book identified by any of [keys]
     * (identity keys and/or local file paths). Returns [GoalMet] exactly once per
     * book per day when the goal is crossed; null otherwise.
     */
    suspend fun addPages(
        keys: Set<String>,
        pages: Int,
        now: Long = System.currentTimeMillis(),
    ): GoalMet? {
        if (pages <= 0 || keys.isEmpty()) return null
        val reminder = findGoalReminder(keys) ?: return null
        val bookKey = canonicalKey(reminder)
        val today = dayString(now)
        val dao = db.dailyReadingProgressDao()
        val existing = dao.get(bookKey, today)
        val newCount = (existing?.pagesRead ?: 0) + pages
        val alreadyMet = (existing?.goalMetAt ?: 0L) > 0L
        val justMet = !alreadyMet && newCount >= reminder.dailyPageGoal
        dao.upsert(
            DailyReadingProgressEntity(
                bookKey = bookKey,
                day = today,
                pagesRead = newCount,
                goalMetAt = when {
                    alreadyMet -> existing!!.goalMetAt
                    justMet -> now
                    else -> 0L
                },
            ),
        )
        dao.pruneBefore(dayString(now - RETENTION_DAYS * DAY_MS))
        return if (justMet) GoalMet(reminder.title, reminder.dailyPageGoal, newCount) else null
    }

    /** Pages counted today for the goal reminder matching [keys]; 0 when untracked. */
    suspend fun pagesReadToday(keys: Set<String>, now: Long = System.currentTimeMillis()): Int {
        if (keys.isEmpty()) return 0
        val reminder = findGoalReminder(keys) ?: return 0
        return db.dailyReadingProgressDao().get(canonicalKey(reminder), dayString(now))?.pagesRead ?: 0
    }

    private suspend fun findGoalReminder(keys: Set<String>): ReminderEntity? {
        val candidates = db.reminderDao().getEnabledGoalReminders(MIN_GOAL)
        return candidates.firstOrNull { reminder -> matchKeys(reminder).any { it in keys } }
    }

    companion object {
        const val MIN_GOAL = 2
        private const val RETENTION_DAYS = 45L
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Keys a reader session may present for the book behind this reminder. */
        fun matchKeys(reminder: ReminderEntity): Set<String> = buildSet {
            reminder.identityKey?.takeIf { it.isNotBlank() }?.let { add(it) }
            reminder.localPath?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        fun canonicalKey(reminder: ReminderEntity): String =
            reminder.identityKey?.takeIf { it.isNotBlank() }
                ?: reminder.localPath?.takeIf { it.isNotBlank() }
                ?: "reminder:${reminder.id}"

        fun dayString(now: Long): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}
