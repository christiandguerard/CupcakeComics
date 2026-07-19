package com.cupcakecomics.kapowarr

import java.net.URI

/**
 * Normalize user input into a Kapowarr base URL.
 * Accepts host, host:port, domain, or full URL.
 *
 * - Private LAN hosts default to http:// and port 5656 when omitted.
 * - Public domains default to https:// with no forced port (443).
 */
object KapowarrUrls {
    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return s

        if (!s.contains("://")) {
            val hostPart = s.substringBefore('/').substringBefore(':')
            s = if (isPrivateHost(hostPart)) "http://$s" else "https://$s"
        }

        val uri = runCatching { URI(s) }.getOrNull() ?: return s
        val host = uri.host ?: return s
        val scheme = (uri.scheme ?: "http").lowercase()
        val port = uri.port

        return when {
            port > 0 -> "$scheme://$host:$port"
            // Default Kapowarr port only for LAN http without an explicit port.
            isPrivateHost(host) && scheme == "http" -> "$scheme://$host:5656"
            else -> "$scheme://$host"
        }
    }

    fun isPrivateLanHttp(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!"http".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host ?: return false
        return isPrivateHost(host)
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val second = h.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    fun displayNameFor(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host ?: url
        val port = uri?.port?.takeIf { it > 0 } ?: return host
        val scheme = uri.scheme?.lowercase()
        // Hide default ports.
        if (port == 5656 && scheme == "http") return host
        if (port == 443 && scheme == "https") return host
        if (port == 80 && scheme == "http") return host
        return "$host:$port"
    }

    /** Resolve a cover URL that may be relative to the Kapowarr base. */
    fun resolveMediaUrl(baseUrl: String, cover: String?): String? {
        val c = cover?.trim().orEmpty()
        if (c.isEmpty()) return null
        if (c.startsWith("http://", ignoreCase = true) || c.startsWith("https://", ignoreCase = true)) {
            return c
        }
        val base = baseUrl.trim().trimEnd('/')
        return if (c.startsWith("/")) "$base$c" else "$base/$c"
    }
}
