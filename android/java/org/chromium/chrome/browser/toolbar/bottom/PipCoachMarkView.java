/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.chromium.base.Log;

/**
 * Full-screen coach mark overlay used to introduce the YouTube PiP toolbar button.
 *
 * The overlay dims the entire screen except for a circular spotlight punched out
 * around the target button, giving the classic "app walkthrough" focus effect.
 * Tapping anywhere dismisses it.
 */
public class PipCoachMarkView extends FrameLayout {
    private static final String TAG = "PipCoachMark";

    // Overlay background colour — 85 % opaque black
    private static final int OVERLAY_COLOR = 0xD9000000;
    // Gold colour matching the button tint
    private static final int GOLD_COLOR = 0xFFD4AF37;
    // Extra padding around the spotlight circle
    private static final float SPOTLIGHT_PADDING_DP = 14f;
    // Ring stroke width
    private static final float RING_STROKE_DP = 2.5f;

    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClearPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mCx, mCy, mRadius;
    private float mAnimatedRadius;

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

        root.addView(this, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        addLabel(root);
        animateIn();
    }

    /** Animates the spotlight radius from 0 → target, giving a "zoom in" feel. */
    private void animateIn() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, mRadius);
        anim.setDuration(350);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            mAnimatedRadius = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    /** Adds the explanatory label positioned above or below the spotlight circle. */
    private void addLabel(ViewGroup root) {
        TextView label = new TextView(getContext());
        label.setText("Tap here to watch in Picture-in-Picture");
        label.setTextColor(Color.WHITE);
        label.setTextSize(14f);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dpToPxi(16), dpToPxi(10), dpToPxi(16), dpToPxi(10));

        // Place label above the spotlight if the button is in the lower half of the screen,
        // otherwise place it below.
        int screenHeight = root.getResources().getDisplayMetrics().heightPixels;
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        float absoluteCy = mCy + rootLoc[1];

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        if (absoluteCy > screenHeight * 0.5f) {
            // Button is in lower half — label goes above the circle
            int topMargin = (int) (mCy - mRadius - dpToPx(56));
            lp.topMargin = Math.max(topMargin, dpToPxi(24));
            lp.gravity = Gravity.TOP;
        } else {
            // Button is in upper half — label goes below the circle
            int topMargin = (int) (mCy + mRadius + dpToPx(12));
            lp.topMargin = topMargin;
            lp.gravity = Gravity.TOP;
        }

        addView(label, lp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1. Full dark overlay
        canvas.drawRect(0, 0, getWidth(), getHeight(), mOverlayPaint);
        // 2. Punch transparent hole (uses animated radius so the circle grows in)
        canvas.drawCircle(mCx, mCy, mAnimatedRadius, mClearPaint);
        // 3. Gold ring around the spotlight edge
        canvas.drawCircle(mCx, mCy, mAnimatedRadius, mRingPaint);
    }

    private void dismiss() {
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
