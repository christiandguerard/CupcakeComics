package com.cupcakecomics.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Full-bleed image with a draggable rectangular crop frame for share-crop. */
class PageCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverse = Matrix()
    private val imageRect = RectF()
    private val cropRect = RectF()
    private val tmp = RectF()

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private var dragMode = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f
    private val handle = resources.displayMetrics.density * 18f
    private val minSize = resources.displayMetrics.density * 64f

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        requestLayout()
        post { layoutImage(); invalidate() }
    }

    fun croppedBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        if (!imageMatrix.invert(inverse)) return null
        tmp.set(cropRect)
        inverse.mapRect(tmp)
        val left = tmp.left.toInt().coerceIn(0, bmp.width - 1)
        val top = tmp.top.toInt().coerceIn(0, bmp.height - 1)
        val right = tmp.right.toInt().coerceIn(left + 1, bmp.width)
        val bottom = tmp.bottom.toInt().coerceIn(top + 1, bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = (width - dw) / 2f
        val dy = (height - dh) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        imageRect.set(dx, dy, dx + dw, dy + dh)
        val inset = min(dw, dh) * 0.08f
        cropRect.set(
            imageRect.left + inset,
            imageRect.top + inset,
            imageRect.right - inset,
            imageRect.bottom - inset,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, null)
        // Dim outside crop
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect, framePaint)
        drawHandle(canvas, cropRect.left, cropRect.top)
        drawHandle(canvas, cropRect.right, cropRect.top)
        drawHandle(canvas, cropRect.left, cropRect.bottom)
        drawHandle(canvas, cropRect.right, cropRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handle * 0.35f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return dragMode != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == Drag.NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = Drag.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Drag {
        fun near(px: Float, py: Float) = max(kotlin.math.abs(x - px), kotlin.math.abs(y - py)) <= handle
        return when {
            near(cropRect.left, cropRect.top) -> Drag.TL
            near(cropRect.right, cropRect.top) -> Drag.TR
            near(cropRect.left, cropRect.bottom) -> Drag.BL
            near(cropRect.right, cropRect.bottom) -> Drag.BR
            cropRect.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            Drag.MOVE -> {
                cropRect.offset(dx, dy)
                if (cropRect.left < imageRect.left) cropRect.offset(imageRect.left - cropRect.left, 0f)
                if (cropRect.top < imageRect.top) cropRect.offset(0f, imageRect.top - cropRect.top)
                if (cropRect.right > imageRect.right) cropRect.offset(imageRect.right - cropRect.right, 0f)
                if (cropRect.bottom > imageRect.bottom) cropRect.offset(0f, imageRect.bottom - cropRect.bottom)
            }
            Drag.TL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageRect.left, cropRect.right - minSize)
                cropRect.top = (cropRect.top + dy).coerceIn(imageRect.top, cropRect.bottom - minSize)
            }
            Drag.TR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imageRect.right)
                cropRect.top = (cropRect.top + dy).coerceIn(imageRect.top, cropRect.bottom - minSize)
            }
            Drag.BL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imageRect.left, cropRect.right - minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imageRect.bottom)
            }
            Drag.BR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imageRect.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imageRect.bottom)
            }
            Drag.NONE -> Unit
        }
    }

    private enum class Drag { NONE, MOVE, TL, TR, BL, BR }
}
