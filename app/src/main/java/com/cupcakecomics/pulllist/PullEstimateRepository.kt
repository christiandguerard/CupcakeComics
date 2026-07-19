package com.cupcakecomics.pulllist

import android.content.Context
import android.graphics.Color
import com.cupcakecomics.comicvine.ComicVineClient
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.CupcakeDatabase
import com.cupcakecomics.data.MonitoredFolderEntity
import com.cupcakecomics.kapowarr.KapowarrClient
import com.cupcakecomics.settings.CupcakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

data class SeriesEstimate(
    val folderId: Long,
    val seriesName: String,
    val status: String,
    val lastReleaseAt: Long?,
    val nextReleaseAt: Long?,
    val typicalGapDays: Int?,
    val accentColor: Int,
    val sourceLabel: String,
    val comicvineId: Int?,
) {
    fun daysUntil(now: Long = System.currentTimeMillis()): Int? {
        val next = nextReleaseAt ?: return null
        if (status == MonitoredFolderEntity.SERIES_ENDED) return null
        val days = TimeUnit.MILLISECONDS.toDays(next - now).toInt()
        return days
    }

    /** 0f..1f progress from last release toward next. */
    fun releaseProgress(now: Long = System.currentTimeMillis()): Float {
        val last = lastReleaseAt ?: return 0f
        val next = nextReleaseAt ?: return if (status == MonitoredFolderEntity.SERIES_ENDED) 1f else 0f
        if (next <= last) return 0f
        return ((now - last).toFloat() / (next - last).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Builds per-series release estimates from SMB cadence, Kapowarr library metadata,
 * and optional ComicVine lookups (ended detection + last issue dates).
 */
class PullEstimateRepository(context: Context) {
    private val app = context.applicationContext
    private val db = CupcakeDatabase.get(app)
    private val connections = ConnectionRepository(app)
    private val settings = CupcakeSettings(app)

    suspend fun estimates(forceRefresh: Boolean = false): List<SeriesEstimate> =
        withContext(Dispatchers.IO) {
            val folders = db.monitoredFolderDao().getAll()
            if (folders.isEmpty()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val refreshed = folders.map { folder ->
                ensureAccent(folder)
                if (forceRefresh || needsRefresh(folder, now)) {
                    refreshFolder(folder, now)
                } else {
                    folder
                }
            }
            refreshed.map { toEstimate(it) }
                .sortedWith(
                    compareBy<SeriesEstimate> {
                        it.status == MonitoredFolderEntity.SERIES_ENDED
                    }.thenBy { it.daysUntil(now) ?: Int.MAX_VALUE }
                        .thenBy { it.seriesName.lowercase() },
                )
        }

    suspend fun estimateForPath(shareId: Long, relativePath: String): SeriesEstimate? =
        withContext(Dispatchers.IO) {
            val folders = db.monitoredFolderDao().getAll().filter { it.shareId == shareId }
            val path = relativePath.trim().trim('/').replace('\\', '/')
            val folder = folders.firstOrNull { f ->
                val root = f.relativePath.trim().trim('/')
                root.isEmpty() || path == root || path.startsWith("$root/")
            } ?: return@withContext null
            val now = System.currentTimeMillis()
            val current = if (needsRefresh(folder, now)) refreshFolder(folder, now) else ensureAccent(folder)
            toEstimate(current)
        }

    private fun needsRefresh(folder: MonitoredFolderEntity, now: Long = System.currentTimeMillis()): Boolean {
        if (folder.metadataUpdatedAt <= 0L) return true
        if (folder.nextReleaseAt == null && folder.seriesStatus != MonitoredFolderEntity.SERIES_ENDED) {
            return now - folder.metadataUpdatedAt > REFRESH_MS / 2
        }
        return now - folder.metadataUpdatedAt > REFRESH_MS
    }

    private suspend fun refreshFolder(
        folder: MonitoredFolderEntity,
        now: Long = System.currentTimeMillis(),
    ): MonitoredFolderEntity {
        var working = ensureAccent(folder)
        val cadence = PullCadenceEstimator.summarize(app, listOf(working), now)
            .estimates.firstOrNull()

        // Kapowarr match (comicvine id + issue dates)
        val kap = matchKapowarr(working)
        if (kap != null) {
            working = working.copy(
                kapowarrVolumeId = kap.volumeId,
                comicvineId = kap.comicvineId ?: working.comicvineId,
                lastReleaseAt = kap.lastReleaseAt ?: working.lastReleaseAt,
            )
        }

        // ComicVine enrich when key present
        val cvKey = settings.comicVineApiKey.trim()
        if (cvKey.isNotBlank()) {
            val cv = enrichComicVine(working, cvKey)
            if (cv != null) working = cv
        }

        val gapDays = working.typicalGapDays
            ?: cadence?.typicalGapDays
            ?: DEFAULT_GAP_DAYS
        val last = working.lastReleaseAt ?: cadence?.let { est ->
            // Derive last from cadence next - gap
            est.nextAtMillis?.let { it - TimeUnit.DAYS.toMillis(gapDays.toLong()) }
        }
        val ended = working.seriesStatus == MonitoredFolderEntity.SERIES_ENDED ||
            isEndedByAge(last, gapDays, now)

        val next = when {
            ended -> null
            working.nextReleaseAt != null && working.nextReleaseAt!! > now -> working.nextReleaseAt
            cadence?.nextAtMillis != null && !ended -> cadence.nextAtMillis
            last != null -> last + TimeUnit.DAYS.toMillis(gapDays.toLong())
            else -> null
        }

        val updated = working.copy(
            seriesStatus = when {
                ended -> MonitoredFolderEntity.SERIES_ENDED
                next != null || last != null -> MonitoredFolderEntity.SERIES_ONGOING
                else -> MonitoredFolderEntity.SERIES_UNKNOWN
            },
            lastReleaseAt = last,
            nextReleaseAt = if (ended) null else next,
            typicalGapDays = gapDays,
            metadataUpdatedAt = now,
        )
        db.monitoredFolderDao().update(updated)
        return updated
    }

    private data class KapMatch(
        val volumeId: Int,
        val comicvineId: Int?,
        val lastReleaseAt: Long?,
    )

    private suspend fun matchKapowarr(folder: MonitoredFolderEntity): KapMatch? {
        val profiles = connections.getAllKapowarrProfiles()
        if (profiles.isEmpty()) return null
        val client = KapowarrClient(connections.credentialStore())
        val needle = folder.displayName.lowercase().trim()
        for (profile in profiles) {
            val volumes = client.listVolumes(profile).getOrNull() ?: continue
            val hit = volumes.firstOrNull { v ->
                folder.kapowarrVolumeId != null && v.id == folder.kapowarrVolumeId ||
                    folder.comicvineId != null && v.comicvineId == folder.comicvineId ||
                    v.title.lowercase().trim() == needle ||
                    v.title.lowercase().contains(needle) ||
                    needle.contains(v.title.lowercase())
            } ?: continue
            val detail = client.getVolume(profile, hit.id).getOrNull()
            val last = detail?.issues
                ?.mapNotNull { it.releaseAtMillis }
                ?.maxOrNull()
            return KapMatch(hit.id, hit.comicvineId ?: detail?.comicvineId, last)
        }
        return null
    }

    private fun enrichComicVine(folder: MonitoredFolderEntity, apiKey: String): MonitoredFolderEntity? {
        val client = ComicVineClient(apiKey)
        val volumeId = folder.comicvineId ?: run {
            val hits = client.searchVolumes(folder.displayName).getOrNull().orEmpty()
            pickBestVolume(folder.displayName, hits)?.id
        } ?: return null
        val volume = client.getVolume(volumeId).getOrNull() ?: return null
        val lastIssue = volume.lastIssueId?.let { client.getIssue(it).getOrNull() }
        val textEnded = ComicVineClient.looksEnded(
            volume.description,
            volume.deck,
            volume.lastIssueName,
        )
        return folder.copy(
            comicvineId = volume.id,
            lastReleaseAt = lastIssue?.releaseAtMillis ?: folder.lastReleaseAt,
            seriesStatus = if (textEnded) {
                MonitoredFolderEntity.SERIES_ENDED
            } else {
                folder.seriesStatus
            },
        )
    }

    private fun pickBestVolume(
        displayName: String,
        hits: List<com.cupcakecomics.comicvine.ComicVineVolumeHit>,
    ): com.cupcakecomics.comicvine.ComicVineVolumeHit? {
        if (hits.isEmpty()) return null
        val needle = displayName.lowercase().trim()
        val year = Regex("""\b((?:19|20)\d{2})\b""").find(displayName)?.groupValues?.get(1)?.toIntOrNull()
        return hits
            .sortedWith(
                compareByDescending<com.cupcakecomics.comicvine.ComicVineVolumeHit> {
                    it.name.lowercase() == needle
                }.thenByDescending {
                    year != null && it.startYear == year
                }.thenByDescending {
                    it.name.lowercase().startsWith(needle.take(12))
                }.thenByDescending { it.issueCount },
            )
            .firstOrNull()
    }

    private fun isEndedByAge(lastReleaseAt: Long?, gapDays: Int, now: Long): Boolean {
        if (lastReleaseAt == null) return false
        val threshold = TimeUnit.DAYS.toMillis(
            maxOf(90L, (gapDays * 2.5).roundToInt().toLong()),
        )
        return now - lastReleaseAt > threshold
    }

    private suspend fun ensureAccent(folder: MonitoredFolderEntity): MonitoredFolderEntity {
        if (folder.accentColor != 0) return folder
        val color = randomAccent(folder.id)
        val updated = folder.copy(accentColor = color)
        db.monitoredFolderDao().update(updated)
        return updated
    }

    private fun toEstimate(folder: MonitoredFolderEntity): SeriesEstimate {
        val source = when {
            folder.comicvineId != null && folder.kapowarrVolumeId != null -> "ComicVine + Kapowarr"
            folder.comicvineId != null -> "ComicVine"
            folder.kapowarrVolumeId != null -> "Kapowarr"
            else -> "SMB cadence"
        }
        return SeriesEstimate(
            folderId = folder.id,
            seriesName = folder.displayName,
            status = folder.seriesStatus,
            lastReleaseAt = folder.lastReleaseAt,
            nextReleaseAt = folder.nextReleaseAt,
            typicalGapDays = folder.typicalGapDays,
            accentColor = if (folder.accentColor != 0) folder.accentColor else randomAccent(folder.id),
            sourceLabel = source,
            comicvineId = folder.comicvineId,
        )
    }

    companion object {
        private val REFRESH_MS = TimeUnit.HOURS.toMillis(12)
        private const val DEFAULT_GAP_DAYS = 30

        fun randomAccent(seed: Long): Int {
            val rnd = Random(seed xor 0x5DEECE66DL)
            val hsv = floatArrayOf(
                rnd.nextFloat() * 360f,
                0.55f + rnd.nextFloat() * 0.35f,
                0.75f + rnd.nextFloat() * 0.2f,
            )
            return Color.HSVToColor(hsv)
        }
    }
}
