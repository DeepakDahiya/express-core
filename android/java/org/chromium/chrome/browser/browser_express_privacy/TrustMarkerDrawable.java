/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import org.chromium.chrome.R;

/**
 * The URL bar's trust marker (Figma "Trust Marker" 161:1379): a 24dp shield that says whether
 * the page is secure, not secure or dangerous. A drawable rather than a view so it can stand in
 * for the security icon inside Chromium's status view.
 *
 * <p>While a page loads it plays the design's scan — trackers, ads, cookies, then fingerprinting,
 * each swept by a scan line — and loops it until the load settles. Then the result plays once: a
 * tick, cross or clock pops, and the shield lands; a secure shield gets one shine, a dangerous one
 * shakes, a not-secure one pulses. The timings are the design's 6.5s timeline, split at the
 * point where the result begins. With animations turned off in system settings it shows the
 * resting shield only.
 */
public class TrustMarkerDrawable extends Drawable {
    /** The design's whole timeline, in milliseconds. */
    private static final float TIMELINE_MS = 6500f;
    /** Where the result phase starts on that timeline (the tick popping in). */
    private static final float RESULT_START = 0.56923f;
    /** Where the scan phase ends: the fingerprint glyph has gone. */
    private static final float SCAN_END = 0.61538f;
    private static final int SIZE_DP = 24;

    private final Context mContext;
    private final int mSize;
    private final Drawable[] mScanGlyphs;
    private final Drawable mScanFrame;
    private final Drawable mShimmer;
    private Drawable mResult;
    private Drawable mShield;

    private @SiteTrust.Status int mStatus = SiteTrust.Status.WARNING;
    private @Nullable ValueAnimator mAnimator;
    private boolean mScanning;
    /** Progress on the design's timeline, 0..1; 1 is the resting shield. */
    private float mTime = 1f;
    private int mAlpha = 255;

    public TrustMarkerDrawable(Context context) {
        mContext = context;
        mSize = Math.round(SIZE_DP * context.getResources().getDisplayMetrics().density);
        mScanGlyphs = new Drawable[] {
            drawable(R.drawable.ic_browser_express_trust_scan_tracker),
            drawable(R.drawable.ic_browser_express_trust_scan_ad),
            drawable(R.drawable.ic_browser_express_trust_scan_cookie),
            drawable(R.drawable.ic_browser_express_trust_scan_fingerprint),
        };
        mScanFrame = drawable(R.drawable.ic_browser_express_trust_scan_frame);
        mShimmer = drawable(R.drawable.ic_browser_express_trust_marker_shimmer);
        applyStatusDrawables();
    }

    private Drawable drawable(int id) {
        Drawable drawable = AppCompatResources.getDrawable(mContext, id);
        assert drawable != null;
        return drawable.mutate();
    }

    public @SiteTrust.Status int getStatus() {
        return mStatus;
    }

    public boolean isScanning() {
        return mScanning;
    }

    /** The shield at rest for {@code status}, with no animation. */
    public void setStatus(@SiteTrust.Status int status) {
        stopAnimation();
        if (mStatus != status) {
            mStatus = status;
            applyStatusDrawables();
        }
        mTime = 1f;
        invalidateSelf();
    }

    /**
     * Records the status of a page that is still loading, without ending the scan; the result
     * plays when the load settles.
     */
    public void setPendingStatus(@SiteTrust.Status int status) {
        if (mStatus == status) return;
        mStatus = status;
        applyStatusDrawables();
        if (!mScanning) {
            mTime = 1f;
            invalidateSelf();
        }
    }

    /** Starts (or keeps) the scan loop: a page is loading. */
    public void startScan() {
        if (mScanning || !animationsEnabled()) return;
        stopAnimation();
        mScanning = true;
        mAnimator = ValueAnimator.ofFloat(0f, SCAN_END);
        mAnimator.setDuration(Math.round(TIMELINE_MS * SCAN_END));
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.addUpdateListener(animation -> {
            mTime = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        mAnimator.start();
        if (!isVisible()) mAnimator.pause();
    }

    /** The load settled on {@code status}: plays the result once, ending on the shield. */
    public void showResult(@SiteTrust.Status int status) {
        if (mStatus != status) {
            mStatus = status;
            applyStatusDrawables();
        }
        stopAnimation();
        if (!animationsEnabled()) {
            mTime = 1f;
            invalidateSelf();
            return;
        }
        mAnimator = ValueAnimator.ofFloat(RESULT_START, 1f);
        mAnimator.setDuration(Math.round(TIMELINE_MS * (1f - RESULT_START)));
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(animation -> {
            mTime = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
        mAnimator.start();
        if (!isVisible()) mAnimator.pause();
    }

    private void stopAnimation() {
        mScanning = false;
        if (mAnimator != null) {
            mAnimator.removeAllUpdateListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    private boolean animationsEnabled() {
        return Settings.Global.getFloat(mContext.getContentResolver(),
                       Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                != 0f;
    }

    private void applyStatusDrawables() {
        switch (mStatus) {
            case SiteTrust.Status.SECURE:
                mResult = drawable(R.drawable.ic_browser_express_trust_result_secure);
                mShield = drawable(R.drawable.ic_browser_express_trust_marker_secure);
                break;
            case SiteTrust.Status.DANGEROUS:
                mResult = drawable(R.drawable.ic_browser_express_trust_result_dangerous);
                mShield = drawable(R.drawable.ic_browser_express_trust_marker_dangerous);
                break;
            default:
                mResult = drawable(R.drawable.ic_browser_express_trust_result_warning);
                mShield = drawable(R.drawable.ic_browser_express_trust_marker_warning);
                break;
        }
    }

    /** Hidden (the URL bar focused, the toolbar scrolled away): the animation waits. */
    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (mAnimator != null) {
            if (visible) {
                mAnimator.resume();
            } else {
                mAnimator.pause();
            }
        }
        return changed;
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha == alpha) return;
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The marker's colours carry its meaning; a tint would erase green, amber and red.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int size = Math.min(bounds.width(), bounds.height());
        if (size <= 0) return;
        canvas.save();
        canvas.translate(bounds.left + (bounds.width() - size) / 2f,
                bounds.top + (bounds.height() - size) / 2f);
        canvas.clipRect(0, 0, size, size);
        float unit = size / 24f;
        float t = mTime * 100f;

        // The four scan glyphs, one after another, each swept by the scan line.
        for (int i = 0; i < mScanGlyphs.length; i++) {
            float start = 15.385f * i;
            float alpha = glyphAlpha(t, start);
            if (alpha > 0f) {
                float scale = glyphScale(t, start);
                drawLayer(canvas, mScanGlyphs[i], size, alpha, scale, 0f, 0f);
            }
            float scanStart = 2.308f + 15.385f * i;
            float scanAlpha = scanAlpha(t, scanStart);
            if (scanAlpha > 0f) {
                float y = lerp(-9.6f, 9.6f,
                        easeInOut(progress(t, scanStart, scanStart + 8.461f)));
                drawLayer(canvas, mScanFrame, size, scanAlpha, 1f, 0f, y * unit);
            }
        }

        // The result glyph pops, then the shield lands.
        float resultAlpha = resultAlpha(t);
        if (resultAlpha > 0f) {
            drawLayer(canvas, mResult, size, resultAlpha, resultScale(t), 0f, 0f);
        }
        float shieldAlpha = shieldAlpha(t);
        if (shieldAlpha > 0f) {
            float shake = mStatus == SiteTrust.Status.DANGEROUS ? shakeX(t) * unit : 0f;
            drawLayer(canvas, mShield, size, shieldAlpha, shieldScale(t), shake, 0f);
        }
        if (mStatus == SiteTrust.Status.SECURE) {
            float shineAlpha = shimmerAlpha(t);
            if (shineAlpha > 0f) {
                float x = lerp(-24f, 24f, easeInOut(progress(t, 80f, 95.385f)));
                drawLayer(canvas, mShimmer, size, shineAlpha, 1f, x * unit, 0f);
            }
        }
        canvas.restore();
    }

    private void drawLayer(Canvas canvas, Drawable drawable, int size, float alpha, float scale,
            float dx, float dy) {
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale, size / 2f, size / 2f);
        drawable.setBounds(0, 0, size, size);
        float clamped = Math.max(0f, Math.min(1f, alpha));
        drawable.setAlpha(Math.round(clamped * mAlpha));
        drawable.draw(canvas);
        canvas.restore();
    }

    // --- The design's keyframe tracks, in percent of the 6.5s timeline. ------------------------

    private static float glyphAlpha(float t, float start) {
        float fadeIn = start + 0.769f;
        float holdEnd = start + 13.077f;
        float fadeOut = start + 15.385f;
        if (t < start || t > fadeOut) return 0f;
        if (t < fadeIn) return easeInOut(progress(t, start, fadeIn));
        if (t < holdEnd) return 1f;
        return 1f - easeInOut(progress(t, holdEnd, fadeOut));
    }

    private static float glyphScale(float t, float start) {
        float grown = start + 1.846f;
        float shrinkStart = start + 13.846f;
        float shrunk = start + 15.385f;
        if (t < grown) return lerp(0.6f, 1f, overshoot(progress(t, start, grown)));
        if (t < shrinkStart) return 1f;
        return lerp(1f, 0.6f, easeIn(progress(t, shrinkStart, shrunk)));
    }

    private static float scanAlpha(float t, float start) {
        float in = start + 0.769f;
        float holdEnd = start + 7.692f;
        float out = start + 8.461f;
        if (t < start || t > out) return 0f;
        if (t < in) return 0.8f * progress(t, start, in);
        if (t < holdEnd) return 0.8f;
        return 0.8f * (1f - easeOut(progress(t, holdEnd, out)));
    }

    private static float resultAlpha(float t) {
        if (t < 56.923f || t > 69.231f) return 0f;
        if (t < 58.462f) return easeOut(progress(t, 56.923f, 58.462f));
        if (t < 66.154f) return 1f;
        return 1f - easeIn(progress(t, 66.154f, 69.231f));
    }

    private static float resultScale(float t) {
        if (t < 60f) return lerp(0.5f, 1.1f, overshoot(progress(t, 56.923f, 60f)));
        return lerp(1.1f, 1f, easeOut(progress(t, 60f, 63.077f)));
    }

    private float shieldAlpha(float t) {
        if (t < 69.231f) return 0f;
        if (mStatus == SiteTrust.Status.WARNING) {
            // Two pulses before settling.
            if (t < 72.308f) return easeIn(progress(t, 69.231f, 72.308f));
            if (t < 76.923f) return lerp(1f, 0.3f, easeOut(progress(t, 72.308f, 76.923f)));
            if (t < 80f) return lerp(0.3f, 1f, easeIn(progress(t, 76.923f, 80f)));
            if (t < 84.615f) return lerp(1f, 0.3f, easeOut(progress(t, 80f, 84.615f)));
            if (t < 87.692f) return lerp(0.3f, 1f, progress(t, 84.615f, 87.692f));
            return 1f;
        }
        if (t < 73.846f) return easeOut(progress(t, 69.231f, 73.846f));
        return 1f;
    }

    private static float shieldScale(float t) {
        if (t < 69.231f) return 0.3f;
        return lerp(0.3f, 1f, overshoot(progress(t, 69.231f, 76.923f)));
    }

    private static float shakeX(float t) {
        float[] times = {78.462f, 80f, 81.538f, 83.077f, 84.615f, 86.154f, 87.692f, 90f};
        float[] values = {0f, -3f, 3f, -3f, 3f, -2f, 2f, 0f};
        if (t <= times[0] || t >= times[times.length - 1]) return 0f;
        for (int i = 1; i < times.length; i++) {
            if (t < times[i]) {
                return lerp(values[i - 1], values[i], progress(t, times[i - 1], times[i]));
            }
        }
        return 0f;
    }

    private static float shimmerAlpha(float t) {
        if (t < 80f || t > 95.385f) return 0f;
        if (t < 83.077f) return 0.9f * easeIn(progress(t, 80f, 83.077f));
        if (t < 89.231f) return 0.9f;
        return 0.9f * (1f - easeOut(progress(t, 89.231f, 95.385f)));
    }

    // --- Easing. ---------------------------------------------------------------------------------

    private static float progress(float t, float from, float to) {
        if (to <= from) return 1f;
        return Math.max(0f, Math.min(1f, (t - from) / (to - from)));
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private static float easeIn(float x) {
        return x * x;
    }

    private static float easeOut(float x) {
        return 1f - (1f - x) * (1f - x);
    }

    private static float easeInOut(float x) {
        return x < 0.5f ? 2f * x * x : 1f - (float) Math.pow(-2f * x + 2f, 2) / 2f;
    }

    /** The design's cubic-bezier(0.34, 1.56, 0.64, 1): a slight overshoot, back-out style. */
    private static float overshoot(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1f, 3) + c1 * (float) Math.pow(x - 1f, 2);
    }
}
