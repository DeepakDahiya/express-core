/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The walkthrough inside a comment stream (design_handoff_walkthrough README §3 step 4, §5):
 *
 * <ul>
 *   <li>Step 4 runs on the first vertical-video take the reader reaches while the walkthrough
 *       waits for one. As soon as the page settles the stream position stays up under the
 *       hashtag, a ring goes round both with "Every Take On #topic", and every 2.6s the video
 *       lifts to show the next take while a ghost finger swipes. The reader's first swipe once
 *       that shows, either way, ends the walkthrough with "All set". A swipe that beats it
 *       doesn't count: the step shows on the next vertical video instead, in this stream or the
 *       next one.
 *   <li>The bonus tip comes once, on the reader's third vertical-video take ever.
 * </ul>
 *
 * Nothing here takes the reel's touches beyond each one's first {@link
 * WalkthroughMotion#STEP_TOUCH_LOCK_MS}. For that long nothing in the comments sheet responds,
 * so the reader reads the step or tip rather than swiping past it. After that the reel works as
 * usual underneath.
 */
public final class WalkthroughReel {
    /** What the reel fragment shows, for the walkthrough to point at. */
    public interface Host {
        /** Whether the page the pager rests on is a full-page vertical video. */
        boolean isCurrentPageReel();

        /** The current reel page's first comment, or null. */
        @Nullable
        String currentReelAnchor();

        /** The current reel page's "#Hashtag" (or "Comments"), or null. */
        @Nullable
        View reelTitle();

        /** The stream position row under it, or null. */
        @Nullable
        View reelStreamProgress();

        /** The current reel page's root; its overlay pieces stay put while the video lifts. */
        @Nullable
        ViewGroup reelPage();

        /** The page's list holding its video row, which lifts with the pager. */
        @Nullable
        View reelRows();

        /** The pager's own list, which the swipe nudge lifts. */
        @Nullable
        RecyclerView pagerList();

        /** Something (a sheet, a viewer, a picker) covers the reel. */
        boolean isCovered();

        /** The post's hashtag as the backend sent it, with its "#"; null when it has none. */
        @Nullable
        String hashtag();

        /** Keeps the stream position showing (true) or lets it fade as usual (false). */
        void holdStreamProgress(boolean hold);

        /** The current reel's emoji chips that show, top to bottom. */
        List<View> reelEmojiChips();

        /** The current reel's replies button, or null. */
        @Nullable
        View reelReplyButton();
    }

    private static final long WATCH_MS = 250;
    /**
     * The watch's pace while step 4 is about to show: a reel page is up but not bound yet, or the
     * ring has just come up and the stream position under the hashtag may still be settling.
     */
    private static final long CLOSE_WATCH_MS = 32;
    /** How long after the ring comes up the watch keeps that pace. */
    private static final long CLOSE_WATCH_AFTER_SHOWN_MS = 600;
    /** How long step 4 waits for the page's hashtag to be laid out before trying again. */
    private static final long LAYOUT_RETRY_MS = 16;

    private static final int IDLE = 0;
    /** Step 4 has its page and shows in a moment, once the page has settled and laid out. */
    private static final int WAITING = 1;
    /** The label shows and the nudge runs. */
    private static final int NUDGING = 2;

    private final FrameLayout mRoot;
    private final Host mHost;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mCountedTakes = new HashSet<>();
    private @Nullable WalkthroughOverlay mOverlay;
    private boolean mActive;
    private final WalkthroughTouchLock mTouchLock = new WalkthroughTouchLock();

    // Step 4.
    private int mPhase = IDLE;
    private @Nullable String mStepAnchor;
    /** {@link SystemClock#elapsedRealtime} when the ring and label went up. */
    private long mShownAtMs;
    private @Nullable View mRing;
    private @Nullable View mLabel;
    private @Nullable View mFinger;
    private @Nullable View mTrail;
    private @Nullable ValueAnimator mNudge;
    private @Nullable RecyclerView mLiftedList;
    /** The pager holding that list, and whether it clipped the list before the nudge. */
    private @Nullable ViewGroup mPager;
    private boolean mPagerClipped = true;
    /**
     * The page chrome the lift holds in place: each view's own translation, and the one the lift
     * last gave it.
     */
    private final Map<View, float[]> mCounterMoved = new HashMap<>();

    // The bonus tip.
    private @Nullable View mTip;
    private @Nullable View mTipChipsRing;
    private @Nullable View mTipReplyRing;
    private boolean mTipShowing;

    private final Runnable mWatch = this::watch;
    private final Runnable mShowNudge = this::showNudge;
    private final Runnable mShowTip = this::showBonusTip;
    private final Runnable mAutoDismissTip = () -> dismissTip("timeout");

    private final RecyclerView.OnItemTouchListener mTouchWatcher =
            new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(
                        @NonNull RecyclerView list, @NonNull MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        // A finger on the reel: the lift lets go at once so it can't fight it.
                        pauseNudge();
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        resumeNudgeLater();
                    }
                    return false;
                }
            };

    /** @param root the reel fragment's root, which the walkthrough's layer is added to. */
    public WalkthroughReel(FrameLayout root, Host host) {
        mRoot = root;
        mHost = host;
        mContext = root.getContext();
    }

    /** The stream is on screen and may run the walkthrough. */
    public void resume() {
        if (mActive) return;
        mActive = true;
        // Laid out ahead of need, so its size is known when a step places things in it.
        overlay();
        mHandler.post(mWatch);
    }

    /** The stream left the screen (back to Home, the app went away): everything stops. */
    public void pause() {
        stopStep4(false);
        dismissTip("covered");
        if (!mActive) return;
        mActive = false;
        mHandler.removeCallbacks(mWatch);
    }

    public void destroy() {
        pause();
        mTouchLock.release();
        mHandler.removeCallbacksAndMessages(null);
        if (mOverlay != null) mRoot.removeView(mOverlay);
        mOverlay = null;
    }

    /**
     * The pager came to rest on a page. A vertical video counts toward the bonus tip, and may
     * start step 4.
     */
    public void onPageSettled() {
        if (!mActive) return;
        String anchor = mHost.isCurrentPageReel() ? mHost.currentReelAnchor() : null;
        if (anchor != null && mCountedTakes.add(anchor)) {
            int seen = WalkthroughState.countVideoTake();
            if (seen >= WalkthroughState.BONUS_TIP_TAKE) scheduleBonusTip();
        }
        if (anchor != null && mPhase != IDLE && !anchor.equals(mStepAnchor)) {
            // Moved without a swipe (a re-pack): start over on the page now showing.
            stopStep4(false);
        }
        maybeStartStep4();
    }

    /** The reader started dragging the pager: their swipe. */
    public void onUserDrag() {
        // A tip that hadn't come up yet comes on the next new vertical video (onPageSettled).
        if (mTipShowing) dismissTip("swipe");
        mHandler.removeCallbacks(mShowTip);
        if (mPhase == NUDGING && SystemClock.elapsedRealtime() - mShownAtMs
                >= WalkthroughMotion.S4_MIN_SEEN_MS) {
            finish();
        } else if (mPhase != IDLE) {
            // The swipe beat the step, or came while it was still fading in, so the reader never
            // really saw it: it waits for the next vertical video rather than counting as done.
            stopStep4(false);
        }
    }

    /**
     * Runs every {@link #WATCH_MS} while the stream shows: starts step 4, and takes it (and the
     * tip) away while something covers the reel, so it starts again once the reel is back.
     */
    private void watch() {
        if (!mActive) return;
        if (isCovered()) {
            if (mPhase != IDLE) stopStep4(false);
            if (mTipShowing) dismissTip("covered");
        } else {
            maybeStartStep4();
            if (mPhase == NUDGING) positionStep4();
        }
        mHandler.postDelayed(mWatch, watchClosely() ? CLOSE_WATCH_MS : WATCH_MS);
    }

    /**
     * Whether step 4 is about to show or has just come up, so the watch checks every frame or
     * two: it shows on its page's first bound frame, and its ring follows the stream position as
     * that settles under the hashtag.
     */
    private boolean watchClosely() {
        if (mPhase == NUDGING) {
            return SystemClock.elapsedRealtime() - mShownAtMs < CLOSE_WATCH_AFTER_SHOWN_MS;
        }
        return mPhase == IDLE && WalkthroughState.isWaitingForVideo()
                && mHost.currentReelAnchor() != null && !isCovered();
    }

    // ---------------------------------------------------------------------------------------
    // Step 4
    // ---------------------------------------------------------------------------------------

    private void maybeStartStep4() {
        if (mPhase != IDLE || !WalkthroughState.isWaitingForVideo()) return;
        if (!mHost.isCurrentPageReel() || isCovered()) return;
        String anchor = mHost.currentReelAnchor();
        if (anchor == null) return;
        mPhase = WAITING;
        mStepAnchor = anchor;
        // The reel holds still from here, so no swipe beats the step; showNudge renews the hold
        // from the moment the label is up.
        mTouchLock.hold(mRoot, WalkthroughMotion.STEP_TOUCH_LOCK_MS);
        // The stream position comes up under the hashtag and stays while this step shows.
        mHost.holdStreamProgress(true);
        // Not waiting for the video to play: it shows as soon as the page is laid out.
        mHandler.post(mShowNudge);
    }

    /** The ring and label, and the nudge every 2.6s. */
    private void showNudge() {
        if (mPhase != WAITING || isCovered()) return;
        WalkthroughOverlay overlay = overlay();
        if (overlay.getWidth() == 0 || overlay.rectOf(mHost.reelTitle()) == null) {
            // A stream just opened may not have laid its first page out yet.
            mHandler.postDelayed(mShowNudge, LAYOUT_RETRY_MS);
            return;
        }
        mPhase = NUDGING;
        mShownAtMs = SystemClock.elapsedRealtime();
        mTouchLock.hold(mRoot, WalkthroughMotion.STEP_TOUCH_LOCK_MS);
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_STEP_SHOWN, "step",
                WalkthroughState.STEP_VIDEO);

        mRing = new WalkthroughShapes.PulseRingView(mContext);
        mLabel = LayoutInflater.from(mContext)
                         .inflate(R.layout.browser_express_walkthrough_label, overlay, false);
        WalkthroughCardFrame frame = (WalkthroughCardFrame) mLabel;
        frame.configure(false, true, 16f, 0);
        String hashtag = mHost.hashtag();
        TextView title = mLabel.findViewById(R.id.walkthrough_title);
        title.setText(hashtag != null
                        ? mContext.getString(R.string.browser_express_walkthrough_video_title,
                                hashtag)
                        : mContext.getString(
                                R.string.browser_express_walkthrough_video_title_fallback));
        mRing.setAlpha(0f);
        mLabel.setAlpha(0f);
        overlay.addView(mRing);
        overlay.addView(mLabel);
        positionStep4();
        mRing.animate().alpha(1f).setDuration(WalkthroughMotion.S4_LABEL_IN_MS)
                .setInterpolator(WalkthroughMotion.EASE_OUT);
        mLabel.animate().alpha(1f).setDuration(WalkthroughMotion.S4_LABEL_IN_MS)
                .setInterpolator(WalkthroughMotion.EASE_OUT);
        View card = mLabel.findViewById(R.id.walkthrough_card);
        card.setContentDescription(title.getText() + ". "
                + mContext.getString(R.string.browser_express_walkthrough_video_line));
        card.announceForAccessibility(card.getContentDescription());

        startNudge();
    }

    /** Keeps the ring on the hashtag and stream position, and the label under it. */
    private void positionStep4() {
        WalkthroughOverlay overlay = mOverlay;
        if (overlay == null || mRing == null || mLabel == null) return;
        RectF title = overlay.rectOf(mHost.reelTitle());
        RectF row = overlay.rectOf(mHost.reelStreamProgress());
        if (title == null) return;
        // The hashtag and its row stay put while the nudge lifts the video (applyLift).
        RectF target = new RectF(title);
        if (row != null) target.union(row);
        target.inset(-dp(10), -dp(8));
        WalkthroughShapes.PulseRingView ring = (WalkthroughShapes.PulseRingView) mRing;
        ring.setCornerRadius(dp(12));
        float margin = ring.margin();
        overlay.place(mRing, target.left - margin, target.top - margin,
                Math.round(target.width() + 2 * margin),
                Math.round(target.height() + 2 * margin));

        WalkthroughCardFrame frame = (WalkthroughCardFrame) mLabel;
        frame.setCaretX(dp(27));
        // The card may grow to 12dp short of the right edge, where a long hashtag ellipsizes.
        // The frame's shadow margin hangs past that, hence a right margin that can be negative.
        overlay.place(mLabel, target.left - frame.cardOffsetX(),
                target.bottom + dp(11) - frame.cardOffsetY(), dp(12) - frame.getPaddingRight());
    }

    /** The lift and the ghost finger, together, every 2.6s. Reduced motion shows them still. */
    private void startNudge() {
        WalkthroughOverlay overlay = overlay();
        mFinger = WalkthroughShapes.newTouchDot(mContext, 36f, 0.7f);
        mTrail = WalkthroughShapes.newFingerTrail(mContext, 120f);
        float fingerSize = dp(36);
        float x = (overlay.getWidth() - fingerSize) / 2f;
        float bottom = overlay.getHeight() - dp(150);
        overlay.place(mTrail, x + (fingerSize - dp(4)) / 2f, bottom - dp(18) - dp(120),
                Math.round(dp(4)), Math.round(dp(120)));
        overlay.place(mFinger, x, bottom - fingerSize, Math.round(fingerSize),
                Math.round(fingerSize));
        mTrail.setPivotY(dp(120));
        mTrail.setPivotX(dp(2));

        if (WalkthroughMotion.reducedMotion()) {
            // A still hint: the finger part-way up its swipe, its trail drawn below it.
            mFinger.setAlpha(0.95f);
            mFinger.setTranslationY(-dp(WalkthroughMotion.S4_FINGER_TRAVEL_DP) / 2f);
            mTrail.setScaleY(0.5f);
            mTrail.setAlpha(1f);
            return;
        }
        mFinger.setAlpha(0f);
        mTrail.setScaleY(0f);
        RecyclerView list = mHost.pagerList();
        mLiftedList = list;
        if (list != null) {
            list.addOnItemTouchListener(mTouchWatcher);
            mPager = list.getParent() instanceof ViewGroup ? (ViewGroup) list.getParent() : null;
            if (mPager != null) mPagerClipped = mPager.getClipChildren();
        }
        runNudgeCycles();
    }

    private void runNudgeCycles() {
        if (mNudge != null) mNudge.cancel();
        float lift = dp(WalkthroughMotion.S4_LIFT_DP);
        float overshoot = dp(WalkthroughMotion.S4_OVERSHOOT_DP);
        float travel = dp(WalkthroughMotion.S4_FINGER_TRAVEL_DP);
        mNudge = ValueAnimator.ofFloat(0f, 1f);
        mNudge.setDuration(WalkthroughMotion.S4_NUDGE_MS);
        mNudge.setRepeatCount(ValueAnimator.INFINITE);
        mNudge.setInterpolator(new LinearInterpolator());
        mNudge.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // wkPeek: up 90dp by 22%, 7dp past rest by 34%, settled by 42%.
            float y;
            if (t < 0.22f) {
                y = -lift * segment(t, 0f, 0.22f);
            } else if (t < 0.34f) {
                y = -lift + (lift + overshoot) * segment(t, 0.22f, 0.34f);
            } else if (t < 0.42f) {
                y = overshoot * (1f - segment(t, 0.34f, 0.42f));
            } else {
                y = 0f;
            }
            applyLift(y);

            // wkSwipeDot / wkTrail.
            if (mFinger == null || mTrail == null) return;
            float alpha;
            float dy;
            float scale;
            if (t < 0.05f) {
                float k = segment(t, 0f, 0.05f);
                alpha = 0.95f * k;
                dy = 0f;
                scale = 0.9f + 0.1f * k;
            } else if (t < 0.22f) {
                alpha = 0.95f;
                dy = -travel * segment(t, 0.05f, 0.22f);
                scale = 1f;
            } else if (t < 0.32f) {
                float k = segment(t, 0.22f, 0.32f);
                alpha = 0.95f * (1f - k);
                dy = -travel - dp(10) * k;
                scale = 1f - 0.1f * k;
            } else {
                alpha = 0f;
                dy = -travel - dp(10);
                scale = 0.9f;
            }
            mFinger.setAlpha(alpha);
            mFinger.setTranslationY(dy);
            mFinger.setScaleX(scale);
            mFinger.setScaleY(scale);
            float trailScale = t <= 0.05f ? 0f : (t < 0.22f ? segment(t, 0.05f, 0.22f) : 1f);
            float trailAlpha = t < 0.22f ? 1f : (t < 0.34f ? 1f - segment(t, 0.22f, 0.34f) : 0f);
            mTrail.setScaleY(trailScale);
            mTrail.setAlpha(trailAlpha);
        });
        mNudge.start();
    }

    /** CSS ease-in-out across one keyframe segment. */
    private static float segment(float t, float from, float to) {
        float k = Math.max(0f, Math.min(1f, (t - from) / (to - from)));
        return WalkthroughMotion.CSS_EASE_IN_OUT.getInterpolation(k);
    }

    /**
     * Lifts the pager's list by {@code y}, which shows the top of the next take below, while the
     * current page's hashtag, pill and scrims stay where they are, under the ring.
     */
    private void applyLift(float y) {
        RecyclerView list = mLiftedList;
        if (list == null) return;
        // The next page lies below the list's own bounds; whether a view is clipped to its
        // bounds is its parent's call, so the pager lets the list draw it into view. The pager
        // itself still clips to the screen.
        if (mPager != null) mPager.setClipChildren(y == 0f && mPagerClipped);
        list.setTranslationY(y);
        if (y == 0f) {
            releaseChrome();
            return;
        }
        ViewGroup page = mHost.reelPage();
        View rows = mHost.reelRows();
        if (page == null) return;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child == rows) continue;
            // On top of the child's own translation: the stream position sits under the hashtag
            // by one (ReelStreamProgress.placeBelow), and losing it put the row at the top of the
            // page, above the hashtag, and stretched the ring off the top of the screen.
            float[] state = mCounterMoved.get(child);
            if (state == null || child.getTranslationY() != state[1]) {
                // First lift, or the child moved itself since: that is its own place now.
                state = new float[] {child.getTranslationY(), 0f};
                mCounterMoved.put(child, state);
            }
            state[1] = state[0] - y;
            child.setTranslationY(state[1]);
        }
    }

    /** Puts the lifted page's chrome back on its own translation, unless it has moved since. */
    private void releaseChrome() {
        for (Map.Entry<View, float[]> entry : mCounterMoved.entrySet()) {
            View child = entry.getKey();
            float[] state = entry.getValue();
            if (child.getTranslationY() == state[1]) child.setTranslationY(state[0]);
        }
        mCounterMoved.clear();
    }

    private void pauseNudge() {
        if (mNudge == null) return;
        mNudge.cancel();
        mNudge = null;
        applyLift(0f);
        if (mFinger != null) mFinger.setAlpha(0f);
        if (mTrail != null) mTrail.setAlpha(0f);
    }

    private void resumeNudgeLater() {
        if (mPhase != NUDGING || mNudge != null || WalkthroughMotion.reducedMotion()) return;
        mHandler.removeCallbacks(mRestartNudge);
        mHandler.postDelayed(mRestartNudge, WalkthroughMotion.S4_NUDGE_MS);
    }

    private final Runnable mRestartNudge = () -> {
        if (mPhase == NUDGING && mNudge == null && !isCovered()) runNudgeCycles();
    };

    /**
     * Ends step 4 here. {@code animated} fades its pieces (the reader swiped) rather than taking
     * them away at once (the stream went or something covered it).
     */
    private void stopStep4(boolean animated) {
        if (mPhase == IDLE) return;
        mPhase = IDLE;
        mStepAnchor = null;
        mTouchLock.release();
        mHandler.removeCallbacks(mShowNudge);
        mHandler.removeCallbacks(mRestartNudge);
        pauseNudge();
        releaseChrome();
        if (mLiftedList != null) {
            mLiftedList.removeOnItemTouchListener(mTouchWatcher);
            mLiftedList = null;
        }
        if (mPager != null) mPager.setClipChildren(mPagerClipped);
        mPager = null;
        mHost.holdStreamProgress(false);
        removeSoon(mRing, animated);
        removeSoon(mLabel, animated);
        removeSoon(mFinger, animated);
        removeSoon(mTrail, animated);
        mRing = null;
        mLabel = null;
        mFinger = null;
        mTrail = null;
    }

    private void removeSoon(@Nullable View view, boolean animated) {
        if (view == null) return;
        Runnable remove = () -> {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        };
        if (animated) {
            WalkthroughMotion.fadeOut(view, WalkthroughMotion.S4_END_FADE_MS, remove);
        } else {
            view.animate().cancel();
            remove.run();
        }
    }

    /** The reader's first swipe once step 4 showed: the walkthrough is complete. */
    private void finish() {
        stopStep4(true);
        WalkthroughState.finish();
        // Always true now that a swipe before the nudge no longer finishes; kept for the dashboards.
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_COMPLETED, "nudged", true);
        mHandler.postDelayed(this::showToast, WalkthroughMotion.S4_TOAST_DELAY);
    }

    private void showToast() {
        WalkthroughOverlay overlay = overlay();
        View toast = LayoutInflater.from(mContext)
                             .inflate(R.layout.browser_express_walkthrough_toast, overlay, false);
        WalkthroughCardFrame frame = (WalkthroughCardFrame) toast;
        frame.configure(false, true, 18f, 0);
        toast.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = Math.round(dp(92)) - frame.cardOffsetY();
        overlay.addView(toast, params);
        toast.announceForAccessibility(
                mContext.getString(R.string.browser_express_walkthrough_all_set));
        // In over 300ms from 6dp up, 2s, out over 400ms.
        WalkthroughMotion.fadeIn(toast, 0, WalkthroughMotion.S4_TOAST_IN_MS, -dp(6));
        mHandler.postDelayed(() -> WalkthroughMotion.fadeOut(toast,
                WalkthroughMotion.S4_TOAST_OUT_MS, () -> overlay.removeView(toast)),
                WalkthroughMotion.S4_TOAST_IN_MS + WalkthroughMotion.S4_TOAST_HOLD_MS);
    }

    // ---------------------------------------------------------------------------------------
    // The bonus tip
    // ---------------------------------------------------------------------------------------

    private void scheduleBonusTip() {
        if (WalkthroughState.isBonusTipShown()) return;
        if (!WalkthroughState.STEP_DONE.equals(WalkthroughState.step())) return;
        if (mTipShowing) return;
        long delay = WalkthroughMotion.TIP_DELAY_AFTER_TAKE;
        long finishedAt = WalkthroughState.finishedAtMs();
        if (finishedAt >= 0) {
            long sinceFinish = SystemClock.elapsedRealtime() - finishedAt;
            delay = Math.max(delay, WalkthroughMotion.TIP_MIN_AFTER_FINISH - sinceFinish);
        }
        mHandler.removeCallbacks(mShowTip);
        mHandler.postDelayed(mShowTip, delay);
    }

    private void showBonusTip() {
        if (!mActive || mTipShowing || WalkthroughState.isBonusTipShown()) return;
        if (isCovered() || !mHost.isCurrentPageReel()) return;
        WalkthroughOverlay overlay = overlay();
        List<View> chips = mHost.reelEmojiChips();
        RectF chipsRect = null;
        for (View chip : chips) {
            RectF rect = overlay.rectOf(chip);
            if (rect == null) continue;
            if (chipsRect == null) {
                chipsRect = rect;
            } else {
                chipsRect.union(rect);
            }
        }
        RectF replyRect = overlay.rectOf(mHost.reelReplyButton());
        mTipShowing = true;
        mTouchLock.hold(mRoot, WalkthroughMotion.STEP_TOUCH_LOCK_MS);
        WalkthroughState.setBonusTipShown();
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_BONUS_TIP_SHOWN);

        float railLeft = overlay.getWidth();
        if (chipsRect != null) {
            chipsRect.inset(-dp(6), -dp(6));
            mTipChipsRing = addRing(overlay, chipsRect, 0);
            railLeft = Math.min(railLeft, chipsRect.left);
        }
        if (replyRect != null) {
            replyRect.inset(-dp(6), -dp(6));
            // The second ring pulses .4s behind the first, as in the reference.
            mTipReplyRing = addRing(overlay, replyRect, 400);
            railLeft = Math.min(railLeft, replyRect.left);
        }

        mTip = LayoutInflater.from(mContext)
                       .inflate(R.layout.browser_express_walkthrough_bonus_tip, overlay, false);
        WalkthroughCardFrame frame = (WalkthroughCardFrame) mTip;
        frame.configure(false, false, 16f, 0);
        View card = mTip.findViewById(R.id.walkthrough_card);
        int width = Math.round(Math.min(dp(316), railLeft - dp(12) - dp(12)));
        ViewGroup.LayoutParams cardParams = card.getLayoutParams();
        cardParams.width = Math.max(Math.round(dp(220)), width);
        card.setLayoutParams(cardParams);
        View dismiss = mTip.findViewById(R.id.walkthrough_tip_dismiss);
        dismiss.setOnClickListener(v -> dismissTip("got_it"));
        overlay.addInteractive(dismiss);
        overlay.addInteractive(card);
        // Tapping an emoji or the replies works as usual, and puts the tip away.
        if (chipsRect != null) overlay.addTapZone(chipsRect, () -> dismissTip("rail"));
        if (replyRect != null) overlay.addTapZone(replyRect, () -> dismissTip("rail"));

        mTip.setAlpha(0f);
        // Bottom left, its card 12dp from the left and 150dp from the bottom.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        params.leftMargin = Math.round(dp(12)) - frame.cardOffsetX();
        params.bottomMargin = Math.round(dp(150)) - frame.getPaddingBottom();
        overlay.addView(mTip, params);
        View tip = mTip;

        // Slides in 10dp from the left as it fades in; its two rows follow 150ms apart.
        if (WalkthroughMotion.reducedMotion()) {
            tip.setAlpha(1f);
        } else {
            tip.setTranslationX(-dp(WalkthroughMotion.TIP_SLIDE_DP));
            tip.animate().alpha(1f).translationX(0f).setDuration(WalkthroughMotion.TIP_IN_MS)
                    .setInterpolator(WalkthroughMotion.EASE_OUT);
            View emojiRow = tip.findViewById(R.id.walkthrough_tip_emoji_row);
            View replyRow = tip.findViewById(R.id.walkthrough_tip_reply_row);
            WalkthroughMotion.fadeIn(emojiRow, 200, 300, 0f);
            WalkthroughMotion.fadeIn(replyRow, 200 + WalkthroughMotion.TIP_ROW_STAGGER, 300, 0f);
        }
        card.setContentDescription(
                mContext.getString(R.string.browser_express_walkthrough_tip_emoji_title) + ". "
                + mContext.getString(R.string.browser_express_walkthrough_tip_emoji_line) + ". "
                + mContext.getString(R.string.browser_express_walkthrough_tip_reply_title) + ". "
                + mContext.getString(R.string.browser_express_walkthrough_tip_reply_line));
        card.announceForAccessibility(card.getContentDescription());
        mHandler.postDelayed(mAutoDismissTip, WalkthroughMotion.TIP_AUTO_DISMISS_MS);
    }

    private View addRing(WalkthroughOverlay overlay, RectF target, long delayMs) {
        WalkthroughShapes.PulseRingView ring = new WalkthroughShapes.PulseRingView(mContext);
        ring.setCornerRadius(dp(12));
        ring.setStartDelay(delayMs);
        float margin = ring.margin();
        overlay.place(ring, target.left - margin, target.top - margin,
                Math.round(target.width() + 2 * margin), Math.round(target.height() + 2 * margin));
        WalkthroughMotion.fadeIn(ring, 0, WalkthroughMotion.TIP_IN_MS, 0f);
        return ring;
    }

    private void dismissTip(String how) {
        mHandler.removeCallbacks(mAutoDismissTip);
        if (!mTipShowing) return;
        mTipShowing = false;
        mTouchLock.release();
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_BONUS_TIP_DISMISSED, "dismissed_by", how);
        boolean animated = !"covered".equals(how);
        if (mOverlay != null) mOverlay.clearTouchTargets();
        for (View view : new View[] {mTip, mTipChipsRing, mTipReplyRing}) {
            if (view == null) continue;
            Runnable remove = () -> {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
            };
            if (animated) {
                WalkthroughMotion.fadeOut(view, WalkthroughMotion.TIP_OUT_MS, remove);
            } else {
                remove.run();
            }
        }
        mTip = null;
        mTipChipsRing = null;
        mTipReplyRing = null;
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Something is over the reel: what the stream knows of (a sheet, a viewer, a picker), or any
     * other window, such as a dialog or a permission prompt.
     */
    private boolean isCovered() {
        return mHost.isCovered() || !mRoot.hasWindowFocus();
    }

    private WalkthroughOverlay overlay() {
        if (mOverlay == null) {
            mOverlay = new WalkthroughOverlay(mContext, false);
            mRoot.addView(mOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return mOverlay;
    }

    private float dp(float value) {
        return WalkthroughMotion.dp(mContext, value);
    }
}
