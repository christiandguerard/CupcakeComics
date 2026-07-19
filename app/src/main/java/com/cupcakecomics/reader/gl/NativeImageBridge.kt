package com.cupcakecomics.reader.gl

/**
 * JNI bridge to native tile helpers and Lanczos kernel tables.
 * Falls back gracefully when the native library is unavailable.
 */
object NativeImageBridge {
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("cupcake_image")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Returns a 1D Lanczos-3 kernel sampled at [samples] points over [-3, 3]. */
    external fun nativeLanczosKernel(samples: Int): FloatArray

    /** Soft white-balance / vibrance / gamma on an ARGB_8888 buffer (in-place). */
    external fun nativeColorCorrect(
        pixels: IntArray,
        width: Int,
        height: Int,
        whiteBalance: Float,
        hardness: Float,
        vibrance: Float,
        gammaR: Float,
        gammaG: Float,
        gammaB: Float,
    )

    fun lanczosKernel(samples: Int = 64): FloatArray {
        if (available) {
            runCatching { return nativeLanczosKernel(samples) }
        }
        return FloatArray(samples) { i ->
            val x = (i.toFloat() / (samples - 1)) * 6f - 3f
            lanczos3(x)
        }
    }

    fun colorCorrect(
        pixels: IntArray,
        width: Int,
        height: Int,
        whiteBalance: Float,
        hardness: Float,
        vibrance: Float,
        gammaR: Float,
        gammaG: Float,
        gammaB: Float,
    ) {
        if (available) {
            val ok = runCatching {
                nativeColorCorrect(
                    pixels, width, height,
                    whiteBalance, hardness, vibrance,
                    gammaR, gammaG, gammaB,
                )
            }.isSuccess
            if (ok) return
        }
        // CPU fallback
        cpuColorCorrect(pixels, whiteBalance, hardness, vibrance, gammaR, gammaG, gammaB)
    }

    private fun lanczos3(x: Float): Float {
        val ax = kotlin.math.abs(x)
        if (ax >= 3f) return 0f
        if (ax < 1e-6f) return 1f
        val pix = Math.PI.toFloat() * ax
        val pix3 = pix / 3f
        return (kotlin.math.sin(pix) / pix) * (kotlin.math.sin(pix3) / pix3)
    }

    private fun cpuColorCorrect(
        pixels: IntArray,
        whiteBalance: Float,
        hardness: Float,
        vibrance: Float,
        gammaR: Float,
        gammaG: Float,
        gammaB: Float,
    ) {
        val wb = whiteBalance.coerceIn(0f, 1f)
        val hard = hardness.coerceIn(0f, 1f)
        val vib = vibrance.coerceIn(-1f, 1f)
        val gr = gammaR.coerceIn(0.2f, 3f)
        val gg = gammaG.coerceIn(0.2f, 3f)
        val gb = gammaB.coerceIn(0.2f, 3f)
        for (i in pixels.indices) {
            var c = pixels[i]
            var r = (c shr 16) and 0xff
            var g = (c shr 8) and 0xff
            var b = c and 0xff
            val a = (c ushr 24) and 0xff

            // White balance: push toward paper white for yellowish scans.
            if (wb > 0f) {
                val maxc = maxOf(r, g, b).toFloat()
                val minc = minOf(r, g, b).toFloat()
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)
                if (lum > 180f - hard * 80f) {
                    val target = (255f * (0.5f + 0.5f * wb)).toInt().coerceIn(0, 255)
                    r = lerp(r, target, wb)
                    g = lerp(g, target, wb)
                    b = lerp(b, target, wb * (1f - hard * 0.3f).coerceAtLeast(0.5f))
                }
                // mild stain reduction at high hardness on midtones
                if (hard > 0.4f && maxc - minc < 40f && lum in 40f..200f) {
                    val mid = ((r + g + b) / 3f).toInt()
                    val t = (hard - 0.4f) / 0.6f
                    r = lerp(r, mid, t * 0.5f)
                    g = lerp(g, mid, t * 0.5f)
                    b = lerp(b, mid, t * 0.5f)
                }
            }

            // Vibrance: selective saturation
            if (vib != 0f) {
                val maxc = maxOf(r, g, b).toFloat()
                val minc = minOf(r, g, b).toFloat()
                val sat = if (maxc > 0f) (maxc - minc) / maxc else 0f
                val amount = if (vib > 0f) vib * (1f - sat) else vib * sat
                val gray = (0.299f * r + 0.587f * g + 0.114f * b)
                r = (gray + (r - gray) * (1f + amount)).toInt().coerceIn(0, 255)
                g = (gray + (g - gray) * (1f + amount)).toInt().coerceIn(0, 255)
                b = (gray + (b - gray) * (1f + amount)).toInt().coerceIn(0, 255)
            }

            // Gamma
            r = (255.0 * Math.pow(r / 255.0, 1.0 / gr)).toInt().coerceIn(0, 255)
            g = (255.0 * Math.pow(g / 255.0, 1.0 / gg)).toInt().coerceIn(0, 255)
            b = (255.0 * Math.pow(b / 255.0, 1.0 / gb)).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)
}
