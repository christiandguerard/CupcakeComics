package com.cupcakecomics.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nkanaev.comics.BuildConfig

/**
 * App-wide Cupcake preferences (Pull List, notifications, library display, storage).
 * Reader defaults live in [com.cupcakecomics.reader.settings.ReaderSettingsStore].
 */
class CupcakeSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // —— Notifications / Pull List scan ——

    var pullListNotify: Boolean
        get() = prefs.getBoolean(KEY_PULL_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_PULL_NOTIFY, value).apply()

    /** One-time first-run permission dialog after folders are monitored. */
    var notifyPermissionPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PROMPT, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_PROMPT, value).apply()

    /** When true, stack multiple new titles into an InboxStyle digest. */
    var notifyDigestMode: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DIGEST, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DIGEST, value).apply()

    /** Suppress Pull List alerts during [quietHoursStartHour]–[quietHoursEndHour]; buffer instead. */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()

    var quietHoursStartHour: Int
        get() = prefs.getInt(KEY_QUIET_START, 22).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_QUIET_START, value.coerceIn(0, 23)).apply()

    var quietHoursEndHour: Int
        get() = prefs.getInt(KEY_QUIET_END, 8).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_QUIET_END, value.coerceIn(0, 23)).apply()

    /** Notify on the Downloads channel when offline downloads finish. */
    var notifyDownloads: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DOWNLOADS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DOWNLOADS, value).apply()

    /** When true, SMB “Download for offline” runs via WorkManager instead of a blocking dialog. */
    var offlineDownloadBackground: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_DL_BG, true)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_DL_BG, value).apply()

    /** Periodic Pull List scan interval in minutes (WorkManager minimum 15). */
    var pullListScanMinutes: Int
        get() = prefs.getInt(KEY_PULL_SCAN_MIN, DEFAULT_SCAN_MINUTES).coerceIn(15, 24 * 60)
        set(value) = prefs.edit().putInt(KEY_PULL_SCAN_MIN, value.coerceIn(15, 24 * 60)).apply()

    /** When true, background Pull List scans require unmetered (Wi‑Fi) network. */
    var pullListScanWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_PULL_SCAN_WIFI, true)
        set(value) = prefs.edit().putBoolean(KEY_PULL_SCAN_WIFI, value).apply()

    /** When true, newly unread Pull List comics are staged for offline reading. */
    var pullListAutoDownload: Boolean
        get() = prefs.getBoolean(KEY_PULL_AUTO_DL, false)
        set(value) = prefs.edit().putBoolean(KEY_PULL_AUTO_DL, value).apply()

    /** When true, auto-download only runs on unmetered (Wi‑Fi) networks. */
    var pullListWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_PULL_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_PULL_WIFI_ONLY, value).apply()

    /**
     * Fraction of pages that must be reached to leave the Pull List (0.80–1.0).
     * SPEC default: 0.90.
     */
    var pullListLeaveThreshold: Float
        get() = prefs.getFloat(KEY_PULL_LEAVE, DEFAULT_LEAVE_THRESHOLD).coerceIn(0.80f, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PULL_LEAVE, value.coerceIn(0.80f, 1.0f)).apply()

    // —— Library / browse display ——

    var hideCoverTitles: Boolean
        get() = prefs.getBoolean(KEY_HIDE_COVER_TITLES, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_COVER_TITLES, value).apply()

    var shortenNetworkFilenames: Boolean
        get() = prefs.getBoolean(KEY_SHORT_NETWORK_FILENAMES, true)
        set(value) = prefs.edit().putBoolean(KEY_SHORT_NETWORK_FILENAMES, value).apply()

    /** Open CBZ from SMB via byte-range streaming when GPU reader is on (default true). */
    var preferSmbStreaming: Boolean
        get() = prefs.getBoolean(KEY_SMB_STREAM, true)
        set(value) = prefs.edit().putBoolean(KEY_SMB_STREAM, value).apply()

    /**
     * Optional ComicVine developer API key for Pull List release estimates
     * and series-ended detection. Get one free at comicvine.gamespot.com/api/
     */
    var comicVineApiKey: String
        get() = prefs.getString(KEY_COMICVINE_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_COMICVINE_KEY, value.trim()).apply()

    /** Cover tile size on Library home grids. */
    var coverSize: CoverSize
        get() = CoverSize.entries.getOrElse(prefs.getInt(KEY_COVER_SIZE, CoverSize.MEDIUM.ordinal)) {
            CoverSize.MEDIUM
        }
        set(value) = prefs.edit().putInt(KEY_COVER_SIZE, value.ordinal).apply()

    /** Ordered Library home sections (Pull, Local, Offline, SMB, Media). */
    var librarySectionOrder: List<LibrarySection>
        get() = LibrarySection.parseOrder(prefs.getString(KEY_SECTION_ORDER, null))
        set(value) = prefs.edit().putString(KEY_SECTION_ORDER, LibrarySection.serialize(value)).apply()

    /**
     * When true, a floating feedback button appears on every screen.
     * Captures screenshot + UI context into markdown for Cursor review.
     */
    var debugFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_FEEDBACK, value).apply()

    // —— GitHub feedback upload ——

    var feedbackGithubToken: String
        get() = prefs.getString(KEY_FEEDBACK_GITHUB_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FEEDBACK_GITHUB_TOKEN, value.trim()).apply()

    var feedbackGithubRepo: String
        get() = prefs.getString(KEY_FEEDBACK_GITHUB_REPO, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FEEDBACK_GITHUB_REPO, value.trim()).apply()

    var feedbackAutoUpload: Boolean
        get() = prefs.getBoolean(KEY_FEEDBACK_AUTO_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_FEEDBACK_AUTO_UPLOAD, value).apply()

    fun canAutoDownloadNow(context: Context): Boolean {
        if (!pullListAutoDownload) return false
        if (!pullListWifiOnly) return true
        return isUnmetered(context)
    }

    companion object {
        private const val PREFS = "cupcake_settings"
        private const val KEY_PULL_NOTIFY = "cupcake_pull_list_notify"
        private const val KEY_NOTIFY_PROMPT = "cupcake_notify_permission_prompt_shown"
        private const val KEY_NOTIFY_DIGEST = "cupcake_notify_digest"
        private const val KEY_QUIET_ENABLED = "cupcake_quiet_hours_enabled"
        private const val KEY_QUIET_START = "cupcake_quiet_hours_start"
        private const val KEY_QUIET_END = "cupcake_quiet_hours_end"
        private const val KEY_NOTIFY_DOWNLOADS = "cupcake_notify_downloads"
        private const val KEY_OFFLINE_DL_BG = "cupcake_offline_download_background"
        private const val KEY_PULL_SCAN_MIN = "cupcake_pull_list_scan_minutes"
        private const val KEY_PULL_SCAN_WIFI = "cupcake_pull_list_scan_wifi_only"
        private const val KEY_PULL_AUTO_DL = "cupcake_pull_list_auto_download"
        private const val KEY_PULL_WIFI_ONLY = "cupcake_pull_list_wifi_only"
        private const val KEY_PULL_LEAVE = "cupcake_pull_list_leave_threshold"
        private const val KEY_HIDE_COVER_TITLES = "cupcake_hide_cover_titles"
        private const val KEY_SHORT_NETWORK_FILENAMES = "cupcake_short_network_filenames"
        private const val KEY_SMB_STREAM = "cupcake_prefer_smb_streaming"
        private const val KEY_COMICVINE_KEY = "cupcake_comicvine_api_key"
        private const val KEY_COVER_SIZE = "cupcake_cover_size"
        private const val KEY_SECTION_ORDER = "cupcake_library_section_order"
        private const val KEY_DEBUG_FEEDBACK = "cupcake_debug_feedback"
        private const val KEY_FEEDBACK_GITHUB_TOKEN = "cupcake_feedback_github_token"
        private const val KEY_FEEDBACK_GITHUB_REPO = "cupcake_feedback_github_repo"
        private const val KEY_FEEDBACK_AUTO_UPLOAD = "cupcake_feedback_auto_upload"

        const val DEFAULT_SCAN_MINUTES = 30
        const val DEFAULT_LEAVE_THRESHOLD = 0.90f

        val SCAN_INTERVAL_CHOICES = intArrayOf(15, 30, 60, 120, 360)
        val LEAVE_THRESHOLD_CHOICES = floatArrayOf(0.80f, 0.90f, 0.95f, 1.0f)

        fun isUnmetered(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
