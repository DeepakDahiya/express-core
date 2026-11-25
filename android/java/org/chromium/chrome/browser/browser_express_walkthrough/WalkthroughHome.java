/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.browser_express_splash.UgcSplash;
import org.chromium.chrome.browser.ntp.Post;
import org.chromium.chrome.browser.ntp.PostListAdapter;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The walkthrough's Home steps (design_handoff_walkthrough README §3–4):
 *
 * <ol>
 *   <li>Search: the screen dims except the search bar; "The Internet, Upgraded".
 *   <li>Scroll: the feed scrolls itself until the featured post sits fully in view.
 *   <li>Post: the screen dims except that post; "The Best User Reactions". "Dive in" opens its
 *       stream, where {@link WalkthroughReel} takes over.
 * </ol>
 *
 * The prompts take every touch while they show; nothing under them responds. For a prompt's first
 * {@link WalkthroughMotion#STEP_TOUCH_LOCK_MS} its own buttons and target don't respond either,
 * so it is read before it is answered. Whenever something else comes in front of the Home (the
 * comments, a dialog, another screen, the app going away), the walkthrough steps back, and the
 * current step starts over when the Home is back.
 *
 * <p>Step 1 shows as soon as the search bar is laid out, without waiting for the feed's first
 * page. That page can take seconds on a first launch. Steps 2 and 3 need the posts, and the
 * featured post's checks start as they arrive.
 */
public final class WalkthroughHome {
    private static final String TAG = "Walkthrough";

    /** How often, and how many times, step 1 looks for the search bar to be laid out. */
    private static final long SEARCH_RETRY_MS = 32;
    private static final int SEARCH_ATTEMPTS = 30;
    /** How often, and how many times, step 3 looks for the featured post on screen. */
    private static final long POST_RETRY_MS = 50;
    private static final int POST_ATTEMPTS = 15;
    /** A jump to the featured post, then a pass that measures it once laid out. */
    private static final long PLACE_PASS_MS = 32;
    /**
     * How long step 2 holds the Home for a feed that hadn't come in when step 1 was answered, and
     * how often it checks whether the feed failed. After that, or on a failure, the Home is the
     * reader's (its retry included), and step 2 runs once the posts arrive.
     */
    private static final long FEED_WAIT_MS = 8000;
    private static final long FEED_CHECK_MS = 250;
    /** How long step 2 may wait for the featured post to be decided. */
    private static final long PICK_WAIT_MS = 1200;
    /** How long a resumed step 3 may wait for it. */
    private static final long RESUME_PICK_WAIT_MS = 2500;
    private static final float POST_BOTTOM_GAP_DP = 24f;
    private static final float CARD_GAP_DP = 14f;

    private final Activity mActivity;
    private final Context mContext;
    private final RecyclerView mList;
    private final PostListAdapter mAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mAttached;
    private boolean mFeedReady;
    private boolean mRunning;
    private boolean mAcknowledged;
    private @Nullable WalkthroughOverlay mOverlay;
    private @Nullable FeaturedPostPicker mPicker;
    private @Nullable String mFeaturedId;
    /** Repeating or delayed effects of the step showing, stopped together. */
    private final List<ValueAnimator> mAnimators = new ArrayList<>();
    private @Nullable WalkthroughShapes.PulseRingView mRing;
    private final WalkthroughTouchLock mTouchLock = new WalkthroughTouchLock();
    /** Step 1 was answered before the feed came in; step 2 scrolls once it does. */
    private boolean mWaitingForFeed;
    /** {@link SystemClock#elapsedRealtime} when step 2 started waiting for the feed. */
    private long mFeedWaitStartMs;

    private final Runnable mStart = this::startCurrentStep;
    /** The launch splash covers the Home; it going changes no focus, so it says so here. */
    private final Runnable mSplashGone = this::maybeStart;
    private final Runnable mFeedCheck = this::checkFeedWait;

    private final ViewTreeObserver.OnWindowFocusChangeListener mFocusListener =
            hasFocus -> {
                if (hasFocus) {
                    maybeStart();
                } else {
                    // Something came in front of the Home: a sheet, a dialog, another screen.
                    pause();
                }
            };

    /** The window's observer {@link #mFocusListener} is on, while the list is in a window. */
    private @Nullable ViewTreeObserver mFocusObserver;

    /**
     * The page hands the walkthrough over as it attaches, before the list itself is in the window,
     * and the feed may already be in by then (the Home shown again). The list arriving in the
     * window is what starts the walkthrough in that case; no focus change will.
     */
    private final View.OnAttachStateChangeListener mListAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    watchWindowFocus();
                    maybeStart();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    unwatchWindowFocus();
                    pause();
                }
            };

    public WalkthroughHome(Activity activity, RecyclerView list, PostListAdapter adapter) {
        mActivity = activity;
        mContext = activity;
        mList = list;
        mAdapter = adapter;
    }

    /** The Home is showing. */
    public void attach() {
        if (mAttached) return;
        mAttached = true;
        mList.addOnAttachStateChangeListener(mListAttachListener);
        UgcSplash.addGoneObserver(mSplashGone);
        if (mList.isAttachedToWindow()) watchWindowFocus();
        maybeStart();
    }

    /** The Home has gone (another tab, the page was left): everything stops. */
    public void detach() {
        if (!mAttached) return;
        mAttached = false;
        mList.removeOnAttachStateChangeListener(mListAttachListener);
        UgcSplash.removeGoneObserver(mSplashGone);
        unwatchWindowFocus();
        pause();
        if (mPicker != null) mPicker.cancel();
        mPicker = null;
    }

    /** The feed's first page is in, so there are posts to feature. */
    public void onFeedReady() {
        mFeedReady = true;
        if (!feedReady()) return;
        // The featured post's checks run while the reader reads step 1.
        if (mAttached && WalkthroughState.isOnHome()) picker();
        if (mRunning && mWaitingForFeed) {
            mWaitingForFeed = false;
            mHandler.removeCallbacks(mFeedCheck);
            runScrollStep(false);
            return;
        }
        maybeStart();
    }

    private boolean feedReady() {
        return mFeedReady && !mAdapter.getPosts().isEmpty();
    }

    /**
     * Listens on the window's own observer. One taken while the list is out of a window is a
     * stand-in that is merged away on attach, so removing from it later would leave the listener
     * on the window.
     */
    private void watchWindowFocus() {
        if (mFocusObserver != null || !mList.isAttachedToWindow()) return;
        ViewTreeObserver observer = mList.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnWindowFocusChangeListener(mFocusListener);
        mFocusObserver = observer;
    }

    private void unwatchWindowFocus() {
        ViewTreeObserver observer = mFocusObserver;
        mFocusObserver = null;
        if (observer != null && observer.isAlive()) {
            observer.removeOnWindowFocusChangeListener(mFocusListener);
        }
    }

    private void maybeStart() {
        if (mRunning) return;
        String blocker = blocker();
        if (blocker != null) {
            // In release logs (cr_Walkthrough) for when a device never shows the walkthrough.
            if (mAttached && WalkthroughState.isOnHome()) {
                Log.i(TAG, "Home step %s waits: %s", WalkthroughState.step(), blocker);
            }
            return;
        }
        mHandler.removeCallbacks(mStart);
        mHandler.post(mStart);
    }

    private boolean canRun() {
        return blocker() == null;
    }

    /** What keeps the walkthrough off the Home right now, or null when it can show. */
    private @Nullable String blocker() {
        if (!mAttached) return "page gone";
        if (!WalkthroughState.isOnHome()) return "step " + WalkthroughState.step();
        // Step 1 points at the search bar only; the steps after it feature a post.
        boolean needsPosts = !WalkthroughState.STEP_SEARCH.equals(WalkthroughState.step());
        if (needsPosts && !feedReady()) return "no posts yet";
        if (!mList.isAttachedToWindow()) return "feed not in window";
        if (!mList.hasWindowFocus()) return "window not focused";
        if (UgcSplash.isShowing()) return "launch splash showing";
        if (mActivity.isFinishing()) return "activity finishing";
        if (WalkthroughState.isPickingPost()) return "reader picking a post";
        return null;
    }

    private void startCurrentStep() {
        if (!canRun() || mRunning) return;
        // Taken from the feed's first page; until that is in, onFeedReady starts it.
        if (feedReady()) picker();
        String step = WalkthroughState.step();
        mRunning = true;
        if (WalkthroughState.STEP_SEARCH.equals(step)) {
            showSearchStep(0);
        } else if (WalkthroughState.STEP_SCROLL.equals(step)) {
            // A scroll that was cut short runs again from the top.
            mList.scrollToPosition(0);
            mList.post(() -> runScrollStep(false));
        } else {
            resumePostStep();
        }
    }

    /** Takes the walkthrough off the Home until {@link #maybeStart} brings it back. */
    private void pause() {
        mHandler.removeCallbacksAndMessages(null);
        mWaitingForFeed = false;
        mTouchLock.release();
        if (!mRunning) return;
        mRunning = false;
        mAcknowledged = false;
        mList.stopScroll();
        removeOverlay();
    }

    // ---------------------------------------------------------------------------------------
    // Step 1: the search bar
    // ---------------------------------------------------------------------------------------

    private void showSearchStep(int attempt) {
        if (!mRunning) return;
        View box = mAdapter.getSearchBoxView();
        WalkthroughOverlay overlay = overlay();
        RectF hole = overlay.rectOf(box);
        if (hole == null || hole.top < 0) {
            // The feed isn't at its top, or the Home's first frame isn't laid out yet: bring the
            // bar into view first. The overlay already holds the Home meanwhile.
            if (attempt >= SEARCH_ATTEMPTS) {
                Log.i(TAG, "Search step gave up: search bar not on screen");
                pause();
                return;
            }
            mList.scrollToPosition(0);
            mHandler.postDelayed(() -> showSearchStep(attempt + 1), SEARCH_RETRY_MS);
            return;
        }
        mAcknowledged = false;
        mTouchLock.hold(overlay, WalkthroughMotion.STEP_TOUCH_LOCK_MS);
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_STEP_SHOWN, "step",
                WalkthroughState.STEP_SEARCH);
        float radius = hole.height() / 2f;

        WalkthroughShapes.SpotlightView dim = new WalkthroughShapes.SpotlightView(mContext);
        dim.setHole(hole, radius);
        overlay.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRing = addRing(overlay, hole, radius);

        WalkthroughShapes.SweepView sweep = new WalkthroughShapes.SweepView(mContext, 0.45f);
        sweep.setCornerRadius(radius);
        overlay.place(sweep, hole.left, hole.top, Math.round(hole.width()),
                Math.round(hole.height()));

        View view = LayoutInflater.from(mContext)
                            .inflate(R.layout.browser_express_walkthrough_search_card, overlay,
                                    false);
        WalkthroughCardFrame frame = (WalkthroughCardFrame) view;
        frame.configure(false, false, 16f, 0);
        frame.setCaretX(dp(31));
        ((HeroOutlineFrame) view.findViewById(R.id.walkthrough_hero)).configure(14f, 22f);
        overlay.place(view, hole.left - frame.cardOffsetX(),
                hole.bottom + dp(CARD_GAP_DP) - frame.cardOffsetY(),
                Math.round(hole.width()) + frame.getPaddingLeft() + frame.getPaddingRight(),
                ViewGroup.LayoutParams.WRAP_CONTENT);

        // The dim, ring and card come in together over 300–650ms, the card from 96% (README §3).
        fadeIn(dim, WalkthroughMotion.S1_CARD_IN_START, WalkthroughMotion.S1_CARD_IN_MS);
        fadeIn(mRing, WalkthroughMotion.S1_CARD_IN_START, WalkthroughMotion.S1_CARD_IN_MS);
        cardIn(frame, false, WalkthroughMotion.S1_CARD_IN_START);
        WordRevealLayout title = view.findViewById(R.id.walkthrough_title);
        title.hideWords();
        title.reveal(WalkthroughMotion.S1_TITLE_START);
        WalkthroughMotion.fadeIn(view.findViewById(R.id.walkthrough_lines),
                WalkthroughMotion.S1_SUB_START, WalkthroughMotion.FADE_MS, 0f);
        WalkthroughMotion.fadeIn(view.findViewById(R.id.walkthrough_hero),
                WalkthroughMotion.S1_HERO_START, WalkthroughMotion.FADE_MS, dp(8));
        View buttons = view.findViewById(R.id.walkthrough_buttons);
        WalkthroughMotion.fadeIn(buttons, WalkthroughMotion.S1_BUTTONS_START,
                WalkthroughMotion.FADE_MS, 0f);
        sweep.post(() -> sweep.play(WalkthroughMotion.S1_SWEEP_START,
                WalkthroughMotion.S1_SWEEP_END));

        view.findViewById(R.id.walkthrough_primary).setOnClickListener(v -> acknowledge("reveal"));
        view.findViewById(R.id.walkthrough_secondary).setOnClickListener(v -> acknowledge("skip"));
        view.findViewById(R.id.walkthrough_card).setOnClickListener(v -> acknowledge("card"));
        // The search bar moves the walkthrough on too; it doesn't open search meanwhile.
        overlay.addTapZone(hole, () -> acknowledge("search_bar"));

        View card = view.findViewById(R.id.walkthrough_card);
        announce(card, view.findViewById(R.id.walkthrough_primary),
                mContext.getString(R.string.browser_express_walkthrough_search_title),
                mContext.getString(R.string.browser_express_walkthrough_search_line),
                mContext.getString(R.string.browser_express_walkthrough_search_features),
                mContext.getString(R.string.browser_express_walkthrough_search_eyebrow),
                mContext.getString(R.string.browser_express_walkthrough_search_hero));
    }

    /** "Reveal it", Skip, the card or the bar: the ring pulses once, then step 2. */
    private void acknowledge(String how) {
        if (mAcknowledged || !mRunning) return;
        mAcknowledged = true;
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_ACTION, "step",
                WalkthroughState.STEP_SEARCH, "action", how);
        if (mRing != null) mRing.acknowledge();
        mHandler.postDelayed(() -> {
            if (!mRunning) return;
            WalkthroughState.setStep(WalkthroughState.STEP_SCROLL);
            runScrollStep(true);
        }, WalkthroughMotion.S1_ACK_PULSE_MS);
    }

    // ---------------------------------------------------------------------------------------
    // Step 2: the feed scrolls to the featured post
    // ---------------------------------------------------------------------------------------

    /** @param afterSearch whether step 1's prompt is still up, to fade out over the first 250ms. */
    private void runScrollStep(boolean afterSearch) {
        if (!mRunning) return;
        WalkthroughOverlay overlay = overlay();
        if (afterSearch) {
            fadeOutChildren(overlay, WalkthroughMotion.S1_DIM_OUT_MS);
        }
        if (!feedReady()) {
            // Step 1 was answered before the feed's first page came in. The overlay stays, clear,
            // over the feed's loading rows, and the scroll starts as the posts arrive.
            mWaitingForFeed = true;
            mFeedWaitStartMs = SystemClock.elapsedRealtime();
            mHandler.removeCallbacks(mFeedCheck);
            mHandler.postDelayed(mFeedCheck, FEED_CHECK_MS);
            return;
        }
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_STEP_SHOWN, "step",
                WalkthroughState.STEP_SCROLL);
        long startedAt = SystemClock.elapsedRealtime();
        // Touches are ignored until the scroll is done: the overlay stays, now clear.
        picker().whenReady(PICK_WAIT_MS, post -> {
            if (!mRunning) return;
            if (post == null) {
                pause();
                return;
            }
            mFeaturedId = post.getId();
            long waited = SystemClock.elapsedRealtime() - startedAt;
            long scrollAt = Math.max(0, WalkthroughMotion.S2_SCROLL_START - waited);
            mHandler.postDelayed(() -> scrollToFeatured(true), scrollAt);
            playScrollDot(overlay, scrollAt);
            mHandler.postDelayed(() -> {
                if (!mRunning) return;
                WalkthroughState.setStep(WalkthroughState.STEP_POST);
                showPostStep(0);
            }, Math.max(scrollAt + WalkthroughMotion.S2_SCROLL_MS,
                    WalkthroughMotion.S2_TOTAL_MS - waited));
        });
    }

    /** Gives the Home back while step 2 waits for a feed that failed or is taking too long. */
    private void checkFeedWait() {
        if (!mRunning || !mWaitingForFeed) return;
        if (mAdapter.isFeedLoadFailed()) {
            Log.i(TAG, "Scroll step waits: the feed failed to load");
            pause();
        } else if (SystemClock.elapsedRealtime() - mFeedWaitStartMs >= FEED_WAIT_MS) {
            Log.i(TAG, "Scroll step waits: the feed didn't load in time");
            pause();
        } else {
            mHandler.postDelayed(mFeedCheck, FEED_CHECK_MS);
        }
    }

    /**
     * Brings the featured post fully into view, its bottom 24dp above the bottom of the screen,
     * measured on the real viewport. A post too tall for that keeps its top in view instead.
     */
    private void scrollToFeatured(boolean animated) {
        if (!mRunning || mFeaturedId == null) return;
        int position = mAdapter.getAdapterPositionOfPost(mFeaturedId);
        if (position < 0) return;
        View card = mAdapter.getPostCardView(mFeaturedId);
        int gap = Math.round(dp(POST_BOTTOM_GAP_DP));
        Rect viewport = new Rect();
        if (!mList.getGlobalVisibleRect(viewport)) return;
        if (card != null && card.isAttachedToWindow()) {
            int[] location = new int[2];
            card.getLocationOnScreen(location);
            int[] listOnScreen = new int[2];
            mList.getLocationOnScreen(listOnScreen);
            int screenBottom = listOnScreen[1] + mList.getHeight();
            int top = location[1];
            int bottom = top + card.getHeight();
            int dy = bottom + gap - screenBottom;
            int maxDy = top - (topLimitOnScreen() + Math.round(dp(8)));
            dy = Math.min(dy, maxDy);
            if (dy == 0) return;
            if (animated && !WalkthroughMotion.reducedMotion()) {
                mList.smoothScrollBy(0, dy, WalkthroughMotion.EASE_IN_OUT,
                        (int) WalkthroughMotion.S2_SCROLL_MS);
            } else {
                mList.scrollBy(0, dy);
            }
            return;
        }
        // Not laid out yet, further down: scroll until it is, then settle it 24dp up from the end.
        if (!(mList.getLayoutManager() instanceof LinearLayoutManager)) return;
        if (!animated || WalkthroughMotion.reducedMotion()) {
            ((LinearLayoutManager) mList.getLayoutManager())
                    .scrollToPositionWithOffset(position, mList.getHeight() / 3);
            mList.post(() -> scrollToFeatured(false));
            return;
        }
        LinearSmoothScroller scroller = new LinearSmoothScroller(mContext) {
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_END;
            }

            @Override
            public int calculateDyToMakeVisible(View view, int snapPreference) {
                return super.calculateDyToMakeVisible(view, snapPreference) - gap;
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics metrics) {
                return 60f / metrics.densityDpi;
            }
        };
        scroller.setTargetPosition(position);
        mList.getLayoutManager().startSmoothScroll(scroller);
    }

    /** Where the toolbar ends on screen, so a tall post keeps its top below it. */
    private int topLimitOnScreen() {
        View toolbar = mActivity.findViewById(R.id.control_container);
        if (toolbar == null || !toolbar.isShown()) {
            int[] list = new int[2];
            mList.getLocationOnScreen(list);
            return list[1];
        }
        int[] location = new int[2];
        toolbar.getLocationOnScreen(location);
        return location[1] + toolbar.getHeight();
    }

    /**
     * A soft touch dot travels up the screen with the scroll, to show the gesture: in over the
     * 250ms before it, up with it, out over 300ms after.
     */
    private void playScrollDot(WalkthroughOverlay overlay, long scrollAt) {
        if (WalkthroughMotion.reducedMotion()) return;
        View dot = WalkthroughShapes.newTouchDot(mContext, 44f, 0.55f);
        float size = dp(44);
        float height = overlay.getHeight();
        // The reference's x 300, y 720 to 420, on a screen 402 wide and 893 tall.
        float x = overlay.getWidth() * 300f / 402f - size / 2f;
        float fromY = height * 720f / 893f - size / 2f;
        float toY = height * 420f / 893f - size / 2f;
        overlay.place(dot, x, fromY, Math.round(size), Math.round(size));
        dot.setAlpha(0f);
        long fadeMs = Math.min(scrollAt, WalkthroughMotion.S2_DOT_FADE_MS);
        dot.animate()
                .alpha(1f)
                .setStartDelay(scrollAt - fadeMs)
                .setDuration(fadeMs)
                .setInterpolator(WalkthroughMotion.EASE_OUT)
                .withEndAction(() -> dot.animate()
                        .translationY(toY - fromY)
                        .setStartDelay(0)
                        .setDuration(WalkthroughMotion.S2_SCROLL_MS)
                        .setInterpolator(WalkthroughMotion.EASE_IN_OUT)
                        .withEndAction(() -> WalkthroughMotion.fadeOut(dot,
                                WalkthroughMotion.S2_DOT_OUT_MS,
                                () -> overlay.removeView(dot))));
    }

    // ---------------------------------------------------------------------------------------
    // Step 3: the featured post
    // ---------------------------------------------------------------------------------------

    /** Back to step 3 after a pause or a relaunch: the post goes back into place first. */
    private void resumePostStep() {
        if (mFeaturedId != null && mAdapter.getAdapterPositionOfPost(mFeaturedId) >= 0) {
            placeFeaturedThenShow();
            return;
        }
        picker().whenReady(RESUME_PICK_WAIT_MS, post -> {
            if (!mRunning) return;
            if (post == null) {
                pause();
                return;
            }
            mFeaturedId = post.getId();
            placeFeaturedThenShow();
        });
    }

    private void placeFeaturedThenShow() {
        scrollToFeatured(false);
        // A second pass once the jump has laid the post out measures it on screen.
        mHandler.postDelayed(() -> {
            scrollToFeatured(false);
            mHandler.postDelayed(() -> showPostStep(0), PLACE_PASS_MS);
        }, PLACE_PASS_MS);
    }

    private void showPostStep(int attempt) {
        if (!mRunning || mFeaturedId == null) return;
        WalkthroughOverlay overlay = overlay();
        View card = mAdapter.getPostCardView(mFeaturedId);
        RectF hole = overlay.rectOf(card);
        if (hole == null) {
            if (attempt >= POST_ATTEMPTS) {
                Log.i(TAG, "Post step gave up: featured post not on screen");
                pause();
                return;
            }
            // The scroll hasn't brought it in yet.
            scrollToFeatured(false);
            mHandler.postDelayed(() -> showPostStep(attempt + 1), POST_RETRY_MS);
            return;
        }
        overlay.removeAllViews();
        overlay.clearTouchTargets();
        mTouchLock.hold(overlay, WalkthroughMotion.STEP_TOUCH_LOCK_MS);
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_STEP_SHOWN, "step",
                WalkthroughState.STEP_POST);
        float radius = dp(4);

        WalkthroughShapes.SpotlightView dim = new WalkthroughShapes.SpotlightView(mContext);
        dim.setHole(hole, radius);
        overlay.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRing = addRing(overlay, hole, radius);
        fadeIn(dim, 0, WalkthroughMotion.S3_RING_IN_MS);
        fadeIn(mRing, 0, WalkthroughMotion.S3_RING_IN_MS);

        WalkthroughShapes.SweepView sweep = new WalkthroughShapes.SweepView(mContext, 0.35f);
        sweep.setCornerRadius(radius);
        overlay.place(sweep, hole.left, hole.top, Math.round(hole.width()),
                Math.round(hole.height()));
        sweep.post(() -> sweep.play(WalkthroughMotion.S3_SWEEP_START,
                WalkthroughMotion.S3_SWEEP_END));

        // The hero card rises in above the post, anchored 14dp above it.
        View view = LayoutInflater.from(mContext)
                            .inflate(R.layout.browser_express_walkthrough_post_card, overlay,
                                    false);
        WalkthroughCardFrame frame = (WalkthroughCardFrame) view;
        frame.configure(true, false, 16f,
                ContextCompat.getColor(mContext, R.color.browser_express_walkthrough_wasabi));
        ((HeroOutlineFrame) view.findViewById(R.id.walkthrough_card)).configure(16f, 0f);
        int frameWidth = Math.round(hole.width()) + frame.getPaddingLeft()
                + frame.getPaddingRight();
        frame.measure(View.MeasureSpec.makeMeasureSpec(frameWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardHeight = frame.getMeasuredHeight() - frame.getPaddingTop()
                - frame.getPaddingBottom();
        float cardTop = hole.top - dp(CARD_GAP_DP) - cardHeight;
        // On a post that leaves no room above it, the card keeps clear of the status bar.
        cardTop = Math.max(cardTop, overlay.visibleFrame().top + dp(8));
        overlay.place(view, hole.left - frame.cardOffsetX(), cardTop - frame.cardOffsetY(),
                frameWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardIn(frame, true, WalkthroughMotion.S3_CARD_IN_START);
        WordRevealLayout title = view.findViewById(R.id.walkthrough_title);
        title.hideWords();
        title.reveal(WalkthroughMotion.S3_TITLE_START);
        WalkthroughMotion.fadeIn(view.findViewById(R.id.walkthrough_line),
                WalkthroughMotion.S3_SUB_START, WalkthroughMotion.FADE_MS, 0f);
        WalkthroughMotion.fadeIn(view.findViewById(R.id.walkthrough_buttons),
                WalkthroughMotion.S3_BUTTONS_START, WalkthroughMotion.FADE_MS, 0f);

        addTapDot(overlay, hole);

        view.findViewById(R.id.walkthrough_primary).setOnClickListener(v -> diveIn("dive_in"));
        view.findViewById(R.id.walkthrough_card).setOnClickListener(v -> diveIn("card"));
        view.findViewById(R.id.walkthrough_secondary).setOnClickListener(v -> pickAnother());
        overlay.addTapZone(hole, () -> diveIn("post"));

        announce(view.findViewById(R.id.walkthrough_card),
                view.findViewById(R.id.walkthrough_primary),
                mContext.getString(R.string.browser_express_walkthrough_post_title),
                mContext.getString(R.string.browser_express_walkthrough_post_line),
                mContext.getString(R.string.browser_express_walkthrough_post_eyebrow));
    }

    /** A touch dot pulsing over the post's play button (its media's centre). */
    private void addTapDot(WalkthroughOverlay overlay, RectF hole) {
        RectF anchor = overlay.rectOf(mAdapter.getPostTapAnchor(mFeaturedId));
        if (anchor == null) anchor = hole;
        View dot = WalkthroughShapes.newTouchDot(mContext, 44f, 0.55f);
        float size = dp(44);
        overlay.place(dot, anchor.centerX() - size / 2f, anchor.centerY() - size / 2f,
                Math.round(size), Math.round(size));
        dot.setAlpha(0f);
        dot.animate().alpha(1f).setStartDelay(WalkthroughMotion.S3_DOT_IN_START)
                .setDuration(300).setInterpolator(WalkthroughMotion.EASE_OUT)
                .withEndAction(() -> {
                    if (WalkthroughMotion.reducedMotion() || !dot.isAttachedToWindow()) return;
                    // wkTap: dims to half and shrinks to 86% and back, every 1.3s.
                    ObjectAnimator tap = ObjectAnimator.ofPropertyValuesHolder(dot,
                            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.5f),
                            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.86f),
                            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.86f));
                    tap.setDuration(WalkthroughMotion.S3_TAP_DOT_MS / 2);
                    tap.setRepeatCount(ValueAnimator.INFINITE);
                    tap.setRepeatMode(ValueAnimator.REVERSE);
                    tap.setInterpolator(WalkthroughMotion.CSS_EASE_IN_OUT);
                    tap.start();
                    mAnimators.add(tap);
                });
    }

    /** "Dive in", the card or the post: its stream opens, and step 4 runs there. */
    private void diveIn(String how) {
        String postId = mFeaturedId;
        if (!mRunning || postId == null) return;
        WalkthroughState.onPostOpened(how, postId);
        mRunning = false;
        removeOverlay();
        mAdapter.openPostStream(postId);
    }

    /** The prompt steps aside; the next post the reader opens runs step 4 (PostListAdapter). */
    private void pickAnother() {
        if (!mRunning) return;
        PostHogUtil.capture(PostHogEventKeys.WALKTHROUGH_ACTION, "step",
                WalkthroughState.STEP_POST, "action", "pick_another");
        WalkthroughState.setPickingPost(true);
        mRunning = false;
        // The Home is the reader's again at once.
        removeOverlay();
    }

    // ---------------------------------------------------------------------------------------

    private FeaturedPostPicker picker() {
        if (mPicker == null) {
            mPicker = new FeaturedPostPicker(mContext, mAdapter.getPosts());
            mPicker.start();
        }
        return mPicker;
    }

    private WalkthroughOverlay overlay() {
        if (mOverlay != null) return mOverlay;
        WalkthroughOverlay overlay = new WalkthroughOverlay(mContext, true);
        // Over the whole window, the toolbar included, and above anything raised in it.
        overlay.setOutlineProvider(null);
        overlay.setTranslationZ(dp(64));
        ViewGroup root = mActivity.findViewById(android.R.id.content);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.hideSiblingsFromAccessibility(root);
        mOverlay = overlay;
        return overlay;
    }

    private void removeOverlay() {
        mTouchLock.release();
        for (ValueAnimator animator : mAnimators) animator.cancel();
        mAnimators.clear();
        mRing = null;
        WalkthroughOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay == null) return;
        overlay.restoreAccessibility();
        overlay.clearTouchTargets();
        if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    private WalkthroughShapes.PulseRingView addRing(
            WalkthroughOverlay overlay, RectF hole, float radius) {
        WalkthroughShapes.PulseRingView ring = new WalkthroughShapes.PulseRingView(mContext);
        ring.setCornerRadius(radius);
        float margin = ring.margin();
        overlay.place(ring, hole.left - margin, hole.top - margin,
                Math.round(hole.width() + 2 * margin), Math.round(hole.height() + 2 * margin));
        return ring;
    }

    private static void fadeIn(View view, long delayMs, long durationMs) {
        WalkthroughMotion.fadeIn(view, delayMs, durationMs, 0f);
    }

    /** Every piece of the step fades out together. */
    private static void fadeOutChildren(WalkthroughOverlay overlay, long durationMs) {
        overlay.clearTouchTargets();
        for (int i = overlay.getChildCount() - 1; i >= 0; i--) {
            View child = overlay.getChildAt(i);
            WalkthroughMotion.fadeOut(child, durationMs, () -> overlay.removeView(child));
        }
    }

    /**
     * The card fades in and grows from 96% over 350ms: from its top edge when it hangs below its
     * target, from its bottom edge when it rises above it.
     */
    private void cardIn(WalkthroughCardFrame frame, boolean fromBottom, long delayMs) {
        frame.setAlpha(0f);
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or,
                    int ob) {
                v.removeOnLayoutChangeListener(this);
                v.setPivotX(v.getWidth() / 2f);
                v.setPivotY(fromBottom ? v.getHeight() - frame.getPaddingBottom()
                                       : frame.getPaddingTop());
            }
        });
        if (WalkthroughMotion.reducedMotion()) {
            frame.setAlpha(1f);
            return;
        }
        frame.setScaleX(WalkthroughMotion.CARD_START_SCALE);
        frame.setScaleY(WalkthroughMotion.CARD_START_SCALE);
        frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delayMs)
                .setDuration(WalkthroughMotion.S1_CARD_IN_MS)
                .setInterpolator(WalkthroughMotion.EASE_OUT);
    }

    /**
     * Screen readers hear the card (title, then its lines) and land on its primary button
     * (README §8). Moving their focus is deliberate: the overlay hides the rest of the window from
     * them, so their focus would otherwise be left on a view they can no longer reach.
     */
    @SuppressLint("AccessibilityFocus")
    private static void announce(View card, View primary, String... lines) {
        String text = String.join(". ", lines);
        card.setContentDescription(text);
        card.announceForAccessibility(text);
        primary.postDelayed(() -> {
            if (!primary.isAttachedToWindow()) return;
            primary.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        }, 500);
    }

    private float dp(float value) {
        return WalkthroughMotion.dp(mContext, value);
    }
}
