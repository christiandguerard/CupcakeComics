package com.cupcakecomics.reader.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.cupcakecomics.reader.model.PageTransition
import kotlin.math.abs
import kotlin.math.max

/**
 * ViewPager2 transformers matching CDisplayEx-style page transitions.
 */
class PageTransitionTransformer(
    var type: PageTransition = PageTransition.TRANSLATE,
) : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.translationX = 0f
        page.translationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
        page.translationZ = 0f
        page.pivotX = page.width * 0.5f
        page.pivotY = page.height * 0.5f

        when (type) {
            PageTransition.TRANSLATE -> {
                // Default ViewPager2 behavior — pages push each other without overlap tricks.
            }
            PageTransition.PAGE -> {
                // Next page stacks above previous while sliding in.
                page.translationZ = if (position < 0f) 0f else 1f
                if (position <= -1f || position >= 1f) {
                    page.alpha = 0f
                } else {
                    page.alpha = 1f
                    page.translationX = -position * page.width * 0.15f
                }
            }
            PageTransition.IN_PLACE -> {
                // Reveal without sliding the outgoing page away.
                when {
                    position < -1f || position > 1f -> page.alpha = 0f
                    position <= 0f -> {
                        page.alpha = 1f + position
                        page.translationX = -position * page.width
                    }
                    else -> {
                        page.alpha = 1f - position
                        page.translationX = -position * page.width
                    }
                }
            }
            PageTransition.SCALE -> {
                val absPos = abs(position)
                val scale = max(0.85f, 1f - absPos * 0.15f)
                page.scaleX = scale
                page.scaleY = scale
                page.alpha = max(0.4f, 1f - absPos)
                page.translationZ = 1f - absPos
            }
        }
    }
}
