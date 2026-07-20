/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.cupcakecomics.reader.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Detects comic panel boundaries in a page bitmap.
 *
 * Current implementation: whole-page fallback stub.
 * TODO: integrate TFLite model from Chika pipeline once a compatible asset is confirmed.
 *       See SPEC Phase 6 / TFLite model asset checklist.
 */
object PanelDetector {
    /**
     * Returns normalized [RectF] coordinates (0..1, x/y relative to image dimensions).
     * Fallback: single full-page rect when no model is loaded.
     */
    fun detect(context: Context, bitmap: Bitmap): List<RectF> {
        // TODO: TFLite inference
        return listOf(RectF(0f, 0f, 1f, 1f))
    }
}
