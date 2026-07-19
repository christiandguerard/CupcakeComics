package com.cupcakecomics.smb

object ComicFileNames {
    data class LibrarySortKey(
        val series: String,
        val volume: Int,
        val issue: Double,
        val title: String,
    ) : Comparable<LibrarySortKey> {
        override fun compareTo(other: LibrarySortKey): Int {
            val bySeries = series.compareTo(other.series, ignoreCase = true)
            if (bySeries != 0) return bySeries
            val byVolume = volume.compareTo(other.volume)
            if (byVolume != 0) return byVolume
            val byIssue = issue.compareTo(other.issue)
            if (byIssue != 0) return byIssue
            return title.compareTo(other.title, ignoreCase = true)
        }
    }

    fun isComicArchive(filename: String): Boolean {
        return filename.matches(
            Regex(
                ".*\\.(cbz|cbr|cb7|cbt|zip|rar|7z|pdf|tar|tgz|tbz2?|txz|tlz|tbr|tzs(t|td)?)$",
                RegexOption.IGNORE_CASE,
            ),
        ) || filename.matches(
            Regex(".*\\.(tar\\.(gz|bz2?|xz|lzma|br|zstd?))$", RegexOption.IGNORE_CASE),
        )
    }

    /**
     * Human-friendly network-library label, e.g.
     * "Absolute Batman 022 (2024) (Digital).cbz" -> "Absolute Batman - #22".
     */
    fun shortDisplayName(filename: String): String {
        var base = filename.trim()
            .replace(
                Regex(
                    "\\.(cbz|cbr|cb7|cbt|zip|rar|7z|pdf|tar|tgz|tbz2?|txz|tlz|tbr|tzs(?:t|td)?)$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\.tar\\.(gz|bz2?|xz|lzma|br|zstd?)$", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .trim()

        // Release metadata generally trails the actual title/issue.
        while (true) {
            val stripped = base.replace(
                Regex(
                    "\\s*[\\[(](?:19|20)\\d{2}[\\])]\\s*$|" +
                        "\\s*[\\[(](?:digital|web|retail|empire|zone[^\\])]*)[\\])]\\s*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).trim()
            if (stripped == base) break
            base = stripped
        }

        val explicit = Regex(
            "^(.*?)(?:\\s*[-–—]\\s*|\\s+)(?:#|issue\\s*|no\\.?\\s*)(\\d{1,4}(?:\\.\\d+)?[a-z]?)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(base)
        val trailing = explicit ?: Regex(
            "^(.*?)(?:\\s*[-–—]\\s*|\\s+)(\\d{1,4}(?:\\.\\d+)?[a-z]?)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(base)
        if (trailing != null) {
            val rawIssue = trailing.groupValues[2]
            val numeric = rawIssue.takeWhile { it.isDigit() }.toIntOrNull()
            if (numeric != null && numeric !in 1900..2099) {
                val suffix = rawIssue.dropWhile { it.isDigit() }
                val issue = numeric.toString() + suffix
                val series = trailing.groupValues[1]
                    .replace(Regex("\\s+v(?:ol(?:ume)?)?\\.?\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "")
                    .trim(' ', '-', '–', '—', '.')
                if (series.isNotBlank()) return "$series - #$issue"
            }
        }
        return base
    }

    /** Sort key for library grids: series → volume → issue number. */
    fun librarySortKey(filename: String): LibrarySortKey {
        val base = filename.trim()
            .replace(
                Regex(
                    "\\.(cbz|cbr|cb7|cbt|zip|rar|7z|pdf|tar|tgz|tbz2?|txz|tlz|tbr|tzs(?:t|td)?)$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\.tar\\.(gz|bz2?|xz|lzma|br|zstd?)$", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .trim()

        val volume = Regex(
            """\b(?:v(?:ol(?:ume)?)?\.?\s*)(\d{1,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(base)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val issue = Regex(
            """\b(?:issue|no\.?|#)\s*(\d{1,4}(?:\.\d+)?)([a-z]?)\b""",
            RegexOption.IGNORE_CASE,
        ).find(base)?.let { match ->
            val n = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val suffix = match.groupValues.getOrNull(2).orEmpty()
            n + if (suffix.isNotEmpty()) (suffix[0].lowercaseChar() - 'a' + 1) * 0.01 else 0.0
        } ?: Regex(
            """(?:^|[\s\-–—])(\d{1,4}(?:\.\d+)?)([a-z]?)(?:\s*[\[(]|$)""",
            RegexOption.IGNORE_CASE,
        ).findAll(base).mapNotNull { match ->
            val n = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            if (n in 1900.0..2099.0) return@mapNotNull null
            n
        }.lastOrNull() ?: Double.MAX_VALUE

        val short = shortDisplayName(filename)
        val seriesFromShort = Regex(
            """^(.*)\s+-\s+#\d""",
            RegexOption.IGNORE_CASE,
        ).find(short)?.groupValues?.getOrNull(1)?.trim()

        val series = seriesFromShort?.ifBlank { null }
            ?: base
                .replace(Regex("""\s*[\[(](?:19|20)\d{2}[\])]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\b(?:v(?:ol(?:ume)?)?\.?\s*)\d{1,4}\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\b(?:issue|no\.?|#)\s*\d{1,4}(?:\.\d+)?[a-z]?\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim(' ', '-', '–', '—', '.')
                .ifBlank { short }

        return LibrarySortKey(
            series = series.lowercase(),
            volume = volume,
            issue = issue,
            title = base.lowercase(),
        )
    }
}
