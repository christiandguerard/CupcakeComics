package com.cupcakecomics.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmbStageDirTokenTest {
    @Test
    fun dirToken_stableForSamePath() {
        val a = SmbStageManager.dirToken(1L, "Comics/Foo.cbz")
        val b = SmbStageManager.dirToken(1L, "Comics/Foo.cbz")
        assertEquals(a, b)
        assertEquals(24, a.length)
    }

    @Test
    fun dirToken_differsAcrossPaths() {
        val a = SmbStageManager.dirToken(1L, "Comics/Foo.cbz")
        val b = SmbStageManager.dirToken(1L, "Comics/Bar.cbz")
        assertNotEquals(a, b)
    }
}
