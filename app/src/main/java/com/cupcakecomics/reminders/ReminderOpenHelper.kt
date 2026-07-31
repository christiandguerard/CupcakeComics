package com.cupcakecomics.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.pullIdentityKey
import com.cupcakecomics.reader.ReaderLauncher
import com.nkanaev.comics.activity.MainActivity
import java.io.File

/** Builds intents that open the Pull List or a specific book page from reminders. */
object ReminderOpenHelper {
    const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"

    fun pullListIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PULL_LIST, true)
        }

    fun readerIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        val safePage = page.coerceAtLeast(1)
        val base = when (reminder.bookSource) {
            ReminderBookSource.LIBRARY -> libraryIntent(context, reminder, safePage)
            ReminderBookSource.PULL -> pullIntent(context, reminder, safePage)
            ReminderBookSource.LOCAL -> localIntent(context, reminder, safePage)
            null -> null
        } ?: return null
        base.putExtra(EXTRA_REMINDER_ID, reminder.id)
        return base
    }

    private fun libraryIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        if (reminder.libraryComicId <= 0) return null
        return ReaderLauncher.libraryComicIntent(
            context,
            comicId = reminder.libraryComicId,
            identityKey = reminder.identityKey,
            initialPage = page,
        )
    }

    private fun pullIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        val shareId = when {
            reminder.smbShareId > 0 -> reminder.smbShareId
            else -> parseShareId(reminder.identityKey)
        } ?: return null
        val rel = reminder.smbRelativePath?.takeIf { it.isNotBlank() }
            ?: parseRelativePath(reminder.identityKey)
            ?: return null
        val identity = reminder.identityKey ?: pullIdentityKey(shareId, rel)
        val title = reminder.title.ifBlank { rel.substringAfterLast('/') }
        return ReaderLauncher.smbIntent(
            context,
            shareId = shareId,
            relativePath = rel,
            displayName = title,
            identityKey = identity,
            initialPage = page,
        )
    }

    private fun localIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        val path = reminder.localPath?.takeIf { it.isNotBlank() } ?: return null
        if (path.startsWith("content://")) {
            return ReaderLauncher.viewIntentIntent(
                context,
                viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(path)),
                identityKey = reminder.identityKey,
                initialPage = page,
            )
        }
        val file = File(path)
        if (!file.isFile) return null
        return ReaderLauncher.fileIntent(
            context,
            file = file,
            identityKey = reminder.identityKey,
            initialPage = page,
        )
    }

    private fun parseShareId(identityKey: String?): Long? {
        if (identityKey.isNullOrBlank() || !identityKey.startsWith("smb:")) return null
        return identityKey.removePrefix("smb:").substringBefore(':').toLongOrNull()
    }

    private fun parseRelativePath(identityKey: String?): String? {
        if (identityKey.isNullOrBlank() || !identityKey.startsWith("smb:")) return null
        val rest = identityKey.removePrefix("smb:")
        val idx = rest.indexOf(':')
        if (idx < 0 || idx >= rest.length - 1) return null
        return rest.substring(idx + 1)
    }
}
