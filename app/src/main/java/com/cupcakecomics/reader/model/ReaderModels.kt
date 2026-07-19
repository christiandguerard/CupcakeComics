package com.cupcakecomics.reader.model

/**
 * Metadata for a single archive page, obtained before full pixel decode when possible.
 */
data class PageDescriptor(
    val index: Int,
    val name: String = "",
    val mime: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val folder: String = "",
    val rotationDegrees: Int = 0,
    /** Normalized crop insets (0..1) applied before layout sizing. */
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
) {
    val aspectRatio: Float
        get() {
            val w = effectiveWidth.toFloat()
            val h = effectiveHeight.toFloat()
            return if (w > 0f && h > 0f) w / h else 0.65f
        }

    val effectiveWidth: Int
        get() {
            if (width <= 0 || height <= 0) return 0
            val cw = (width * (1f - cropLeft - cropRight)).toInt().coerceAtLeast(1)
            return if (rotationDegrees % 180 == 0) cw else (height * (1f - cropTop - cropBottom)).toInt().coerceAtLeast(1)
        }

    val effectiveHeight: Int
        get() {
            if (width <= 0 || height <= 0) return 0
            val ch = (height * (1f - cropTop - cropBottom)).toInt().coerceAtLeast(1)
            return if (rotationDegrees % 180 == 0) ch else (width * (1f - cropLeft - cropRight)).toInt().coerceAtLeast(1)
        }

    val isPortrait: Boolean
        get() = aspectRatio < 1f

    val isLandscapeSpread: Boolean
        get() = aspectRatio >= SPREAD_ASPECT_THRESHOLD

    companion object {
        /** Pages wider than this are treated as double-page spreads. */
        const val SPREAD_ASPECT_THRESHOLD = 1.35f
    }
}

/**
 * A logical viewing unit: one page, a paired double, a split half, or continuous row/column cell.
 */
data class PageSlot(
    val slotIndex: Int,
    val kind: SlotKind,
    val primaryPage: Int,
    val secondaryPage: Int? = null,
    /** For split spreads: 0 = left half, 1 = right half (reading-order aware). */
    val splitHalf: Int? = null,
    val aspectRatio: Float = 0.65f,
    val folder: String = "",
) {
    val pageIndices: List<Int>
        get() = listOfNotNull(primaryPage, secondaryPage)

    val firstPageIndex: Int get() = primaryPage
}

enum class SlotKind {
    SINGLE,
    DOUBLE,
    SPLIT_HALF,
    CONTINUOUS,
}

enum class ReadingFlow {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
}

enum class FitMode {
    FIT_SCREEN,
    FULL_SIZE,
    FIT_WIDTH,
    FIT_HEIGHT,
}

enum class PagesLayout {
    SINGLE,
    DOUBLE,
    DOUBLE_WITH_COVER,
    CONTINUOUS_VERTICAL,
    CONTINUOUS_HORIZONTAL,
}

enum class PageTransition {
    TRANSLATE,
    PAGE,
    IN_PLACE,
    SCALE,
}

enum class TransitionAxis {
    HORIZONTAL,
    VERTICAL,
    BOTH,
}

data class ColorCorrection(
    val whiteBalance: Float = 0f,
    val whiteBalanceHardness: Float = 0f,
    val vibrance: Float = 0f,
    val gammaR: Float = 1f,
    val gammaG: Float = 1f,
    val gammaB: Float = 1f,
    val linkGamma: Boolean = true,
) {
    val isIdentity: Boolean
        get() = whiteBalance == 0f &&
            whiteBalanceHardness == 0f &&
            vibrance == 0f &&
            gammaR == 1f &&
            gammaG == 1f &&
            gammaB == 1f
}

data class OrientationContext(
    val screenPortrait: Boolean,
    val pagePortrait: Boolean,
)

/**
 * Fit preferences keyed by screen+page orientation context.
 */
data class FitPreferences(
    val portraitScreenPortraitPage: FitMode = FitMode.FIT_SCREEN,
    val portraitScreenLandscapePage: FitMode = FitMode.FIT_WIDTH,
    val landscapeScreenPortraitPage: FitMode = FitMode.FIT_HEIGHT,
    val landscapeScreenLandscapePage: FitMode = FitMode.FIT_SCREEN,
    val widthMultiplier: Float = 1f,
    val heightMultiplier: Float = 1f,
) {
    fun modeFor(ctx: OrientationContext): FitMode = when {
        ctx.screenPortrait && ctx.pagePortrait -> portraitScreenPortraitPage
        ctx.screenPortrait && !ctx.pagePortrait -> portraitScreenLandscapePage
        !ctx.screenPortrait && ctx.pagePortrait -> landscapeScreenPortraitPage
        else -> landscapeScreenLandscapePage
    }
}

data class LayoutPreferences(
    val portrait: PagesLayout = PagesLayout.SINGLE,
    val landscape: PagesLayout = PagesLayout.DOUBLE_WITH_COVER,
) {
    fun forScreen(screenPortrait: Boolean): PagesLayout =
        if (screenPortrait) this.portrait else this.landscape
}

data class TransitionPreferences(
    val axis: TransitionAxis = TransitionAxis.BOTH,
    val horizontal: PageTransition = PageTransition.TRANSLATE,
    val vertical: PageTransition = PageTransition.TRANSLATE,
    val landscapeHorizontal: PageTransition = PageTransition.SCALE,
    val landscapeVertical: PageTransition = PageTransition.TRANSLATE,
) {
    fun horizontalFor(screenPortrait: Boolean): PageTransition =
        if (screenPortrait) horizontal else landscapeHorizontal

    fun verticalFor(screenPortrait: Boolean): PageTransition =
        if (screenPortrait) vertical else landscapeVertical
}

data class ReaderPreferences(
    val readingFlow: ReadingFlow = ReadingFlow.LEFT_TO_RIGHT,
    val fit: FitPreferences = FitPreferences(),
    val layout: LayoutPreferences = LayoutPreferences(),
    val transitions: TransitionPreferences = TransitionPreferences(),
    val colorCorrection: ColorCorrection = ColorCorrection(),
    val cropBorders: Boolean = false,
    val lockRotation: Boolean = false,
    val showThumbnails: Boolean = true,
    val splitSpreads: Boolean = false,
    val useGpuRenderer: Boolean = true,
    val useLanczos: Boolean = true,
    val lowPowerScaling: Boolean = false,
)

data class Bookmark(
    val pageIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val label: String = "",
)

data class TocEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int = 0,
)

data class ReaderSession(
    val identityKey: String? = null,
    val title: String = "",
    val sourceType: String = "",
    val pageCount: Int = 0,
    val descriptors: List<PageDescriptor> = emptyList(),
    val slots: List<PageSlot> = emptyList(),
    val currentSlotIndex: Int = 0,
    val bookmarks: Set<Int> = emptySet(),
    val toc: List<TocEntry> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    val chromeVisible: Boolean = false,
    val pageInfoVisible: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val remoteStreaming: Boolean = false,
) {
    val currentSlot: PageSlot?
        get() = slots.getOrNull(currentSlotIndex)

    val currentPageIndex: Int
        get() = currentSlot?.firstPageIndex ?: 0

    val progressFraction: Float
        get() = if (pageCount <= 0) 0f else (currentPageIndex + 1).toFloat() / pageCount
}
