/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

/**
 * The walkthrough's timings and curves (design_handoff_walkthrough tokens.json "motion"), and the
 * one-off fades they share. Repeating effects live with their views. Everything here animates
 * only alpha and transforms (README §7).
 *
 * <p>Steps 1, 3 and 4 come in sooner than the handoff's timeline (2026-11-19): readers swiped or
 * scrolled before a step was up. Each prompt now starts showing on its first frame and the rest
 * of its sequence follows at the handoff's pace. Since 2026-11-20 nothing waits before a step's
 * first frame: step 1 no longer waits for the feed, and step 4 shows as soon as its reel page is
 * laid out. Touches are held for each step's first {@link #STEP_TOUCH_LOCK_MS}.
 */
final class WalkthroughMotion {
    /** The reference's cubic ease-out, 1 - (1 - x)^3. */
    static final Interpolator EASE_OUT = new PathInterpolator(0.33f, 1f, 0.68f, 1f);

    /** The reference's cubic ease-in-out; tokens.json step2 "cubic-bezier(.65,0,.35,1)". */
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

    /** CSS "ease-out", the ring ripple's curve. */
    static final Interpolator CSS_EASE_OUT = new PathInterpolator(0f, 0f, 0.58f, 1f);

    /** CSS "ease-in-out", the swipe nudge's per-keyframe curve. */
    static final Interpolator CSS_EASE_IN_OUT = new PathInterpolator(0.42f, 0f, 0.58f, 1f);

    // Step 1 (from the step's start; the handoff's times less 300ms).
    static final long S1_SWEEP_START = 100;
    static final long S1_SWEEP_END = 1000;
    static final long S1_CARD_IN_START = 0;
    static final long S1_CARD_IN_MS = 350;
    static final long S1_TITLE_START = 300;
    static final long S1_SUB_START = 650;
    static final long S1_HERO_START = 900;
    static final long S1_BUTTONS_START = 1200;
    static final long S1_ACK_PULSE_MS = 450;
    static final long S1_DIM_OUT_MS = 250;

    // Step 2.
    static final long S2_SCROLL_START = 250;
    static final long S2_SCROLL_MS = 1500;
    /** Step 3 comes up as the scroll ends (the handoff paused 450ms more). */
    static final long S2_TOTAL_MS = S2_SCROLL_START + S2_SCROLL_MS;
    static final long S2_DOT_FADE_MS = 250;
    static final long S2_DOT_OUT_MS = 300;

    // Step 3 (the handoff's times less 250ms; the dim and ring were already at 0).
    static final long S3_SWEEP_START = 100;
    static final long S3_SWEEP_END = 1000;
    static final long S3_CARD_IN_START = 0;
    static final long S3_CARD_IN_MS = 350;
    static final long S3_TITLE_START = 300;
    static final long S3_SUB_START = 800;
    static final long S3_BUTTONS_START = 1000;
    static final long S3_DOT_IN_START = 0;
    static final long S3_TAP_DOT_MS = 1300;
    static final long S3_RING_IN_MS = 350;

    // Step 4. Its ring and label come up on the page's first laid-out frame. The handoff waited
    // for the video to play and then 2s more, long enough for a reader to swipe on first.
    static final long S4_LABEL_IN_MS = 350;
    /**
     * A swipe ends the walkthrough only once the label has been fully up a moment; an earlier one
     * means the reader didn't see it, and the step moves to the next vertical video. The touch
     * hold outlasts this, so only a swipe that gets around the hold can come that early.
     */
    static final long S4_MIN_SEEN_MS = S4_LABEL_IN_MS + 150;
    static final long S4_NUDGE_MS = 2600;
    static final float S4_LIFT_DP = 90f;
    static final float S4_OVERSHOOT_DP = 7f;
    static final float S4_FINGER_TRAVEL_DP = 110f;
    static final long S4_END_FADE_MS = 300;
    static final long S4_TOAST_DELAY = 550;
    static final long S4_TOAST_IN_MS = 300;
    static final long S4_TOAST_HOLD_MS = 2000;
    static final long S4_TOAST_OUT_MS = 400;

    // Bonus tip.
    static final long TIP_DELAY_AFTER_TAKE = 300;
    static final long TIP_MIN_AFTER_FINISH = 3300;
    static final long TIP_IN_MS = 350;
    static final float TIP_SLIDE_DP = 10f;
    static final long TIP_ROW_STAGGER = 150;
    static final long TIP_AUTO_DISMISS_MS = 7000;
    static final long TIP_OUT_MS = 300;

    // Shared.
    /**
     * For this long after a step (or the bonus tip) shows, taps and swipes go nowhere
     * (WalkthroughTouchLock), so the reader reads it rather than tapping or swiping past it.
     */
    static final long STEP_TOUCH_LOCK_MS = 2000;
    static final long FADE_MS = 350;
    static final long WORD_STAGGER = 90;
    static final long WORD_MS = 350;
    static final float RISE_DP = 8f;
    static final float CARD_START_SCALE = 0.96f;

    private WalkthroughMotion() {}

    /**
     * The system's "remove animations" (or a zero animator scale): repeating effects stand still
     * and text shows all at once (README §7).
     */
    static boolean reducedMotion() {
        return !ValueAnimator.areAnimatorsEnabled();
    }

    static float dp(Context context, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    /** Fades {@code view} in from nothing after {@code delayMs}, rising {@code risePx} as it goes. */
    static void fadeIn(View view, long delayMs, long durationMs, float risePx) {
        view.animate().cancel();
        if (reducedMotion()) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(risePx);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(durationMs)
                .setInterpolator(EASE_OUT)
                .withLayer();
    }

    /** Fades {@code view} out, then runs {@code then} (if any). */
    static void fadeOut(View view, long durationMs, @Nullable Runnable then) {
        view.animate().cancel();
        android.view.ViewPropertyAnimator animator =
                view.animate()
                        .alpha(0f)
                        .setStartDelay(0)
                        .setDuration(reducedMotion() ? 0 : durationMs)
                        .setInterpolator(EASE_IN_OUT);
        if (then != null) animator.withEndAction(then);
    }
}
