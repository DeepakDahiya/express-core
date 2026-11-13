/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * A FrameLayout that can be swiped away in any direction, like a notification: once a drag passes
 * the touch slop it takes the gesture from its children (so a drag that starts on a button still
 * swipes, and a tap is still a tap), follows the finger and fades, and on release either carries
 * on out the way it was thrown — far enough, or fast enough — or settles back.
 */
public class SwipeDismissLayout extends FrameLayout {
    /** What the swipe did; all on the UI thread. */
    interface Listener {
        /** The finger took the view; it may yet settle back. */
        default void onSwipeStarted() {}

        /** Let go short of leaving: the view is back where it was. */
        default void onSwipeCancelled() {}

        /** Gone off the way it was swiped; the view is still translated and transparent. */
        void onSwipedAway();
    }

    /** How far across, of the view's width, or down/up, of its height, lets it go. */
    private static final float LEAVE_FRACTION_X = 0.35f;
    private static final float LEAVE_FRACTION_Y = 0.6f;
    /** A throw at least this fast (dp/s) lets it go however short the drag. */
    private static final float FLING_DP_PER_S = 700f;
    /** How faint it gets on its way out, at the distance that lets it go. */
    private static final float FADE_AT_LEAVE = 0.5f;
    private static final long SETTLE_MS = 200;
    private static final long LEAVE_MS = 180;

    private final int mTouchSlop;
    private final float mFlingVelocity;
    private @Nullable Listener mListener;
    private @Nullable VelocityTracker mVelocity;
    private float mDownX;
    private float mDownY;
    private boolean mDragging;
    /** Carrying on out after a swipe that let it go. */
    private boolean mLeaving;

    public SwipeDismissLayout(Context context) {
        this(context, null);
    }

    public SwipeDismissLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mFlingVelocity = FLING_DP_PER_S * context.getResources().getDisplayMetrics().density;
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** In the reader's hand, or on its way out after it; leave its position alone meanwhile. */
    boolean isSwiping() {
        return mDragging || mLeaving;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // On its way out: nothing in it can be touched any more.
        if (mLeaving) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startTracking(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(event);
                if (!mDragging && pastSlop(event)) {
                    startDrag();
                    // The child under the finger gets a cancel; the rest comes to onTouchEvent.
                    return true;
                }
                return mDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopTracking();
                return false;
            default:
                return false;
        }
    }

    // A tap still clicks through super.onTouchEvent (which calls performClick); only a swipe,
    // which is never a click, is handled here.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mLeaving) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // On the layout itself (no child took it): keep the gesture even when not clickable.
            startTracking(event);
            super.onTouchEvent(event);
            return true;
        }
        track(event);
        if (!mDragging && action == MotionEvent.ACTION_MOVE && pastSlop(event)) {
            // A press on the layout itself turning into a swipe is no longer a click.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
            startDrag();
        }
        if (!mDragging) {
            super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopTracking();
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                follow(event.getRawX() - mDownX, event.getRawY() - mDownY);
                break;
            case MotionEvent.ACTION_UP:
                release();
                break;
            case MotionEvent.ACTION_CANCEL:
                settleBack();
                break;
            default:
                break;
        }
        return true;
    }

    private void startTracking(MotionEvent event) {
        animate().cancel();
        mDownX = event.getRawX();
        mDownY = event.getRawY();
        mDragging = false;
        if (mVelocity != null) mVelocity.recycle();
        mVelocity = VelocityTracker.obtain();
        track(event);
    }

    /** Velocity in screen coordinates: the view moves under the finger while it is dragged. */
    private void track(MotionEvent event) {
        if (mVelocity == null) return;
        MotionEvent screen = MotionEvent.obtain(event);
        screen.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(screen);
        screen.recycle();
    }

    private void stopTracking() {
        mDragging = false;
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }

    private boolean pastSlop(MotionEvent event) {
        return Math.hypot(event.getRawX() - mDownX, event.getRawY() - mDownY) > mTouchSlop;
    }

    private void startDrag() {
        mDragging = true;
        setPressed(false);
        cancelLongPress();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (mListener != null) mListener.onSwipeStarted();
    }

    private void follow(float dx, float dy) {
        setTranslationX(dx);
        setTranslationY(dy);
        setAlpha(1f - FADE_AT_LEAVE * Math.min(1f, leaveProgress(dx, dy)));
    }

    /** 1 where the drag alone is enough to let it go. */
    private float leaveProgress(float dx, float dy) {
        float x = getWidth() > 0 ? Math.abs(dx) / (getWidth() * LEAVE_FRACTION_X) : 0f;
        float y = getHeight() > 0 ? Math.abs(dy) / (getHeight() * LEAVE_FRACTION_Y) : 0f;
        return Math.max(x, y);
    }

    private void release() {
        float dx = getTranslationX();
        float dy = getTranslationY();
        float vx = 0f;
        float vy = 0f;
        if (mVelocity != null) {
            mVelocity.computeCurrentVelocity(1000);
            vx = mVelocity.getXVelocity();
            vy = mVelocity.getYVelocity();
        }
        stopTracking();
        // A throw counts when it is fast and goes the way the banner was dragged.
        boolean thrown = Math.hypot(vx, vy) >= mFlingVelocity && vx * dx + vy * dy > 0;
        if (thrown) {
            leave(vx, vy);
        } else if (leaveProgress(dx, dy) >= 1f) {
            leave(dx, dy);
        } else {
            settleBack();
        }
    }

    /** Carries on along ({@code ux}, {@code uy}) until it is well clear of where it sat. */
    private void leave(float ux, float uy) {
        float length = (float) Math.hypot(ux, uy);
        if (length == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            if (mListener != null) mListener.onSwipedAway();
            return;
        }
        float distance = Math.max(getWidth(), getHeight()) * 1.5f;
        mLeaving = true;
        animate()
                .translationX(getTranslationX() + ux / length * distance)
                .translationY(getTranslationY() + uy / length * distance)
                .alpha(0f)
                .setDuration(LEAVE_MS)
                .withEndAction(() -> {
                    mLeaving = false;
                    if (mListener != null) mListener.onSwipedAway();
                });
    }

    private void settleBack() {
        stopTracking();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setTranslationX(0f);
            setTranslationY(0f);
            setAlpha(1f);
        } else {
            animate().translationX(0f).translationY(0f).alpha(1f).setDuration(SETTLE_MS);
        }
        if (mListener != null) mListener.onSwipeCancelled();
    }
}
