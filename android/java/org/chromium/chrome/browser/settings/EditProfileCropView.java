/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

/**
 * Edit profile's "Move and scale" stage (design handoff "Edit profile" §4): the photo fills a
 * square as wide as the view, under a mask with a round hole 300/402 of the square across. Drag
 * moves the photo and pinch zooms it, 1× (covering the square) to 2×; the photo always covers the
 * hole. {@link #crop} returns what the hole shows.
 */
public class EditProfileCropView extends View {
    public static final float MIN_ZOOM = 1f;
    public static final float MAX_ZOOM = 2f;
    private static final float HOLE_SHARE = 300f / 402f;
    private static final float EDGE_DP = 1f;

    /** Told when a pinch changes the zoom, so the slider can follow. */
    public interface OnZoomChangedListener {
        void onZoomChanged(float zoom);
    }

    private final Paint mPhotoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mMask = new Path();
    private final RectF mSquare = new RectF();
    private final RectF mHole = new RectF();
    private final Matrix mMatrix = new Matrix();
    private final ScaleGestureDetector mPinch;

    private @Nullable Bitmap mPhoto;
    private @Nullable OnZoomChangedListener mListener;
    private float mZoom = MIN_ZOOM;
    /** Where the photo's centre sits, from the hole's centre, in view pixels. */
    private float mOffsetX;
    private float mOffsetY;
    private int mActivePointer = MotionEvent.INVALID_POINTER_ID;
    private float mLastX;
    private float mLastY;

    public EditProfileCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mMaskPaint.setColor(
                ContextCompat.getColor(context, R.color.browser_express_edit_profile_crop_mask));
        mEdgePaint.setStyle(Paint.Style.STROKE);
        mEdgePaint.setStrokeWidth(EDGE_DP * getResources().getDisplayMetrics().density);
        mEdgePaint.setColor(ContextCompat.getColor(
                context, R.color.browser_express_edit_profile_crop_mask_edge));
        mMask.setFillType(Path.FillType.EVEN_ODD);
        mPinch = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        setZoom(mZoom * detector.getScaleFactor());
                        if (mListener != null) mListener.onZoomChanged(mZoom);
                        return true;
                    }
                });
    }

    public void setPhoto(Bitmap photo) {
        mPhoto = photo;
        mZoom = MIN_ZOOM;
        mOffsetX = 0;
        mOffsetY = 0;
        invalidate();
    }

    public void setOnZoomChangedListener(@Nullable OnZoomChangedListener listener) {
        mListener = listener;
    }

    public float getZoom() {
        return mZoom;
    }

    /** Zooms about the hole's centre, keeping the point under it where it is. */
    public void setZoom(float zoom) {
        float clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        if (clamped == mZoom) return;
        mOffsetX *= clamped / mZoom;
        mOffsetY *= clamped / mZoom;
        mZoom = clamped;
        clampOffset();
        invalidate();
    }

    /**
     * What the hole shows, as a square bitmap at most {@code maxSize} across (smaller when the
     * photo has fewer pixels there); null before a photo is set. JPEG has no transparency, so
     * anything uncovered would be white, but the photo always covers the hole.
     */
    public @Nullable Bitmap crop(int maxSize) {
        if (mPhoto == null || mHole.isEmpty()) return null;
        updateMatrix();
        float photoScale = displayScale();
        int size = Math.max(1, Math.min(maxSize, Math.round(mHole.width() / photoScale)));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Matrix matrix = new Matrix(mMatrix);
        matrix.postTranslate(-mHole.left, -mHole.top);
        matrix.postScale(size / mHole.width(), size / mHole.height());
        canvas.drawBitmap(mPhoto, matrix, mPhotoPaint);
        return out;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float side = Math.min(w, h);
        mSquare.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
        float hole = side * HOLE_SHARE;
        mHole.set(mSquare.centerX() - hole / 2f, mSquare.centerY() - hole / 2f,
                mSquare.centerX() + hole / 2f, mSquare.centerY() + hole / 2f);
        mMask.reset();
        mMask.addRect(0, 0, w, h, Path.Direction.CW);
        mMask.addOval(mHole, Path.Direction.CW);
        clampOffset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPhoto != null) {
            updateMatrix();
            canvas.save();
            canvas.clipRect(mSquare);
            canvas.drawBitmap(mPhoto, mMatrix, mPhotoPaint);
            canvas.restore();
        }
        canvas.drawPath(mMask, mMaskPaint);
        // A 1px border inside the hole's edge, as the design's border-box circle has it.
        float inset = mEdgePaint.getStrokeWidth() / 2f;
        canvas.drawOval(mHole.left + inset, mHole.top + inset, mHole.right - inset,
                mHole.bottom - inset, mEdgePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mPhoto == null) return false;
        mPinch.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                startDrag(event, 0);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(mActivePointer);
                if (index < 0) return true;
                float x = event.getX(index);
                float y = event.getY(index);
                if (!mPinch.isInProgress()) {
                    mOffsetX += x - mLastX;
                    mOffsetY += y - mLastY;
                    clampOffset();
                    invalidate();
                }
                mLastX = x;
                mLastY = y;
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // The finger that was dragging lifted: carry on with one still down.
                int lifted = event.getActionIndex();
                if (event.getPointerId(lifted) == mActivePointer) {
                    startDrag(event, lifted == 0 ? 1 : 0);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                performClick();
                // Fall through.
            case MotionEvent.ACTION_CANCEL:
                mActivePointer = MotionEvent.INVALID_POINTER_ID;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void startDrag(MotionEvent event, int index) {
        mActivePointer = event.getPointerId(index);
        mLastX = event.getX(index);
        mLastY = event.getY(index);
    }

    /** The photo's pixels per bitmap pixel: covering the square at 1×. */
    private float displayScale() {
        if (mPhoto == null || mSquare.isEmpty()) return 1f;
        float cover = Math.max(mSquare.width() / mPhoto.getWidth(),
                mSquare.height() / mPhoto.getHeight());
        return cover * mZoom;
    }

    private void updateMatrix() {
        if (mPhoto == null) return;
        float scale = displayScale();
        mMatrix.setScale(scale, scale);
        mMatrix.postTranslate(mHole.centerX() + mOffsetX - mPhoto.getWidth() * scale / 2f,
                mHole.centerY() + mOffsetY - mPhoto.getHeight() * scale / 2f);
    }

    /** Keeps the hole covered: the photo's edges may not come inside it. */
    private void clampOffset() {
        if (mPhoto == null) return;
        float scale = displayScale();
        float slackX = Math.max(0f, (mPhoto.getWidth() * scale - mHole.width()) / 2f);
        float slackY = Math.max(0f, (mPhoto.getHeight() * scale - mHole.height()) / 2f);
        mOffsetX = Math.max(-slackX, Math.min(slackX, mOffsetX));
        mOffsetY = Math.max(-slackY, Math.min(slackY, mOffsetY));
    }
}
