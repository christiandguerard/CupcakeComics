package com.cupcakecomics.reader.source

import android.content.Context
import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.smb.ComicFileNames
import com.cupcakecomics.smb.SmbStageManager
import java.io.File

/**
 * Resolves the best [PageSource] for a comic — streaming CBZ over SMB when possible,
 * otherwise staging the full file and using Bubble2 parsers.
 */
object PageSourceFactory {

    data class OpenResult(
        val source: PageSource,
        val localFile: File? = null,
        val streamed: Boolean = false,
    )

    fun openLocal(file: File): PageSource = ParserPageSource.fromFile(file)

    /**
     * Prefer ZIP byte-range streaming for .cbz/.zip; stage everything else.
     */
    fun openSmb(
        context: Context,
        share: SmbShareEntity,
        relativePath: String,
        keepOffline: Boolean = false,
        onStageProgress: ((Long, Long) -> Unit)? = null,
    ): OpenResult {
        val name = relativePath.substringAfterLast('/').substringAfterLast('\\')
        if (ZipRangePageSource.isZipName(name)) {
            return try {
                val credentials = CredentialStore(context)
                val seekable = SmbSeekableByteSource(share, relativePath, credentials)
                val source = ZipRangePageSource(
                    source = seekable,
                    title = name,
                    remoteStreaming = true,
                )
                source.open() // validate central directory early
                OpenResult(source = source, streamed = true)
            } catch (t: Throwable) {
                // Fall back to full stage
                stageAndParse(context, share, relativePath, keepOffline, onStageProgress)
            }
        }
        return stageAndParse(context, share, relativePath, keepOffline, onStageProgress)
    }

    private fun stageAndParse(
        context: Context,
        share: SmbShareEntity,
        relativePath: String,
        keepOffline: Boolean,
        onStageProgress: ((Long, Long) -> Unit)?,
    ): OpenResult {
        require(ComicFileNames.isComicArchive(relativePath.substringAfterLast('/'))) {
            "Not a comic archive"
        }
        val staged = SmbStageManager(context, CredentialStore(context))
            .stage(share, relativePath, keepOffline, onProgress = onStageProgress)
            .getOrThrow()
        return OpenResult(
            source = ParserPageSource.fromFile(staged),
            localFile = staged,
            streamed = false,
        )
    }
}
