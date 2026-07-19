package com.nkanaev.comics.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageView;

public class CoverImageView extends AppCompatImageView {
    // The standard American comic page size is 6.875 by 10.438 inches with bleed
    public static final double FACTOR = 6.875/10.438;

    public CoverImageView(Context context) {
        this(context,null);
    }

    public CoverImageView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = getMeasuredWidth();
        setMeasuredDimension(width, (int)(width * 1/ FACTOR) );
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        scale();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        scale();
    }

    private void scale () {
        Drawable drawable = getDrawable();
        if (drawable==null)
            return;

        Matrix matrix = getImageMatrix();
        matrix.reset();

        int vwidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int vheight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        int dwidth = drawable.getIntrinsicWidth();
        int dheight = drawable.getIntrinsicHeight();

        float scale;
        float dx = 0, dy = 0;

        // landscape format
        if (dwidth > dheight) {
            // fit to height, crop left side overflow assuming
            // it is a wraparound with actual cover on the right side
            scale = (float) vheight / (float) dheight;
            dx = (vwidth - dwidth * scale) * 1f;
        } else {
            // Portrait: center-crop to fill the comic tile so shorter book/PDF
            // pages do not leave a gray gap under the cover.
            float scaleW = (float) vwidth / (float) dwidth;
            float scaleH = (float) vheight / (float) dheight;
            scale = Math.max(scaleW, scaleH);
            dx = (vwidth - dwidth * scale) * 0.5f;
            dy = (vheight - dheight * scale) * 0.5f;
        }

        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));

        setImageMatrix(matrix);
    }
}
