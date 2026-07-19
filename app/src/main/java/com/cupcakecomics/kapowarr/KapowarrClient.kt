package com.cupcakecomics.kapowarr

import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.KapowarrProfileEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class KapowarrError {
    AUTH_INVALID,
    SERVER_UNREACHABLE,
    CLEARTEXT_BLOCKED,
    NO_ROOT_FOLDER,
    ALREADY_ADDED,
    VALIDATION,
    SERVER_ERROR,
    UNKNOWN,
}

class KapowarrException(val code: KapowarrError, message: String) : Exception(message)

data class KapowarrRootFolder(val id: Int, val folder: String)

data class KapowarrSearchResult(
    val comicvineId: Int,
    val title: String,
    val year: Int?,
    val volumeNumber: Int?,
    val alreadyAdded: Boolean,
    val siteUrl: String?,
    val coverUrl: String?,
)

data class KapowarrQueueItem(
    val id: Int,
    val title: String,
    val status: String,
    val progress: Double,
    val speedBytesPerSec: Long,
    val sizeBytes: Long,
    val volumeId: Int?,
    val sourceName: String?,
)

data class KapowarrAddResult(val volumeId: Int?, val folderPath: String? = null)

data class KapowarrVolumeSummary(
    val id: Int,
    val title: String,
    val year: Int?,
    val comicvineId: Int?,
    val issueCount: Int,
    val folder: String?,
)

data class KapowarrIssueSummary(
    val id: Int,
    val issueNumber: String?,
    val title: String?,
    val releaseAtMillis: Long?,
)

data class KapowarrVolumeDetail(
    val id: Int,
    val title: String,
    val comicvineId: Int?,
    val issueCount: Int,
    val folder: String?,
    val issues: List<KapowarrIssueSummary>,
)

/**
 * Minimal Kapowarr HTTP client per SPEC-v1 / ticket 002.
 * Auth via api_key query param. Envelope: { "error": null|string, "result": T }.
 */
class KapowarrClient(
    private val credentials: CredentialStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun probe(profile: KapowarrProfileEntity): Result<String> = runCatching {
        val apiKey = apiKey(profile)
        // Prefer auth/check; fall back to system/about.
        try {
            val body = getJson(profile.baseUrl, "/api/auth/check", apiKey)
            if (body.opt("error") != null && !body.isNull("error")) {
                throw KapowarrException(KapowarrError.AUTH_INVALID, body.optString("error", "auth failed"))
            }
            "ok"
        } catch (e: KapowarrException) {
            if (e.code == KapowarrError.AUTH_INVALID) throw e
            val about = getJson(profile.baseUrl, "/api/system/about", apiKey)
            checkEnvelope(about)
            about.optJSONObject("result")?.optString("version") ?: "ok"
        }
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    fun rootFolders(profile: KapowarrProfileEntity): Result<List<KapowarrRootFolder>> = runCatching {
        val body = getJson(profile.baseUrl, "/api/rootfolder", apiKey(profile))
        checkEnvelope(body)
        val result = body.opt("result")
        val list = mutableListOf<KapowarrRootFolder>()
        when (result) {
            is JSONArray -> {
                for (i in 0 until result.length()) {
                    val o = result.getJSONObject(i)
                    list.add(
                        KapowarrRootFolder(
                            id = o.optInt("id", o.optInt("folder_id", -1)),
                            folder = o.optString("folder", o.optString("path", "")),
                        ),
                    )
                }
            }
            is JSONObject -> {
                // single object unlikely
            }
        }
        if (list.isEmpty()) {
            throw KapowarrException(KapowarrError.NO_ROOT_FOLDER, "No root folders configured")
        }
        list
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    fun search(profile: KapowarrProfileEntity, query: String): Result<List<KapowarrSearchResult>> =
        runCatching {
            val q = query.trim()
            require(q.isNotEmpty()) { "Empty query" }
            val body = getJson(
                profile.baseUrl,
                "/api/volumes/search",
                apiKey(profile),
                mapOf("query" to q),
            )
            checkEnvelope(body)
            val result = body.opt("result") as? JSONArray
                ?: throw KapowarrException(KapowarrError.SERVER_ERROR, "Bad search payload")
            val out = mutableListOf<KapowarrSearchResult>()
            for (i in 0 until result.length()) {
                val o = result.getJSONObject(i)
                val cvId = o.optInt(
                    "comicvine_id",
                    o.optInt("comicvineId", o.optJSONObject("comicvine")?.optInt("id", -1) ?: -1),
                )
                if (cvId <= 0) continue
                val coverRaw = o.optString("cover_link", o.optString("cover", ""))
                    .takeIf { it.isNotBlank() }
                out.add(
                    KapowarrSearchResult(
                        comicvineId = cvId,
                        title = o.optString("title", o.optString("name", "Untitled")),
                        year = o.optInt("year").takeIf { o.has("year") && !o.isNull("year") && it > 0 },
                        volumeNumber = o.optInt("volume_number")
                            .takeIf { o.has("volume_number") && !o.isNull("volume_number") },
                        alreadyAdded = parseAlreadyAdded(o),
                        siteUrl = o.optString("site_url").takeIf { it.isNotBlank() },
                        coverUrl = KapowarrUrls.resolveMediaUrl(profile.baseUrl, coverRaw),
                    ),
                )
            }
            out
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(mapFailure(it)) },
        )

    fun downloadQueue(profile: KapowarrProfileEntity): Result<List<KapowarrQueueItem>> = runCatching {
        val body = getJson(profile.baseUrl, "/api/activity/queue", apiKey(profile))
        checkEnvelope(body)
        val result = body.opt("result") as? JSONArray ?: JSONArray()
        val out = mutableListOf<KapowarrQueueItem>()
        for (i in 0 until result.length()) {
            val o = result.getJSONObject(i)
            out.add(
                KapowarrQueueItem(
                    id = o.optInt("id", -1),
                    title = o.optString("title", "Download"),
                    status = o.optString("status", "unknown"),
                    progress = o.optDouble("progress", 0.0),
                    speedBytesPerSec = o.optLong("speed", 0L),
                    sizeBytes = o.optLong("size", -1L),
                    volumeId = o.optInt("volume_id").takeIf { o.has("volume_id") && it > 0 },
                    sourceName = o.optString("source_name").takeIf { it.isNotBlank() },
                ),
            )
        }
        out
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    fun addVolume(
        profile: KapowarrProfileEntity,
        comicvineId: Int,
        rootFolderId: Int,
        autoSearch: Boolean = true,
    ): Result<KapowarrAddResult> = runCatching {
        val payload = JSONObject()
            .put("comicvine_id", comicvineId)
            .put("root_folder_id", rootFolderId)
            .put("monitor", true)
            .put("monitoring_scheme", "all")
            .put("monitor_new_issues", true)
            .put("auto_search", autoSearch)
        val body = postJson(profile.baseUrl, "/api/volumes", apiKey(profile), payload)
        if (!body.isNull("error")) {
            val err = body.optString("error", "add failed")
            if (err.contains("already", ignoreCase = true)) {
                throw KapowarrException(KapowarrError.ALREADY_ADDED, err)
            }
            throw KapowarrException(KapowarrError.SERVER_ERROR, err)
        }
        val result = body.optJSONObject("result")
        val volumeId = result?.optInt("id", result.optInt("volume_id", -1))?.takeIf { it > 0 }
        if (autoSearch && volumeId != null) {
            // Best-effort task kick; ignore failure if auto_search on add already queued it.
            runCatching {
                val task = JSONObject().put("cmd", "auto_search").put("volume_id", volumeId)
                postJson(profile.baseUrl, "/api/system/tasks", apiKey(profile), task)
            }
        }
        val folderPath = result?.optString("folder", result.optString("path", ""))
            ?.takeIf { it.isNotBlank() }
        KapowarrAddResult(volumeId, folderPath)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    /** Resolve Kapowarr's actual series folder after adding a volume. */
    fun volumeFolder(profile: KapowarrProfileEntity, volumeId: Int): Result<String?> = runCatching {
        getVolume(profile, volumeId).getOrThrow().folder
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    fun listVolumes(profile: KapowarrProfileEntity): Result<List<KapowarrVolumeSummary>> = runCatching {
        val body = getJson(profile.baseUrl, "/api/volumes", apiKey(profile))
        checkEnvelope(body)
        val result = body.opt("result")
        val arr = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("volumes") ?: JSONArray().put(result)
            else -> JSONArray()
        }
        val out = mutableListOf<KapowarrVolumeSummary>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", o.optInt("volume_id", -1))
            if (id <= 0) continue
            out.add(
                KapowarrVolumeSummary(
                    id = id,
                    title = o.optString("title", o.optString("name", "Untitled")),
                    year = o.optInt("year").takeIf { o.has("year") && it > 0 },
                    comicvineId = o.optInt("comicvine_id", o.optInt("comicvineId", -1))
                        .takeIf { it > 0 },
                    issueCount = o.optInt("issue_count", o.optInt("issues", 0)),
                    folder = o.optString("folder", o.optString("path", ""))
                        .takeIf { it.isNotBlank() },
                ),
            )
        }
        out
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(mapFailure(it)) },
    )

    fun getVolume(profile: KapowarrProfileEntity, volumeId: Int): Result<KapowarrVolumeDetail> =
        runCatching {
            val body = getJson(profile.baseUrl, "/api/volumes/$volumeId", apiKey(profile))
            checkEnvelope(body)
            val result = body.opt("result")
            val volume = when (result) {
                is JSONObject -> result
                is JSONArray -> result.optJSONObject(0)
                else -> null
            } ?: throw KapowarrException(KapowarrError.SERVER_ERROR, "Missing volume")
            val issuesArr = volume.optJSONArray("issues") ?: JSONArray()
            val issues = mutableListOf<KapowarrIssueSummary>()
            for (i in 0 until issuesArr.length()) {
                val o = issuesArr.optJSONObject(i) ?: continue
                val release = parseKapowarrDate(
                    o.optString("date", o.optString("release_date", o.optString("cover_date", ""))),
                )
                issues.add(
                    KapowarrIssueSummary(
                        id = o.optInt("id", -1),
                        issueNumber = o.optString("issue_number", o.optString("number", ""))
                            .takeIf { it.isNotBlank() },
                        title = o.optString("title", o.optString("name", ""))
                            .takeIf { it.isNotBlank() },
                        releaseAtMillis = release,
                    ),
                )
            }
            KapowarrVolumeDetail(
                id = volume.optInt("id", volumeId),
                title = volume.optString("title", volume.optString("name", "Untitled")),
                comicvineId = volume.optInt("comicvine_id", volume.optInt("comicvineId", -1))
                    .takeIf { it > 0 },
                issueCount = volume.optInt("issue_count", issues.size),
                folder = volume.optString("folder", volume.optString("path", ""))
                    .takeIf { it.isNotBlank() },
                issues = issues,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(mapFailure(it)) },
        )

    private fun parseKapowarrDate(raw: String?): Long? {
        if (raw.isNullOrBlank() || raw == "null") return null
        // Epoch seconds/millis from Kapowarr
        raw.toLongOrNull()?.let { n ->
            return if (n < 10_000_000_000L) n * 1000L else n
        }
        return com.cupcakecomics.comicvine.ComicVineClient.parseDate(raw)
    }

    /** Kapowarr returns null or a volume id (int), not always a boolean. */
    private fun parseAlreadyAdded(o: JSONObject): Boolean {
        if (!o.has("already_added") || o.isNull("already_added")) {
            if (!o.has("alreadyAdded") || o.isNull("alreadyAdded")) return false
            return when (val v = o.opt("alreadyAdded")) {
                is Boolean -> v
                is Number -> v.toInt() > 0
                is String -> v.toIntOrNull()?.let { it > 0 } ?: v.equals("true", ignoreCase = true)
                else -> false
            }
        }
        return when (val v = o.opt("already_added")) {
            is Boolean -> v
            is Number -> v.toInt() > 0
            is String -> v.toIntOrNull()?.let { it > 0 } ?: v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun apiKey(profile: KapowarrProfileEntity): String {
        val key = credentials.getSecret(profile.apiKeyCredentialKey).orEmpty()
        if (key.isBlank()) {
            throw KapowarrException(KapowarrError.AUTH_INVALID, "Missing API key")
        }
        return key
    }

    private fun checkEnvelope(body: JSONObject) {
        if (!body.isNull("error")) {
            val err = body.optString("error", "error")
            if (err.contains("api", ignoreCase = true) || err.contains("auth", ignoreCase = true)) {
                throw KapowarrException(KapowarrError.AUTH_INVALID, err)
            }
            throw KapowarrException(KapowarrError.SERVER_ERROR, err)
        }
    }

    private fun getJson(
        baseUrl: String,
        path: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = buildUrl(baseUrl, path, apiKey, query)
        val req = Request.Builder().url(url).get().build()
        return execute(req)
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        apiKey: String,
        payload: JSONObject,
    ): JSONObject {
        val url = buildUrl(baseUrl, path, apiKey)
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return execute(req)
    }

    private fun buildUrl(
        baseUrl: String,
        path: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        // Outside-LAN Kapowarr must use HTTPS; block public cleartext even if NSC allows it.
        if (base.startsWith("http://", ignoreCase = true) && !KapowarrUrls.isPrivateLanHttp(base)) {
            throw KapowarrException(
                KapowarrError.CLEARTEXT_BLOCKED,
                "Public HTTP blocked — use HTTPS for non-LAN hosts",
            )
        }
        val p = if (path.startsWith("/")) path else "/$path"
        val sb = StringBuilder("$base$p?api_key=").append(java.net.URLEncoder.encode(apiKey, "UTF-8"))
        query.forEach { (k, v) ->
            sb.append('&').append(java.net.URLEncoder.encode(k, "UTF-8"))
                .append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString()
    }

    private fun execute(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) {
                    throw KapowarrException(KapowarrError.AUTH_INVALID, "Unauthorized (${resp.code})")
                }
                if (!resp.isSuccessful) {
                    throw KapowarrException(
                        KapowarrError.SERVER_ERROR,
                        "HTTP ${resp.code}: ${text.take(200)}",
                    )
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: KapowarrException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw KapowarrException(KapowarrError.SERVER_UNREACHABLE, e.message ?: "unreachable")
        } catch (e: java.net.ConnectException) {
            throw KapowarrException(KapowarrError.SERVER_UNREACHABLE, e.message ?: "connect failed")
        } catch (e: javax.net.ssl.SSLException) {
            // Outside-LAN Kapowarr uses HTTPS; surface TLS failures distinctly from cleartext blocks.
            throw KapowarrException(
                KapowarrError.SERVER_UNREACHABLE,
                e.message?.takeIf { it.isNotBlank() } ?: "TLS/SSL error",
            )
        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty()
            if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                throw KapowarrException(KapowarrError.CLEARTEXT_BLOCKED, msg)
            }
            throw KapowarrException(KapowarrError.SERVER_UNREACHABLE, msg.ifBlank { "network error" })
        }
    }

    private fun mapFailure(t: Throwable): Exception {
        return when (t) {
            is KapowarrException -> t
            else -> KapowarrException(KapowarrError.UNKNOWN, t.message ?: "unknown")
        }
    }
}
