package com.cupcakecomics.settings

/** Cover tile size on Library home grids. */
enum class CoverSize {
    SMALL,
    MEDIUM,
    LARGE,
    ;

    /** Approximate tile width in dp used for span calculation. */
    fun tileWidthDp(): Int = when (this) {
        SMALL -> 140
        MEDIUM -> 200
        LARGE -> 280
    }
}

/** Ordered sections on the Library home page. */
enum class LibrarySection {
    PULL,
    LOCAL,
    OFFLINE,
    SMB,
    MEDIA,
    ;

    companion object {
        val DEFAULT_ORDER: List<LibrarySection> = listOf(
            PULL, LOCAL, OFFLINE, SMB, MEDIA,
        )

        fun parseOrder(raw: String?): List<LibrarySection> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(',')
                .mapNotNull { token ->
                    runCatching { valueOf(token.trim()) }.getOrNull()
                }
                .distinct()
            if (parsed.isEmpty()) return DEFAULT_ORDER
            // Append any missing sections so upgrades stay complete
            val missing = DEFAULT_ORDER.filter { it !in parsed }
            return parsed + missing
        }

        fun serialize(order: List<LibrarySection>): String =
            order.joinToString(",") { it.name }
    }
}
