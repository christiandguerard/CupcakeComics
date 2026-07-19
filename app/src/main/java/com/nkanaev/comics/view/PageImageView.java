package com.nkanaev.comics.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import androidx.core.view.ViewCompat;
import android.util.AttributeSet;
import android.view.*;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.OverScroller;
import com.nkanaev.comics.Constants;

public class PageImageView extends androidx.appcompat.widget.AppCompatImageView {
    public interface OnSwipePageListener {
        void onSwipeNextPage();
        void onSwipePreviousPage();

        /**
         * Finger is dragging while the page is at its fitted home position.
         * Host should drive ViewPager fake-drag so the next/prev page peeks in.
         * @param deltaX incremental horizontal drag (finger right = positive)
         * @return true if the host consumed the drag as a page peek
         */
        boolean onPagePeekDrag(float deltaX);

        /** Finger up after a peek drag — host should endFakeDrag() to settle. */
        void onPagePeekEnd();
    }

    private Constants.PageViewMode mViewMode;
    private boolean mHaveFrame = false;
    private boolean mSkipScaling = false;
    private boolean mTranslateRightEdge = false;
    private OnTouchListener mOuterTouchListener;
    private OnSwipePageListener mSwipePageListener;
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mDragGestureDetector;
    private OverScroller mScroller;
    private float mMinScale, mMaxScale, mDblTapScale;
    private float mOriginalScale;
    private float[] m = new float[9];
    private Matrix mMatrix;
    private final Matrix mHomeMatrix = new Matrix();
    private boolean mUserPanned = false;
    private boolean mSnappingHome = false;
    private boolean mPeekDragging = false;
    private boolean mPeekAxisVertical = false;
    private boolean mSkipFixMatrix = false;
    private float mDownX;
    private float mDownY;
    private boolean mTrackingSwipe = false;
    /** Allow panning past page edges without zooming (fraction of view size). */
    private static final float OVERSCROLL = 0.4f;
    private static final int SNAP_DURATION_MS = 220;
    /** Fraction of the shorter screen edge required to commit a page turn. */
    private static final float PAGE_TURN_THRESHOLD = 0.22f;

    public PageImageView(Context context) {
        super(context);
        init();
    }

    public PageImageView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        init();
    }

    public void setViewMode(Constants.PageViewMode viewMode) {
        mViewMode = viewMode;
        mSkipScaling = false;
        requestLayout();
        invalidate();
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        mHaveFrame = true;
        scale();
        return changed;
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        mSkipScaling = false;
        try {
            // Shared black letterbox so adjacent pages read as one continuous canvas.
            setBackgroundColor(0xFF000000);
        } catch (Throwable ignored) {
            setBackgroundColor(0xFF000000);
        }
        scale();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        mSkipScaling = false;
        setBackgroundColor(0xFF000000);
        scale();
    }

    private void applyBorderBackground(Drawable drawable) {
        setBackgroundColor(0xFF000000);
    }

    private void init() {
        mMatrix = new Matrix();
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(mMatrix);

        mScaleGestureDetector = new ScaleGestureDetector(getContext(), new PrivateScaleDetector());
        mDragGestureDetector = new GestureDetector(getContext(), new PrivateDragListener());
        super.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mTrackingSwipe = true;
                    mUserPanned = false;
                    mPeekDragging = false;
                    mPeekAxisVertical = false;
                }

                boolean b1 = mScaleGestureDetector.onTouchEvent(event);
                boolean b2 = mDragGestureDetector.onTouchEvent(event);
                boolean b3 = false;
                if (mOuterTouchListener != null)
                    b3 = mOuterTouchListener.onTouch(v, event);

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (mPeekDragging) {
                        if (mSwipePageListener != null) {
                            mSwipePageListener.onPagePeekEnd();
                        }
                        mPeekDragging = false;
                        mUserPanned = false;
                        mTrackingSwipe = false;
                        return true;
                    }
                    boolean turned = false;
                    if (!mScaleGestureDetector.isInProgress() && mTrackingSwipe) {
                        turned = maybeTurnPageFromSwipe(event.getX() - mDownX, event.getY() - mDownY);
                    }
                    if (!turned && !mScaleGestureDetector.isInProgress() && mUserPanned) {
                        snapToFullPage();
                    }
                    mUserPanned = false;
                    mTrackingSwipe = false;
                }
                return true;
            }
        });

        mScroller = new OverScroller(getContext());
        mScroller.setFriction(ViewConfiguration.getScrollFriction() * 2);
        mViewMode = Constants.PageViewMode.ASPECT_FIT;
    }

    public void setOnSwipePageListener(OnSwipePageListener listener) {
        mSwipePageListener = listener;
    }

    /**
     * Finger left/up → next page; finger right/down → previous page.
     * @return true if a page turn was committed
     */
    private boolean maybeTurnPageFromSwipe(float fingerDx, float fingerDy) {
        if (mSwipePageListener == null) return false;
        float threshold = Math.min(getWidth(), getHeight()) * PAGE_TURN_THRESHOLD;
        if (threshold < 48f) threshold = 48f;

        float absX = Math.abs(fingerDx);
        float absY = Math.abs(fingerDy);
        if (absX < threshold && absY < threshold) return false;

        boolean horizontal = absX >= absY;
        if (horizontal) {
            // Finger moved left → next; right → previous
            if (fingerDx < 0) {
                mSwipePageListener.onSwipeNextPage();
            } else {
                mSwipePageListener.onSwipePreviousPage();
            }
        } else {
            // Finger moved up → next; down → previous
            if (fingerDy < 0) {
                mSwipePageListener.onSwipeNextPage();
            } else {
                mSwipePageListener.onSwipePreviousPage();
            }
        }
        // Stay put visually; pager will swap the view. Avoid snap fighting the turn.
        mScroller.forceFinished(true);
        mSnappingHome = false;
        return true;
    }

    private boolean isAtHome() {
        if (getDrawable() == null) return true;
        float[] cur = new float[9];
        float[] home = new float[9];
        mMatrix.getValues(cur);
        mHomeMatrix.getValues(home);
        return Math.abs(cur[Matrix.MSCALE_X] - home[Matrix.MSCALE_X]) < 0.01f
                && Math.abs(cur[Matrix.MTRANS_X] - home[Matrix.MTRANS_X]) < 1.5f
                && Math.abs(cur[Matrix.MTRANS_Y] - home[Matrix.MTRANS_Y]) < 1.5f;
    }

    private void snapToFullPage() {
        mScroller.forceFinished(true);
        mSnappingHome = true;
        // Temporarily allow unconstrained lerp toward home (fixMatrix would fight overshoot).
        post(new SnapHomeAnimation());
    }

    /** Restore fitted full-page matrix without animation (e.g. after page change). */
    public void resetToFullPage() {
        mScroller.forceFinished(true);
        mSnappingHome = false;
        mUserPanned = false;
        mSkipScaling = false;
        scale();
    }

    @Override
    public void setOnTouchListener(OnTouchListener l) {
        mOuterTouchListener = l;
    }

    public void setTranslateToRightEdge(boolean translate) {
        mTranslateRightEdge = translate;
    }

    private void scale() {
        Drawable drawable = getDrawable();
        if (drawable == null || !mHaveFrame || mSkipScaling) return;

        int dwidth = drawable.getIntrinsicWidth();
        int dheight = drawable.getIntrinsicHeight();

        int vwidth = getWidth();
        int vheight = getHeight();

        if (mViewMode == Constants.PageViewMode.ASPECT_FILL) {
            float scale;
            float dx = 0;

            if (dwidth * vheight > vwidth * dheight) {
                scale = (float) vheight / (float) dheight;
                if (mTranslateRightEdge)
                    dx = vwidth - dwidth * scale;
            } else {
                scale = (float) vwidth / (float) dwidth;
            }

            mMatrix.setScale(scale, scale);
            mMatrix.postTranslate((int) (dx + 0.5f), 0);
        }
        else if (mViewMode == Constants.PageViewMode.ASPECT_FIT) {
            RectF mTempSrc = new RectF(0, 0, dwidth, dheight);
            RectF mTempDst = new RectF(0, 0, vwidth, vheight);

            mMatrix.setRectToRect(mTempSrc, mTempDst, Matrix.ScaleToFit.CENTER);
        }
        else if (mViewMode == Constants.PageViewMode.FIT_WIDTH) {
            float widthScale = (float)getWidth()/drawable.getIntrinsicWidth();
            mMatrix.setScale(widthScale, widthScale);
            mMatrix.postTranslate(0, 0);
        }

        // calculate min/max scale
        float heightRatio = (float)vheight / dheight;
        float w = dwidth * heightRatio;
        if (w < vwidth) {
            mMinScale = vheight * 0.75f / dheight;
            mMaxScale = Math.max(dwidth, vwidth) * 10f / dwidth;
            mDblTapScale = Math.max(dwidth, vwidth) * 1.5f / dwidth;
        }
        else {
            mMinScale = vwidth * 0.75f / dwidth;
            mMaxScale = Math.max(dheight, vheight) * 10f / dheight;
            mDblTapScale = Math.max(dheight, vheight) * 1.5f / dheight;
        }
        setImageMatrix(mMatrix);
        mOriginalScale = getCurrentScale();
        mHomeMatrix.set(mMatrix);
        mSkipScaling = true;
    }

    // TODO: unused, kept as reference code
    public void rotate(float degrees) {
        animate().rotation(degrees).setDuration(1000);
        super.invalidate();
        if (true) return;

        Drawable drawable = getDrawable();
        if (drawable == null) return;

        mMatrix.postRotate(90);
        super.setImageMatrix(mMatrix);
        super.postInvalidate();
    }

    private class PrivateScaleDetector extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mMatrix.getValues(m);

            float scale = m[Matrix.MSCALE_X];
            float scaleFactor = detector.getScaleFactor();
            float scaleNew = scale * scaleFactor;
            boolean scalable = true;

            if (scaleFactor > 1 && mMaxScale - scaleNew < 0) {
                scaleFactor = mMaxScale / scale;
                scalable = false;
            }
            else if (scaleFactor < 1 && mMinScale - scaleNew > 0) {
                scaleFactor = mMinScale / scale;
                scalable = false;
            }

            mMatrix.postScale(
                    scaleFactor, scaleFactor,
                    detector.getFocusX(), detector.getFocusY());
            setImageMatrix(mMatrix);

            return scalable;
        }
    }

    private class PrivateDragListener extends SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            mScroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            mSnappingHome = false;

            // Fitted page: never pan the bitmap — only slide the pager so pages share one canvas.
            if (!mScaleGestureDetector.isInProgress() && mSwipePageListener != null
                    && (mPeekDragging || isAtHome())) {
                float totalDx = e2.getX() - mDownX;
                float totalDy = e2.getY() - mDownY;
                float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

                if (!mPeekDragging) {
                    if (Math.hypot(totalDx, totalDy) < slop) {
                        // Absorb micro-moves so the page doesn't jitter before the turn starts.
                        return true;
                    }
                    mPeekAxisVertical = Math.abs(totalDy) > Math.abs(totalDx);
                    float startDelta = mPeekAxisVertical ? -distanceY : -distanceX;
                    if (mSwipePageListener.onPagePeekDrag(startDelta)) {
                        mPeekDragging = true;
                        // Keep matrix exactly at home (no animated reset mid-gesture).
                        mMatrix.set(mHomeMatrix);
                        mSkipFixMatrix = true;
                        setImageMatrix(mMatrix);
                        mSkipFixMatrix = false;
                        return true;
                    }
                    return true;
                }

                float delta = mPeekAxisVertical ? -distanceY : -distanceX;
                if (mSwipePageListener.onPagePeekDrag(delta)) {
                    return true;
                }
                mPeekDragging = false;
            }

            mUserPanned = true;
            mMatrix.postTranslate(-distanceX, -distanceY);
            setImageMatrix(mMatrix);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Never leave the page off-center after a fling — snap home instead.
            mUserPanned = true;
            mScroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float scale = (mOriginalScale == getCurrentScale()) ? mDblTapScale : mOriginalScale;
                zoomAnimated(e, scale);
            }
            return true;
        }
    }

    private void zoomAnimated(MotionEvent e, float scale) {
        post(new ZoomAnimation(e.getX(), e.getY(), scale));
    }

    @Override
    public void computeScroll() {
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            int curX = mScroller.getCurrX();
            int curY = mScroller.getCurrY();

            mMatrix.getValues(m);
            m[Matrix.MTRANS_X] = curX;
            m[Matrix.MTRANS_Y] = curY;
            mMatrix.setValues(m);
            setImageMatrix(mMatrix);
            ViewCompat.postInvalidateOnAnimation(this);
        }
        super.computeScroll();
    }

    private float getCurrentScale() {
        mMatrix.getValues(m);
        return m[Matrix.MSCALE_X];
    }

    private Point computeCurrentImageSize() {
        final Point size = new Point();
        Drawable d = getDrawable();
        if (d != null) {
            mMatrix.getValues(m);

            float scale = m[Matrix.MSCALE_X];
            float width = d.getIntrinsicWidth() * scale;
            float height = d.getIntrinsicHeight() * scale;

            size.set((int)width, (int)height);

            return size;
        }

        size.set(0, 0);
        return size;
    }

    private Point computeCurrentOffset() {
        final Point offset = new Point();

        mMatrix.getValues(m);
        float transX = m[Matrix.MTRANS_X];
        float transY = m[Matrix.MTRANS_Y];

        offset.set((int)transX, (int)transY);

        return offset;
    }

    @Override
    public void setImageMatrix(Matrix matrix) {
        super.setImageMatrix(fixMatrix(matrix));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            postInvalidate();
        }
    }

    private Matrix fixMatrix(Matrix matrix) {
        if (getDrawable() == null)
            return matrix;
        // While animating home or locking home for pager peek, apply the matrix as-is.
        if (mSnappingHome || mSkipFixMatrix)
            return matrix;

        matrix.getValues(m);

        Point imageSize = computeCurrentImageSize();

        int imageWidth = imageSize.x;
        int imageHeight = imageSize.y;
        int viewW = getWidth();
        int viewH = getHeight();
        float overX = viewW * OVERSCROLL;
        float overY = viewH * OVERSCROLL;

        // Allow free pan even when the page fits the view, plus soft overscroll past edges.
        float minX = viewW - imageWidth - overX;
        float maxX = overX;
        float minY = viewH - imageHeight - overY;
        float maxY = overY;

        if (imageWidth <= viewW) {
            // Center bias but still allow dragging past the page boundary.
            float center = (viewW - imageWidth) / 2f;
            minX = center - overX;
            maxX = center + overX;
        }
        if (imageHeight <= viewH) {
            float center = (viewH - imageHeight) / 2f;
            minY = center - overY;
            maxY = center + overY;
        }

        m[Matrix.MTRANS_X] = Math.min(maxX, Math.max(m[Matrix.MTRANS_X], minX));
        m[Matrix.MTRANS_Y] = Math.min(maxY, Math.max(m[Matrix.MTRANS_Y], minY));

        matrix.setValues(m);
        return matrix;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (getDrawable() == null)
            return false;

        float imageWidth = computeCurrentImageSize().x;
        float offsetX = computeCurrentOffset().x;
        int viewW = getWidth();
        float overX = viewW * OVERSCROLL;
        float minX = viewW - imageWidth - overX;
        float maxX = overX;
        if (imageWidth <= viewW) {
            float center = (viewW - imageWidth) / 2f;
            minX = center - overX;
            maxX = center + overX;
        }

        if (direction < 0) {
            return offsetX < maxX - 1f;
        } else {
            return offsetX > minX + 1f;
        }
    }

    @Override
    public boolean canScrollVertically(int direction) {
        if (getDrawable() == null)
            return false;

        float imageHeight = computeCurrentImageSize().y;
        float offsetY = computeCurrentOffset().y;
        int viewH = getHeight();
        float overY = viewH * OVERSCROLL;
        float minY = viewH - imageHeight - overY;
        float maxY = overY;
        if (imageHeight <= viewH) {
            float center = (viewH - imageHeight) / 2f;
            minY = center - overY;
            maxY = center + overY;
        }

        if (direction < 0) {
            return offsetY < maxY - 1f;
        } else {
            return offsetY > minY + 1f;
        }
    }

    private class ZoomAnimation implements Runnable {
        public final static int ZOOM_DURATION = 200;
        float mX;
        float mY;
        float mScale;
        Interpolator mInterpolator;
        float mStartScale;
        long mStartTime;

        ZoomAnimation(float x, float y, float scale) {
            mMatrix.getValues(m);
            mX = x;
            mY = y;
            mScale = scale;

            mInterpolator = new AccelerateDecelerateInterpolator();
            mStartScale = getCurrentScale();
            mStartTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            float t = (float)(System.currentTimeMillis() - mStartTime) / ZOOM_DURATION;
            float interpolateRatio = mInterpolator.getInterpolation(t);
            t = (t > 1f) ? 1f : t;

            mMatrix.getValues(m);
            float newScale = mStartScale + interpolateRatio * (mScale - mStartScale);
            float newScaleFactor = newScale / m[Matrix.MSCALE_X];

            mMatrix.postScale(newScaleFactor, newScaleFactor, mX, mY);
            setImageMatrix(mMatrix);

            if (t < 1f) {
                post(this);
            }
            else {
                // set exact scale
                mMatrix.getValues(m);
                mMatrix.setScale(mScale, mScale);
                mMatrix.postTranslate(m[Matrix.MTRANS_X], m[Matrix.MTRANS_Y]);
                setImageMatrix(mMatrix);
                if (Math.abs(mScale - mOriginalScale) < 0.01f) {
                    mHomeMatrix.set(mMatrix);
                }
            }
        }
    }

    private class SnapHomeAnimation implements Runnable {
        private final Interpolator mInterpolator = new AccelerateDecelerateInterpolator();
        private final long mStartTime = System.currentTimeMillis();
        private final float[] mStart = new float[9];
        private final float[] mEnd = new float[9];
        private final float[] mCur = new float[9];

        SnapHomeAnimation() {
            mMatrix.getValues(mStart);
            mHomeMatrix.getValues(mEnd);
            // If home was never captured, rebuild fitted matrix.
            if (mEnd[Matrix.MSCALE_X] == 0f) {
                mSkipScaling = false;
                scale();
                mHomeMatrix.getValues(mEnd);
            }
        }

        @Override
        public void run() {
            float t = (float) (System.currentTimeMillis() - mStartTime) / SNAP_DURATION_MS;
            float ratio = mInterpolator.getInterpolation(Math.min(t, 1f));
            for (int i = 0; i < 9; i++) {
                mCur[i] = mStart[i] + (mEnd[i] - mStart[i]) * ratio;
            }
            mMatrix.setValues(mCur);
            setImageMatrix(mMatrix);
            if (t < 1f) {
                post(this);
            } else {
                mMatrix.set(mHomeMatrix);
                setImageMatrix(mMatrix);
                mSnappingHome = false;
                mUserPanned = false;
            }
        }
    }
}