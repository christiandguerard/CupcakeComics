package com.cupcakecomics.reader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Lightweight in-app banner anchored to the top of the reader. It is not clickable
 * or focusable, so it never steals touches and reading can continue uninterrupted;
 * it slides in, lingers briefly, then dismisses itself.
 */
object TopBanner {
    private const val TAG = "cupcake_top_banner"
    private const val HOLD_MS = 3400L
    private const val FADE_IN_MS = 220L
    private const val FADE_OUT_MS = 260L

    fun show(root: FrameLayout, message: CharSequence) {
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        val density = root.resources.displayMetrics.density
        val pill = TextView(root.context).apply {
            tag = TAG
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val hPad = (20 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(0xF0242126.toInt())
            }
            isClickable = false
            isFocusable = false
            elevation = 8 * density
            alpha = 0f
            translationY = -24 * density
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        lp.topMargin = (16 * density).toInt()
        root.addView(pill, lp)
        root.announceForAccessibility(message)

        pill.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pill.postDelayed({ dismiss(root, pill) }, HOLD_MS)
            }
            .start()
    }

    private fun dismiss(root: FrameLayout, pill: View) {
        if (!pill.isAttachedToWindow) return
        pill.animate()
            .alpha(0f)
            .translationY(pill.translationY - 12 * pill.resources.displayMetrics.density)
            .setDuration(FADE_OUT_MS)
            .withEndAction { root.removeView(pill) }
            .start()
    }
}
