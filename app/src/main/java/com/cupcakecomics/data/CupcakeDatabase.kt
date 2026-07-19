package com.cupcakecomics.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SmbShareEntity::class,
        KapowarrProfileEntity::class,
        OfflineComicEntity::class,
        ReadMarkEntity::class,
        MonitoredFolderEntity::class,
        PullComicEntity::class,
        ReminderEntity::class,
        LocalFileEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@androidx.room.TypeConverters(ReminderConverters::class)
abstract class CupcakeDatabase : RoomDatabase() {
    abstract fun smbShareDao(): SmbShareDao
    abstract fun kapowarrProfileDao(): KapowarrProfileDao
    abstract fun offlineComicDao(): OfflineComicDao
    abstract fun readMarkDao(): ReadMarkDao
    abstract fun monitoredFolderDao(): MonitoredFolderDao
    abstract fun pullComicDao(): PullComicDao
    abstract fun reminderDao(): ReminderDao
    abstract fun localFileDao(): LocalFileDao

    companion object {
        @Volatile private var instance: CupcakeDatabase? = null

        fun get(context: Context): CupcakeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CupcakeDatabase::class.java,
                    "cupcake.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
