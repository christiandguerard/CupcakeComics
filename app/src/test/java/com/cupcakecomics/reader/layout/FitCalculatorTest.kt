package com.cupcakecomics.reader.layout

import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.FitPreferences
import com.cupcakecomics.reader.model.PageSlot
import com.cupcakecomics.reader.model.SlotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCalculatorTest {
    @Test
    fun fitScreenLetterboxesPortraitPage() {
        val slot = PageSlot(0, SlotKind.SINGLE, 0, aspectRatio = 0.65f)
        val fitted = FitCalculator.fit(slot, 1080f, 1920f, FitPreferences(), screenPortrait = true)
        assertTrue(fitted.width <= 1080f + 0.1f)
        assertTrue(fitted.height <= 1920f + 0.1f)
        assertEquals(fitted.width / fitted.height, 0.65f, 0.02f)
    }

    @Test
    fun fitWidthUsesFullVirtualWidth() {
        val slot = PageSlot(0, SlotKind.SINGLE, 0, aspectRatio = 0.65f)
        val prefs = FitPreferences(portraitScreenPortraitPage = FitMode.FIT_WIDTH, widthMultiplier = 2f)
        val fitted = FitCalculator.fit(slot, 1000f, 2000f, prefs, screenPortrait = true)
        assertEquals(2000f, fitted.width, 0.1f)
    }

    @Test
    fun scaleForFitScreen() {
        val s = FitCalculator.scaleForFit(1000f, 2000f, 500f, 500f, FitMode.FIT_SCREEN)
        assertEquals(0.25f, s, 0.001f)
    }
}
