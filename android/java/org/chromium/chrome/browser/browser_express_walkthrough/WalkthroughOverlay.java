/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The layer the walkthrough draws in, laid over a whole screen: the Home (steps 1–3) or a reel
 * (step 4 and the bonus tip). Its children are placed in its own coordinates with {@link
 * #place}.
 *
 * <p>On the Home it takes every touch: the reader can only answer the prompt, and taps on the
 * spotlit target count as answers ({@link #addTapZone}). Over a reel it takes none except on its
 * own buttons, so the reader swipes and taps the reel as usual; a tap zone there can watch a tap
 * go by without keeping it.
 */
final class WalkthroughOverlay extends FrameLayout {
    private static final class TapZone {
        final RectF rect;
        final Runnable action;

        TapZone(RectF rect, Runnable action) {
            this.rect = rect;
            this.action = action;
        }
    }

    private final boolean mBlocking;
    private final List<TapZone> mTapZones = new ArrayList<>();
    private final List<View> mInteractive = new ArrayList<>();
    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private @Nullable TapZone mDownZone;
    /** In a passing overlay, whether this gesture is one of ours. */
    private boolean mOwnGesture;

    /** @param blocking whether it takes every touch (the Home) or only its own buttons (a reel). */
    WalkthroughOverlay(Context context, boolean blocking) {
        super(context);
        mBlocking = blocking;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
        setMotionEventSplittingEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Lays {@code child} out with its top-left at ({@code x}, {@code y}) of this overlay. */
    void place(View child, float x, float y, int width, int height) {
        place(child, x, y, width, height, 0f);
    }

    /**
     * Lays {@code child} out at its own size from ({@code x}, {@code y}), allowed to grow to
     * {@code rightMargin} from this overlay's right edge (negative reaches past it).
     */
    void place(View child, float x, float y, float rightMargin) {
        place(child, x, y, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, rightMargin);
    }

    private void place(View child, float x, float y, int width, int height, float rightMargin) {
        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(rightMargin);
        if (child.getParent() == this && child.getLayoutParams() instanceof LayoutParams) {
            LayoutParams current = (LayoutParams) child.getLayoutParams();
            // Called again on every check: only a real move lays anything out.
            if (current.width == width && current.height == height && current.leftMargin == left
                    && current.topMargin == top && current.rightMargin == right) {
                return;
            }
        }
        LayoutParams params = new LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        if (child.getParent() == this) {
            child.setLayoutParams(params);
        } else {
            addView(child, params);
        }
    }

    /** A tap inside {@code rect} (this overlay's coordinates) runs {@code action}. */
    void addTapZone(RectF rect, Runnable action) {
        mTapZones.add(new TapZone(new RectF(rect), action));
    }

    /** In a passing overlay, touches on {@code view} are this overlay's (e.g. a card's button). */
    void addInteractive(View view) {
        mInteractive.add(view);
    }

    void clearTouchTargets() {
        mTapZones.clear();
        mInteractive.clear();
        mDownZone = null;
    }

    /** Where {@code target} is, in this overlay's coordinates; null when either isn't attached. */
    @Nullable
    RectF rectOf(@Nullable View target) {
        if (target == null || !target.isAttachedToWindow() || !isAttachedToWindow()) return null;
        if (target.getWidth() <= 0 || target.getHeight() <= 0) return null;
        int[] mine = new int[2];
        int[] theirs = new int[2];
        getLocationInWindow(mine);
        target.getLocationInWindow(theirs);
        float left = theirs[0] - mine[0];
        float top = theirs[1] - mine[1];
        return new RectF(left, top, left + target.getWidth(), top + target.getHeight());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mBlocking) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mOwnGesture = false;
            for (View view : mInteractive) {
                if (view.isShown() && view.getAlpha() > 0.5f && contains(view, event)) {
                    mOwnGesture = true;
                    break;
                }
            }
            if (!mOwnGesture) {
                // A reel's own touch: noted by the zones that care, then passed on. A zone's action
                // may clear the zones (the bonus tip puts itself away), hence the copy.
                for (TapZone zone : new ArrayList<>(mTapZones)) {
                    if (zone.rect.contains(event.getX(), event.getY())) zone.action.run();
                }
                return false;
            }
        }
        if (!mOwnGesture) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = super.dispatchTouchEvent(event);
            mOwnGesture = false;
            return handled;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean contains(View view, MotionEvent event) {
        RectF rect = rectOf(view);
        return rect != null && rect.contains(event.getX(), event.getY());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mBlocking) return false;
        // Nothing under a blocking overlay hears the touch; a tap on a spotlit target is an
        // answer to the prompt.
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mDownZone = zoneAt(mDownX, mDownY);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - mDownX) > mTouchSlop
                        || Math.abs(event.getY() - mDownY) > mTouchSlop) {
                    mDownZone = null;
                }
                break;
            case MotionEvent.ACTION_UP:
                TapZone zone = mDownZone;
                mDownZone = null;
                if (zone != null && zone == zoneAt(event.getX(), event.getY())) {
                    zone.action.run();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mDownZone = null;
                break;
            default:
                break;
        }
        return true;
    }

    private @Nullable TapZone zoneAt(float x, float y) {
        for (TapZone zone : mTapZones) {
            if (zone.rect.contains(x, y)) return zone;
        }
        return null;
    }

    /**
     * Hides everything beside the overlay in {@code root} from screen readers while it shows, so
     * they stay inside the prompt; {@link #restoreAccessibility} gives it back.
     */
    void hideSiblingsFromAccessibility(ViewGroup root) {
        restoreAccessibility();
        for (int i = 0; i < root.getChildCount(); i++) {
            View sibling = root.getChildAt(i);
            if (sibling == this) continue;
            mHidden.add(sibling);
            mHiddenModes.add(sibling.getImportantForAccessibility());
            sibling.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    void restoreAccessibility() {
        for (int i = 0; i < mHidden.size(); i++) {
            mHidden.get(i).setImportantForAccessibility(mHiddenModes.get(i));
        }
        mHidden.clear();
        mHiddenModes.clear();
    }

    private final List<View> mHidden = new ArrayList<>();
    private final List<Integer> mHiddenModes = new ArrayList<>();

    /** The visible part of the window, in this overlay's coordinates. */
    Rect visibleFrame() {
        Rect frame = new Rect();
        getWindowVisibleDisplayFrame(frame);
        int[] mine = new int[2];
        getLocationOnScreen(mine);
        frame.offset(-mine[0], -mine[1]);
        return frame;
    }
}
