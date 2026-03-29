/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.base.Log;

/**
 * Full-screen coach mark overlay used to introduce the YouTube PiP toolbar button.
 *
 * The overlay dims the entire screen except for a circular spotlight punched out
 * around the target button. A banner card positioned just above the toolbar explains
 * the feature. The spotlight ring pulses with a gold glow animation.
 * Tapping anywhere dismisses it.
 */
public class PipCoachMarkView extends FrameLayout {
    private static final String TAG = "PipCoachMark";

    // Overlay background colour — 60 % opaque black (background still visible through it)
    private static final int OVERLAY_COLOR = 0x99000000;
    // Gold colour matching the button tint
    private static final int GOLD_COLOR = 0xFFD4AF37;
    // Extra padding around the spotlight circle
    private static final float SPOTLIGHT_PADDING_DP = 14f;
    // Solid ring stroke width
    private static final float RING_STROKE_DP = 2.5f;
    // Glow ring — max extra radius beyond spotlight at peak of pulse
    private static final float GLOW_MAX_EXTRA_DP = 10f;
    // Glow ring stroke width
    private static final float GLOW_RING_STROKE_DP = 4f;

    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClearPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mCx, mCy, mRadius;
    private float mAnimatedRadius;
    private float mGlowFraction;

    private ValueAnimator mGlowAnimator;
    private Runnable mOnDismiss;

    public PipCoachMarkView(Context context) {
        super(context);

        // Hardware layer required so PorterDuff.CLEAR actually punches through.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);

        mOverlayPaint.setColor(OVERLAY_COLOR);
        mOverlayPaint.setStyle(Paint.Style.FILL);

        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mClearPaint.setStyle(Paint.Style.FILL);

        mRingPaint.setColor(GOLD_COLOR);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(dpToPx(RING_STROKE_DP));

        mGlowPaint.setColor(GOLD_COLOR);
        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeWidth(dpToPx(GLOW_RING_STROKE_DP));

        // Dismiss on any tap
        setOnClickListener(v -> dismiss());
    }

    /**
     * Positions the spotlight over {@code targetView} and attaches this overlay to the
     * nearest ancestor {@link FrameLayout} (typically the DecorView content root).
     *
     * @param targetView  The button to highlight.
     * @param onDismiss   Called after the overlay is removed.
     */
    public void show(View targetView, Runnable onDismiss) {
        mOnDismiss = onDismiss;

        // Resolve screen-space centre of the target button
        int[] loc = new int[2];
        targetView.getLocationOnScreen(loc);
        float targetCx = loc[0] + targetView.getWidth() / 2f;
        float targetCy = loc[1] + targetView.getHeight() / 2f;
        float baseRadius = Math.max(targetView.getWidth(), targetView.getHeight()) / 2f
                + dpToPx(SPOTLIGHT_PADDING_DP);

        // Find the root FrameLayout to attach to (DecorView child)
        ViewGroup root = findRootFrame(targetView);
        if (root == null) {
            Log.e(TAG, "Could not find a FrameLayout root to attach coach mark.");
            return;
        }

        // Adjust coordinates relative to the root view
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        mCx = targetCx - rootLoc[0];
        mCy = targetCy - rootLoc[1];
        mRadius = baseRadius;
        mAnimatedRadius = 0f;
        mGlowFraction = 0f;

        root.addView(this, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        addBanner(root);
        animateIn();
    }

    /** Animates the spotlight radius from 0 → target, then starts the glow pulse. */
    private void animateIn() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, mRadius);
        anim.setDuration(350);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            mAnimatedRadius = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startGlowAnimation();
            }
        });
        anim.start();
    }

    /** Continuously pulses the glow ring outward and fades it, creating a glowing effect. */
    private void startGlowAnimation() {
        mGlowAnimator = ValueAnimator.ofFloat(0f, 1f);
        mGlowAnimator.setDuration(900);
        mGlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mGlowAnimator.setInterpolator(new DecelerateInterpolator());
        mGlowAnimator.addUpdateListener(a -> {
            mGlowFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mGlowAnimator.start();
    }

    /**
     * Adds a banner card positioned just above the bottom toolbar spotlight.
     * The card contains the intro title and body text and sits on top of the
     * dim overlay so it appears fully visible.
     */
    private void addBanner(ViewGroup root) {
        FrameLayout card = new FrameLayout(getContext());

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1A2E); // Dark navy background
        bg.setCornerRadius(dpToPx(16f));
        bg.setStroke(dpToPxi(1), 0x55D4AF37); // Subtle gold border
        card.setBackground(bg);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dpToPxi(20), dpToPxi(16), dpToPxi(20), dpToPxi(16));

        TextView titleView = new TextView(getContext());
        titleView.setText(getContext().getString(
                org.chromium.chrome.R.string.pip_intro_banner_title));
        titleView.setTextColor(0xFFD4AF37); // Gold
        titleView.setTextSize(15f);
        titleView.setTypeface(null, Typeface.BOLD);

        TextView bodyView = new TextView(getContext());
        bodyView.setText(getContext().getString(
                org.chromium.chrome.R.string.pip_intro_banner_body));
        bodyView.setTextColor(Color.WHITE);
        bodyView.setTextSize(13f);
        bodyView.setLineSpacing(dpToPx(2f), 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dpToPxi(6);

        content.addView(titleView);
        content.addView(bodyView, bodyLp);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Position banner above the spotlight circle with 12dp gap, 16dp horizontal margins.
        // We anchor from the bottom so it always sits just above the toolbar spotlight.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dpToPxi(16);
        lp.rightMargin = dpToPxi(16);
        int rootHeight = root.getHeight();
        int bannerBottomY = (int) (mCy - mRadius - dpToPx(12));
        lp.bottomMargin = rootHeight - bannerBottomY;
        lp.gravity = Gravity.BOTTOM;

        addView(card, lp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1. Full dark overlay — dims everything (acts as the blur/dim effect)
        canvas.drawRect(0, 0, getWidth(), getHeight(), mOverlayPaint);
        // 2. Punch transparent hole so the PIP button is visible through the overlay
        canvas.drawCircle(mCx, mCy, mAnimatedRadius, mClearPaint);
        // 3. Pulsating glow ring: expands outward and fades to create a glow
        float glowRadius = mAnimatedRadius + dpToPx(GLOW_MAX_EXTRA_DP) * mGlowFraction;
        int glowAlpha = (int) (160 * (1f - mGlowFraction));
        mGlowPaint.setAlpha(glowAlpha);
        canvas.drawCircle(mCx, mCy, glowRadius, mGlowPaint);
        // 4. Solid gold ring around the spotlight edge
        canvas.drawCircle(mCx, mCy, mAnimatedRadius, mRingPaint);
    }

    private void dismiss() {
        if (mGlowAnimator != null) {
            mGlowAnimator.cancel();
            mGlowAnimator = null;
        }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        if (mOnDismiss != null) mOnDismiss.run();
    }

    // Walk up the view tree to find the first FrameLayout ancestor (usually DecorView's child)
    private static ViewGroup findRootFrame(View view) {
        View current = view.getRootView();
        if (current instanceof ViewGroup) {
            return findFirstFrameLayout((ViewGroup) current);
        }
        return null;
    }

    private static FrameLayout findFirstFrameLayout(ViewGroup group) {
        if (group instanceof FrameLayout) return (FrameLayout) group;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof FrameLayout) return (FrameLayout) child;
            if (child instanceof ViewGroup) {
                FrameLayout found = findFirstFrameLayout((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int dpToPxi(float dp) {
        return Math.round(dpToPx(dp));
    }
}
