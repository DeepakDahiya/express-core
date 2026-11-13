/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import java.util.function.Supplier;

/**
 * Hold the right half of a video (or either half, on a reel) to watch it at 2×, like Instagram
 * Reels; let go and it carries on at the speed it had. A quick tap stays a tap and a drag stays a
 * drag: the hold only starts once a press has rested for a moment without moving, and only on a
 * video that is playing.
 *
 * <p>The player view's touch listener feeds it every event. While a hold runs the rest of the
 * gesture belongs to it, so the view's own tap (play/pause, controls) doesn't fire on release and
 * scrolling parents (the reel pager, the comments sheet) don't take the gesture over.
 */
public final class HoldToFastForward {
    private static final float FAST_SPEED = 2f;
    /** How long a press has to rest before it is a hold rather than a tap. */
    private static final long HOLD_DELAY_MS = 300;

    private final Supplier<Player> mPlayer;
    private final @Nullable View mBadge;
    private final boolean mEitherSide;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStartHold = this::startHold;

    /** The view a press is resting on, until it becomes a hold or stops being one. */
    private @Nullable View mTarget;
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private int mTouchSlop;
    private boolean mHolding;
    private float mSpeedBeforeHold = 1f;

    /**
     * @param player the video's player, or null when it has none right now.
     * @param badge the "2×" badge shown while the hold runs, or null for none.
     */
    public HoldToFastForward(Supplier<Player> player, @Nullable View badge) {
        this(player, badge, false);
    }

    /**
     * @param player the video's player, or null when it has none right now.
     * @param badge the "2×" badge shown while the hold runs, or null for none.
     * @param eitherSide a hold anywhere on the video counts, not just on its right half.
     */
    public HoldToFastForward(Supplier<Player> player, @Nullable View badge, boolean eitherSide) {
        mPlayer = player;
        mBadge = badge;
        mEitherSide = eitherSide;
    }

    /**
     * Feed it every touch event on the video view.
     *
     * @return true while a hold runs: the event is the hold's, and the view should not also act
     *     on it.
     */
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                if (!mEitherSide && event.getX() < view.getWidth() / 2f) return false;
                mTarget = view;
                mDownX = event.getX();
                mDownY = event.getY();
                mDownTime = event.getDownTime();
                mTouchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                mHandler.postDelayed(mStartHold, HOLD_DELAY_MS);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mHolding) return true;
                if (mTarget != null
                        && (Math.abs(event.getX() - mDownX) > mTouchSlop
                                || Math.abs(event.getY() - mDownY) > mTouchSlop)) {
                    // A drag (a swipe to the next reel, to the comments): not a hold.
                    cancelPending();
                }
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger (a pinch) is not a hold either.
                if (!mHolding) cancelPending();
                return mHolding;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasHolding = mHolding;
                cancel();
                return wasHolding;
            default:
                return mHolding;
        }
    }

    private void startHold() {
        View target = mTarget;
        Player player = mPlayer.get();
        if (target == null || player == null || !player.getPlayWhenReady()) {
            mTarget = null;
            return;
        }
        mHolding = true;
        mSpeedBeforeHold = player.getPlaybackParameters().speed;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(FAST_SPEED));
        if (target.getParent() != null) {
            target.getParent().requestDisallowInterceptTouchEvent(true);
        }
        // The view saw the press go down; cancel it there so letting go isn't also a tap.
        MotionEvent cancel =
                MotionEvent.obtain(
                        mDownTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        mDownX,
                        mDownY,
                        0);
        target.onTouchEvent(cancel);
        cancel.recycle();
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (mBadge != null) mBadge.setVisibility(View.VISIBLE);
    }

    private void cancelPending() {
        mHandler.removeCallbacks(mStartHold);
        mTarget = null;
    }

    /**
     * Ends a hold, or one about to start, back at the speed the video had. Call it before the
     * player is released or swapped, so the speed goes back to the player it was taken from.
     */
    public void cancel() {
        cancelPending();
        if (!mHolding) return;
        mHolding = false;
        Player player = mPlayer.get();
        if (player != null) {
            player.setPlaybackParameters(
                    player.getPlaybackParameters().withSpeed(mSpeedBeforeHold));
        }
        if (mBadge != null) mBadge.setVisibility(View.GONE);
    }
}
