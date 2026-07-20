package com.cupcakecomics.reader.panel

import android.graphics.RectF

/**
 * Manages panel-slot navigation for Guided Panel reading mode.
 * Panels are detected per-page; navigation steps left-to-right, top-to-bottom.
 */
class GuidedPanelController {
    private var panels: List<RectF> = listOf(RectF(0f, 0f, 1f, 1f))
    private var panelIndex: Int = 0

    val currentPanel: RectF get() = panels.getOrElse(panelIndex) { RectF(0f, 0f, 1f, 1f) }
    val isAtLastPanel: Boolean get() = panelIndex >= panels.size - 1
    val isAtFirstPanel: Boolean get() = panelIndex <= 0

    fun setPagePanels(detected: List<RectF>) {
        panels = detected.ifEmpty { listOf(RectF(0f, 0f, 1f, 1f)) }
        panelIndex = 0
    }

    fun resetToLast() {
        panelIndex = (panels.size - 1).coerceAtLeast(0)
    }

    /** @return true if caller should advance to next page */
    fun advance(): Boolean {
        if (panelIndex < panels.size - 1) {
            panelIndex++
            return false
        }
        return true
    }

    /** @return true if caller should retreat to previous page */
    fun retreat(): Boolean {
        if (panelIndex > 0) {
            panelIndex--
            return false
        }
        return true
    }
}
