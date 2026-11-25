/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

/**
 * Holds off every touch in a window for the first moments of a walkthrough step
 * ({@link WalkthroughMotion#STEP_TOUCH_LOCK_MS}), so the reader reads the prompt before they can
 * tap it away or swipe past it. On the Home that covers the prompt's own buttons and the spotlit
 * target. Over a reel it covers the swipe to the next take and the rest of the comments sheet.
 *
 * <p>A clear shield over the window's content takes the touches, raised above the Home's
 * walkthrough layer. A gesture that starts under it stays dead to its end, even when the hold
 * lifts part-way through: the views below never saw it start. Screen readers aren't held. Their
 * clicks don't arrive as touches, and hovering passes through.
 */
final class WalkthroughTouchLock {
    /** Above the Home's walkthrough layer, which WalkthroughHome raises 64dp. */
    private static final float SHIELD_Z_DP = 96f;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRelease = this::release;
    private @Nullable View mShield;
    /** {@link SystemClock#uptimeMillis} when the hold lifts. */
    private long mUntilMs;

    /**
     * Holds every touch in {@code anchor}'s window for the next {@code durationMs}. A hold that is
     * already on ends at whichever time is later.
     */
    void hold(View anchor, long durationMs) {
        ViewGroup content = windowContent(anchor);
        if (content == null) return;
        if (mShield == null || mShield.getParent() != content) {
            release();
            View shield = new Shield(anchor.getContext());
            content.addView(shield, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mShield = shield;
        }
        long until = SystemClock.uptimeMillis() + durationMs;
        if (until <= mUntilMs) return;
        mUntilMs = until;
        mHandler.removeCallbacks(mRelease);
        mHandler.postAtTime(mRelease, until);
    }

    /** Touches work again now. */
    void release() {
        mHandler.removeCallbacks(mRelease);
        mUntilMs = 0;
        View shield = mShield;
        mShield = null;
        if (shield != null && shield.getParent() instanceof ViewGroup) {
            ((ViewGroup) shield.getParent()).removeView(shield);
        }
    }

    /** The content of {@code anchor}'s window: the activity's, or a dialog's such as the sheet. */
    private static @Nullable ViewGroup windowContent(View anchor) {
        View content = anchor.getRootView().findViewById(android.R.id.content);
        if (content instanceof ViewGroup) return (ViewGroup) content;
        return anchor instanceof ViewGroup ? (ViewGroup) anchor : null;
    }

    /** Takes every touch and draws nothing. */
    private static final class Shield extends View {
        Shield(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOutlineProvider(null);
            setTranslationZ(WalkthroughMotion.dp(context, SHIELD_Z_DP));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }
    }
}
