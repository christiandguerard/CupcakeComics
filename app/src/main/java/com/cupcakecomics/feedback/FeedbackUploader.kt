package com.cupcakecomics.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.cupcakecomics.settings.CupcakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads saved feedback reports to GitHub Issues.
 *
 * After FeedbackCapture writes the local files, the app can call [uploadReport]
 * immediately (fire-and-forget) or enqueue via WorkManager for Wi-Fi conditions.
 * A successful submit also backfills any older reports that never reached
 * GitHub, deduping against the issues the repo already has.
 *
 * Tracked reports are recorded in a local JSON sidecar so we can
 * distinguish pending / submitted / addressed statuses.
 */
object FeedbackUploader {
    private const val API_BASE = "https://api.github.com"
    private const val SIDECAR_FILE = "feedback_submitted.json"
    private const val LABEL = "feedback"
    private const val TAG = "FeedbackUploader"

    /** GitHub rejects issue bodies longer than this with a 422. */
    internal const val GITHUB_BODY_LIMIT = 65536

    /** Reports are capped well under [GITHUB_BODY_LIMIT] to leave room for wrapping. */
    private const val MAX_REPORT_CHARS = GITHUB_BODY_LIMIT - 512

    /**
     * Seeds the feedback upload settings from the token/repo baked into the build
     * (CI secrets -> BuildConfig) when the user has not configured them manually.
     * Without this the uploader no-ops forever, because nothing else writes these
     * settings. Manually entered values always win.
     */
    fun ensureSeeded(context: Context) {
        val settings = CupcakeSettings(context)
        val token = resolveConfigValue(
            settings.feedbackGithubToken,
            com.nkanaev.comics.BuildConfig.FEEDBACK_GITHUB_TOKEN,
        )
        if (token.isNotBlank()) settings.feedbackGithubToken = token
        val repo = resolveConfigValue(
            settings.feedbackGithubRepo,
            com.nkanaev.comics.BuildConfig.FEEDBACK_GITHUB_REPO,
        )
        if (repo.isNotBlank()) settings.feedbackGithubRepo = repo
    }

    /** Manually configured value wins; fall back to the build-baked one. */
    internal fun resolveConfigValue(current: String, baked: String): String =
        current.trim().ifBlank { baked.trim() }

    // ── Data model ──────────────────────────────────────────────────

    data class Submission(
        val stamp: String,
        val title: String,
        val issueNumber: Int,
        val state: String,   // "open" or "closed"
        val url: String,
    )

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Attempts to upload one report to GitHub immediately.
     * Call from the UI thread (it launches its own coroutine).
     * On success writes a tracking sidecar so we know it's been sent, then sweeps
     * up any older reports that never made it (see [backfillMissedReports]).
     * [onResult] fires on the main thread with true on submission; on failure,
     * false plus a short human-readable reason (see [shortReason]).
     * [onBackfilled] fires on the main thread with how many older reports were
     * newly posted by the sweep (only when that count is > 0).
     */
    fun uploadReport(
        context: Context,
        result: FeedbackResult,
        title: String,
        onBackfilled: ((Int) -> Unit)? = null,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        ensureSeeded(context)
        val settings = CupcakeSettings(context)
        val token = settings.feedbackGithubToken
        val repo = settings.feedbackGithubRepo
        if (token.isBlank() || repo.isBlank()) {
            postResult(onResult, false, "not configured")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val effectiveTitle = title.ifBlank {
                    extractTitle(result.markdown) ?: "Feedback: ${result.stamp}"
                }
                val issueNumber = postIssue(token, repo, effectiveTitle, result)
                recordSubmission(context, result.stamp, effectiveTitle, issueNumber, "open")
                postResult(onResult, true, null)
                // Older builds could never reach GitHub, so their reports are still
                // sitting on disk — sweep them up now that a post succeeded.
                try {
                    val uploaded = backfillBlocking(context, token, repo).uploaded
                    if (uploaded > 0) postResult(onBackfilled, uploaded)
                } catch (e: Exception) {
                    // Backfill is best-effort; the files stay put for the next submit.
                    Log.w(TAG, "feedback backfill failed", e)
                }
            } catch (e: Exception) {
                // Leave files in place — will retry via WorkManager / manual
                Log.w(TAG, "feedback upload failed", e)
                postResult(onResult, false, shortReason(e))
            }
        }
    }

    /** Short, toast-safe description of why an upload failed. */
    internal fun shortReason(t: Throwable): String {
        Regex("GitHub API (\\d{3})").find(t.message.orEmpty())
            ?.let { return "HTTP ${it.groupValues[1]}" }
        return when (t) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            -> "no connection"
            else -> t.javaClass.simpleName.ifBlank { "unknown error" }
        }
    }

    private fun <T> postResult(callback: ((T) -> Unit)?, value: T) {
        callback ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(value) }
    }

    private fun postResult(onResult: ((Boolean, String?) -> Unit)?, ok: Boolean, reason: String?) {
        onResult ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(ok, reason) }
    }

    /**
     * Send a single pending feedback file to GitHub.
     * Returns the issue number on success, null on failure.
     */
    fun uploadPendingFile(
        context: Context,
        token: String,
        repo: String,
        mdFile: File,
        stamp: String,
    ): Int? {
        return try {
            val text = mdFile.readText(Charsets.UTF_8)
            val title = extractTitle(text) ?: "Feedback: $stamp"
            val issueNumber = postIssue(token, repo, title, buildIssueBody(text))
            recordSubmission(context, stamp, title, issueNumber, "open")
            issueNumber
        } catch (e: Exception) {
            Log.w(TAG, "failed to upload ${mdFile.name}", e)
            null
        }
    }

    // ── Backfill of missed reports ─────────────────────────────────

    /** Outcome of one backfill sweep. */
    data class BackfillResult(
        val uploaded: Int,  // newly posted to GitHub
        val adopted: Int,   // already on GitHub, now tracked locally
        val failed: Int,    // attempted but still unsent
    )

    internal data class PendingReport(
        val stamp: String,
        val mdFile: File,
    )

    internal data class RemoteIssue(
        val number: Int,
        val state: String,
        val title: String,
        val url: String,
    )

    internal data class BackfillPlan(
        val toUpload: List<PendingReport>,
        val toAdopt: List<Pair<PendingReport, RemoteIssue>>,
    )

    /** Capture stamp embedded in report file names and issue bodies. */
    private val STAMP_REGEX = Regex("\\d{8}_\\d{6}")
    private val REPORT_FILE_REGEX = Regex("feedback_(\\d{8}_\\d{6})\\.md")

    /** The relative screenshot link FeedbackCapture writes into each report. */
    private val SCREENSHOT_LINK_REGEX = Regex("!\\[screenshot]\\((feedback_\\d{8}_\\d{6}\\.png)\\)")

    /** Stamp from a report file name like `feedback_20260801_153000.md`; null for LATEST.md, the sidecar, PNGs, etc. */
    internal fun stampFromFileName(name: String): String? =
        REPORT_FILE_REGEX.matchEntire(name)?.groupValues?.get(1)

    /** Stamps referenced by issue text — the report header, screenshot link and `_File:` line all carry it. */
    internal fun extractStamps(text: String): List<String> =
        STAMP_REGEX.findAll(text).map { it.value }.distinct().toList()

    /** Every locally stored report, oldest first, regardless of submission state. */
    internal fun findPendingReports(dir: File): List<PendingReport> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { file ->
                val stamp = stampFromFileName(file.name) ?: return@mapNotNull null
                PendingReport(stamp = stamp, mdFile = file)
            }
            .sortedBy { it.stamp }

    /**
     * Splits local reports into those that still need posting and those the repo
     * already has (submitted from another device, or before local tracking
     * existed). Reports already in the sidecar are left alone.
     */
    internal fun planBackfill(
        local: List<PendingReport>,
        sidecarStamps: Set<String>,
        remote: Map<String, RemoteIssue>,
    ): BackfillPlan {
        val toUpload = mutableListOf<PendingReport>()
        val toAdopt = mutableListOf<Pair<PendingReport, RemoteIssue>>()
        for (report in local) {
            if (report.stamp in sidecarStamps) continue
            val issue = remote[report.stamp]
            if (issue != null) toAdopt += report to issue else toUpload += report
        }
        return BackfillPlan(toUpload, toAdopt)
    }

    /**
     * Posts local reports that never became GitHub issues. Reports the repo
     * already has (matched by the capture stamp in the issue text) are recorded
     * locally instead of being posted twice. [onDone] fires on the main thread.
     */
    fun backfillMissedReports(context: Context, onDone: ((BackfillResult) -> Unit)? = null) {
        ensureSeeded(context)
        val settings = CupcakeSettings(context)
        val token = settings.feedbackGithubToken
        val repo = settings.feedbackGithubRepo
        if (token.isBlank() || repo.isBlank()) {
            postResult(onDone, BackfillResult(0, 0, 0))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                backfillBlocking(context, token, repo)
            } catch (_: Exception) {
                BackfillResult(0, 0, 0)
            }
            postResult(onDone, result)
        }
    }

    internal fun backfillBlocking(context: Context, token: String, repo: String): BackfillResult {
        val remote = try {
            fetchRemoteReports(token, repo)
        } catch (_: Exception) {
            // Without the repo listing we can't tell what is already an issue,
            // so don't risk double-posting — the next submit retries.
            return BackfillResult(0, 0, 0)
        }
        val sidecarStamps = getSubmissions(context).map { it.stamp }.toSet()
        val plan = planBackfill(findPendingReports(FeedbackCapture.feedbackDir(context)), sidecarStamps, remote)

        plan.toAdopt.forEach { (report, issue) ->
            recordSubmission(context, report.stamp, issue.title, issue.number, issue.state, issue.url)
        }

        var uploaded = 0
        var failed = 0
        plan.toUpload.forEachIndexed { index, report ->
            val number = uploadPendingFile(context, token, repo, report.mdFile, report.stamp)
            if (number != null) uploaded++ else failed++
            // Stay well clear of GitHub's secondary rate limits on issue creation.
            if (index < plan.toUpload.lastIndex) Thread.sleep(500)
        }
        return BackfillResult(uploaded, plan.toAdopt.size, failed)
    }

    // ── GitHub API ─────────────────────────────────────────────────

    private fun postIssue(token: String, repo: String, title: String, body: String): Int {
        val payload = JSONObject().apply {
            put("title", title.take(256))
            put("body", body)
            put("labels", JSONArray().put(LABEL))
        }

        val conn = URL("$API_BASE/repos/$repo/issues").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = readStream(conn.errorStream ?: conn.inputStream)
            throw RuntimeException("GitHub API $code: $err")
        }

        val json = JSONObject(readStream(conn.inputStream))
        return json.getInt("number")
    }

    private fun postIssue(token: String, repo: String, title: String, feedbackResult: FeedbackResult): Int =
        postIssue(token, repo, title, buildIssueBody(feedbackResult.markdown))

    /**
     * Issue body for a report. The screenshot is never inlined: GitHub strips
     * data-URI images from rendered issues, and anything over ~48 KB of image
     * pushes the body past the 65536-char API limit, failing the whole upload
     * with a 422. Instead the `![screenshot](...)` link is rewritten to point
     * at the PNG on the device. Oversized reports are truncated as a last
     * resort — a trimmed issue beats a rejected one.
     */
    internal fun buildIssueBody(text: String): String {
        val truncated = if (text.length > MAX_REPORT_CHARS) {
            text.take(MAX_REPORT_CHARS) + "\n\n_(truncated — full report is on the device)_"
        } else {
            text
        }
        return SCREENSHOT_LINK_REGEX.replace(truncated) { m ->
            "_Screenshot on device: `${m.groupValues[1]}` (Downloads/${FeedbackCapture.DOWNLOADS_FOLDER}/)_"
        }
    }

    private fun extractTitle(markdown: String): String? {
        // Try the first non-empty line after "# Cupcake Comics feedback — " header
        val lines = markdown.lines()
        var started = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## Notes")) {
                started = true
                continue
            }
            if (started && trimmed.isNotBlank() && !trimmed.startsWith("##") && !trimmed.startsWith("_File:") && !trimmed.startsWith("![screenshot]")) {
                return trimmed.take(120)
            }
        }
        return null
    }

    // ── Status tracking ─────────────────────────────────────────────

    /** Return all tracked submissions from the sidecar file. */
    fun getSubmissions(context: Context): List<Submission> {
        val file = sidecarFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val arr = json.getJSONArray("submissions")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Submission(
                    stamp = obj.getString("stamp"),
                    title = obj.optString("title", ""),
                    issueNumber = obj.getInt("issueNumber"),
                    state = obj.optString("state", "open"),
                    url = obj.optString("url", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Sync open/closed state from GitHub. */
    fun syncStatus(context: Context) {
        ensureSeeded(context)
        val settings = CupcakeSettings(context)
        val token = settings.feedbackGithubToken
        val repo = settings.feedbackGithubRepo
        if (token.isBlank() || repo.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val openIssues = fetchIssueStates(token, repo, "open")
                val closedIssues = fetchIssueStates(token, repo, "closed")
                val states = openIssues + closedIssues

                val subs = getSubmissions(context)
                val updated = subs.map { sub ->
                    val ghState = states[sub.issueNumber]
                    if (ghState != null) sub.copy(state = ghState) else sub
                }
                writeSubmissions(context, updated)
            } catch (_: Exception) {
                // Silently fail — next sync will retry
            }
        }
    }

    private fun fetchIssueStates(token: String, repo: String, state: String): Map<Int, String> =
        fetchIssues(token, repo, state, strict = false)
            .associate { it.getInt("number") to it.getString("state") }

    /** Maps capture stamp → issue for every feedback-labelled issue the repo knows about. */
    private fun fetchRemoteReports(token: String, repo: String): Map<String, RemoteIssue> {
        val result = mutableMapOf<String, RemoteIssue>()
        for (state in listOf("open", "closed")) {
            for (obj in fetchIssues(token, repo, state, strict = true)) {
                val title = obj.optString("title", "")
                val issue = RemoteIssue(
                    number = obj.getInt("number"),
                    state = obj.optString("state", state),
                    title = title,
                    url = obj.optString("html_url", ""),
                )
                extractStamps("$title\n${obj.optString("body", "")}")
                    .forEach { stamp -> result.putIfAbsent(stamp, issue) }
            }
        }
        return result
    }

    /**
     * Pages through the repo's feedback-labelled issues in [state].
     * In strict mode HTTP errors throw — callers that dedupe against the repo
     * must not guess. Otherwise the partial result is returned.
     */
    private fun fetchIssues(token: String, repo: String, state: String, strict: Boolean): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val conn = URL("$API_BASE/repos/$repo/issues?state=$state&labels=$LABEL&per_page=100&page=$page")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode !in 200..299) {
                if (strict) throw RuntimeException("GitHub API ${conn.responseCode} listing $state issues")
                break
            }

            val json = JSONArray(readStream(conn.inputStream))
            if (json.length() == 0) break

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                if (obj.has("pull_request")) continue // the issues endpoint also lists PRs
                result += obj
            }

            // Check for Link header pagination
            val link = conn.getHeaderField("Link")
            if (link == null || !link.contains("rel=\"next\"")) break
            page++
        }
        return result
    }

    private fun recordSubmission(context: Context, stamp: String, title: String, issueNumber: Int, state: String) {
        recordSubmission(context, stamp, title, issueNumber, state, url = "")
    }

    private fun recordSubmission(context: Context, stamp: String, title: String, issueNumber: Int, state: String, url: String) {
        val subs = getSubmissions(context).toMutableList()
        // Remove any existing entry for same stamp or same issue number
        subs.removeAll { it.stamp == stamp || it.issueNumber == issueNumber }
        val link = url.ifBlank {
            "https://github.com/${CupcakeSettings(context).feedbackGithubRepo}/issues/$issueNumber"
        }
        subs.add(Submission(stamp, title, issueNumber, state, link))
        writeSubmissions(context, subs)
    }

    private fun writeSubmissions(context: Context, subs: List<Submission>) {
        val file = sidecarFile(context)
        val arr = JSONArray()
        subs.forEach { s ->
            arr.put(JSONObject().apply {
                put("stamp", s.stamp)
                put("title", s.title)
                put("issueNumber", s.issueNumber)
                put("state", s.state)
                put("url", s.url)
            })
        }
        file.writeText(JSONObject().put("submissions", arr).toString(2), Charsets.UTF_8)
    }

    private fun sidecarFile(context: Context): File {
        val dir = FeedbackCapture.feedbackDir(context)
        return File(dir, SIDECAR_FILE)
    }

    // ── Utilities ───────────────────────────────────────────────────

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            .readText()
    }

    /** Check network: is this device on an unmetered (Wi‑Fi) connection? */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
