package com.cupcakecomics.reader.layout

/**
 * Estimates empty border crop fractions from a decoded ARGB sample strip.
 * Used to pre-size layout without waiting for full-resolution crop.
 */
object BorderCropEstimator {
    data class Crop(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    ) {
        val isEmpty: Boolean get() = left + top + right + bottom < 0.001f
    }

    fun estimate(pixels: IntArray, width: Int, height: Int, threshold: Int = 245): Crop {
        if (width < 8 || height < 8 || pixels.size < width * height) return Crop()
        fun isEmpty(x: Int, y: Int): Boolean {
            val c = pixels[y * width + x]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            return lum >= threshold || lum <= 10
        }
        var left = 0
        while (left < width / 4) {
            var empty = true
            for (y in 0 until height step maxOf(1, height / 64)) {
                if (!isEmpty(left, y)) { empty = false; break }
            }
            if (!empty) break
            left++
        }
        var right = 0
        while (right < width / 4) {
            var empty = true
            val x = width - 1 - right
            for (y in 0 until height step maxOf(1, height / 64)) {
                if (!isEmpty(x, y)) { empty = false; break }
            }
            if (!empty) break
            right++
        }
        var top = 0
        while (top < height / 4) {
            var empty = true
            for (x in 0 until width step maxOf(1, width / 64)) {
                if (!isEmpty(x, top)) { empty = false; break }
            }
            if (!empty) break
            top++
        }
        var bottom = 0
        while (bottom < height / 4) {
            var empty = true
            val y = height - 1 - bottom
            for (x in 0 until width step maxOf(1, width / 64)) {
                if (!isEmpty(x, y)) { empty = false; break }
            }
            if (!empty) break
            bottom++
        }
        return Crop(
            left = left.toFloat() / width,
            top = top.toFloat() / height,
            right = right.toFloat() / width,
            bottom = bottom.toFloat() / height,
        )
    }
}
