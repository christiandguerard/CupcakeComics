package com.cupcakecomics.pulllist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class PullCadenceEstimatorTest {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    @Test
    fun computeEta_insufficientSamples_returnsNullEta() {
        val eta = PullCadenceEstimator.computeEtaFromTimes("Batman", listOf(1000L))
        assertNull(eta.nextAtMillis)
        assertNull(eta.typicalGapDays)
        assertEquals(1, eta.sampleCount)
    }

    @Test
    fun computeEta_filtersGapsOutsideMinMax() {
        // Gap of 1 hour (too short, < 2 days) -> filtered out
        val t0 = System.currentTimeMillis()
        val times = listOf(t0, t0 + TimeUnit.HOURS.toMillis(1))
        val eta = PullCadenceEstimator.computeEtaFromTimes("Spider-Man", times)
        assertNull(eta.nextAtMillis)
        assertNull(eta.typicalGapDays)
    }

    @Test
    fun computeEta_regularMonthlyCadence_estimatesNextMonth() {
        val now = System.currentTimeMillis()
        val t1 = now - 60 * DAY_MS
        val t2 = now - 30 * DAY_MS
        val times = listOf(t1, t2) // 30-day gap

        val eta = PullCadenceEstimator.computeEtaFromTimes("Saga", times, nowMillis = now)
        assertEquals("Saga", eta.seriesName)
        assertEquals(30, eta.typicalGapDays)
        assertNotNull(eta.nextAtMillis)
        // nextAtMillis should step forward to around now
        assertEquals(now, eta.nextAtMillis!!)
    }

    @Test
    fun formatLine_formatsReadableCadenceString() {
        val now = System.currentTimeMillis()
        val eta = PullCadenceEstimator.SeriesEta(
            seriesName = "X-Men",
            nextAtMillis = now + 7 * DAY_MS,
            typicalGapDays = 30,
            sampleCount = 5,
        )
        val line = PullCadenceEstimator.formatLine(eta, nowMillis = now)
        assertNotNull(line)
        assertEquals("X-Men — next in about 7 days (every ~30 days)", line)
    }
}
