/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A free-form crop selector over a single bitmap: the image is letterboxed into the view, the
 * area outside the selection is dimmed, and the selection is adjusted by dragging its corners or
 * edges, or moved by dragging its inside. The view never mutates the bitmap; callers read the
 * selection back through {@link #getCropRectInBitmap()} and do the cutting themselves.
 */
public class ImageCropView extends View {
    /** Touch slop around a corner or edge, and the smallest selection the user can shrink to. */
    private static final float HANDLE_SLOP_DP = 24f;
    private static final float MIN_CROP_DP = 48f;
    /** Room kept around the image so the corner handles stay visible at full selection. */
    private static final float CONTENT_PADDING_DP = 12f;
    private static final float CORNER_LENGTH_DP = 20f;
    private static final float CORNER_THICKNESS_DP = 3f;
    /** Below this difference from the full image, the selection counts as "no crop". */
    private static final float IDENTITY_EPSILON_PX = 2f;

    private static final int EDGE_LEFT = 1;
    private static final int EDGE_TOP = 1 << 1;
    private static final int EDGE_RIGHT = 1 << 2;
    private static final int EDGE_BOTTOM = 1 << 3;

    private final float mDensity;
    private final float mHandleSlop;
    private final float mMinCrop;
    private final float mContentPadding;

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mScrimPaint = new Paint();
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private Bitmap mBitmap;
    private final RectF mDisplayRect = new RectF();
    private final RectF mCropRect = new RectF();

    /** Bitmask of grabbed edges while resizing; 0 with mMoving set means a whole-rect drag. */
    private int mActiveEdges;
    private boolean mMoving;
    private float mLastTouchX;
    private float mLastTouchY;

    public ImageCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mHandleSlop = HANDLE_SLOP_DP * mDensity;
        mMinCrop = MIN_CROP_DP * mDensity;
        mContentPadding = CONTENT_PADDING_DP * mDensity;

        mScrimPaint.setColor(0x99000000);

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setColor(Color.WHITE);
        mBorderPaint.setStrokeWidth(1.5f * mDensity);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setColor(0x66FFFFFF);
        mGridPaint.setStrokeWidth(mDensity);

        mCornerPaint.setStyle(Paint.Style.STROKE);
        mCornerPaint.setColor(Color.WHITE);
        mCornerPaint.setStrokeWidth(CORNER_THICKNESS_DP * mDensity);
    }

    /** Replaces the displayed bitmap and resets the selection to the full image. */
    public void setBitmap(@Nullable Bitmap bitmap) {
        mBitmap = bitmap;
        computeDisplayRect();
        invalidate();
    }

    /** Restores the selection to the full image. */
    public void resetSelection() {
        mCropRect.set(mDisplayRect);
        invalidate();
    }

    /** Whether the user has shrunk the selection away from the full image. */
    public boolean hasSelection() {
        return Math.abs(mCropRect.left - mDisplayRect.left) > IDENTITY_EPSILON_PX
                || Math.abs(mCropRect.top - mDisplayRect.top) > IDENTITY_EPSILON_PX
                || Math.abs(mCropRect.right - mDisplayRect.right) > IDENTITY_EPSILON_PX
                || Math.abs(mCropRect.bottom - mDisplayRect.bottom) > IDENTITY_EPSILON_PX;
    }

    /** The selection in bitmap pixel coordinates, clamped to the bitmap's bounds. */
    @Nullable
    public Rect getCropRectInBitmap() {
        if (mBitmap == null || mDisplayRect.width() <= 0) return null;
        float scale = mBitmap.getWidth() / mDisplayRect.width();
        int left = Math.round((mCropRect.left - mDisplayRect.left) * scale);
        int top = Math.round((mCropRect.top - mDisplayRect.top) * scale);
        int right = Math.round((mCropRect.right - mDisplayRect.left) * scale);
        int bottom = Math.round((mCropRect.bottom - mDisplayRect.top) * scale);

        left = Math.max(0, Math.min(left, mBitmap.getWidth() - 1));
        top = Math.max(0, Math.min(top, mBitmap.getHeight() - 1));
        right = Math.max(left + 1, Math.min(right, mBitmap.getWidth()));
        bottom = Math.max(top + 1, Math.min(bottom, mBitmap.getHeight()));
        return new Rect(left, top, right, bottom);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeDisplayRect();
    }

    private void computeDisplayRect() {
        if (mBitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float availableW = getWidth() - 2 * mContentPadding;
        float availableH = getHeight() - 2 * mContentPadding;
        if (availableW <= 0 || availableH <= 0) return;

        float scale = Math.min(availableW / mBitmap.getWidth(), availableH / mBitmap.getHeight());
        float displayW = mBitmap.getWidth() * scale;
        float displayH = mBitmap.getHeight() * scale;
        float left = (getWidth() - displayW) / 2f;
        float top = (getHeight() - displayH) / 2f;
        mDisplayRect.set(left, top, left + displayW, top + displayH);
        mCropRect.set(mDisplayRect);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mBitmap == null || mBitmap.isRecycled() || mDisplayRect.isEmpty()) return;

        canvas.drawBitmap(mBitmap, null, mDisplayRect, mBitmapPaint);

        // Dim everything outside the selection, but only within the image itself.
        canvas.drawRect(mDisplayRect.left, mDisplayRect.top, mDisplayRect.right, mCropRect.top,
                mScrimPaint);
        canvas.drawRect(mDisplayRect.left, mCropRect.bottom, mDisplayRect.right,
                mDisplayRect.bottom, mScrimPaint);
        canvas.drawRect(mDisplayRect.left, mCropRect.top, mCropRect.left, mCropRect.bottom,
                mScrimPaint);
        canvas.drawRect(mCropRect.right, mCropRect.top, mDisplayRect.right, mCropRect.bottom,
                mScrimPaint);

        // Rule-of-thirds grid inside the selection.
        float thirdW = mCropRect.width() / 3f;
        float thirdH = mCropRect.height() / 3f;
        canvas.drawLine(mCropRect.left + thirdW, mCropRect.top, mCropRect.left + thirdW,
                mCropRect.bottom, mGridPaint);
        canvas.drawLine(mCropRect.left + 2 * thirdW, mCropRect.top, mCropRect.left + 2 * thirdW,
                mCropRect.bottom, mGridPaint);
        canvas.drawLine(mCropRect.left, mCropRect.top + thirdH, mCropRect.right,
                mCropRect.top + thirdH, mGridPaint);
        canvas.drawLine(mCropRect.left, mCropRect.top + 2 * thirdH, mCropRect.right,
                mCropRect.top + 2 * thirdH, mGridPaint);

        canvas.drawRect(mCropRect, mBorderPaint);
        drawCornerHandles(canvas);
    }

    private void drawCornerHandles(Canvas canvas) {
        float len = CORNER_LENGTH_DP * mDensity;
        // Push the L-shapes half a stroke outward so they sit on the border, not inside it.
        float o = mCornerPaint.getStrokeWidth() / 2f;
        float l = mCropRect.left - o;
        float t = mCropRect.top - o;
        float r = mCropRect.right + o;
        float b = mCropRect.bottom + o;

        canvas.drawLine(l, t, l + len, t, mCornerPaint);
        canvas.drawLine(l, t, l, t + len, mCornerPaint);

        canvas.drawLine(r, t, r - len, t, mCornerPaint);
        canvas.drawLine(r, t, r, t + len, mCornerPaint);

        canvas.drawLine(l, b, l + len, b, mCornerPaint);
        canvas.drawLine(l, b, l, b - len, mCornerPaint);

        canvas.drawLine(r, b, r - len, b, mCornerPaint);
        canvas.drawLine(r, b, r, b - len, mCornerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBitmap == null || mDisplayRect.isEmpty()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                classifyTouch(event.getX(), event.getY());
                if (mActiveEdges != 0 || mMoving) {
                    mLastTouchX = event.getX();
                    mLastTouchY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (mActiveEdges == 0 && !mMoving) return false;
                float dx = event.getX() - mLastTouchX;
                float dy = event.getY() - mLastTouchY;
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                if (mMoving) {
                    moveSelection(dx, dy);
                } else {
                    resizeSelection(dx, dy);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                performClick();
                mActiveEdges = 0;
                mMoving = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                mActiveEdges = 0;
                mMoving = false;
                return true;

            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        // Adjusting the selection is a pure drag interaction; there is no click action to
        // perform, but the call keeps the gesture visible to accessibility services.
        return super.performClick();
    }

    private void classifyTouch(float x, float y) {
        mActiveEdges = 0;
        mMoving = false;

        boolean withinVerticalSpan =
                y >= mCropRect.top - mHandleSlop && y <= mCropRect.bottom + mHandleSlop;
        boolean withinHorizontalSpan =
                x >= mCropRect.left - mHandleSlop && x <= mCropRect.right + mHandleSlop;

        if (withinVerticalSpan) {
            float distLeft = Math.abs(x - mCropRect.left);
            float distRight = Math.abs(x - mCropRect.right);
            // On a very small selection both edges are within slop; grab only the nearer one.
            if (distLeft <= mHandleSlop && distLeft <= distRight) {
                mActiveEdges |= EDGE_LEFT;
            } else if (distRight <= mHandleSlop) {
                mActiveEdges |= EDGE_RIGHT;
            }
        }
        if (withinHorizontalSpan) {
            float distTop = Math.abs(y - mCropRect.top);
            float distBottom = Math.abs(y - mCropRect.bottom);
            if (distTop <= mHandleSlop && distTop <= distBottom) {
                mActiveEdges |= EDGE_TOP;
            } else if (distBottom <= mHandleSlop) {
                mActiveEdges |= EDGE_BOTTOM;
            }
        }

        if (mActiveEdges == 0 && mCropRect.contains(x, y)) {
            mMoving = true;
        }
    }

    private void resizeSelection(float dx, float dy) {
        // The image may be smaller than the minimum selection on one axis, in which case that
        // axis simply cannot shrink at all.
        float minW = Math.min(mMinCrop, mDisplayRect.width());
        float minH = Math.min(mMinCrop, mDisplayRect.height());

        if ((mActiveEdges & EDGE_LEFT) != 0) {
            mCropRect.left = clamp(mCropRect.left + dx, mDisplayRect.left, mCropRect.right - minW);
        }
        if ((mActiveEdges & EDGE_RIGHT) != 0) {
            mCropRect.right =
                    clamp(mCropRect.right + dx, mCropRect.left + minW, mDisplayRect.right);
        }
        if ((mActiveEdges & EDGE_TOP) != 0) {
            mCropRect.top = clamp(mCropRect.top + dy, mDisplayRect.top, mCropRect.bottom - minH);
        }
        if ((mActiveEdges & EDGE_BOTTOM) != 0) {
            mCropRect.bottom =
                    clamp(mCropRect.bottom + dy, mCropRect.top + minH, mDisplayRect.bottom);
        }
    }

    private void moveSelection(float dx, float dy) {
        dx = clamp(dx, mDisplayRect.left - mCropRect.left, mDisplayRect.right - mCropRect.right);
        dy = clamp(dy, mDisplayRect.top - mCropRect.top, mDisplayRect.bottom - mCropRect.bottom);
        mCropRect.offset(dx, dy);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
