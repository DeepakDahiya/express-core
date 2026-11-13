/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * The bottom bar's comments-icon wiggle (Express AI handoff, nodes 283:12322 and 283:12326).
 * Every two seconds the clapper tilts and pulses while its play mark nudges right and dims. The
 * movement uses the design's keyframes and spring curves and lasts the first 860ms of each
 * cycle. Nothing ticks between wiggles.
 */
final class CommentsBarIconMotion implements View.OnAttachStateChangeListener {
    private static final long CYCLE_MS = 2000;
    // Every track is at rest from 43% of the cycle on.
    private static final float ACTIVE_FRACTION = 0.43f;

    private static final Interpolator LINEAR = new LinearInterpolator();
    // The design's two springs as its CSS linear() samples: BOUNCY carries each move out, GENTLE
    // settles it back.
    private static final Interpolator BOUNCY =
            new SampledInterpolator(
                    new float[] {
                        0f, 0.03f, 0.1077f, 0.2165f, 0.342f, 0.4728f, 0.6001f, 0.7175f, 0.8208f,
                        0.9077f, 0.9776f, 1.0307f, 1.0684f, 1.0925f, 1.1051f, 1.1084f, 1.1047f,
                        1.096f, 1.084f, 1.0703f, 1.0561f, 1.0424f, 1.0297f, 1.0187f, 1.0094f,
                        1.0019f, 0.9963f, 0.9923f, 0.9898f, 0.9885f, 0.9882f, 0.9887f, 0.9897f,
                        0.991f, 0.9925f, 0.994f, 0.9955f, 0.9969f, 0.9981f, 0.9991f, 0.9998f,
                        1.0004f, 1.0009f, 1.0011f, 1.0012f, 1.0013f, 1.0012f, 1.0011f, 1.001f,
                        1.0008f, 1.0006f
                    });
    private static final Interpolator GENTLE =
            new SampledInterpolator(
                    new float[] {
                        0f, 0.0188f, 0.0679f, 0.1374f, 0.2195f, 0.308f, 0.3978f, 0.4856f, 0.5686f,
                        0.6452f, 0.7142f, 0.7753f, 0.8283f, 0.8735f, 0.9113f, 0.9423f, 0.9671f,
                        0.9866f, 1.0014f, 1.0123f, 1.0198f, 1.0247f, 1.0274f, 1.0283f, 1.0281f,
                        1.0268f, 1.025f, 1.0227f, 1.0202f, 1.0177f, 1.0152f, 1.0128f, 1.0106f,
                        1.0085f, 1.0068f, 1.0052f, 1.0039f, 1.0028f, 1.0018f, 1.0011f, 1.0005f, 1f,
                        0.9997f, 0.9995f, 0.9993f, 0.9992f, 0.9992f, 0.9992f, 0.9992f, 0.9993f,
                        0.9993f
                    });

    // The clapper, about its centre: degrees and scale.
    private static final Track ROTATION =
            new Track(
                    new float[] {0f, 0.12f, 0.21f, 0.31f, ACTIVE_FRACTION},
                    new float[] {
                        0f,
                        (float) Math.toDegrees(0.038),
                        (float) Math.toDegrees(-0.019),
                        (float) Math.toDegrees(0.021),
                        0f
                    },
                    BOUNCY, GENTLE, BOUNCY, GENTLE);
    private static final Track SCALE =
            new Track(
                    new float[] {0f, 0.12f, 0.21f, 0.31f, ACTIVE_FRACTION},
                    new float[] {1f, 1.07f, 0.985f, 1.045f, 1f},
                    BOUNCY, GENTLE, BOUNCY, GENTLE);
    // The play mark: opacity, and a nudge right in dp.
    private static final Track PLAY_ALPHA =
            new Track(
                    new float[] {0f, 0.12f, 0.17f, 0.24f, 0.33f, 0.41f},
                    new float[] {1f, 1f, 0.72f, 1f, 0.82f, 1f},
                    LINEAR, BOUNCY, GENTLE, BOUNCY, GENTLE);
    private static final Track PLAY_SHIFT =
            new Track(
                    new float[] {0f, 0.09f, 0.16f, 0.24f, 0.29f, 0.36f, ACTIVE_FRACTION},
                    new float[] {0f, 0f, 1.5f, 0f, 0f, 1f, 0f},
                    LINEAR, BOUNCY, GENTLE, LINEAR, BOUNCY, GENTLE);

    private final View mIcon;
    private final View mPlay;
    private final float mDensity;
    private final Runnable mNextWiggle = this::wiggle;
    private @Nullable ValueAnimator mWiggle;
    private boolean mEnabled;

    /**
     * @param icon The clapper layer, rotated and scaled about its centre.
     * @param play The play mark inside it.
     */
    CommentsBarIconMotion(View icon, View play) {
        mIcon = icon;
        mPlay = play;
        mDensity = icon.getResources().getDisplayMetrics().density;
        icon.addOnAttachStateChangeListener(this);
    }

    /** Starts or stops the wiggle; the bar runs it while the page has comments. */
    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (enabled) {
            if (mIcon.isAttachedToWindow()) wiggle();
        } else {
            stop();
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mEnabled) wiggle();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        stop();
    }

    private void wiggle() {
        mIcon.removeCallbacks(mNextWiggle);
        if (!mEnabled || !mIcon.isAttachedToWindow()) return;
        mIcon.postDelayed(mNextWiggle, CYCLE_MS);
        // Keep the beat but skip the movement while the bar is hidden, including while it
        // scrolls as a compositor bitmap that each frame would have to recapture.
        if (!mIcon.isShown() || !ValueAnimator.areAnimatorsEnabled()) return;
        cancelWiggle();
        ValueAnimator wiggle = ValueAnimator.ofFloat(0f, ACTIVE_FRACTION);
        wiggle.setDuration((long) (CYCLE_MS * ACTIVE_FRACTION));
        wiggle.setInterpolator(LINEAR);
        wiggle.addUpdateListener(animator -> apply((float) animator.getAnimatedValue()));
        mWiggle = wiggle;
        wiggle.start();
    }

    private void stop() {
        mIcon.removeCallbacks(mNextWiggle);
        cancelWiggle();
        apply(ACTIVE_FRACTION);
    }

    private void cancelWiggle() {
        if (mWiggle == null) return;
        mWiggle.cancel();
        mWiggle = null;
    }

    /** Poses both layers at {@code t}, a fraction of the two-second cycle. */
    private void apply(float t) {
        float scale = SCALE.at(t);
        mIcon.setRotation(ROTATION.at(t));
        mIcon.setScaleX(scale);
        mIcon.setScaleY(scale);
        mPlay.setAlpha(PLAY_ALPHA.at(t));
        mPlay.setTranslationX(PLAY_SHIFT.at(t) * mDensity);
    }

    /** Keyframes over the cycle, each eased into the next like a CSS animation-timing-function. */
    private static final class Track {
        private final float[] mAt;
        private final float[] mValues;
        private final Interpolator[] mEasings;

        Track(float[] at, float[] values, Interpolator... easings) {
            assert at.length == values.length && easings.length == at.length - 1;
            mAt = at;
            mValues = values;
            mEasings = easings;
        }

        float at(float t) {
            for (int i = 0; i < mEasings.length; i++) {
                if (t < mAt[i + 1]) {
                    float progress = Math.max(0f, (t - mAt[i]) / (mAt[i + 1] - mAt[i]));
                    float eased = mEasings[i].getInterpolation(progress);
                    return mValues[i] + (mValues[i + 1] - mValues[i]) * eased;
                }
            }
            return mValues[mValues.length - 1];
        }
    }

    /** A CSS linear() easing: evenly spaced samples joined by straight lines. */
    private static final class SampledInterpolator implements Interpolator {
        private final float[] mSamples;

        SampledInterpolator(float[] samples) {
            mSamples = samples;
        }

        @Override
        public float getInterpolation(float input) {
            if (input <= 0f) return mSamples[0];
            if (input >= 1f) return mSamples[mSamples.length - 1];
            float position = input * (mSamples.length - 1);
            int i = (int) position;
            return mSamples[i] + (mSamples[i + 1] - mSamples[i]) * (position - i);
        }
    }
}
