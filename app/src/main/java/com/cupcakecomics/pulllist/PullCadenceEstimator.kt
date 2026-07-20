package com.cupcakecomics.pulllist

import android.content.Context
import com.cupcakecomics.data.ConnectionRepository
import com.cupcakecomics.data.MonitoredFolderEntity
import com.cupcakecomics.smb.ComicFileNames
import com.cupcakecomics.smb.SmbBrowser
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Estimates when the next issue may appear under a monitored series folder,
 * using the same signal comic-watcher style tools use: SMB file last-write times.
 */
object PullCadenceEstimator {
    data class SeriesEta(
        val seriesName: String,
        val nextAtMillis: Long?,
        val typicalGapDays: Int?,
        val sampleCount: Int,
    )

    data class Summary(
        val lines: List<String>,
        val estimates: List<SeriesEta>,
    )

    suspend fun summarize(
        context: Context,
        folders: List<MonitoredFolderEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Summary {
        if (folders.isEmpty()) {
            return Summary(emptyList(), emptyList())
        }
        val connections = ConnectionRepository(context)
        val browser = SmbBrowser(connections.credentialStore())
        val estimates = folders.map { folder ->
            estimateFolder(folder, connections, browser, nowMillis)
        }
        val lines = estimates.mapNotNull { eta -> formatLine(eta, nowMillis) }
        return Summary(lines = lines, estimates = estimates)
    }

    private suspend fun estimateFolder(
        folder: MonitoredFolderEntity,
        connections: ConnectionRepository,
        browser: SmbBrowser,
        nowMillis: Long,
    ): SeriesEta {
        val share = connections.getSmbShare(folder.shareId)
            ?: return SeriesEta(folder.displayName, null, null, 0)
        val times = collectComicTimes(browser, share, folder.relativePath)
        return computeEtaFromTimes(folder.displayName, times, nowMillis)
    }

    internal fun computeEtaFromTimes(
        displayName: String,
        times: List<Long>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SeriesEta {
        if (times.size < 2) {
            return SeriesEta(displayName, null, null, times.size)
        }
        val sorted = times.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }
            .filter { it in MIN_GAP_MS..MAX_GAP_MS }
        if (gaps.isEmpty()) {
            return SeriesEta(displayName, null, null, times.size)
        }
        val median = median(gaps)
        val gapDays = TimeUnit.MILLISECONDS.toDays(median).toInt().coerceAtLeast(1)
        val last = sorted.last()
        var next = last + median
        // If the estimate is already past, step forward by the median until in the future.
        while (next < nowMillis - DAY_MS) {
            next += median
        }
        if (next < nowMillis) {
            next = nowMillis + median / 4
        }
        return SeriesEta(
            seriesName = displayName,
            nextAtMillis = next,
            typicalGapDays = gapDays,
            sampleCount = times.size,
        )
    }

    private suspend fun collectComicTimes(
        browser: SmbBrowser,
        share: com.cupcakecomics.data.SmbShareEntity,
        rootPath: String,
    ): List<Long> {
        val times = ArrayList<Long>(64)
        val queue = ArrayDeque<String>()
        queue.add(rootPath)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITS && times.size < MAX_SAMPLES) {
            val path = queue.removeFirst()
            val listing = browser.list(share, path).getOrNull() ?: continue
            for (entry in listing) {
                visited++
                if (entry.isDirectory) {
                    queue.addLast(entry.relativePath)
                } else if (ComicFileNames.isComicArchive(entry.name) && entry.lastModified > 0L) {
                    times.add(entry.lastModified)
                }
                if (visited >= MAX_VISITS || times.size >= MAX_SAMPLES) break
            }
        }
        return times
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2
        } else {
            sorted[mid]
        }
    }

    internal fun formatLine(eta: SeriesEta, nowMillis: Long): String? {
        val next = eta.nextAtMillis ?: return null
        val gap = eta.typicalGapDays ?: return null
        val dayMs = DAY_MS.toDouble()
        val daysUntil = ((next - nowMillis) / dayMs).roundToInt().coerceAtLeast(0)
        val whenText = when {
            daysUntil <= 0 -> "any day now"
            daysUntil == 1 -> "tomorrow"
            daysUntil < 14 -> "in about $daysUntil days"
            else -> {
                val weeks = (daysUntil / 7.0).roundToInt().coerceAtLeast(1)
                if (weeks == 1) "in about a week" else "in about $weeks weeks"
            }
        }
        val cadence = if (gap == 1) "daily" else "every ~$gap days"
        return "${eta.seriesName} — next $whenText ($cadence)"
    }

    private const val MAX_VISITS = 2_500
    private const val MAX_SAMPLES = 80
    private val DAY_MS = TimeUnit.DAYS.toMillis(1)
    private val MIN_GAP_MS = TimeUnit.DAYS.toMillis(2)
    private val MAX_GAP_MS = TimeUnit.DAYS.toMillis(120)
}
