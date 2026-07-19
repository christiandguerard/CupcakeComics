package com.cupcakecomics.smb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.LibraryRepository
import com.cupcakecomics.data.SmbShareEntity
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Downloads a remote comic into local staging / offline storage for Bubble2 parsers.
 * Never mutates the share.
 *
 * SPEC caps for [stageDir]: max [MAX_STAGED] comics; total size ≤ min(2 GiB, 20% free).
 */
class SmbStageManager(
    context: Context,
    private val credentials: CredentialStore,
) {
    private val app = context.applicationContext
    private val libraryRepo = LibraryRepository(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(30, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    fun sourceKey(shareId: Long, relativePath: String): String =
        "smb:$shareId:${SmbBrowser.normalizePath(relativePath)}"

    fun stageDir(): File = File(app.cacheDir, "smb-stage").also { it.mkdirs() }

    /**
     * @param keepOffline if true, stores under filesDir and registers in offline library
     * @param isCancelled when true mid-copy, aborts and deletes the partial download
     */
    fun stage(
        share: SmbShareEntity,
        relativePath: String,
        keepOffline: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<File> {
        val rel = SmbBrowser.normalizePath(relativePath)
        val key = sourceKey(share.id, rel)
        val lock = locks.getOrPut(key) { Any() }
        return synchronized(lock) {
            runCatching {
                if (isCancelled()) throw CancellationException("Cancelled")
                require(rel.isNotBlank()) { "Empty path" }
                val fileName = rel.substringAfterLast('/')
                require(ComicFileNames.isComicArchive(fileName)) { "Not a comic archive" }

                val existing = kotlinx.coroutines.runBlocking {
                    libraryRepo.getOfflineBySource(key)
                }
                if (existing != null) {
                    val f = File(existing.localPath)
                    if (f.exists() && f.length() > 0) {
                        onProgress?.let { mainHandler.post { it(f.length(), f.length()) } }
                        return@runCatching f
                    }
                }

                val dir = if (keepOffline) {
                    File(libraryRepo.downloadsDir(), dirToken(share.id, rel))
                } else {
                    File(stageDir(), dirToken(share.id, rel))
                }
                val outFile = File(dir, fileName)
                if (outFile.exists() && outFile.length() > 0) {
                    onProgress?.let { mainHandler.post { it(outFile.length(), outFile.length()) } }
                    if (keepOffline) {
                        kotlinx.coroutines.runBlocking {
                            libraryRepo.upsertOffline(
                                title = fileName,
                                localPath = outFile.absolutePath,
                                sourceKey = key,
                                sizeBytes = outFile.length(),
                            )
                        }
                    }
                    return@runCatching outFile
                }

                try {
                    SMBClient(config).use { client ->
                        client.connect(share.host, share.port).use { connection ->
                            val session = connection.authenticate(authContext(share))
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
                                    val total = remoteFile.fileInformation.standardInformation.endOfFile
                                    ensureCapacity(keepOffline, total, excludeDir = dir)
                                    if (dir.exists()) dir.deleteRecursively()
                                    dir.mkdirs()
                                    remoteFile.inputStream.use { input ->
                                        FileOutputStream(outFile).use { output ->
                                            val buf = ByteArray(1024 * 256)
                                            var copied = 0L
                                            var lastPost = 0L
                                            while (true) {
                                                if (isCancelled()) {
                                                    throw CancellationException("Cancelled")
                                                }
                                                val n = input.read(buf)
                                                if (n <= 0) break
                                                output.write(buf, 0, n)
                                                copied += n
                                                if (onProgress != null &&
                                                    ((copied - lastPost > 256 * 1024) || copied >= total)
                                                ) {
                                                    lastPost = copied
                                                    val c = copied
                                                    mainHandler.post { onProgress(c, total) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    runCatching { if (dir.exists()) dir.deleteRecursively() }
                    throw e
                }

                if (isCancelled()) {
                    runCatching { if (dir.exists()) dir.deleteRecursively() }
                    throw CancellationException("Cancelled")
                }

                if (keepOffline) {
                    kotlinx.coroutines.runBlocking {
                        libraryRepo.upsertOffline(
                            title = fileName,
                            localPath = outFile.absolutePath,
                            sourceKey = key,
                            sizeBytes = outFile.length(),
                        )
                        libraryRepo.enforceOfflineBudget(maxBudgetBytes())
                    }
                    try {
                        com.cupcakecomics.cover.FileCoverHandler.warmCache(outFile.absolutePath)
                    } catch (_: Throwable) {
                    }
                } else {
                    enforceStageCaps(excludeDir = dir)
                }
                outFile
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { err ->
                    if (err is CancellationException || err.cause is CancellationException) {
                        Result.failure(err)
                    } else {
                        Result.failure(IllegalStateException(SmbBrowser.friendlyError(err), err))
                    }
                },
            )
        }
    }

    private fun authContext(share: SmbShareEntity): AuthenticationContext {
        return if (share.useGuest || share.username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(
                share.username.trim(),
                credentials.getSecret(share.credentialKey).orEmpty().toCharArray(),
                share.domain.trim(),
            )
        }
    }

    private fun ensureCapacity(keepOffline: Boolean, neededBytes: Long, excludeDir: File) {
        val budget = maxBudgetBytes()
        if (neededBytes > budget) {
            throw IllegalStateException("Comic is larger than the local cache budget")
        }
        if (keepOffline) {
            kotlinx.coroutines.runBlocking {
                libraryRepo.enforceOfflineBudget(budget, reserveBytes = neededBytes)
            }
        } else {
            enforceStageCaps(excludeDir = excludeDir, reserveBytes = neededBytes)
        }
        val free = freeBytes(if (keepOffline) libraryRepo.downloadsDir() else stageDir())
        if (free < neededBytes + MIN_HEADROOM_BYTES) {
            throw IllegalStateException("Not enough free storage for download")
        }
    }

    private fun enforceStageCaps(excludeDir: File? = null, reserveBytes: Long = 0L) {
        val root = stageDir()
        if (!root.exists()) return
        val budget = maxBudgetBytes()

        fun listSorted(): MutableList<File> =
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.lastModified() }
                ?.toMutableList()
                ?: mutableListOf()

        fun pickVictim(entries: List<File>): File? {
            val other = entries.firstOrNull {
                excludeDir == null || it.absolutePath != excludeDir.absolutePath
            }
            return other ?: entries.firstOrNull()
        }

        // Before a new download, leave one slot; afterward keep at most MAX_STAGED.
        val incoming = reserveBytes > 0L && (excludeDir == null || !excludeDir.exists())
        val maxCount = if (incoming) MAX_STAGED - 1 else MAX_STAGED

        var entries = listSorted()
        while (entries.size > maxCount) {
            val victim = pickVictim(entries) ?: break
            victim.deleteRecursively()
            entries = listSorted()
        }

        fun totalSize(): Long =
            (root.listFiles()?.filter { it.isDirectory }?.sumOf { dirSize(it) } ?: 0L) + reserveBytes

        while (totalSize() > budget) {
            entries = listSorted()
            val victim = pickVictim(entries) ?: break
            // Never delete the comic we are actively writing if it is the only one left.
            if (excludeDir != null && victim.absolutePath == excludeDir.absolutePath && entries.size <= 1) {
                break
            }
            victim.deleteRecursively()
        }
    }

    private fun maxBudgetBytes(): Long {
        val free = freeBytes(app.cacheDir)
        val twentyPercent = (free * 0.20).toLong().coerceAtLeast(0L)
        return minOf(MAX_STAGE_BYTES, twentyPercent).coerceAtLeast(MIN_BUDGET_BYTES)
    }

    companion object {
        private const val MAX_STAGED = 2
        private const val MAX_STAGE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GiB
        private const val MIN_BUDGET_BYTES = 32L * 1024 * 1024
        private const val MIN_HEADROOM_BYTES = 16L * 1024 * 1024

        private val locks = ConcurrentHashMap<String, Any>()

        fun dirToken(shareId: Long, relativePath: String): String {
            val rel = SmbBrowser.normalizePath(relativePath)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$shareId:$rel".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }.take(24)
        }

        fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            var total = 0L
            dir.walkTopDown().forEach { f ->
                if (f.isFile) total += f.length()
            }
            return total
        }

        fun freeBytes(path: File): Long {
            return try {
                val stat = StatFs(path.absolutePath)
                stat.availableBlocksLong * stat.blockSizeLong
            } catch (_: Throwable) {
                Long.MAX_VALUE / 4
            }
        }
    }
}
