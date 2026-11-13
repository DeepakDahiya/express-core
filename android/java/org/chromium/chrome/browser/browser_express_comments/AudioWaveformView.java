/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.chromium.chrome.R;

/**
 * The bar strip on a voice-note comment.
 *
 * <p>The shape is synthetic rather than a decode of the audio: bars are generated from a seed, so
 * a given comment always draws the same pattern no matter how often its row is recycled, and no
 * peaks data has to be produced or stored. Bars behind the playhead are drawn lit, the rest dim.
 *
 * <p>Also used live while recording, where {@link #pushAmplitude} feeds it real microphone levels
 * and the strip scrolls instead.
 */
public class AudioWaveformView extends View {
    private static final int DEFAULT_BAR_COUNT = 36;
    private static final float MIN_BAR = 0.12f;
    private static final int DIM_ALPHA = 90;

    /** Colours for a strip drawn over video, which stays dark whatever the theme. */
    public static final int ON_MEDIA_LIT_COLOR = Color.WHITE;
    public static final int ON_MEDIA_DIM_COLOR = Color.argb(DIM_ALPHA, 255, 255, 255);

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBarRect = new RectF();

    private float[] mBars = generateBars(0, DEFAULT_BAR_COUNT);
    private float mProgress;
    private int mLitColor;
    private int mDimColor;
    private boolean mLiveMode;
    private int mLiveCount;

    public AudioWaveformView(Context context) {
        this(context, null);
    }

    public AudioWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.FILL);
        int barColor = context.getColor(R.color.browser_express_icon_tint);
        mLitColor = barColor;
        mDimColor = Color.argb(
                DIM_ALPHA, Color.red(barColor), Color.green(barColor), Color.blue(barColor));
    }

    /**
     * Generates the bar pattern for a comment.
     *
     * <p>Deterministic in {@code seed}: the same comment gets the same strip every time, which is
     * what stops the waveform reshuffling as the row scrolls in and out of the pager.
     *
     * @return heights in the range [{@value #MIN_BAR}, 1].
     */
    static float[] generateBars(int seed, int count) {
        if (count <= 0) return new float[0];
        float[] bars = new float[count];
        // Plain LCG rather than java.util.Random: no allocation, and identical output everywhere.
        int state = seed * 2 + 1;
        for (int i = 0; i < count; i++) {
            state = state * 1103515245 + 12345;
            float noise = ((state >>> 16) & 0x7fff) / 32767f;
            // A slow swell over the strip keeps it from looking like uniform static.
            float envelope = (float) (0.55 + 0.45 * Math.sin(i * 0.45));
            float height = MIN_BAR + (1f - MIN_BAR) * noise * envelope;
            bars[i] = Math.max(MIN_BAR, Math.min(1f, height));
        }
        return bars;
    }

    /** Number of bars drawn lit for the given progress. */
    static int litBarCount(float progress, int barCount) {
        if (barCount <= 0) return 0;
        float clamped = Math.max(0f, Math.min(1f, progress));
        return Math.round(clamped * barCount);
    }

    /** Seeds the pattern from a comment id so it is stable across rebinds. */
    public void setSeed(String commentId) {
        mLiveMode = false;
        mBars = generateBars(commentId == null ? 0 : commentId.hashCode(), DEFAULT_BAR_COUNT);
        invalidate();
    }

    public void setColors(int litColor, int dimColor) {
        mLitColor = litColor;
        mDimColor = dimColor;
        invalidate();
    }

    /** @param progress 0..1 through the clip. */
    public void setProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (clamped == mProgress) return;
        mProgress = clamped;
        invalidate();
    }

    /** Switches to the live recording strip: every bar lit, newest on the right. */
    public void startLiveMode() {
        mLiveMode = true;
        mLiveCount = 0;
        mBars = new float[DEFAULT_BAR_COUNT];
        java.util.Arrays.fill(mBars, MIN_BAR);
        mProgress = 1f;
        invalidate();
    }

    /**
     * Appends one microphone reading while recording, scrolling the strip left.
     *
     * @param amplitude 0..1, normalised from {@code MediaRecorder.getMaxAmplitude()}.
     */
    public void pushAmplitude(float amplitude) {
        if (!mLiveMode) startLiveMode();
        float clamped = Math.max(MIN_BAR, Math.min(1f, amplitude));
        System.arraycopy(mBars, 1, mBars, 0, mBars.length - 1);
        mBars[mBars.length - 1] = clamped;
        if (mLiveCount < mBars.length) mLiveCount++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = mBars.length;
        if (count == 0) return;

        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;

        float slot = width / (float) count;
        float barWidth = Math.max(1f, slot * 0.55f);
        float radius = barWidth / 2f;
        float centerY = getPaddingTop() + height / 2f;
        int lit = mLiveMode ? count : litBarCount(mProgress, count);

        for (int i = 0; i < count; i++) {
            float barHeight = Math.max(barWidth, mBars[i] * height);
            float left = getPaddingLeft() + i * slot + (slot - barWidth) / 2f;
            mBarRect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f);
            mPaint.setColor(i < lit ? mLitColor : mDimColor);
            canvas.drawRoundRect(mBarRect, radius, radius, mPaint);
        }
    }
}
