package com.cupcakecomics.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** SMB folder the user enrolled for virtual Pull List monitoring. */
@Entity(
    tableName = "monitored_folders",
    indices = [Index(value = ["shareId", "relativePath"], unique = true)],
)
data class MonitoredFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    val relativePath: String,
    val displayName: String,
    val enrolledAt: Long = System.currentTimeMillis(),
    /** True after the first baseline scan (existing comics not added to pull list). */
    val baselined: Boolean = false,
    /** ComicVine volume id when known (from Kapowarr request or CV search). */
    val comicvineId: Int? = null,
    /** Kapowarr library volume id when linked. */
    val kapowarrVolumeId: Int? = null,
    /**
     * Series run status from ComicVine / Kapowarr heuristics:
     * `ongoing`, `ended`, or `unknown`.
     */
    val seriesStatus: String = SERIES_UNKNOWN,
    /** Last known issue release (store/cover date or SMB mtime), epoch millis. */
    val lastReleaseAt: Long? = null,
    /** Estimated or known next issue release, epoch millis. Null when ended/unknown. */
    val nextReleaseAt: Long? = null,
    /** Typical gap between issues in days (from cadence or CV). */
    val typicalGapDays: Int? = null,
    /** Stable accent for the release progress bar (ARGB). */
    val accentColor: Int = 0,
    val metadataUpdatedAt: Long = 0L,
) {
    companion object {
        const val SERIES_ONGOING = "ongoing"
        const val SERIES_ENDED = "ended"
        const val SERIES_UNKNOWN = "unknown"
    }
}

/**
 * Every comic path ever seen under a monitored folder.
 * New paths after baseline enter the pull list (`inPullList=true`).
 */
@Entity(tableName = "pull_comics")
data class PullComicEntity(
    /** Stable identity: smb:{shareId}:{relativePath} */
    @PrimaryKey val identityKey: String,
    val shareId: Long,
    val relativePath: String,
    val title: String,
    val sizeBytes: Long = 0L,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val inPullList: Boolean = false,
    val missing: Boolean = false,
    val highestPage: Int = 0,
    val pageCount: Int = 0,
    val markedReadManually: Boolean = false,
)

fun pullIdentityKey(shareId: Long, relativePath: String): String {
    val path = relativePath.trim().trim('/').replace('\\', '/')
    return "smb:$shareId:$path"
}
