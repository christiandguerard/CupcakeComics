package com.cupcakecomics.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cupcakecomics.cover.FileCoverHandler
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.nkanaev.comics.managers.Utils
import com.nkanaev.comics.model.Storage
import java.io.File

/**
 * Resolves a software [Bitmap] of a reminded book's cover for rich notifications.
 * Offline-first: uses the path-keyed disk cover cache, warming it from the local
 * file when possible. Never touches the network — a book that only exists on an
 * SMB share simply falls back to the small icon until it has an offline copy.
 */
object ReminderCoverLoader {

    suspend fun load(context: Context, reminder: ReminderEntity): Bitmap? {
        val app = context.applicationContext
        val path = localCoverPath(app, reminder) ?: return null
        val cache = Utils.getCoverCacheFileForPath(path)
        if (!cache.isFile && File(path).isFile) {
            runCatching { FileCoverHandler.warmCache(path) }
        }
        if (!cache.isFile) return null
        // Default config on purpose: HARDWARE bitmaps cannot go into a Notification.
        return runCatching { BitmapFactory.decodeFile(cache.absolutePath) }.getOrNull()
    }

    private suspend fun localCoverPath(context: Context, reminder: ReminderEntity): String? =
        when (reminder.bookSource) {
            ReminderBookSource.LOCAL ->
                reminder.localPath?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ReminderBookSource.LIBRARY -> {
                if (reminder.libraryComicId <= 0) {
                    null
                } else {
                    runCatching {
                        Storage.getStorage(context).getComic(reminder.libraryComicId)
                            ?.file?.absolutePath
                    }.getOrNull()
                }
            }
            ReminderBookSource.PULL -> {
                val key = reminder.identityKey?.takeIf { it.isNotBlank() } ?: return null
                runCatching {
                    CupcakeDatabase.get(context).offlineComicDao().getBySourceKey(key)?.localPath
                }.getOrNull()
            }
            null -> null
        }
}
