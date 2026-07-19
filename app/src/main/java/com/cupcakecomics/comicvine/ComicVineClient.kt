package com.cupcakecomics.comicvine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class ComicVineVolumeHit(
    val id: Int,
    val name: String,
    val startYear: Int?,
    val issueCount: Int,
    val siteUrl: String?,
)

data class ComicVineVolumeDetail(
    val id: Int,
    val name: String,
    val startYear: Int?,
    val issueCount: Int,
    val description: String?,
    val deck: String?,
    val lastIssueId: Int?,
    val lastIssueNumber: String?,
    val lastIssueName: String?,
)

data class ComicVineIssueDetail(
    val id: Int,
    val issueNumber: String?,
    val name: String?,
    /** Prefer store date, else cover date. */
    val releaseAtMillis: Long?,
)

/**
 * Thin ComicVine API client. Requires a free developer API key from
 * https://comicvine.gamespot.com/api/
 */
class ComicVineClient(
    private val apiKey: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun searchVolumes(query: String, limit: Int = 8): Result<List<ComicVineVolumeHit>> = runCatching {
        val q = query.trim()
        require(q.isNotEmpty()) { "Empty query" }
        require(apiKey.isNotBlank()) { "Missing ComicVine API key" }
        val body = get(
            "/api/search/",
            mapOf(
                "query" to q,
                "resources" to "volume",
                "limit" to limit.toString(),
                "field_list" to "id,name,start_year,count_of_issues,site_detail_url",
            ),
        )
        checkError(body)
        val results = body.optJSONArray("results") ?: return@runCatching emptyList()
        val out = ArrayList<ComicVineVolumeHit>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id <= 0) continue
            out.add(
                ComicVineVolumeHit(
                    id = id,
                    name = o.optString("name", "Untitled"),
                    startYear = o.optInt("start_year").takeIf { o.has("start_year") && it > 0 },
                    issueCount = o.optInt("count_of_issues", 0),
                    siteUrl = o.optString("site_detail_url").takeIf { it.isNotBlank() },
                ),
            )
        }
        out
    }

    fun getVolume(volumeId: Int): Result<ComicVineVolumeDetail> = runCatching {
        require(apiKey.isNotBlank()) { "Missing ComicVine API key" }
        val body = get(
            "/api/volume/4050-$volumeId/",
            mapOf(
                "field_list" to "id,name,start_year,count_of_issues,description,deck,last_issue",
            ),
        )
        checkError(body)
        val o = body.optJSONObject("results")
            ?: throw IllegalStateException("No volume payload")
        val last = o.optJSONObject("last_issue")
        ComicVineVolumeDetail(
            id = o.optInt("id", volumeId),
            name = o.optString("name", "Untitled"),
            startYear = o.optInt("start_year").takeIf { o.has("start_year") && it > 0 },
            issueCount = o.optInt("count_of_issues", 0),
            description = o.optString("description").takeIf { it.isNotBlank() },
            deck = o.optString("deck").takeIf { it.isNotBlank() },
            lastIssueId = last?.optInt("id")?.takeIf { it > 0 },
            lastIssueNumber = last?.optString("issue_number")?.takeIf { it.isNotBlank() },
            lastIssueName = last?.optString("name")?.takeIf { it.isNotBlank() },
        )
    }

    fun getIssue(issueId: Int): Result<ComicVineIssueDetail> = runCatching {
        require(apiKey.isNotBlank()) { "Missing ComicVine API key" }
        val body = get(
            "/api/issue/4000-$issueId/",
            mapOf(
                "field_list" to "id,name,issue_number,cover_date,store_date",
            ),
        )
        checkError(body)
        val o = body.optJSONObject("results")
            ?: throw IllegalStateException("No issue payload")
        ComicVineIssueDetail(
            id = o.optInt("id", issueId),
            issueNumber = o.optString("issue_number").takeIf { it.isNotBlank() },
            name = o.optString("name").takeIf { it.isNotBlank() },
            releaseAtMillis = parseDate(o.optString("store_date"))
                ?: parseDate(o.optString("cover_date")),
        )
    }

    private fun get(path: String, query: Map<String, String>): JSONObject {
        val sb = StringBuilder(BASE).append(path.trimStart('/'))
        sb.append("?api_key=").append(java.net.URLEncoder.encode(apiKey, "UTF-8"))
        sb.append("&format=json")
        query.forEach { (k, v) ->
            sb.append('&').append(java.net.URLEncoder.encode(k, "UTF-8"))
                .append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val req = Request.Builder().url(sb.toString()).get()
            .header("User-Agent", "CupcakeComics/0.1 (Android; PullList)")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("ComicVine HTTP ${resp.code}: ${text.take(160)}")
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun checkError(body: JSONObject) {
        val status = body.optString("error", "OK")
        if (!status.equals("OK", ignoreCase = true)) {
            throw IllegalStateException(status)
        }
    }

    companion object {
        private const val BASE = "https://comicvine.gamespot.com/"
        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

        fun parseDate(raw: String?): Long? {
            if (raw.isNullOrBlank() || raw == "null") return null
            return try {
                val d = LocalDate.parse(raw.take(10), DATE)
                d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Throwable) {
                null
            }
        }

        fun looksEnded(description: String?, deck: String?, lastIssueName: String?): Boolean {
            val blob = listOfNotNull(description, deck, lastIssueName)
                .joinToString(" ")
                .lowercase()
            if (blob.isBlank()) return false
            val markers = listOf(
                "final issue",
                "series finale",
                "concluded",
                "series ends",
                "ends with",
                "last issue of the series",
                "cancelled",
                "canceled",
                "one-shot",
            )
            return markers.any { it in blob }
        }
    }
}
