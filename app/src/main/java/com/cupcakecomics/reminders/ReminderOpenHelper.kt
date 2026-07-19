package com.cupcakecomics.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cupcakecomics.data.ReminderBookSource
import com.cupcakecomics.data.ReminderEntity
import com.cupcakecomics.data.pullIdentityKey
import com.cupcakecomics.reader.CupcakeReaderFragment
import com.nkanaev.comics.activity.MainActivity
import com.nkanaev.comics.activity.ReaderActivity
import com.nkanaev.comics.fragment.ReaderFragment
import java.io.File
import java.io.Serializable

/** Builds intents that open the Pull List or a specific book page from reminders. */
object ReminderOpenHelper {
    const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"

    fun pullListIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PULL_LIST, true)
        }

    fun readerIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        val base = when (reminder.bookSource) {
            ReminderBookSource.LIBRARY -> libraryIntent(context, reminder, page)
            ReminderBookSource.PULL -> pullIntent(context, reminder, page)
            ReminderBookSource.LOCAL -> localIntent(context, reminder, page)
            null -> null
        } ?: return null
        base.putExtra(EXTRA_REMINDER_ID, reminder.id)
        return base
    }

    private fun libraryIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        if (reminder.libraryComicId <= 0) return null
        return Intent(context, ReaderActivity::class.java).apply {
            putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_LIBRARY)
            putExtra(ReaderFragment.PARAM_HANDLER, reminder.libraryComicId)
            reminder.identityKey?.let { putExtra(ReaderFragment.PARAM_IDENTITY_KEY, it) }
            putExtra(ReaderFragment.PARAM_PAGE, page.coerceAtLeast(1))
            putExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER, true)
        }
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
        return Intent(context, ReaderActivity::class.java).apply {
            putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
            putExtra(ReaderFragment.PARAM_HANDLER, File(title) as Serializable)
            putExtra(ReaderFragment.PARAM_IDENTITY_KEY, identity)
            putExtra(CupcakeReaderFragment.PARAM_SMB_SHARE_ID, shareId)
            putExtra(CupcakeReaderFragment.PARAM_SMB_RELATIVE_PATH, rel)
            putExtra(ReaderFragment.PARAM_PAGE, page.coerceAtLeast(1))
            putExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER, true)
        }
    }

    private fun localIntent(context: Context, reminder: ReminderEntity, page: Int): Intent? {
        val path = reminder.localPath?.takeIf { it.isNotBlank() } ?: return null
        if (path.startsWith("content://")) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(path))
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_INTENT)
                putExtra(ReaderFragment.PARAM_HANDLER, viewIntent)
                reminder.identityKey?.let { putExtra(ReaderFragment.PARAM_IDENTITY_KEY, it) }
                putExtra(ReaderFragment.PARAM_PAGE, page.coerceAtLeast(1))
                putExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER, true)
            }
        }
        val file = File(path)
        if (!file.isFile) return null
        return Intent(context, ReaderActivity::class.java).apply {
            putExtra(ReaderFragment.PARAM_MODE, ReaderFragment.Mode.MODE_BROWSER)
            putExtra(ReaderFragment.PARAM_HANDLER, file as Serializable)
            reminder.identityKey?.let { putExtra(ReaderFragment.PARAM_IDENTITY_KEY, it) }
            putExtra(ReaderFragment.PARAM_PAGE, page.coerceAtLeast(1))
            putExtra(CupcakeReaderFragment.PARAM_USE_GPU_READER, true)
        }
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
