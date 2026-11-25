/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

/**
 * A hero card's edge (README §2, steps 1 and 3): a 1dp wasabi outline whose bright arc travels
 * around it, a conic gradient turning once every 3.6s. The XML children are the card's content
 * and sit 1dp inside; they bring their own background. With reduced motion the arc stands still.
 *
 * <p>The arc is a large square behind the content that only rotates (a transform), clipped to
 * the rounded card. {@link #configure} can add the hero tile's glow around it, drawn in a margin
 * the layout gives back with negative margins.
 */
public class HeroOutlineFrame extends FrameLayout {
    private static final long TURN_MS = 3600;
    private static final float BASE_ALPHA = 0.15f;

    private final FrameLayout mClip;
    private final View mSpinner;
    private float mRadius;
    private @Nullable ObjectAnimator mTurn;

    public HeroOutlineFrame(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        int wasabi = ContextCompat.getColor(context, R.color.browser_express_walkthrough_wasabi);

        mClip = new FrameLayout(context);
        mClip.setClipToOutline(true);
        mClip.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
                    }
                });
        int border = Math.round(WalkthroughMotion.dp(context, 1f));
        mClip.setPadding(border, border, border, border);
        mSpinner = new ArcView(context, wasabi);
        mClip.addView(mSpinner, new LayoutParams(0, 0, android.view.Gravity.CENTER));
        setCornerRadius(WalkthroughMotion.dp(context, 14f), wasabi);
    }

    private void setCornerRadius(float radius, int wasabi) {
        mRadius = radius;
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius);
        base.setColor(Color.argb(Math.round(255 * BASE_ALPHA), Color.red(wasabi),
                Color.green(wasabi), Color.blue(wasabi)));
        mClip.setBackground(base);
        mClip.invalidateOutline();
    }

    /**
     * @param radiusDp the card's corner radius.
     * @param glowDp the blur of a glow around it (0 for none); the layout must leave that much
     *     room on every side, as negative margins.
     */
    void configure(float radiusDp, float glowDp) {
        Context context = getContext();
        int wasabi = ContextCompat.getColor(context, R.color.browser_express_walkthrough_wasabi);
        setCornerRadius(WalkthroughMotion.dp(context, radiusDp), wasabi);
        if (glowDp <= 0f) {
            setBackground(null);
            setPadding(0, 0, 0, 0);
            return;
        }
        int pad = Math.round(WalkthroughMotion.dp(context, glowDp));
        int glow = Color.argb(Math.round(255 * WalkthroughCardFrame.GLOW_ALPHA),
                Color.red(wasabi), Color.green(wasabi), Color.blue(wasabi));
        setBackground(new WalkthroughShapes.ShadowDrawable(new int[] {glow},
                new float[] {WalkthroughMotion.dp(context, glowDp)}, new float[] {0f},
                new Rect(pad, pad, pad, pad), mRadius));
        setPadding(pad, pad, pad, pad);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // The content declared in XML moves inside the clip, over the arc.
        while (getChildCount() > 0) {
            View child = getChildAt(0);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            removeViewAt(0);
            mClip.addView(child, new LayoutParams(params.width, params.height));
        }
        addView(mClip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Big enough that the turning square always covers the card's corners.
        int side = (int) Math.ceil(Math.hypot(w, h)) + 2;
        ViewGroup.LayoutParams params = mSpinner.getLayoutParams();
        if (params.width != side) {
            params.width = side;
            params.height = side;
            mSpinner.setLayoutParams(params);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (WalkthroughMotion.reducedMotion()) return;
        mTurn = ObjectAnimator.ofFloat(mSpinner, View.ROTATION, 0f, 360f);
        mTurn.setDuration(TURN_MS);
        mTurn.setInterpolator(new LinearInterpolator());
        mTurn.setRepeatCount(ValueAnimator.INFINITE);
        mTurn.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mTurn != null) mTurn.cancel();
        mTurn = null;
        super.onDetachedFromWindow();
    }

    /** conic-gradient(transparent 0°, wasabi 50°, transparent 110°, transparent 360°). */
    private static final class ArcView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mWasabi;

        ArcView(Context context, int wasabi) {
            super(context);
            mWasabi = wasabi;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            int clear = mWasabi & 0x00FFFFFF;
            mPaint.setShader(new SweepGradient(w / 2f, h / 2f,
                    new int[] {clear, mWasabi, clear, clear},
                    new float[] {0f, 50f / 360f, 110f / 360f, 1f}));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }
    }
}
