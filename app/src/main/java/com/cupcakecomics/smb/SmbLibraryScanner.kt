package com.cupcakecomics.smb

import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.SmbShareEntity
import java.util.ArrayDeque

data class SmbShareStats(
    val comicCount: Int,
    val totalBytes: Long,
)

/**
 * Metadata-only recursive scan for comic archives under a share's start path.
 */
class SmbLibraryScanner(credentials: CredentialStore) {
    private val browser = SmbBrowser(credentials)

    fun scan(share: SmbShareEntity, maxEntries: Int = 50_000): Result<SmbShareStats> = runCatching {
        var comics = 0
        var bytes = 0L
        var visited = 0
        val queue = ArrayDeque<String>()
        queue.add(SmbBrowser.normalizePath(share.startPath))

        while (queue.isNotEmpty() && visited < maxEntries) {
            val path = queue.removeFirst()
            val listing = browser.list(share, path).getOrThrow()
            for (entry in listing) {
                visited++
                if (visited > maxEntries) break
                if (entry.isDirectory) {
                    queue.addLast(entry.relativePath)
                } else if (ComicFileNames.isComicArchive(entry.name)) {
                    comics++
                    bytes += entry.size.coerceAtLeast(0L)
                }
            }
        }
        SmbShareStats(comicCount = comics, totalBytes = bytes)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalStateException(SmbBrowser.friendlyError(it), it)) },
    )
}
