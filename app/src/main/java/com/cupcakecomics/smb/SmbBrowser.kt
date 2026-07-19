package com.cupcakecomics.smb

import com.cupcakecomics.data.CredentialStore
import com.cupcakecomics.data.SmbShareEntity
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class SmbListEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class ParsedSmbTarget(
    val host: String,
    val shareName: String,
    val startPath: String,
    val port: Int = 445,
)

/**
 * Read-only SMB helper. Browse uses metadata listing only — no file staging yet.
 */
class SmbBrowser(private val credentials: CredentialStore) {

    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .build()

    fun testConnection(share: SmbShareEntity): Result<Unit> = runCatching {
        withDisk(share) { disk ->
            disk.list(normalizePath(share.startPath))
            Unit
        }
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.failure(IllegalStateException(friendlyError(it), it)) },
    )

    fun list(share: SmbShareEntity, relativePath: String): Result<List<SmbListEntry>> = runCatching {
        withDisk(share) { disk ->
            val path = normalizePath(relativePath.ifBlank { share.startPath })
            disk.list(path)
                .asSequence()
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val attrs = info.fileAttributes
                    val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    SmbListEntry(
                        name = info.fileName,
                        relativePath = joinPath(path, info.fileName),
                        isDirectory = isDir,
                        size = info.endOfFile,
                        lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
                    )
                }
                .sortedWith(compareByDescending<SmbListEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                .toList()
        }
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalStateException(friendlyError(it), it)) },
    )

    private fun <T> withDisk(share: SmbShareEntity, block: (DiskShare) -> T): T {
        require(share.host.isNotBlank()) { "Host is required" }
        require(share.shareName.isNotBlank()) { "Share name is required (for your server this is usually Comics)" }

        SMBClient(config).use { client ->
            client.connect(share.host, share.port).use { connection ->
                val session = connection.authenticate(authContext(share))
                session.connectShare(share.shareName).use { remoteShare ->
                    return block(remoteShare as DiskShare)
                }
            }
        }
    }

    private fun authContext(share: SmbShareEntity): AuthenticationContext {
        if (share.useGuest || share.username.isBlank()) {
            return AuthenticationContext.guest()
        }
        val password = credentials.getSecret(share.credentialKey).orEmpty()
        return AuthenticationContext(
            share.username.trim(),
            password.toCharArray(),
            share.domain.trim(),
        )
    }

    companion object {
        fun normalizePath(path: String): String {
            return path.trim().trim('/').replace('\\', '/')
        }

        fun joinPath(parent: String, child: String): String {
            val p = normalizePath(parent)
            return if (p.isEmpty()) child else "$p/$child"
        }

        fun parentPath(path: String): String {
            val n = normalizePath(path)
            if (n.isEmpty()) return ""
            val idx = n.lastIndexOf('/')
            return if (idx < 0) "" else n.substring(0, idx)
        }

        /**
         * Accepts host, `\\host\share\path`, or `smb://host/share/path`.
         */
        fun parseTarget(rawHost: String, rawShare: String, rawStart: String, rawPort: String): ParsedSmbTarget {
            var host = rawHost.trim()
            var share = rawShare.trim().trim('/')
            var start = normalizePath(rawStart)
            var port = rawPort.trim().toIntOrNull() ?: 445

            host = host.removePrefix("smb://").removePrefix("SMB://")
            host = host.replace('/', '\\')

            if (host.startsWith("\\\\")) {
                val body = host.removePrefix("\\\\")
                val parts = body.split('\\').filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    host = parts[0]
                }
                if (parts.size >= 2 && share.isBlank()) {
                    share = parts[1]
                }
                if (parts.size >= 3 && start.isBlank()) {
                    start = parts.drop(2).joinToString("/")
                }
            } else if (host.contains('\\')) {
                val parts = host.split('\\').filter { it.isNotBlank() }
                if (parts.isNotEmpty()) host = parts[0]
                if (parts.size >= 2 && share.isBlank()) share = parts[1]
                if (parts.size >= 3 && start.isBlank()) start = parts.drop(2).joinToString("/")
            }

            if (host.contains(':') && !host.contains("://")) {
                val idx = host.lastIndexOf(':')
                val maybePort = host.substring(idx + 1).toIntOrNull()
                if (maybePort != null) {
                    port = maybePort
                    host = host.substring(0, idx)
                }
            }

            return ParsedSmbTarget(host = host.trim(), shareName = share, startPath = start, port = port)
        }

        fun friendlyError(t: Throwable): String {
            var cur: Throwable? = t
            while (cur != null) {
                when (cur) {
                    is SMBApiException -> {
                        val status = cur.status?.name ?: ""
                        return when {
                            status.contains("LOGON_FAILURE") || status.contains("ACCESS_DENIED") ->
                                "Login failed — check username/password (and leave Guest unchecked)"
                            status.contains("BAD_NETWORK_NAME") || status.contains("OBJECT_PATH_NOT_FOUND") ->
                                "Share not found — set Share name to Comics (not the host IP)"
                            status.contains("USER_SESSION_DELETED") || status.contains("NETWORK_NAME_DELETED") ->
                                "Share disconnected — try Test again"
                            else -> "SMB error: ${cur.status?.name ?: cur.message}"
                        }
                    }
                    is UnknownHostException -> return "Host not found — check the IP ($cur)"
                    is SocketTimeoutException -> return "Timed out reaching the server on port 445"
                    is ConnectException -> return "Could not connect — is Samba up on port 445?"
                }
                cur = cur.cause
            }
            return t.message ?: t.javaClass.simpleName
        }
    }
}
