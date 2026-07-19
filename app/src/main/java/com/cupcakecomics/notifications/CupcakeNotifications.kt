package com.cupcakecomics.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cupcakecomics.settings.CupcakeSettings
import com.cupcakecomics.smb.ComicFileNames
import com.nkanaev.comics.R
import com.nkanaev.comics.activity.MainActivity
import org.json.JSONArray
import java.util.Calendar

/**
 * Notification channels + posting for Pull List and offline downloads.
 * Handles quiet hours (buffer) and digest / InboxStyle presentation.
 */
object CupcakeNotifications {
    const val CHANNEL_PULL_LIST = "pull_list"
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_REMINDERS = "reminders"

    private const val NOTIF_PULL = 1001
    private const val NOTIF_DOWNLOADS = 1002
    private const val NOTIF_REMINDER_PULL_BASE = 1100
    private const val NOTIF_REMINDER_BOOK_BASE = 1200
    private const val PREFS = "cupcake_notify_buffer"
    private const val KEY_PENDING_TITLES = "pending_pull_titles"
    private const val MAX_LINES = 5

    @JvmStatic
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PULL_LIST,
                context.getString(R.string.pull_list_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.pull_list_channel_desc)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.downloads_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.downloads_channel_desc)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.reminders_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminders_channel_desc)
            },
        )
    }

    @JvmStatic
    fun areNotificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Record new Pull List titles and post (or buffer) according to settings.
     * Also flushes any quiet-hours buffer once outside the quiet window.
     */
    @JvmStatic
    fun onNewPullItems(context: Context, titles: List<String>) {
        val app = context.applicationContext
        val settings = CupcakeSettings(app)
        if (!settings.pullListNotify) return
        ensureChannels(app)

        val pretty = titles.map { displayTitle(it, settings) }.filter { it.isNotBlank() }
        if (pretty.isNotEmpty()) {
            if (settings.quietHoursEnabled && isInQuietHours(settings)) {
                appendPending(app, pretty)
            } else {
                postPullList(app, pretty, settings.notifyDigestMode)
            }
        }
        // Leaving quiet hours: flush anything that accumulated.
        if (!(settings.quietHoursEnabled && isInQuietHours(settings))) {
            flushPendingPullList(app)
        }
    }

    @JvmStatic
    fun onDownloadsComplete(context: Context, titles: List<String>) {
        val app = context.applicationContext
        val settings = CupcakeSettings(app)
        if (!settings.notifyDownloads || titles.isEmpty()) return
        if (!areNotificationsAllowed(app)) return
        ensureChannels(app)
        if (settings.quietHoursEnabled && isInQuietHours(settings)) {
            // Downloads stay quiet during quiet hours (no separate buffer for v1).
            return
        }
        val pretty = titles.map { displayTitle(it, settings) }
        val open = pullListIntent(app)
        val pending = PendingIntent.getActivity(
            app, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (pretty.size == 1) {
            app.getString(R.string.downloads_notify_title_one)
        } else {
            app.getString(R.string.downloads_notify_title_many, pretty.size)
        }
        val body = if (pretty.size == 1) {
            pretty[0]
        } else {
            pretty.take(MAX_LINES).joinToString("\n")
        }
        val builder = NotificationCompat.Builder(app, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_collections_image_24)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        NotificationManagerCompat.from(app).notify(NOTIF_DOWNLOADS, builder.build())
    }

    /** Flush buffered Pull List alerts if not in quiet hours. */
    @JvmStatic
    fun flushPendingPullList(context: Context) {
        val app = context.applicationContext
        val settings = CupcakeSettings(app)
        if (!settings.pullListNotify) return
        if (settings.quietHoursEnabled && isInQuietHours(settings)) return
        val pending = takePending(app)
        if (pending.isEmpty()) return
        postPullList(app, pending, digest = true)
    }

    @JvmStatic
    fun isInQuietHours(
        settings: CupcakeSettings,
        nowHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): Boolean = isInQuietHours(
        enabled = settings.quietHoursEnabled,
        startHour = settings.quietHoursStartHour,
        endHour = settings.quietHoursEndHour,
        nowHour = nowHour,
    )

    /** Pure helper for tests and scheduling logic. */
    @JvmStatic
    fun isInQuietHours(enabled: Boolean, startHour: Int, endHour: Int, nowHour: Int): Boolean {
        if (!enabled) return false
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        if (start == end) return false // disabled degenerate window
        return if (start < end) {
            nowHour in start until end
        } else {
            // Crosses midnight, e.g. 22 → 8
            nowHour >= start || nowHour < end
        }
    }

    private fun postPullList(context: Context, titles: List<String>, digest: Boolean) {
        if (!areNotificationsAllowed(context) || titles.isEmpty()) return
        val open = pullListIntent(context)
        val pending = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val count = titles.size
        val contentTitle = if (count == 1) {
            context.getString(R.string.pull_list_notify_title_one)
        } else {
            context.getString(R.string.pull_list_notify_title_many, count)
        }
        val summary = if (count == 1) {
            titles[0]
        } else {
            context.getString(R.string.pull_list_notify_body, count)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_PULL_LIST)
            .setSmallIcon(R.drawable.ic_collections_image_24)
            .setContentTitle(contentTitle)
            .setContentText(if (count == 1) titles[0] else summary)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(count)

        if (digest || count > 1) {
            val inbox = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)
                .setSummaryText(summary)
            titles.take(MAX_LINES).forEach { inbox.addLine(it) }
            if (count > MAX_LINES) {
                inbox.addLine(context.getString(R.string.pull_list_notify_more, count - MAX_LINES))
            }
            builder.setStyle(inbox)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(titles[0]))
        }
        NotificationManagerCompat.from(context).notify(NOTIF_PULL, builder.build())
    }

    private fun displayTitle(raw: String, settings: CupcakeSettings): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { raw }
        return if (settings.shortenNetworkFilenames) {
            ComicFileNames.shortDisplayName(name)
        } else {
            name.replace(Regex("\\.[^.]+$"), "")
        }
    }

    private fun pullListIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PULL_LIST, true)
        }

    private fun appendPending(context: Context, titles: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = readPending(prefs)
        existing.addAll(titles)
        // Cap buffer
        val trimmed = existing.takeLast(50)
        prefs.edit().putString(KEY_PENDING_TITLES, JSONArray(trimmed).toString()).apply()
    }

    private fun takePending(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = readPending(prefs)
        if (list.isEmpty()) return emptyList()
        prefs.edit().remove(KEY_PENDING_TITLES).apply()
        return list
    }

    private fun readPending(prefs: android.content.SharedPreferences): MutableList<String> {
        val raw = prefs.getString(KEY_PENDING_TITLES, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> arr.getString(i) }
        }.getOrElse { mutableListOf() }
    }

    /** Scheduled reminder: unread Pull List nudge. */
    @JvmStatic
    fun notifyPullListReminder(context: Context, unreadCount: Int) {
        if (!areNotificationsAllowed(context) || unreadCount <= 0) return
        ensureChannels(context)
        val app = context.applicationContext
        val open = com.cupcakecomics.reminders.ReminderOpenHelper.pullListIntent(app)
        val pending = PendingIntent.getActivity(
            app, NOTIF_REMINDER_PULL_BASE, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = app.getString(R.string.reminder_pull_notify_title)
        val body = app.getString(R.string.reminder_pull_notify_body, unreadCount)
        val builder = NotificationCompat.Builder(app, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_collections_image_24)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(unreadCount)
        NotificationManagerCompat.from(app).notify(NOTIF_REMINDER_PULL_BASE, builder.build())
    }

    /** Scheduled reminder: open a specific book at [page] (1-based). */
    @JvmStatic
    fun notifyBookReminder(
        context: Context,
        reminderId: Long,
        title: String,
        page: Int,
        reminder: com.cupcakecomics.data.ReminderEntity,
    ) {
        if (!areNotificationsAllowed(context)) return
        ensureChannels(context)
        val app = context.applicationContext
        val open = com.cupcakecomics.reminders.ReminderOpenHelper.readerIntent(app, reminder, page)
            ?: return
        val notifId = (NOTIF_REMINDER_BOOK_BASE + (reminderId % 1000).toInt())
        val pending = PendingIntent.getActivity(
            app, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentTitle = app.getString(R.string.reminder_book_notify_title, title)
        val body = app.getString(R.string.reminder_book_notify_body, page)
        val builder = NotificationCompat.Builder(app, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_collections_image_24)
            .setContentTitle(contentTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        NotificationManagerCompat.from(app).notify(notifId, builder.build())
    }

    @JvmStatic
    fun notifyBookFinished(context: Context, reminderId: Long, title: String) {
        if (!areNotificationsAllowed(context)) return
        ensureChannels(context)
        val app = context.applicationContext
        val notifId = (NOTIF_REMINDER_BOOK_BASE + (reminderId % 1000).toInt())
        val body = app.getString(R.string.reminder_book_finished_body, title)
        val builder = NotificationCompat.Builder(app, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_collections_image_24)
            .setContentTitle(app.getString(R.string.reminder_book_finished_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        NotificationManagerCompat.from(app).notify(notifId, builder.build())
    }
}
