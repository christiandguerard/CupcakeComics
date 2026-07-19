package com.cupcakecomics.reader.layout

import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.PagesLayout
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.SlotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutEngineTest {
    private fun page(index: Int, w: Int, h: Int, folder: String = ""): PageDescriptor =
        PageDescriptor(index = index, width = w, height = h, folder = folder, name = "p$index.jpg")

    @Test
    fun singleLayoutKeepsOneSlotPerPage() {
        val pages = listOf(page(0, 1000, 1500), page(1, 1000, 1500), page(2, 2000, 1000))
        val slots = PageLayoutEngine.buildSlots(pages, PagesLayout.SINGLE, ReadingFlow.LEFT_TO_RIGHT)
        assertEquals(3, slots.size)
        assertEquals(SlotKind.SINGLE, slots[0].kind)
    }

    @Test
    fun doubleWithCoverLeavesFirstAloneThenPairs() {
        val pages = (0..4).map { page(it, 800, 1200, "ch1") }
        val slots = PageLayoutEngine.buildSlots(
            pages, PagesLayout.DOUBLE_WITH_COVER, ReadingFlow.LEFT_TO_RIGHT,
        )
        assertEquals(3, slots.size)
        assertEquals(SlotKind.SINGLE, slots[0].kind)
        assertEquals(0, slots[0].primaryPage)
        assertEquals(SlotKind.DOUBLE, slots[1].kind)
        assertEquals(1, slots[1].primaryPage)
        assertEquals(2, slots[1].secondaryPage)
    }

    @Test
    fun rtlDoubleSwapsPairOrder() {
        val pages = listOf(page(0, 800, 1200), page(1, 800, 1200))
        val slots = PageLayoutEngine.buildSlots(pages, PagesLayout.DOUBLE, ReadingFlow.RIGHT_TO_LEFT)
        assertEquals(1, slots.size)
        assertEquals(1, slots[0].primaryPage)
        assertEquals(0, slots[0].secondaryPage)
    }

    @Test
    fun splitSpreadsCreatesTwoHalves() {
        val pages = listOf(page(0, 2000, 1000))
        val slots = PageLayoutEngine.buildSlots(
            pages, PagesLayout.SINGLE, ReadingFlow.LEFT_TO_RIGHT, splitSpreads = true,
        )
        assertEquals(2, slots.size)
        assertEquals(SlotKind.SPLIT_HALF, slots[0].kind)
        assertEquals(0, slots[0].splitHalf)
        assertEquals(1, slots[1].splitHalf)
    }

    @Test
    fun widePagesStaySingleInDoubleLayout() {
        val pages = listOf(page(0, 2000, 1000), page(1, 800, 1200))
        val slots = PageLayoutEngine.buildSlots(pages, PagesLayout.DOUBLE, ReadingFlow.LEFT_TO_RIGHT)
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.kind == SlotKind.SINGLE })
    }

    @Test
    fun slotIndexForPageFindsDouble() {
        val pages = listOf(page(0, 800, 1200), page(1, 800, 1200))
        val slots = PageLayoutEngine.buildSlots(pages, PagesLayout.DOUBLE, ReadingFlow.LEFT_TO_RIGHT)
        assertEquals(0, PageLayoutEngine.slotIndexForPage(slots, 1))
        assertEquals(1, PageLayoutEngine.highestPageInSlot(slots[0]))
    }
}
