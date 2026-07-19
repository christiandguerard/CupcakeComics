package com.cupcakecomics.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.reader.source.SmbSeekableByteSource
import com.cupcakecomics.reader.source.ZipRangePageSource
import com.cupcakecomics.smb.ComicFileNames
import com.cupcakecomics.smb.SmbBrowser
import com.cupcakecomics.smb.SmbListEntry
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.nkanaev.comics.Constants
import com.nkanaev.comics.managers.Utils
import com.nkanaev.comics.parsers.ParserFactory
import com.nkanaev.comics.view.CoverImageView
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Small cover cache for SMB browsing and network-backed Pull List entries.
 * Full-size covers remain in [FileCoverHandler] / [LocalCoverHandler] for downloads and media.
 */
object SmbNetworkCoverCache {
    private const val TAG = "SmbNetworkCover"
    private val fetchLock = Semaphore(2)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val smbConfig: SmbConfig = SmbConfig.builder()
        .withTimeout(45, TimeUnit.SECONDS)
        .withSoTimeout(45, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    data class PrecacheProgress(
        val message: String,
        val done: Int,
        val total: Int,
    )

    data class PrecacheResult(
        val cached: Int,
        val failed: Int,
        val alreadyHad: Int,
    )

    fun cacheFile(context: Context, sourceKey: String): File {
        val crc = CRC32()
        crc.update(sourceKey.toByteArray())
        val dir = File(context.cacheDir, "covers-network")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, String.format("ncover-%08X.jpg", crc.value))
    }

    fun sourceKey(shareId: Long, comicRelativePath: String): String =
        "smb:$shareId:${SmbBrowser.normalizePath(comicRelativePath)}"

    /**
     * Resolve a small cover for a browse row (comic file or folder).
     * Folders use the first comic archive found in that folder (name-sorted).
     */
    fun resolve(
        context: Context,
        share: SmbShareEntity,
        entry: SmbListEntry,
        browser: SmbBrowser,
        credentials: CredentialStore,
        libraryRepo: LibraryRepository,
    ): String? {
        val comicPath = resolveComicPath(share, entry, browser) ?: return null
        return ensureComicCover(context, share, comicPath, credentials, libraryRepo)
    }

    /** Ensure a small cover exists for a specific comic path. */
    fun ensureComicCover(
        context: Context,
        share: SmbShareEntity,
        comicRelativePath: String,
        credentials: CredentialStore,
        libraryRepo: LibraryRepository,
    ): String? {
        val rel = SmbBrowser.normalizePath(comicRelativePath)
        if (rel.isBlank()) return null
        val key = sourceKey(share.id, rel)
        val out = cacheFile(context, key)
        if (out.isFile && out.length() > 0) return out.absolutePath

        val offline = runBlocking { libraryRepo.getOfflineBySource(key) }
        if (offline != null) {
            val local = File(offline.localPath)
            if (local.isFile) {
                return runCatching {
                    writeSmallCoverFromComicFile(local, out)
                    out.absolutePath
                }.onFailure { Log.w(TAG, "offline downsample failed $key", it) }
                    .getOrNull()
            }
        }

        if (!inFlight.add(key)) return null
        try {
            if (!fetchLock.tryAcquire(60, TimeUnit.SECONDS)) return null
            try {
                if (out.isFile && out.length() > 0) return out.absolutePath
                downloadAndWriteSmallCover(share, rel, credentials, out)
                return if (out.isFile && out.length() > 0) out.absolutePath else null
            } finally {
                fetchLock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensure failed $key", e)
            out.delete()
            return null
        } finally {
            inFlight.remove(key)
        }
    }

    /** Walk all shares and cache small covers for every comic (and folder first-issues). */
    fun precacheAll(
        context: Context,
        shares: List<SmbShareEntity>,
        browser: SmbBrowser,
        credentials: CredentialStore,
        libraryRepo: LibraryRepository,
        onProgress: (PrecacheProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): PrecacheResult {
        val comicPaths = linkedMapOf<String, Pair<SmbShareEntity, String>>() // key -> share+path
        for (share in shares) {
            if (isCancelled()) break
            onProgress(PrecacheProgress("Scanning ${share.displayName}…", 0, 0))
            collectComics(share, browser, comicPaths)
        }

        val total = comicPaths.size
        var cached = 0
        var failed = 0
        var already = 0
        var done = 0
        for ((key, pair) in comicPaths) {
            if (isCancelled()) break
            val (share, path) = pair
            val name = path.substringAfterLast('/')
            onProgress(PrecacheProgress(name, done, total))
            val out = cacheFile(context, key)
            if (out.isFile && out.length() > 0) {
                already++
            } else {
                val result = ensureComicCover(context, share, path, credentials, libraryRepo)
                if (result != null) cached++ else failed++
            }
            done++
            onProgress(PrecacheProgress(name, done, total))
        }
        return PrecacheResult(cached = cached, failed = failed, alreadyHad = already)
    }

    private fun collectComics(
        share: SmbShareEntity,
        browser: SmbBrowser,
        out: MutableMap<String, Pair<SmbShareEntity, String>>,
    ) {
        val root = SmbBrowser.normalizePath(share.startPath)
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.add(root)
        var dirs = 0
        while (queue.isNotEmpty() && dirs < 5_000) {
            val dir = queue.removeFirst()
            dirs++
            val listed = browser.list(share, dir).getOrNull() ?: continue
            val comics = listed
                .filter { !it.isDirectory && ComicFileNames.isComicArchive(it.name) }
                .sortedBy { it.name.lowercase() }
            for (comic in comics) {
                val key = sourceKey(share.id, comic.relativePath)
                out.putIfAbsent(key, share to comic.relativePath)
            }
            // Folder cover = first comic in that folder (already covered by comics list).
            for (child in listed) {
                if (child.isDirectory) {
                    queue.add(SmbBrowser.normalizePath(child.relativePath))
                }
            }
        }
    }

    fun resolveComicPath(
        share: SmbShareEntity,
        entry: SmbListEntry,
        browser: SmbBrowser,
    ): String? {
        if (!entry.isDirectory) {
            return if (ComicFileNames.isComicArchive(entry.name)) entry.relativePath else null
        }
        val listed = browser.list(share, entry.relativePath).getOrNull() ?: return null
        return listed
            .asSequence()
            .filter { !it.isDirectory && ComicFileNames.isComicArchive(it.name) }
            .sortedBy { it.name.lowercase() }
            .firstOrNull()
            ?.relativePath
    }

    private const val NON_ZIP_COVER_MAX_BYTES = 50L * 1024 * 1024 // 50 MB

    private fun buildAuth(share: SmbShareEntity, credentials: CredentialStore): AuthenticationContext =
        if (share.useGuest || share.username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(
                share.username.trim(),
                credentials.getSecret(share.credentialKey).orEmpty().toCharArray(),
                share.domain.trim(),
            )
        }

    private fun downloadAndWriteSmallCover(
        share: SmbShareEntity,
        relativePath: String,
        credentials: CredentialStore,
        outFile: File,
    ) {
        val rel = SmbBrowser.normalizePath(relativePath)
        val fileName = rel.substringAfterLast('/').ifBlank { "comic.cbz" }
        // CBZ/ZIP: fetch only page 0 via byte-range reads (no full archive download).
        if (ZipRangePageSource.isZipName(fileName)) {
            writeSmallCoverFromZipRange(share, rel, fileName, credentials, outFile)
            return
        }

        // CBR/other: check file size before downloading full archive
        val fileSize = runCatching {
            SMBClient(smbConfig).use { client ->
                client.connect(share.host, share.port).use { connection ->
                    val session = connection.authenticate(buildAuth(share, credentials))
                    session.connectShare(share.shareName).use { remote ->
                        (remote as DiskShare)
                            .getFileInformation(rel.replace('/', '\\'))
                            .standardInformation
                            .endOfFile
                    }
                }
            }
        }.getOrElse { -1L }

        if (fileSize > NON_ZIP_COVER_MAX_BYTES) {
            Log.d(TAG, "Skipping cover for $rel — file too large ($fileSize bytes)")
            return   // leave outFile absent; caller returns null -> placeholder shown
        }

        // CBR/other: parsers need a local file — download full archive once, then delete.
        val safeName = if (ComicFileNames.isComicArchive(fileName)) fileName else "$fileName.cbz"
        val tmpDir = File(outFile.parentFile, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val token = MessageDigest.getInstance("SHA-256")
            .digest(rel.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val tmp = File(tmpDir, "t-$token-$safeName")
        try {
            SMBClient(smbConfig).use { client ->
                client.connect(share.host, share.port).use { connection ->
                    val auth = buildAuth(share, credentials)
                    val session = connection.authenticate(auth)
                    session.connectShare(share.shareName).use { remote ->
                        val disk = remote as DiskShare
                        disk.openFile(
                            rel.replace('/', '\\'),
                            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.FILE_READ_DATA),
                            null,
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_OPEN,
                            null,
                        ).use { remoteFile ->
                            remoteFile.inputStream.use { input ->
                                FileOutputStream(tmp).use { output ->
                                    val buf = ByteArray(256 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            writeSmallCoverFromComicFile(tmp, outFile)
        } finally {
            tmp.delete()
        }
    }

    private fun writeSmallCoverFromZipRange(
        share: SmbShareEntity,
        relativePath: String,
        title: String,
        credentials: CredentialStore,
        outFile: File,
    ) {
        val seekable = SmbSeekableByteSource(share, relativePath, credentials)
        val source = ZipRangePageSource(seekable, title, remoteStreaming = true)
        try {
            source.open()
            if (source.pageCount() < 1) throw IllegalStateException("no pages")
            source.openPage(0).use { input ->
                val data = Utils.toByteArray(BufferedInputStream(input))
                writeSmallCoverFromBytes(data, outFile)
            }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun writeSmallCoverFromComicFile(comicFile: File, outFile: File) {
        val parser = ParserFactory.create(comicFile.absolutePath)
            ?: throw IllegalStateException("no parser for ${comicFile.name}")
        var bis: BufferedInputStream? = null
        try {
            if (parser.numPages() < 1) throw IllegalStateException("no pages")
            bis = BufferedInputStream(parser.getPage(0))
            val data = Utils.toByteArray(bis)
            writeSmallCoverFromBytes(data, outFile)
        } finally {
            Utils.close(parser)
            Utils.close(bis)
        }
    }

    private fun writeSmallCoverFromBytes(data: ByteArray, outFile: File) {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        options.inSampleSize = Utils.calculateInSampleSize(
            options,
            Constants.COVER_NETWORK_THUMB_WIDTH,
            Constants.COVER_NETWORK_THUMB_HEIGHT,
        )
        options.inJustDecodeBounds = false
        var bmp = BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw IllegalStateException("decode failed")

        val height = bmp.height
        val width = bmp.width
        val hLimit = (width * (1 / CoverImageView.FACTOR)).toInt()
        val wLimit = (height * (2.5 * CoverImageView.FACTOR)).toInt()
        if (height > width && height > hLimit) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, hLimit)
            bmp.recycle()
            bmp = cropped
        } else if (width > height && width > wLimit) {
            val cropped = Bitmap.createBitmap(bmp, width - wLimit - 1, 0, wLimit, height)
            bmp.recycle()
            bmp = cropped
        }

        val tw = Constants.COVER_NETWORK_THUMB_WIDTH
        val th = Constants.COVER_NETWORK_THUMB_HEIGHT
        if (bmp.width > tw || bmp.height > th) {
            val scale = minOf(tw.toFloat() / bmp.width, th.toFloat() / bmp.height)
            val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) {
                bmp.recycle()
                bmp = scaled
            }
        }

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 70, fos)) {
                outFile.delete()
                throw IllegalStateException("compress failed")
            }
        }
        bmp.recycle()
    }
}
