package com.cupcakecomics.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.cupcakecomics.settings.CupcakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads saved feedback reports to GitHub Issues.
 *
 * After FeedbackCapture writes the local files, the app can call [uploadReport]
 * immediately (fire-and-forget) or enqueue via WorkManager for Wi-Fi conditions.
 *
 * Tracked reports are recorded in a local JSON sidecar so we can
 * distinguish pending / submitted / addressed statuses.
 */
object FeedbackUploader {
    private const val API_BASE = "https://api.github.com"
    private const val SIDECAR_FILE = "feedback_submitted.json"
    private const val LABEL = "feedback"

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
     * On success writes a tracking sidecar so we know it's been sent.
     */
    fun uploadReport(context: Context, result: FeedbackResult, title: String) {
        val settings = CupcakeSettings(context)
        val token = settings.feedbackGithubToken
        val repo = settings.feedbackGithubRepo
        if (token.isBlank() || repo.isBlank()) return  // not configured

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val issueNumber = postIssue(token, repo, title, result)
                recordSubmission(context, result.stamp, title, issueNumber, "open")
            } catch (_: Exception) {
                // Leave files in place — will retry via WorkManager / manual
            }
        }
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
        pngFile: File?,
        stamp: String,
    ): Int? {
        return try {
            val text = mdFile.readText(Charsets.UTF_8)
            val title = extractTitle(text) ?: "Feedback: $stamp"
            val body = buildIssueBody(text, pngFile)
            val issueNumber = postIssue(token, repo, title, body)
            recordSubmission(context, stamp, title, issueNumber, "open")
            issueNumber
        } catch (_: Exception) {
            null
        }
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

    private fun postIssue(token: String, repo: String, title: String, feedbackResult: FeedbackResult): Int {
        val body = buildIssueBody(feedbackResult)
        return postIssue(token, repo, title, body)
    }

    private fun buildIssueBody(feedbackResult: FeedbackResult): String {
        val sb = StringBuilder()
        sb.appendLine(feedbackResult.markdown)

        // Embed screenshot as base64 data URI
        val shotFile = feedbackResult.screenshotFile
        if (shotFile != null && shotFile.exists() && shotFile.length() < 3_000_000) {
            try {
                val bytes = FileInputStream(shotFile).use { it.readBytes() }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                sb.appendLine()
                sb.appendLine("## Screenshot (inline)")
                sb.appendLine()
                sb.appendLine("![screenshot](data:image/png;base64,$b64)")
                sb.appendLine()
            } catch (_: Exception) {
                // skip screenshot if encoding fails
            }
        }

        return sb.toString()
    }

    private fun buildIssueBody(text: String, pngFile: File?): String {
        val sb = StringBuilder()
        sb.appendLine(text)

        if (pngFile != null && pngFile.exists() && pngFile.length() < 3_000_000) {
            try {
                val bytes = FileInputStream(pngFile).use { it.readBytes() }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                sb.appendLine()
                sb.appendLine("## Screenshot (inline)")
                sb.appendLine()
                sb.appendLine("![screenshot](data:image/png;base64,$b64)")
                sb.appendLine()
            } catch (_: Exception) { }
        }

        return sb.toString()
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

    private fun fetchIssueStates(token: String, repo: String, state: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var page = 1
        while (true) {
            val conn = URL("$API_BASE/repos/$repo/issues?state=$state&labels=$LABEL&per_page=100&page=$page")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode !in 200..299) break

            val json = JSONArray(readStream(conn.inputStream))
            if (json.length() == 0) break

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                result[obj.getInt("number")] = obj.getString("state")
            }

            // Check for Link header pagination
            val link = conn.getHeaderField("Link")
            if (link == null || !link.contains("rel=\"next\"")) break
            page++
        }
        return result
    }

    private fun recordSubmission(context: Context, stamp: String, title: String, issueNumber: Int, state: String) {
        val subs = getSubmissions(context).toMutableList()
        // Remove any existing entry for same stamp or same issue number
        subs.removeAll { it.stamp == stamp || it.issueNumber == issueNumber }
        subs.add(Submission(stamp, title, issueNumber, state, "https://github.com/${CupcakeSettings(context).feedbackGithubRepo}/issues/$issueNumber"))
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
