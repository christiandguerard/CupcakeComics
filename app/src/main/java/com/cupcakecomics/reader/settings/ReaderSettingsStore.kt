package com.cupcakecomics.reader.settings

import android.content.Context
import android.content.SharedPreferences
import com.cupcakecomics.reader.model.ColorCorrection
import com.cupcakecomics.reader.model.FitMode
import com.cupcakecomics.reader.model.FitPreferences
import com.cupcakecomics.reader.model.LayoutPreferences
import com.cupcakecomics.reader.model.PageTransition
import com.cupcakecomics.reader.model.PagesLayout
import com.cupcakecomics.reader.model.ReadingFlow
import com.cupcakecomics.reader.model.ReaderPreferences
import com.cupcakecomics.reader.model.TransitionAxis
import com.cupcakecomics.reader.model.TransitionPreferences
import com.nkanaev.comics.Constants
import com.nkanaev.comics.MainApplication

/**
 * Persists global reader defaults and optional per-book overrides.
 * Migrates legacy Bubble2 LTR / page-view-mode settings on first access.
 */
class ReaderSettingsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    fun loadDefaults(): ReaderPreferences = ReaderPreferences(
        readingFlow = readingFlowFromStored(prefs.getString(KEY_FLOW, null)),
        fit = FitPreferences(
            portraitScreenPortraitPage = fitFrom(prefs.getString(KEY_FIT_PP, null), FitMode.FIT_SCREEN),
            portraitScreenLandscapePage = fitFrom(prefs.getString(KEY_FIT_PL, null), FitMode.FIT_WIDTH),
            landscapeScreenPortraitPage = fitFrom(prefs.getString(KEY_FIT_LP, null), FitMode.FIT_HEIGHT),
            landscapeScreenLandscapePage = fitFrom(prefs.getString(KEY_FIT_LL, null), FitMode.FIT_SCREEN),
            widthMultiplier = prefs.getFloat(KEY_FIT_W_MULT, 1f),
            heightMultiplier = prefs.getFloat(KEY_FIT_H_MULT, 1f),
        ),
        layout = LayoutPreferences(
            portrait = layoutFrom(prefs.getString(KEY_LAYOUT_P, null), PagesLayout.SINGLE),
            landscape = layoutFrom(prefs.getString(KEY_LAYOUT_L, null), PagesLayout.DOUBLE_WITH_COVER),
        ),
        transitions = TransitionPreferences(
            axis = axisFrom(prefs.getString(KEY_TRANS_AXIS, null), TransitionAxis.BOTH),
            horizontal = transitionFrom(prefs.getString(KEY_TRANS_H, null), PageTransition.TRANSLATE),
            vertical = transitionFrom(prefs.getString(KEY_TRANS_V, null), PageTransition.TRANSLATE),
            landscapeHorizontal = transitionFrom(prefs.getString(KEY_TRANS_LH, null), PageTransition.SCALE),
            landscapeVertical = transitionFrom(prefs.getString(KEY_TRANS_LV, null), PageTransition.TRANSLATE),
        ),
        colorCorrection = ColorCorrection(
            whiteBalance = prefs.getFloat(KEY_WB, 0f),
            whiteBalanceHardness = prefs.getFloat(KEY_WB_HARD, 0f),
            vibrance = prefs.getFloat(KEY_VIBRANCE, 0f),
            gammaR = prefs.getFloat(KEY_GAMMA_R, 1f),
            gammaG = prefs.getFloat(KEY_GAMMA_G, 1f),
            gammaB = prefs.getFloat(KEY_GAMMA_B, 1f),
            linkGamma = prefs.getBoolean(KEY_GAMMA_LINK, true),
        ),
        cropBorders = prefs.getBoolean(KEY_CROP, false),
        lockRotation = prefs.getBoolean(KEY_LOCK_ROTATION, false),
        showThumbnails = prefs.getBoolean(KEY_SHOW_THUMBS, true),
        splitSpreads = prefs.getBoolean(KEY_SPLIT_SPREADS, false),
        useGpuRenderer = prefs.getBoolean(KEY_GPU, true),
        useLanczos = prefs.getBoolean(KEY_LANCZOS, true),
        lowPowerScaling = prefs.getBoolean(KEY_LOW_POWER, false),
    )

    fun saveDefaults(prefsModel: ReaderPreferences) {
        prefs.edit()
            .putString(KEY_FLOW, prefsModel.readingFlow.name)
            .putString(KEY_FIT_PP, prefsModel.fit.portraitScreenPortraitPage.name)
            .putString(KEY_FIT_PL, prefsModel.fit.portraitScreenLandscapePage.name)
            .putString(KEY_FIT_LP, prefsModel.fit.landscapeScreenPortraitPage.name)
            .putString(KEY_FIT_LL, prefsModel.fit.landscapeScreenLandscapePage.name)
            .putFloat(KEY_FIT_W_MULT, prefsModel.fit.widthMultiplier)
            .putFloat(KEY_FIT_H_MULT, prefsModel.fit.heightMultiplier)
            .putString(KEY_LAYOUT_P, prefsModel.layout.portrait.name)
            .putString(KEY_LAYOUT_L, prefsModel.layout.landscape.name)
            .putString(KEY_TRANS_AXIS, prefsModel.transitions.axis.name)
            .putString(KEY_TRANS_H, prefsModel.transitions.horizontal.name)
            .putString(KEY_TRANS_V, prefsModel.transitions.vertical.name)
            .putString(KEY_TRANS_LH, prefsModel.transitions.landscapeHorizontal.name)
            .putString(KEY_TRANS_LV, prefsModel.transitions.landscapeVertical.name)
            .putFloat(KEY_WB, prefsModel.colorCorrection.whiteBalance)
            .putFloat(KEY_WB_HARD, prefsModel.colorCorrection.whiteBalanceHardness)
            .putFloat(KEY_VIBRANCE, prefsModel.colorCorrection.vibrance)
            .putFloat(KEY_GAMMA_R, prefsModel.colorCorrection.gammaR)
            .putFloat(KEY_GAMMA_G, prefsModel.colorCorrection.gammaG)
            .putFloat(KEY_GAMMA_B, prefsModel.colorCorrection.gammaB)
            .putBoolean(KEY_GAMMA_LINK, prefsModel.colorCorrection.linkGamma)
            .putBoolean(KEY_CROP, prefsModel.cropBorders)
            .putBoolean(KEY_LOCK_ROTATION, prefsModel.lockRotation)
            .putBoolean(KEY_SHOW_THUMBS, prefsModel.showThumbnails)
            .putBoolean(KEY_SPLIT_SPREADS, prefsModel.splitSpreads)
            .putBoolean(KEY_GPU, prefsModel.useGpuRenderer)
            .putBoolean(KEY_LANCZOS, prefsModel.useLanczos)
            .putBoolean(KEY_LOW_POWER, prefsModel.lowPowerScaling)
            .apply()

        // Keep Bubble2 keys in sync for legacy reader path.
        val legacy = MainApplication.getPreferences().edit()
        legacy.putBoolean(
            Constants.SETTINGS_READING_LEFT_TO_RIGHT,
            prefsModel.readingFlow != ReadingFlow.RIGHT_TO_LEFT,
        )
        val viewMode = when (prefsModel.fit.portraitScreenPortraitPage) {
            FitMode.FIT_WIDTH -> Constants.PageViewMode.FIT_WIDTH.native_int
            FitMode.FULL_SIZE -> Constants.PageViewMode.ASPECT_FILL.native_int
            else -> Constants.PageViewMode.ASPECT_FIT.native_int
        }
        legacy.putInt(Constants.SETTINGS_PAGE_VIEW_MODE, viewMode)
        legacy.apply()
    }

    fun loadForBook(identityKey: String?): ReaderPreferences {
        val base = loadDefaults()
        if (identityKey.isNullOrBlank()) return base
        val prefix = "book:${identityKey.hashCode()}:"
        if (!prefs.contains(prefix + KEY_FLOW)) return base
        return base.copy(
            readingFlow = readingFlowFromStored(prefs.getString(prefix + KEY_FLOW, base.readingFlow.name)),
            layout = LayoutPreferences(
                portrait = layoutFrom(
                    prefs.getString(prefix + KEY_LAYOUT_P, base.layout.portrait.name),
                    base.layout.portrait,
                ),
                landscape = layoutFrom(
                    prefs.getString(prefix + KEY_LAYOUT_L, base.layout.landscape.name),
                    base.layout.landscape,
                ),
            ),
            cropBorders = prefs.getBoolean(prefix + KEY_CROP, base.cropBorders),
            splitSpreads = prefs.getBoolean(prefix + KEY_SPLIT_SPREADS, base.splitSpreads),
            showThumbnails = prefs.getBoolean(prefix + KEY_SHOW_THUMBS, base.showThumbnails),
        )
    }

    fun saveForBook(identityKey: String?, model: ReaderPreferences) {
        if (identityKey.isNullOrBlank()) {
            saveDefaults(model)
            return
        }
        val prefix = "book:${identityKey.hashCode()}:"
        prefs.edit()
            .putString(prefix + KEY_FLOW, model.readingFlow.name)
            .putString(prefix + KEY_LAYOUT_P, model.layout.portrait.name)
            .putString(prefix + KEY_LAYOUT_L, model.layout.landscape.name)
            .putBoolean(prefix + KEY_CROP, model.cropBorders)
            .putBoolean(prefix + KEY_SPLIT_SPREADS, model.splitSpreads)
            .putBoolean(prefix + KEY_SHOW_THUMBS, model.showThumbnails)
            .apply()
        saveDefaults(model)
    }

    fun getBookmarks(identityKey: String?): Set<Int> {
        if (identityKey.isNullOrBlank()) return emptySet()
        val raw = prefs.getStringSet(bookmarkKey(identityKey), emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setBookmarks(identityKey: String?, pages: Set<Int>) {
        if (identityKey.isNullOrBlank()) return
        prefs.edit()
            .putStringSet(bookmarkKey(identityKey), pages.map { it.toString() }.toSet())
            .apply()
    }

    fun toggleBookmark(identityKey: String?, pageIndex: Int): Set<Int> {
        val current = getBookmarks(identityKey).toMutableSet()
        if (!current.add(pageIndex)) current.remove(pageIndex)
        setBookmarks(identityKey, current)
        return current
    }

    /** 1-based last page reached for resume (0 = never opened / start of book). */
    fun getLastPage(identityKey: String?): Int {
        if (identityKey.isNullOrBlank()) return 0
        return prefs.getInt(lastPageKey(identityKey), 0).coerceAtLeast(0)
    }

    fun setLastPage(identityKey: String?, pageOneBased: Int) {
        if (identityKey.isNullOrBlank() || pageOneBased < 1) return
        prefs.edit().putInt(lastPageKey(identityKey), pageOneBased).apply()
    }

    private fun bookmarkKey(identityKey: String) = "bookmarks:${identityKey.hashCode()}"

    private fun lastPageKey(identityKey: String) = "last_page:${identityKey.hashCode()}"

    private fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = MainApplication.getPreferences()
        val ltr = legacy.getBoolean(Constants.SETTINGS_READING_LEFT_TO_RIGHT, true)
        val viewMode = legacy.getInt(
            Constants.SETTINGS_PAGE_VIEW_MODE,
            Constants.PageViewMode.ASPECT_FIT.native_int,
        )
        val fit = when (viewMode) {
            Constants.PageViewMode.FIT_WIDTH.native_int -> FitMode.FIT_WIDTH
            Constants.PageViewMode.ASPECT_FILL.native_int -> FitMode.FULL_SIZE
            else -> FitMode.FIT_SCREEN
        }
        prefs.edit()
            .putString(KEY_FLOW, if (ltr) ReadingFlow.LEFT_TO_RIGHT.name else ReadingFlow.RIGHT_TO_LEFT.name)
            .putString(KEY_FIT_PP, fit.name)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    companion object {
        private const val PREFS = "cupcake_reader_settings"
        private const val KEY_MIGRATED = "migrated_legacy"
        private const val KEY_FLOW = "reading_flow"
        private const val KEY_FIT_PP = "fit_pp"
        private const val KEY_FIT_PL = "fit_pl"
        private const val KEY_FIT_LP = "fit_lp"
        private const val KEY_FIT_LL = "fit_ll"
        private const val KEY_FIT_W_MULT = "fit_w_mult"
        private const val KEY_FIT_H_MULT = "fit_h_mult"
        private const val KEY_LAYOUT_P = "layout_p"
        private const val KEY_LAYOUT_L = "layout_l"
        private const val KEY_TRANS_AXIS = "trans_axis"
        private const val KEY_TRANS_H = "trans_h"
        private const val KEY_TRANS_V = "trans_v"
        private const val KEY_TRANS_LH = "trans_lh"
        private const val KEY_TRANS_LV = "trans_lv"
        private const val KEY_WB = "wb"
        private const val KEY_WB_HARD = "wb_hard"
        private const val KEY_VIBRANCE = "vibrance"
        private const val KEY_GAMMA_R = "gamma_r"
        private const val KEY_GAMMA_G = "gamma_g"
        private const val KEY_GAMMA_B = "gamma_b"
        private const val KEY_GAMMA_LINK = "gamma_link"
        private const val KEY_CROP = "crop"
        private const val KEY_LOCK_ROTATION = "lock_rotation"
        private const val KEY_SHOW_THUMBS = "show_thumbs"
        private const val KEY_SPLIT_SPREADS = "split_spreads"
        private const val KEY_GPU = "gpu"
        private const val KEY_LANCZOS = "lanczos"
        private const val KEY_LOW_POWER = "low_power"

        private fun readingFlowFromStored(name: String?): ReadingFlow =
            runCatching { ReadingFlow.valueOf(name!!) }.getOrDefault(ReadingFlow.LEFT_TO_RIGHT)

        private fun fitFrom(name: String?, fallback: FitMode): FitMode =
            runCatching { FitMode.valueOf(name!!) }.getOrDefault(fallback)

        private fun layoutFrom(name: String?, fallback: PagesLayout): PagesLayout =
            runCatching { PagesLayout.valueOf(name!!) }.getOrDefault(fallback)

        private fun transitionFrom(name: String?, fallback: PageTransition): PageTransition =
            runCatching { PageTransition.valueOf(name!!) }.getOrDefault(fallback)

        private fun axisFrom(name: String?, fallback: TransitionAxis): TransitionAxis =
            runCatching { TransitionAxis.valueOf(name!!) }.getOrDefault(fallback)
    }
}
