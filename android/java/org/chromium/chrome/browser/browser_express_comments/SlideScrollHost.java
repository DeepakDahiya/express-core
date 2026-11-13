/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Wraps a scrollable view that shares its axis with the {@link ViewPager2} above it, and decides
 * which of the two a drag belongs to.
 *
 * <p>A page and its pager scroll the same way, so without this the pager wins every drag — it
 * intercepts as an ancestor, before the list inside ever sees the gesture. A page whose comments
 * fit wants exactly that. A page holding a comment the reader expanded does not: it has more
 * content than screen, and swiping should read it rather than skip past it.
 *
 * <p>So the drag is handed over on the fly. While the list can still scroll the way the finger is
 * moving, the pager is held off; the moment the list runs out — the reader keeps pulling at the
 * bottom of the page — the pager is released and takes the rest of the same gesture, turning the
 * page. Reading to the end and carrying on into the next page is one continuous movement, with no
 * lift in between.
 *
 * <p>A page with nothing to scroll never holds the pager off at all, so ordinary pages behave
 * exactly as they did before this existed.
 */
public class SlideScrollHost extends FrameLayout {
    private final int mTouchSlop;

    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    /** True once the gesture has travelled far enough to be a scroll rather than a tap. */
    private boolean mPastSlop;

    public SlideScrollHost(Context context) {
        this(context, null);
    }

    public SlideScrollHost(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SlideScrollHost(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Watching only — the host never consumes anything itself, it just keeps the pager's
        // hands off the gesture for as long as the list can use it.
        updatePagerHold(ev);
        return super.onInterceptTouchEvent(ev);
    }

    private void updatePagerHold(MotionEvent ev) {
        ViewPager2 pager = findParentPager();
        View scrollable = getChildCount() > 0 ? getChildAt(0) : null;
        if (pager == null || scrollable == null) return;
        boolean vertical = pager.getOrientation() == ViewPager2.ORIENTATION_VERTICAL;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = mLastX = ev.getX();
                mDownY = mLastY = ev.getY();
                mPastSlop = false;
                // Nothing to scroll means nothing to arbitrate: leave the pager alone and this
                // page swipes like any other.
                if (canScroll(scrollable, vertical, -1) || canScroll(scrollable, vertical, 1)) {
                    // Direction is unknown until the finger moves, so hold the pager off until
                    // it is — otherwise the page turns before the list gets a say.
                    holdPager(true);
                }
                break;

            case MotionEvent.ACTION_MOVE: {
                float x = ev.getX();
                float y = ev.getY();
                float stepX = x - mLastX;
                float stepY = y - mLastY;
                mLastX = x;
                mLastY = y;

                if (!mPastSlop) {
                    if (Math.abs(y - mDownY) < mTouchSlop && Math.abs(x - mDownX) < mTouchSlop) {
                        break;
                    }
                    mPastSlop = true;
                }

                // The step, not the total: the decision is re-taken continuously so the pager
                // can pick the gesture up at the moment the list hits its end.
                float step = vertical ? stepY : stepX;
                if (step == 0f) break; // Nothing new to go on; leave the last decision standing.

                // A finger moving down pulls content down, which is the list scrolling towards
                // its start — the negative direction canScroll* is asked about.
                holdPager(canScroll(scrollable, vertical, step > 0f ? -1 : 1));
                break;
            }

            default:
                break;
        }
    }

    private static boolean canScroll(View view, boolean vertical, int direction) {
        return vertical ? view.canScrollVertically(direction)
                        : view.canScrollHorizontally(direction);
    }

    private void holdPager(boolean hold) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(hold);
    }

    @Nullable
    private ViewPager2 findParentPager() {
        for (ViewParent parent = getParent(); parent instanceof ViewGroup;
                parent = parent.getParent()) {
            if (parent instanceof ViewPager2) return (ViewPager2) parent;
        }
        return null;
    }
}
