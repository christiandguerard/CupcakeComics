package com.cupcakecomics.reader.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import com.cupcakecomics.reader.model.FitMode
import kotlin.math.abs
import kotlin.math.min

/**
 * Pinch-zoomable comic page with bounded panning.
 *
 * A drag that reaches an edge remains on the page. A new gesture beginning at
 * that edge and continuing outward releases interception to the parent pager.
 */
class ZoomablePageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val pageMatrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val scroller = OverScroller(context).apply {
        // A short, controlled glide rather than a long gallery-style fling.
        setFriction(ViewConfiguration.getScrollFriction() * 1.35f)
    }
    private var velocityTracker: VelocityTracker? = null
    private var scaledThisGesture = false
    private var baseScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var startedAtLeft = false
    private var startedAtRight = false
    private var startedAtTop = false
    private var startedAtBottom = false
    private var fitMode = FitMode.FIT_SCREEN

    var parentHandoff: Boolean = false
        private set

    val hasPanOrZoom: Boolean
        get() = zoom > 1.01f || contentWidth() > width + 1f || contentHeight() > height + 1f

    private var onCenterTap: (() -> Unit)? = null
    private var onEdgeTap: ((Boolean) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaledThisGesture = true
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldTotal = totalScale()
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                if (nextZoom == zoom) return true
                zoom = nextZoom
                val ratio = totalScale() / oldTotal
                offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
                offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
                constrainAndApply()
                return true
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = width.toFloat().coerceAtLeast(1f)
                when {
                    e.x < w * 0.28f -> onEdgeTap?.invoke(false)
                    e.x > w * 0.72f -> onEdgeTap?.invoke(true)
                    else -> onCenterTap?.invoke()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.01f) {
                    resetZoom(animated = true)
                } else {
                    zoomAround(e.x, e.y, 2.5f)
                }
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = pageMatrix
        isClickable = true
    }

    fun configure(
        fit: FitMode,
        centerTap: () -> Unit,
        edgeTap: (Boolean) -> Unit,
    ) {
        fitMode = fit
        onCenterTap = centerTap
        onEdgeTap = edgeTap
        post { resetZoom(animated = false) }
    }

    fun resetZoom(animated: Boolean = false) {
        if (!animated) {
            zoom = 1f
            recalculateBase(resetPosition = true)
            return
        }
        animate()
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(80)
            .withEndAction {
                zoom = 1f
                recalculateBase(resetPosition = true)
                scaleX = 1f
                scaleY = 1f
            }
            .start()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { recalculateBase(resetPosition = true) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateBase(resetPosition = true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                scaledThisGesture = false
                scroller.forceFinished(true)
                parentHandoff = false
                startedAtLeft = atLeftEdge()
                startedAtRight = atRightEdge()
                startedAtTop = atTopEdge()
                startedAtBottom = atBottomEdge()
                // Hold ownership until the first move. If another pointer lands,
                // ScaleGestureDetector wins before ViewPager can claim the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                scaledThisGesture = true
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                if (!hasPanOrZoom) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                // Handoff is only possible when this gesture began on the edge.
                // Reaching the edge during this gesture merely clamps the page.
                val horizontalIntent = abs(totalDx) >= abs(totalDy) * 0.70f
                val verticalIntent = abs(totalDy) >= abs(totalDx) * 0.70f
                val outward =
                    (horizontalIntent && startedAtLeft && totalDx > touchSlop) ||
                    (horizontalIntent && startedAtRight && totalDx < -touchSlop) ||
                    (verticalIntent && startedAtTop && totalDy > touchSlop) ||
                    (verticalIntent && startedAtBottom && totalDy < -touchSlop)

                if (outward) {
                    parentHandoff = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                offsetX += dx
                offsetY += dy
                constrainAndApply()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!parentHandoff && hasPanOrZoom && !scaledThisGesture) {
                    velocityTracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (abs(vx) >= minimumFlingVelocity || abs(vy) >= minimumFlingVelocity) {
                        startBoundedFling(vx * 0.68f, vy * 0.68f)
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun zoomAround(focusX: Float, focusY: Float, targetZoom: Float) {
        val oldTotal = totalScale()
        zoom = targetZoom.coerceIn(1f, 5f)
        val ratio = totalScale() / oldTotal
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        constrainAndApply()
    }

    private fun recalculateBase(resetPosition: Boolean) {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val sx = width.toFloat() / d.intrinsicWidth
        val sy = height.toFloat() / d.intrinsicHeight
        baseScale = when (fitMode) {
            FitMode.FIT_SCREEN -> min(sx, sy)
            FitMode.FIT_WIDTH -> sx
            FitMode.FIT_HEIGHT -> sy
            FitMode.FULL_SIZE -> 1f
        }
        if (resetPosition) {
            offsetX = (width - d.intrinsicWidth * totalScale()) / 2f
            offsetY = (height - d.intrinsicHeight * totalScale()) / 2f
        }
        constrainAndApply()
    }

    private fun constrainAndApply() {
        val cw = contentWidth()
        val ch = contentHeight()
        offsetX = if (cw <= width) {
            (width - cw) / 2f
        } else {
            offsetX.coerceIn(width - cw, 0f)
        }
        offsetY = if (ch <= height) {
            (height - ch) / 2f
        } else {
            offsetY.coerceIn(height - ch, 0f)
        }
        pageMatrix.reset()
        pageMatrix.setScale(totalScale(), totalScale())
        pageMatrix.postTranslate(offsetX, offsetY)
        imageMatrix = pageMatrix
        invalidate()
    }

    private fun startBoundedFling(velocityX: Float, velocityY: Float) {
        val cw = contentWidth()
        val ch = contentHeight()
        val centeredX = ((width - cw) / 2f).toInt()
        val centeredY = ((height - ch) / 2f).toInt()
        val minX = if (cw <= width) centeredX else (width - cw).toInt()
        val maxX = if (cw <= width) centeredX else 0
        val minY = if (ch <= height) centeredY else (height - ch).toInt()
        val maxY = if (ch <= height) centeredY else 0
        scroller.fling(
            offsetX.toInt(),
            offsetY.toInt(),
            velocityX.toInt(),
            velocityY.toInt(),
            minX,
            maxX,
            minY,
            maxY,
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (!scroller.computeScrollOffset()) return
        offsetX = scroller.currX.toFloat()
        offsetY = scroller.currY.toFloat()
        constrainAndApply()
        postInvalidateOnAnimation()
    }

    private fun totalScale(): Float = baseScale * zoom

    private fun contentWidth(): Float =
        (drawable?.intrinsicWidth ?: 0) * totalScale()

    private fun contentHeight(): Float =
        (drawable?.intrinsicHeight ?: 0) * totalScale()

    private fun atLeftEdge(): Boolean = contentWidth() <= width + 1f || offsetX >= -1f
    private fun atRightEdge(): Boolean =
        contentWidth() <= width + 1f || offsetX <= width - contentWidth() + 1f
    private fun atTopEdge(): Boolean = contentHeight() <= height + 1f || offsetY >= -1f
    private fun atBottomEdge(): Boolean =
        contentHeight() <= height + 1f || offsetY <= height - contentHeight() + 1f
}
