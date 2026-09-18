package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderType
import com.nkanaev.comics.R

object ReminderFormat {
    fun hourLabel(context: Context, hour: Int): String {
        val labels = context.resources.getStringArray(R.array.settings_hour_labels)
        return labels.getOrNull(hour.coerceIn(0, 23)) ?: "$hour:00"
    }

    fun weekdayLabel(context: Context, dayOfWeek: Int): String {
        val labels = context.resources.getStringArray(R.array.reminder_weekday_labels)
        val idx = (dayOfWeek - 1).coerceIn(0, labels.size - 1)
        return labels[idx]
    }

    fun scheduleSummary(context: Context, entity: ReminderEntity): String {
        val time = hourLabel(context, entity.hourOfDay)
        val base = when (entity.frequency) {
            ReminderFrequency.DAILY ->
                context.getString(R.string.reminders_schedule_daily, time)
            ReminderFrequency.WEEKLY ->
                context.getString(
                    R.string.reminders_schedule_weekly,
                    weekdayLabel(context, entity.dayOfWeek),
                    time,
                )
            ReminderFrequency.MONTHLY ->
                context.getString(
                    R.string.reminders_schedule_monthly,
                    entity.dayOfMonth,
                    time,
                )
            ReminderFrequency.INTERVAL ->
                context.getString(
                    R.string.reminders_schedule_interval,
                    entity.intervalDays.coerceAtLeast(2),
                    time,
                )
        }
        val withBlocked = if (entity.blockedWeekdays != 0) {
            val days = (0..6)
                .filter { entity.blockedWeekdays and (1 shl it) != 0 }
                .joinToString(" ") { weekdayLabel(context, it + 1).take(3) }
            base + context.getString(R.string.reminders_schedule_blocked_suffix, days)
        } else {
            base
        }
        if (entity.type == ReminderType.BOOK && !entity.effectiveNotify()) {
            return "$withBlocked · ${context.getString(R.string.reminders_notify_off)}"
        }
        return withBlocked
    }

    fun title(context: Context, entity: ReminderEntity): String {
        return when (entity.type) {
            ReminderType.PULL_LIST -> context.getString(R.string.reminders_type_pull)
            ReminderType.BOOK ->
                entity.title.ifBlank { context.getString(R.string.reminders_no_book) }
        }
    }

    fun modeBadge(context: Context, entity: ReminderEntity): String? {
        if (entity.type != ReminderType.BOOK) return null
        if (entity.hasGoal()) {
            return context.getString(
                R.string.reminders_mode_goal,
                entity.goalPages,
                context.getString(GoalWindow.perLabelRes(entity.goalCadence)),
            )
        }
        return context.getString(R.string.reminders_mode_resume)
    }
}
