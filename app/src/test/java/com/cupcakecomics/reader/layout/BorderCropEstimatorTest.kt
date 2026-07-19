package com.cupcakecomics.reader.layout

import org.junit.Assert.assertTrue
import org.junit.Test

class BorderCropEstimatorTest {
    @Test
    fun detectsWhiteBorders() {
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // Content rectangle
        for (y in 10 until 30) {
            for (x in 8 until 32) {
                pixels[y * w + x] = 0xFF202020.toInt()
            }
        }
        val crop = BorderCropEstimator.estimate(pixels, w, h)
        assertTrue(crop.left > 0.1f)
        assertTrue(crop.right > 0.1f)
        assertTrue(crop.top > 0.1f)
        assertTrue(crop.bottom > 0.1f)
    }
}
