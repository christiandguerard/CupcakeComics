package com.cupcakecomics.reader.source

import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.SmbShareEntity
import com.cupcakecomics.smb.SmbBrowser
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Byte-range SMB reader. Does not mutate the remote file.
 */
class SmbSeekableByteSource(
    private val share: SmbShareEntity,
    relativePath: String,
    private val credentials: CredentialStore,
) : SeekableByteSource {
    private val relative = SmbBrowser.normalizePath(relativePath)
    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(30, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    private val closed = AtomicBoolean(false)
    private var client: SMBClient? = null
    private var remoteFile: SmbFile? = null
    private var cachedLength: Long = -1L

    private fun ensureOpen() {
        if (closed.get()) throw IOException("Source closed")
        if (remoteFile != null) return
        synchronized(this) {
            if (remoteFile != null) return
            val c = SMBClient(config)
            client = c
            val connection = c.connect(share.host, share.port)
            val auth = if (share.useGuest || share.username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(
                    share.username.trim(),
                    credentials.getSecret(share.credentialKey).orEmpty().toCharArray(),
                    share.domain.trim(),
                )
            }
            val session = connection.authenticate(auth)
            val disk = session.connectShare(share.shareName) as DiskShare
            val f = disk.openFile(
                relative.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.FILE_READ_DATA),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            remoteFile = f
            cachedLength = f.fileInformation.standardInformation.endOfFile
        }
    }

    @Throws(IOException::class)
    override fun length(): Long {
        ensureOpen()
        return cachedLength
    }

    @Throws(IOException::class)
    override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        ensureOpen()
        val f = remoteFile ?: throw IOException("Not open")
        if (length <= 0) return 0
        return try {
            synchronized(this) {
                // smbj File.read(buffer, fileOffset, bufferOffset, length)
                f.read(dest, offset, destOffset, length).toInt()
            }
        } catch (t: Throwable) {
            // Reconnect once on failure
            reconnect()
            val f2 = remoteFile ?: throw IOException("Reconnect failed", t)
            synchronized(this) {
                f2.read(dest, offset, destOffset, length).toInt()
            }
        }
    }

    private fun reconnect() {
        runCatching { remoteFile?.close() }
        runCatching { client?.close() }
        remoteFile = null
        client = null
        ensureOpen()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { remoteFile?.close() }
        runCatching { client?.close() }
        remoteFile = null
        client = null
    }
}
