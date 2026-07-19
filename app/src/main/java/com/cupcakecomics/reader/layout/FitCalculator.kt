package com.cupcakecomics.reader.layout

import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.FitPreferences
import com.cupcakecomics.reader.model.OrientationContext
import com.cupcakecomics.reader.model.PageSlot
import kotlin.math.max
import kotlin.math.min

data class FittedSize(
    val width: Float,
    val height: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

object FitCalculator {
    fun fit(
        slot: PageSlot,
        viewWidth: Float,
        viewHeight: Float,
        prefs: FitPreferences,
        screenPortrait: Boolean,
    ): FittedSize {
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return FittedSize(0f, 0f, 1f, 0f, 0f)
        }
        val pagePortrait = slot.aspectRatio < 1f
        val mode = prefs.modeFor(OrientationContext(screenPortrait, pagePortrait))
        val virtualW = viewWidth * prefs.widthMultiplier.coerceAtLeast(0.5f)
        val virtualH = viewHeight * prefs.heightMultiplier.coerceAtLeast(0.5f)
        val contentAspect = slot.aspectRatio.coerceAtLeast(0.01f)
        // Content size at height = virtualH
        val contentW = virtualH * contentAspect
        val contentH = virtualH

        return when (mode) {
            FitMode.FIT_SCREEN -> {
                val s = min(virtualW / contentW, virtualH / contentH)
                val w = contentW * s
                val h = contentH * s
                FittedSize(w, h, s, (viewWidth - w) / 2f, (viewHeight - h) / 2f)
            }
            FitMode.FIT_WIDTH -> {
                val w = virtualW
                val h = w / contentAspect
                FittedSize(w, h, w / contentW, (viewWidth - w) / 2f, 0f)
            }
            FitMode.FIT_HEIGHT -> {
                val h = virtualH
                val w = h * contentAspect
                FittedSize(w, h, h / contentH, (viewWidth - w) / 2f, (viewHeight - h) / 2f)
            }
            FitMode.FULL_SIZE -> {
                val baseline = min(viewWidth, viewHeight)
                val h = baseline
                val w = h * contentAspect
                FittedSize(w, h, 1f, (viewWidth - w) / 2f, (viewHeight - h) / 2f)
            }
        }
    }

    fun scaleForFit(
        contentWidth: Float,
        contentHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        mode: FitMode,
        widthMult: Float = 1f,
        heightMult: Float = 1f,
    ): Float {
        if (contentWidth <= 0f || contentHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return 1f
        val vw = viewWidth * widthMult.coerceAtLeast(0.5f)
        val vh = viewHeight * heightMult.coerceAtLeast(0.5f)
        return when (mode) {
            FitMode.FIT_SCREEN -> min(vw / contentWidth, vh / contentHeight)
            FitMode.FIT_WIDTH -> vw / contentWidth
            FitMode.FIT_HEIGHT -> vh / contentHeight
            FitMode.FULL_SIZE -> 1f
        }
    }

    fun containsPoint(fitted: FittedSize, x: Float, y: Float): Boolean {
        return x >= fitted.offsetX &&
            x <= fitted.offsetX + fitted.width &&
            y >= fitted.offsetY &&
            y <= fitted.offsetY + fitted.height
    }

    fun maxZoom(minScale: Float): Float = max(minScale * 8f, 4f)
}
