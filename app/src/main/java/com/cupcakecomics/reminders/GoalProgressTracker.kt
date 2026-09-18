package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.DailyReadingProgressEntity
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import java.util.Calendar
import java.util.Locale

/**
 * Counts forward-reading progress per book for enabled book reminders that carry a
 * page goal ([ReminderEntity.goalPages] >= [MIN_GOAL]). Progress accumulates over the
 * goal's cadence window (day / week / month, see [GoalWindow]); when the goal is
 * first reached inside a window, [addPages] returns a [GoalMet] so the reader can
 * show a one-time, non-invasive banner. State lives in Room so progress survives
 * restarts; rows are per local day and window sums are calendar-aligned, so window
 * rollover needs no bookkeeping.
 */
class GoalProgressTracker internal constructor(
    private val db: CupcakeDatabase,
) {
    constructor(context: Context) : this(CupcakeDatabase.get(context.applicationContext))

    data class GoalMet(
        val title: String,
        val goal: Int,
        val pagesRead: Int,
        val cadence: ReminderFrequency,
    )

    /**
     * Adds [pages] newly-read pages for the book identified by any of [keys]
     * (identity keys and/or local file paths). Returns [GoalMet] exactly once per
     * book per goal window when the goal is crossed; null otherwise.
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
        val windowStart = GoalWindow.windowStartDay(reminder.goalCadence, now)
        val dao = db.dailyReadingProgressDao()

        val existing = dao.get(bookKey, today)
        val newTodayCount = (existing?.pagesRead ?: 0) + pages
        dao.upsert(
            DailyReadingProgressEntity(
                bookKey = bookKey,
                day = today,
                pagesRead = newTodayCount,
                // Written transactionally with the row so the banner fires once per window.
                goalMetAt = existing?.goalMetAt ?: 0L,
            ),
        )

        val windowTotal = dao.sumPages(bookKey, windowStart, today)
        val alreadyMetInWindow = dao.goalMetCount(bookKey, windowStart, today) > 0
        val justMet = !alreadyMetInWindow && windowTotal >= reminder.goalPages
        if (justMet) {
            dao.upsert(
                DailyReadingProgressEntity(
                    bookKey = bookKey,
                    day = today,
                    pagesRead = newTodayCount,
                    goalMetAt = now,
                ),
            )
        }
        dao.pruneBefore(dayString(now - RETENTION_DAYS * DAY_MS))
        return if (justMet) {
            GoalMet(reminder.title, reminder.goalPages, windowTotal, reminder.goalCadence)
        } else {
            null
        }
    }

    /** Pages counted in the current goal window for [reminder]; 0 when untracked. */
    suspend fun pagesReadInWindow(
        reminder: ReminderEntity,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (!reminder.hasGoal()) return 0
        val bookKey = canonicalKey(reminder)
        val today = dayString(now)
        val windowStart = GoalWindow.windowStartDay(reminder.goalCadence, now)
        return db.dailyReadingProgressDao().sumPages(bookKey, windowStart, today)
    }

    /** Pages still needed to hit the goal in the current window; 0 when met/no goal. */
    suspend fun pagesLeftInWindow(
        reminder: ReminderEntity,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (!reminder.hasGoal()) return 0
        return (reminder.goalPages - pagesReadInWindow(reminder, now)).coerceAtLeast(0)
    }

    private suspend fun findGoalReminder(keys: Set<String>): ReminderEntity? {
        val candidates = db.reminderDao().getEnabledGoalReminders(MIN_GOAL)
        return candidates.firstOrNull { reminder -> matchKeys(reminder).any { it in keys } }
    }

    companion object {
        const val MIN_GOAL = 1
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
