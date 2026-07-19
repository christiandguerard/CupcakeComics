package com.cupcakecomics.data

/** Helpers for SQL LIKE patterns over relative paths (escape `_` / `%` / `\`). */
object PullPathSql {
    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /** Prefix pattern matching children of [folderPath] (`folder/%`). */
    fun childPrefix(folderPath: String): String {
        val escaped = escapeLike(folderPath)
        return if (folderPath.isEmpty()) "/%" else "$escaped/%"
    }
}
