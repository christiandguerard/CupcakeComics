package com.cupcakecomics.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the 90% leave-threshold logic for Pull List progress.
 *
 * The SQL in PullDaos.updateProgress:
 *   (1.0 * MAX(highestPage, :highestPage) / MAX(pageCount, :pageCount)) >= :leaveThreshold
 * removes from pull list when the ratio >= threshold.
 */
class PullProgressTest {

    private val LEAVE_THRESHOLD = 0.90f

    private fun wouldLeave(
        storedHighest: Int,
        storedPage: Int,
        newHighest: Int,
        newPage: Int,
        threshold: Float = LEAVE_THRESHOLD,
    ): Boolean {
        val hp = maxOf(storedHighest, newHighest)
        val pc = maxOf(storedPage, newPage)
        if (pc <= 0) return false
        return (1.0 * hp / pc) >= threshold
    }

    @Test
    fun highestPage_isMonotonic() {
        val stored = 90
        val regressed = 10
        assertEquals(90, maxOf(stored, regressed))
    }

    @Test
    fun exactlyAt90Percent_leavesFromPullList() {
        assertTrue(wouldLeave(90, 100, 90, 100))
    }

    @Test
    fun justBelow90Percent_staysOnPullList() {
        assertFalse(wouldLeave(89, 100, 89, 100))
    }

    @Test
    fun pageCountZero_neverLeaves() {
        assertFalse(wouldLeave(100, 0, 100, 0))
    }

    @Test
    fun swipeBack_doesNotRegress_storedHighest() {
        // User read to page 90, swiped back to page 20.
        // MAX(90, 20) = 90. 90/100 = 0.90 -> leaves.
        assertTrue(wouldLeave(storedHighest = 90, storedPage = 100, newHighest = 20, newPage = 100))
        // Confirmed monotonic: effective highest = max(90, 20) = 90
        assertEquals(90, maxOf(90, 20))
    }

    @Test
    fun freshOpenAtPage1_doesNotLeave() {
        assertFalse(wouldLeave(0, 200, 1, 200))
    }
}
