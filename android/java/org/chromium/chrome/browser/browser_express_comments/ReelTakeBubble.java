/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

/**
 * The reel page's "Add your take" pill (design handoffs "Add your take" §1A and "Karma earned"):
 * a 36dp pill holding a plus in a white circle. Every reel lands with the circle. On every third
 * reel the pill then opens to 146dp with its label, once the stream position under the hashtag
 * has faded (ReelStreamProgress spans the title column up to the folded circle, so the two must
 * not show at once), and folds back to the circle 1.2s later. Both moves take 600ms with the
 * right edge in place. With animations off it stays folded.
 *
 * <p>Nothing here lays out per frame. The slot is always the full pill plus a 4dp margin that
 * keeps the folded pill's target at 44dp; the pill is drawn at the current width from the right,
 * the circle, the label and the tap target follow its left edge, and the slot's clip bounds hide
 * whatever lies outside it.
 */
final class ReelTakeBubble {
    private static final long FOLD_MS = 600;
    /** How long the open pill shows its label before folding. */
    private static final long FOLD_DELAY_MS = 1200;
    /** From the page settling to the pill opening: the stream position has gone by then. */
    private static final long OPEN_DELAY_MS = ReelStreamProgress.Sequence.SHOWING_MS + 100;
    private static final float MARGIN_DP = 4f;
    private static final float PILL_HEIGHT_DP = 36f;
    private static final float PILL_WIDTH_DP = 146f;
    private static final float PADDING_DP = 3f;
    private static final float CIRCLE_DP = 30f;
    private static final float LABEL_GAP_DP = 9f;
    private static final Interpolator EASE_IN_OUT =
            new PathInterpolator(0.645f, 0.045f, 0.355f, 1f);

    private final View mSlot;
    private final View mCircle;
    private final View mLabel;
    private final View mHit;
    private final PillDrawable mPill;
    private final float mDensity;
    private final int mSlotWidthPx;
    private final int mSlotHeightPx;
    private final Rect mClip = new Rect();
    private final Runnable mOpen = this::open;
    private final Runnable mFold = () -> animateTo(0f);
    private @Nullable ValueAnimator mAnimator;
    /** 0 is the folded circle, 1 the pill with its label. */
    private float mProgress;
    /** This page still owes the reader its label: a third reel whose pill hasn't opened yet. */
    private boolean mLabelOwed;

    ReelTakeBubble(View slot) {
        mSlot = slot;
        mCircle = slot.findViewById(R.id.reel_take_plus);
        mLabel = slot.findViewById(R.id.reel_take_label);
        mHit = slot.findViewById(R.id.reel_take_hit);
        Context context = slot.getContext();
        mDensity = context.getResources().getDisplayMetrics().density;
        // The slot's size is fixed in the layout, so it is known before the first layout pass.
        ViewGroup.LayoutParams params = slot.getLayoutParams();
        mSlotWidthPx = params.width;
        mSlotHeightPx = params.height;
        mPill = new PillDrawable(
                ContextCompat.getColor(context, R.color.browser_express_reel_take_fill),
                ContextCompat.getColor(context, R.color.browser_express_reel_take_stroke),
                mDensity, MARGIN_DP * mDensity, PILL_HEIGHT_DP * mDensity);
        slot.findViewById(R.id.reel_take_outline).setBackground(mPill);
        slot.setTag(R.id.reel_take_bubble, this);
        apply(0f);
    }

    /** The bubble bound to {@code slot}, the page's reel_take_bubble; null if there is none. */
    static @Nullable ReelTakeBubble from(@Nullable View slot) {
        if (slot == null) return null;
        Object bubble = slot.getTag(R.id.reel_take_bubble);
        return bubble instanceof ReelTakeBubble ? (ReelTakeBubble) bubble : null;
    }

    void setOnClickListener(View.OnClickListener listener) {
        mHit.setOnClickListener(listener);
    }

    void setContentDescription(CharSequence label) {
        mHit.setContentDescription(label);
    }

    /**
     * Poses the bubble for a freshly bound page: folded, owing its label on every third reel.
     */
    void show(boolean withLabel) {
        cancel();
        mSlot.removeCallbacks(mOpen);
        mLabelOwed = withLabel && ValueAnimator.areAnimatorsEnabled();
        apply(0f);
    }

    /**
     * The page has settled, so its stream position is showing up to the folded circle. A pill
     * still open (back on a page before its fold) folds now; a page owing its label opens once
     * the stream position has gone.
     */
    void onSettled() {
        mSlot.removeCallbacks(mOpen);
        if (mProgress != 0f) {
            mSlot.removeCallbacks(mFold);
            animateTo(0f);
        }
        if (mLabelOwed) mSlot.postDelayed(mOpen, OPEN_DELAY_MS);
    }

    /**
     * The reader is dragging the page away: an opening still to come waits for the page to
     * settle again, so the pill never opens off-screen.
     */
    void holdOff() {
        mSlot.removeCallbacks(mOpen);
    }

    /**
     * Folds to the circle now, e.g. so something can come out from behind it. The label doesn't
     * show on this page after that.
     */
    void foldNow() {
        mLabelOwed = false;
        mSlot.removeCallbacks(mOpen);
        mSlot.removeCallbacks(mFold);
        animateTo(0f);
    }

    private void open() {
        mLabelOwed = false;
        animateTo(1f);
        mSlot.postDelayed(mFold, FOLD_MS + FOLD_DELAY_MS);
    }

    private void animateTo(float target) {
        if (mProgress == target) return;
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            apply(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mProgress, target);
        animator.setDuration(Math.round(FOLD_MS * Math.abs(target - mProgress)));
        animator.setInterpolator(EASE_IN_OUT);
        animator.addUpdateListener(animation -> apply((float) animation.getAnimatedValue()));
        animator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mAnimator == animation) mAnimator = null;
                    }
                });
        mAnimator = animator;
        animator.start();
    }

    private void cancel() {
        mSlot.removeCallbacks(mFold);
        if (mAnimator == null) return;
        ValueAnimator animator = mAnimator;
        mAnimator = null;
        animator.cancel();
    }

    /** Poses the bubble {@code progress} of the way from the circle (0) to the pill (1). */
    private void apply(float progress) {
        mProgress = progress;
        float margin = MARGIN_DP * mDensity;
        float width = (PILL_HEIGHT_DP + (PILL_WIDTH_DP - PILL_HEIGHT_DP) * progress) * mDensity;
        float right = mSlotWidthPx - margin;
        float left = right - width;
        mPill.setWidth(width);

        float circleLeft = left + PADDING_DP * mDensity;
        mCircle.setTranslationX(circleLeft);
        mLabel.setAlpha(progress);
        mLabel.setTranslationX(circleLeft + (CIRCLE_DP + LABEL_GAP_DP * progress) * mDensity);
        mHit.setTranslationX(left - margin);

        mClip.set(Math.round(left), 0, Math.round(right), mSlotHeightPx);
        mSlot.setClipBounds(mClip);
    }

    /** The pill's fill and inner outline, at the current width against the slot's right edge. */
    private static final class PillDrawable extends Drawable {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mMarginPx;
        private final float mHeightPx;
        private final RectF mRect = new RectF();
        private float mWidthPx;

        PillDrawable(int fillColor, int strokeColor, float strokeWidthPx, float marginPx,
                float heightPx) {
            mFill.setColor(fillColor);
            mStroke.setColor(strokeColor);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(strokeWidthPx);
            mMarginPx = marginPx;
            mHeightPx = heightPx;
        }

        void setWidth(float widthPx) {
            if (widthPx == mWidthPx) return;
            mWidthPx = widthPx;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float right = bounds.right - mMarginPx;
            float top = bounds.exactCenterY() - mHeightPx / 2f;
            mRect.set(right - mWidthPx, top, right, top + mHeightPx);
            float radius = mHeightPx / 2f;
            canvas.drawRoundRect(mRect, radius, radius, mFill);
            // The outline sits inside the pill, as the design's inset shadow does.
            float inset = mStroke.getStrokeWidth() / 2f;
            mRect.inset(inset, inset);
            canvas.drawRoundRect(mRect, radius - inset, radius - inset, mStroke);
        }

        @Override
        public void setAlpha(int alpha) {
            // The pill is always fully drawn; it only changes width.
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mFill.setColorFilter(colorFilter);
            mStroke.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
