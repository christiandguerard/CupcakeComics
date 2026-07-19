package com.cupcakecomics.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs

/**
 * Picks letterbox/matte color from page borders: whichever of near-white vs near-black
 * has more contiguous edge pixels wins. Falls back to black.
 */
object PageBorderColor {
    @JvmStatic
    fun fromDrawable(drawable: Drawable?): Int {
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return Color.BLACK
        if (bmp.isRecycled || bmp.width < 4 || bmp.height < 4) return Color.BLACK
        return fromBitmap(bmp)
    }

    @JvmStatic
    fun fromBitmap(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val strip = (minOf(w, h) * 0.03f).toInt().coerceIn(2, 24)

        var white = 0
        var black = 0

        fun sample(x: Int, y: Int) {
            val c = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            val sat = abs(r - g) + abs(g - b) + abs(b - r)
            // Prefer low-saturation border paper/ink.
            if (sat > 80) return
            when {
                lum >= 210 -> white++
                lum <= 45 -> black++
            }
        }

        for (x in 0 until w) {
            for (y in 0 until strip) sample(x, y)
            for (y in (h - strip) until h) sample(x, y)
        }
        for (y in strip until (h - strip)) {
            for (x in 0 until strip) sample(x, y)
            for (x in (w - strip) until w) sample(x, y)
        }

        return if (white >= black) Color.WHITE else Color.BLACK
    }
}
