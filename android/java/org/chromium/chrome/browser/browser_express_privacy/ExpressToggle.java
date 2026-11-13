/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.CompoundButton;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.chromium.chrome.R;

/**
 * The design's 50x30 preference toggle (Figma "Trust/Preference toggle"): a pill track, accent
 * when on, with a 26dp white knob that slides across in 160ms. Drawn directly because
 * SwitchCompat sizes its track from twice the knob width, which cannot reach the design's
 * proportions. Announced to accessibility services as a switch.
 */
public class ExpressToggle extends CompoundButton {
    private static final long ANIMATION_MS = 160;
    private static final float KNOB_INSET_DP = 2f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrack = new RectF();
    private final int mOnColor;
    private final int mOffColor;
    private final float mInset;

    /** 0 = off, 1 = on; animated between the two. */
    private float mPosition;
    private ValueAnimator mAnimator;

    public ExpressToggle(Context context) {
        this(context, null);
    }

    public ExpressToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mOnColor = ContextCompat.getColor(context, R.color.browser_express_privacy_toggle_on);
        mOffColor = ContextCompat.getColor(context, R.color.browser_express_privacy_toggle_off);
        mKnobPaint.setColor(0xFFFFFFFF);
        mInset = KNOB_INSET_DP * getResources().getDisplayMetrics().density;
        setBackground(null);
        setButtonDrawable(null);
        setClickable(true);
        setFocusable(true);
        mPosition = isChecked() ? 1f : 0f;
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!changed) return;
        float target = checked ? 1f : 0f;
        if (mAnimator != null) mAnimator.cancel();
        if (!isLaidOut() || !isAttachedToWindow()) {
            mPosition = target;
            invalidate();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mPosition, target);
        mAnimator.setDuration(ANIMATION_MS);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(animation -> {
            mPosition = (float) animation.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    /** Sets the state without the slide, e.g. when first binding or restoring a failed change. */
    public void setCheckedImmediately(boolean checked) {
        if (mAnimator != null) mAnimator.cancel();
        super.setChecked(checked);
        mPosition = checked ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float radius = height / 2f;
        mTrack.set(0, 0, width, height);
        mTrackPaint.setColor(ColorUtils.blendARGB(mOffColor, mOnColor, mPosition));
        if (!isEnabled()) mTrackPaint.setAlpha(110);
        canvas.drawRoundRect(mTrack, radius, radius, mTrackPaint);
        float knobRadius = radius - mInset;
        float start = radius;
        float end = width - radius;
        canvas.drawCircle(start + (end - start) * mPosition, radius, knobRadius, mKnobPaint);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Switch.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(isChecked());
    }
}
