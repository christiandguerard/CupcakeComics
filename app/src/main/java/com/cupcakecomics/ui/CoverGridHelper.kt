package com.cupcakecomics.ui

import android.content.Context
import com.cupcakecomics.settings.CoverSize
import com.cupcakecomics.settings.CupcakeSettings

object CoverGridHelper {
    fun spanCount(context: Context, settings: CupcakeSettings = CupcakeSettings(context)): Int {
        return spanCount(context, settings.coverSize)
    }

    fun spanCount(context: Context, size: CoverSize): Int {
        val width = context.resources.displayMetrics.widthPixels
        val tile = (size.tileWidthDp() * context.resources.displayMetrics.density).toInt()
        return (width / tile.coerceAtLeast(1)).coerceIn(2, 5)
    }

    /** Column width integer for the legacy media folder grid. */
    fun mediaColumnWidthPx(context: Context, size: CoverSize): Int {
        val dp = when (size) {
            CoverSize.SMALL -> 200
            CoverSize.MEDIUM -> 280
            CoverSize.LARGE -> 360
        }
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
