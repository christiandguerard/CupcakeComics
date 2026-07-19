package com.cupcakecomics.reader.layout

import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.PageSlot
import com.cupcakecomics.reader.model.PagesLayout
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.SlotKind

/**
 * Builds stable logical [PageSlot]s from page descriptors before images decode.
 * Prevents layout jank by using header aspect ratios only.
 */
object PageLayoutEngine {

    fun buildSlots(
        descriptors: List<PageDescriptor>,
        layout: PagesLayout,
        flow: ReadingFlow,
        splitSpreads: Boolean = false,
        screenPortrait: Boolean = true,
    ): List<PageSlot> {
        if (descriptors.isEmpty()) return emptyList()
        return when (layout) {
            PagesLayout.SINGLE -> buildSingle(descriptors, flow, splitSpreads)
            PagesLayout.DOUBLE -> buildDouble(descriptors, flow, splitSpreads, leaveCoverAlone = false)
            PagesLayout.DOUBLE_WITH_COVER -> buildDouble(descriptors, flow, splitSpreads, leaveCoverAlone = true)
            PagesLayout.CONTINUOUS_VERTICAL -> buildContinuous(descriptors, horizontal = false)
            PagesLayout.CONTINUOUS_HORIZONTAL -> buildContinuous(descriptors, horizontal = true)
        }.also {
            // screenPortrait reserved for future density-aware pairing thresholds
            @Suppress("UNUSED_EXPRESSION")
            screenPortrait
        }
    }

    private fun buildSingle(
        descriptors: List<PageDescriptor>,
        flow: ReadingFlow,
        splitSpreads: Boolean,
    ): List<PageSlot> {
        val slots = ArrayList<PageSlot>()
        var slotIndex = 0
        for (d in descriptors) {
            if (splitSpreads && d.isLandscapeSpread && d.width > 0 && d.height > 0) {
                val halves = splitSpread(d, flow)
                for (half in halves) {
                    slots.add(half.copy(slotIndex = slotIndex++))
                }
            } else {
                slots.add(
                    PageSlot(
                        slotIndex = slotIndex++,
                        kind = SlotKind.SINGLE,
                        primaryPage = d.index,
                        aspectRatio = d.aspectRatio,
                        folder = d.folder,
                    ),
                )
            }
        }
        return slots
    }

    private fun buildDouble(
        descriptors: List<PageDescriptor>,
        flow: ReadingFlow,
        splitSpreads: Boolean,
        leaveCoverAlone: Boolean,
    ): List<PageSlot> {
        val slots = ArrayList<PageSlot>()
        var slotIndex = 0
        val byFolder = descriptors.groupBy { it.folder }.toSortedMap()

        for ((_, pages) in byFolder) {
            var i = 0
            while (i < pages.size) {
                val page = pages[i]

                if (splitSpreads && page.isLandscapeSpread) {
                    val halves = splitSpread(page, flow)
                    for (half in halves) {
                        slots.add(half.copy(slotIndex = slotIndex++))
                    }
                    i++
                    continue
                }

                val isCover = leaveCoverAlone && i == 0
                val canPair = !isCover &&
                    !page.isLandscapeSpread &&
                    page.isPortrait &&
                    i + 1 < pages.size

                if (canPair) {
                    val next = pages[i + 1]
                    val nextOk = !next.isLandscapeSpread && next.isPortrait && next.folder == page.folder
                    if (nextOk) {
                        val (left, right) = if (flow == ReadingFlow.RIGHT_TO_LEFT) {
                            next to page
                        } else {
                            page to next
                        }
                        val combinedAspect = (left.aspectRatio + right.aspectRatio).coerceAtLeast(0.01f)
                        slots.add(
                            PageSlot(
                                slotIndex = slotIndex++,
                                kind = SlotKind.DOUBLE,
                                primaryPage = left.index,
                                secondaryPage = right.index,
                                aspectRatio = combinedAspect,
                                folder = page.folder,
                            ),
                        )
                        i += 2
                        continue
                    }
                }

                slots.add(
                    PageSlot(
                        slotIndex = slotIndex++,
                        kind = SlotKind.SINGLE,
                        primaryPage = page.index,
                        aspectRatio = page.aspectRatio,
                        folder = page.folder,
                    ),
                )
                i++
            }
        }
        return slots
    }

    private fun buildContinuous(
        descriptors: List<PageDescriptor>,
        horizontal: Boolean,
    ): List<PageSlot> {
        return descriptors.mapIndexed { index, d ->
            PageSlot(
                slotIndex = index,
                kind = SlotKind.CONTINUOUS,
                primaryPage = d.index,
                aspectRatio = d.aspectRatio,
                folder = d.folder,
            )
        }.also {
            @Suppress("UNUSED_EXPRESSION")
            horizontal
        }
    }

    private fun splitSpread(page: PageDescriptor, flow: ReadingFlow): List<PageSlot> {
        val halfAspect = page.aspectRatio / 2f
        val leftHalf = 0
        val rightHalf = 1
        val first = if (flow == ReadingFlow.RIGHT_TO_LEFT) rightHalf else leftHalf
        val second = if (flow == ReadingFlow.RIGHT_TO_LEFT) leftHalf else rightHalf
        return listOf(
            PageSlot(
                slotIndex = 0,
                kind = SlotKind.SPLIT_HALF,
                primaryPage = page.index,
                splitHalf = first,
                aspectRatio = halfAspect.coerceAtLeast(0.01f),
                folder = page.folder,
            ),
            PageSlot(
                slotIndex = 0,
                kind = SlotKind.SPLIT_HALF,
                primaryPage = page.index,
                splitHalf = second,
                aspectRatio = halfAspect.coerceAtLeast(0.01f),
                folder = page.folder,
            ),
        )
    }

    /** Maps a physical page index to the first slot that contains it. */
    fun slotIndexForPage(slots: List<PageSlot>, pageIndex: Int): Int {
        val idx = slots.indexOfFirst { pageIndex in it.pageIndices }
        return if (idx >= 0) idx else 0
    }

    /** Highest physical page index represented by a slot (for progress reporting). */
    fun highestPageInSlot(slot: PageSlot): Int =
        slot.pageIndices.maxOrNull() ?: slot.primaryPage
}
