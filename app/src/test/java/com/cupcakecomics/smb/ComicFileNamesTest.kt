package com.cupcakecomics.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicFileNamesTest {
    @Test
    fun shortDisplayName_extractsIssueAndDropsReleaseMetadata() {
        assertEquals(
            "Absolute Batman - #22",
            ComicFileNames.shortDisplayName(
                "Absolute Batman 022 (2024) (Digital).cbz",
            ),
        )
        assertEquals(
            "Batman - #14",
            ComicFileNames.shortDisplayName("Batman - #014.cbz"),
        )
        assertEquals(
            "Batman - #3",
            ComicFileNames.shortDisplayName("Batman v2 003.cbz"),
        )
    }

    @Test
    fun shortDisplayName_doesNotTreatYearAsIssue() {
        assertEquals("Batman", ComicFileNames.shortDisplayName("Batman (2024).cbz"))
        assertEquals("Batman 2024", ComicFileNames.shortDisplayName("Batman 2024.cbz"))
    }
}
