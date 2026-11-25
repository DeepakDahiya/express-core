/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

/** The walkthrough's small drawing pieces, all built in code (README §2, variant 1a). */
final class WalkthroughShapes {
    private WalkthroughShapes() {}

    /**
     * The dimming: the whole view in the theme's dim colour except a rounded cut-out around the
     * target. Drawn once per hole; it fades with its own alpha.
     */
    static final class SpotlightView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mHole = new RectF();
        private float mHoleRadius;
        private boolean mHasHole;

        SpotlightView(Context context) {
            super(context);
            mPaint.setColor(ContextCompat.getColor(context, R.color.browser_express_walkthrough_dim));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** Cuts the target out of the dimming; null dims everything. */
        void setHole(@Nullable RectF hole, float radius) {
            mHasHole = hole != null;
            if (hole != null) mHole.set(hole);
            mHoleRadius = radius;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            mPath.reset();
            mPath.setFillType(Path.FillType.EVEN_ODD);
            mPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            if (mHasHole) mPath.addRoundRect(mHole, mHoleRadius, mHoleRadius, Path.Direction.CW);
            canvas.drawPath(mPath, mPaint);
        }
    }

    /**
     * The 1a highlight: a 2dp wasabi ring on the target's edge and a ripple growing out of it, 0
     * to 10dp while fading from .45 to 0, every 1.6s (ease-out). {@link #acknowledge} is the
     * single wider pulse that answers a tap. Laid out over the target with {@link #MAX_SPREAD_DP}
     * to spare on every side; only this small view redraws per frame.
     */
    static final class PulseRingView extends View {
        static final float MAX_SPREAD_DP = 16f;
        private static final float RING_DP = 2f;
        private static final float RIPPLE_DP = 10f;
        private static final float RIPPLE_ALPHA = 0.45f;
        private static final long RIPPLE_MS = 1600;
        private static final long ACK_MS = 500;

        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRipple = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mTarget = new RectF();
        private final RectF mOuter = new RectF();
        private final float mMargin;
        private final float mRingWidth;
        private float mRadius;
        private float mSpread;
        private float mRippleAlpha;
        private @Nullable ValueAnimator mAnimator;
        private long mStartDelayMs;

        PulseRingView(Context context) {
            super(context);
            int wasabi = ContextCompat.getColor(context, R.color.browser_express_walkthrough_wasabi);
            mMargin = WalkthroughMotion.dp(context, MAX_SPREAD_DP);
            mRingWidth = WalkthroughMotion.dp(context, RING_DP);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(mRingWidth);
            mRing.setColor(wasabi);
            mRipple.setColor(wasabi);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** Margin this view keeps around the target; the controller sizes it target + 2 × this. */
        float margin() {
            return mMargin;
        }

        void setCornerRadius(float radius) {
            mRadius = radius;
            invalidate();
        }

        /** The ripple's first cycle waits this long, e.g. to follow a fade-in. */
        void setStartDelay(long delayMs) {
            mStartDelayMs = delayMs;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startRipple();
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        private void startRipple() {
            stop();
            if (WalkthroughMotion.reducedMotion()) return; // A still ring.
            float rippleMax = WalkthroughMotion.dp(getContext(), RIPPLE_DP);
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(RIPPLE_MS);
            mAnimator.setStartDelay(mStartDelayMs);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.setInterpolator(WalkthroughMotion.CSS_EASE_OUT);
            mAnimator.addUpdateListener(
                    a -> {
                        float p = (float) a.getAnimatedValue();
                        mSpread = rippleMax * p;
                        mRippleAlpha = RIPPLE_ALPHA * (1f - p);
                        invalidate();
                    });
            mAnimator.start();
        }

        /** One wider pulse (0 to 16dp, .45 to 0) instead of the ripple: the tap was taken. */
        void acknowledge() {
            stop();
            if (WalkthroughMotion.reducedMotion()) return;
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(ACK_MS);
            mAnimator.setInterpolator(WalkthroughMotion.EASE_OUT);
            mAnimator.addUpdateListener(
                    a -> {
                        float p = (float) a.getAnimatedValue();
                        mSpread = mMargin * p;
                        mRippleAlpha = RIPPLE_ALPHA * (1f - p);
                        invalidate();
                    });
            mAnimator.start();
        }

        void stop() {
            if (mAnimator != null) mAnimator.cancel();
            mAnimator = null;
            mSpread = 0f;
            mRippleAlpha = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            mTarget.set(mMargin, mMargin, getWidth() - mMargin, getHeight() - mMargin);
            if (mSpread > 0f && mRippleAlpha > 0f) {
                // CSS box-shadow 0 0 0 <spread>: a band of that width hugging the target, its
                // corners rounded by the target's radius plus the spread.
                mOuter.set(mTarget);
                mOuter.inset(-mSpread, -mSpread);
                mPath.reset();
                mPath.setFillType(Path.FillType.EVEN_ODD);
                mPath.addRoundRect(mOuter, mRadius + mSpread, mRadius + mSpread,
                        Path.Direction.CW);
                mPath.addRoundRect(mTarget, mRadius, mRadius, Path.Direction.CW);
                mRipple.setAlpha(Math.round(255 * mRippleAlpha));
                canvas.drawPath(mPath, mRipple);
            }
            // CSS box-sizing border-box: the 2px border sits inside the target's edge.
            float half = mRingWidth / 2f;
            mTarget.inset(half, half);
            float radius = Math.max(0f, mRadius - half);
            canvas.drawRoundRect(mTarget, radius, radius, mRing);
        }
    }

    /**
     * The wasabi light crossing a target once: a 120dp band leaning 18° that slides from just off
     * its left edge to past its right, clipped to the target's rounded shape. Only the band's
     * translation and the view's alpha change.
     */
    static final class SweepView extends FrameLayout {
        private static final float BAND_DP = 120f;
        private static final float SKEW = (float) Math.tan(Math.toRadians(-18));

        private final View mBand;
        private float mRadius;

        SweepView(Context context, float bandAlpha) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setAlpha(0f);
            setClipToOutline(true);
            setOutlineProvider(
                    new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
                        }
                    });
            int wasabi = ContextCompat.getColor(context, R.color.browser_express_walkthrough_wasabi);
            int peak = Color.argb(Math.round(255 * bandAlpha), Color.red(wasabi),
                    Color.green(wasabi), Color.blue(wasabi));
            mBand = new BandView(context, peak);
            addView(mBand, new LayoutParams(Math.round(WalkthroughMotion.dp(context, BAND_DP)),
                    LayoutParams.MATCH_PARENT));
            mBand.setTranslationX(-WalkthroughMotion.dp(context, BAND_DP));
        }

        void setCornerRadius(float radius) {
            mRadius = radius;
            invalidateOutline();
        }

        /** Runs the sweep between {@code startMs} and {@code endMs} from now. */
        void play(long startMs, long endMs) {
            if (WalkthroughMotion.reducedMotion()) return;
            float band = WalkthroughMotion.dp(getContext(), BAND_DP);
            long duration = endMs - startMs;
            mBand.setTranslationX(-band);
            mBand.animate()
                    .translationX(getWidth() + band * 0.2f)
                    .setStartDelay(startMs)
                    .setDuration(duration)
                    .setInterpolator(WalkthroughMotion.EASE_IN_OUT);
            // In over the first 100ms, out over the last 150ms, as in the reference.
            animate().alpha(1f).setStartDelay(startMs).setDuration(100)
                    .withEndAction(() -> animate().alpha(0f)
                            .setStartDelay(Math.max(0, duration - 250)).setDuration(150));
        }

        void stop() {
            mBand.animate().cancel();
            animate().cancel();
            setAlpha(0f);
        }

        private static final class BandView extends View {
            private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final int mPeak;

            BandView(Context context, int peak) {
                super(context);
                mPeak = peak;
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                int clear = mPeak & 0x00FFFFFF;
                mPaint.setShader(new LinearGradient(0, 0, w, 0,
                        new int[] {clear, mPeak, clear}, null, Shader.TileMode.CLAMP));
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                canvas.save();
                canvas.translate(cx, cy);
                canvas.skew(SKEW, 0f);
                canvas.translate(-cx, -cy);
                // Tall enough that the leaning band still covers the target top to bottom.
                canvas.drawRect(0, -getHeight(), getWidth(), getHeight() * 2f, mPaint);
                canvas.restore();
            }
        }
    }

    /**
     * A soft white touch point: the 44dp dot of steps 2 and 3 (55% white) or the 36dp ghost
     * finger of step 4 (70% white), each with a 1.5dp white ring and a shadow.
     */
    static View newTouchDot(Context context, float sizeDp, float fillAlpha) {
        View dot = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.argb(Math.round(255 * fillAlpha), 255, 255, 255));
        shape.setStroke(Math.round(WalkthroughMotion.dp(context, 1.5f)),
                Color.argb(Math.round(255 * 0.9f), 255, 255, 255));
        dot.setBackground(shape);
        dot.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        dot.setElevation(WalkthroughMotion.dp(context, 6f));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(WalkthroughMotion.dp(context, sizeDp));
        dot.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        return dot;
    }

    /** The ghost finger's trail: 4dp wide, fading from 55% white at the top to nothing. */
    static View newFingerTrail(Context context, float heightDp) {
        View trail = new View(context);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {Color.argb(0, 255, 255, 255), Color.argb(140, 255, 255, 255)});
        shape.setCornerRadius(WalkthroughMotion.dp(context, 2f));
        trail.setBackground(shape);
        trail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        trail.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(WalkthroughMotion.dp(context, 4f)),
                Math.round(WalkthroughMotion.dp(context, heightDp))));
        return trail;
    }

    /**
     * Soft shadows and glows under a rounded card (CSS box-shadow with a blur): each layer is a
     * blurred copy of the card's shape in one colour, offset down by its dy. The card sits inset by
     * the drawable's padding. Rendered once per size into a half-resolution bitmap, since a blur
     * has no fine detail to lose and BlurMaskFilter needs a software canvas.
     */
    static final class ShadowDrawable extends Drawable {
        private static final float SCALE = 0.5f;

        private final int[] mColors;
        private final float[] mBlurs;
        private final float[] mOffsets;
        private final Rect mPadding;
        private final float mRadius;
        private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private @Nullable Bitmap mBitmap;
        private int mBitmapW;
        private int mBitmapH;

        /**
         * @param colors one colour per layer.
         * @param blurs each layer's CSS blur radius, px.
         * @param offsets each layer's CSS y offset, px.
         * @param padding where the card sits inside the drawable's bounds.
         */
        ShadowDrawable(int[] colors, float[] blurs, float[] offsets, Rect padding, float radius) {
            mColors = colors;
            mBlurs = blurs;
            mOffsets = offsets;
            mPadding = padding;
            mRadius = radius;
        }

        @Override
        public boolean getPadding(@NonNull Rect padding) {
            padding.set(mPadding);
            return true;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            if (mBitmap == null || mBitmapW != bounds.width() || mBitmapH != bounds.height()) {
                render(bounds.width(), bounds.height());
            }
            if (mBitmap != null) canvas.drawBitmap(mBitmap, null, bounds, mBitmapPaint);
        }

        private void render(int width, int height) {
            if (mBitmap != null) mBitmap.recycle();
            mBitmapW = width;
            mBitmapH = height;
            int w = Math.max(1, Math.round(width * SCALE));
            int h = Math.max(1, Math.round(height * SCALE));
            mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mBitmap);
            canvas.scale(SCALE, SCALE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF card = new RectF(mPadding.left, mPadding.top, width - mPadding.right,
                    height - mPadding.bottom);
            for (int i = 0; i < mColors.length; i++) {
                // A CSS blur of b px is a Gaussian of sigma b / 2; BlurMaskFilter takes a radius
                // (in the canvas's own units, so the half-size bitmap needs no correction) that
                // Skia turns into sigma as 0.57735 × radius + 0.5.
                float sigma = mBlurs[i] / 2f;
                float radius = Math.max(0.5f, (sigma - 0.5f) / 0.57735f);
                paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
                paint.setColor(mColors[i]);
                canvas.save();
                canvas.translate(0, mOffsets[i]);
                canvas.drawRoundRect(card, mRadius, mRadius, paint);
                canvas.restore();
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mBitmapPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mBitmapPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
