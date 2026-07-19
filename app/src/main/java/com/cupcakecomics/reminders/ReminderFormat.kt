package com.cupcakecomics.reminders

import android.content.Context
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.ReminderFrequency
import com.cupcakecomics.data.ReminderPageMode
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
        return when (entity.frequency) {
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
        }
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
        return when (entity.pageMode) {
            ReminderPageMode.PAGE_A_DAY -> context.getString(R.string.reminders_mode_page_a_day)
            ReminderPageMode.RESUME -> context.getString(R.string.reminders_mode_resume)
        }
    }
}
