package com.cupcakecomics.data

import androidx.room.TypeConverter

class ReminderConverters {
    @TypeConverter
    fun fromReminderType(value: ReminderType): String = value.name

    @TypeConverter
    fun toReminderType(value: String): ReminderType = ReminderType.valueOf(value)

    @TypeConverter
    fun fromReminderFrequency(value: ReminderFrequency): String = value.name

    @TypeConverter
    fun toReminderFrequency(value: String): ReminderFrequency = ReminderFrequency.valueOf(value)

    @TypeConverter
    fun fromReminderBookSource(value: ReminderBookSource?): String? = value?.name

    @TypeConverter
    fun toReminderBookSource(value: String?): ReminderBookSource? =
        value?.let { ReminderBookSource.valueOf(it) }

    @TypeConverter
    fun fromReminderPageMode(value: ReminderPageMode): String = value.name

    @TypeConverter
    fun toReminderPageMode(value: String): ReminderPageMode = ReminderPageMode.valueOf(value)
}
