/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;

import java.util.HashSet;
import java.util.Set;

/**
 * The publishing banner at the top of the comments (design handoff "Add your take" §2.5), for a
 * take or a reply on its way: uploading with its progress, posted with View (and a small × to
 * dismiss), or failed with Retry. It stays across page swipes; a posted banner goes after 4s and
 * a failed one after 5s (the card keeps its Retry). Any of them can be swiped away in any
 * direction ({@link SwipeDismissLayout}); a swiped-away upload stays hidden until it posts or
 * fails.
 *
 * <p>A posted banner belongs to its comment: once the reader taps View or ×, or swipes it away,
 * that comment's banner never shows again, the dismissal kept on the device.
 */
final class TakeBanner {
    private static final long POSTED_MS = 4000;
    private static final long FAILED_MS = 5000;
    private static final long ANIMATION_MS = 300;
    private static final float SLIDE_DP = 14f;
    private static final String PREFS = "browser_express_take_banner";
    private static final String PREF_DISMISSED = "dismissed";
    /** Enough to cover any comment still being talked about; the oldest go first. */
    private static final int MAX_DISMISSED = 200;

    private final SwipeDismissLayout mBanner;
    private final View mDot;
    private final TextView mTitle;
    private final TextView mSub;
    private final TextView mAction;
    private final View mClose;
    private final ProgressBar mProgress;
    private final float mDp;
    private final Runnable mHide = this::hide;
    private @Nullable String mPostedId;
    /** The upload the banner is showing, while it shows one. */
    private @Nullable String mUploadingId;
    /** The upload the reader swiped away; its progress stays hidden. */
    private @Nullable String mSwipedUploadId;
    /** How long the current state stays up on its own; 0 while it should stay. */
    private long mTimeoutMs;

    TakeBanner(SwipeDismissLayout banner) {
        mBanner = banner;
        mDot = banner.findViewById(R.id.take_banner_dot);
        mTitle = banner.findViewById(R.id.take_banner_title);
        mSub = banner.findViewById(R.id.take_banner_sub);
        mAction = banner.findViewById(R.id.take_banner_action);
        mClose = banner.findViewById(R.id.take_banner_close);
        mProgress = banner.findViewById(R.id.take_banner_progress);
        mDp = banner.getResources().getDisplayMetrics().density;
        banner.setClipToOutline(true);
        mClose.setOnClickListener(v -> dismissPosted());
        banner.setListener(new SwipeDismissLayout.Listener() {
            @Override
            public void onSwipeStarted() {
                // Not while it is in the reader's hand.
                mBanner.removeCallbacks(mHide);
            }

            @Override
            public void onSwipeCancelled() {
                scheduleHide();
            }

            @Override
            public void onSwipedAway() {
                if (mPostedId != null) markDismissed(mPostedId);
                if (mUploadingId != null) mSwipedUploadId = mUploadingId;
                hideNow();
            }
        });
    }

    /**
     * Upload {@code uploadId}, a take or reply with files, on its way: "{pct}% · You can keep
     * scrolling". Nothing shows once the reader has swiped this upload's banner away.
     */
    void showUploading(String uploadId, boolean video, float progress) {
        if (uploadId.equals(mSwipedUploadId)) return;
        mPostedId = null;
        mUploadingId = uploadId;
        mTimeoutMs = 0;
        int percent = Math.round(Math.max(0f, Math.min(1f, progress)) * 100);
        bind(R.color.browser_express_take_status_uploading,
                video ? R.string.browser_express_take_uploading_reel
                      : R.string.browser_express_take_uploading_photo,
                context().getString(R.string.browser_express_take_uploading_progress, percent),
                0, null, false);
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setProgress(Math.round(Math.max(0f, progress) * 1000));
        show();
    }

    /**
     * Posted, with View going to {@code commentId}'s card or reel. {@code sub} says where it
     * went. Nothing shows if the reader already dismissed this comment's banner.
     */
    void showPosted(String commentId, boolean reel, CharSequence sub, Runnable onView) {
        if (isDismissed(commentId)) {
            hide();
            return;
        }
        mPostedId = commentId;
        mUploadingId = null;
        mTimeoutMs = POSTED_MS;
        bind(R.color.browser_express_take_status_posted,
                reel ? R.string.browser_express_take_posted_reel
                     : R.string.browser_express_take_posted_comment,
                sub, R.string.browser_express_take_view, v -> {
                    dismissPosted();
                    onView.run();
                }, true);
        mProgress.setVisibility(View.GONE);
        show();
    }

    /**
     * Couldn't post: the banner and Retry both try again. It goes after a few seconds; the card
     * keeps its own Retry. A retry shows its progress again even if the upload was swiped away.
     *
     * @param reason what the server said was wrong ("An attachment is too large."), shown in
     *     place of "Tap to retry"; null when it said nothing (a dropped connection, say).
     */
    void showFailed(@Nullable CharSequence reason, Runnable onRetry) {
        mPostedId = null;
        mUploadingId = null;
        mSwipedUploadId = null;
        mTimeoutMs = FAILED_MS;
        View.OnClickListener retry = v -> onRetry.run();
        bind(R.color.browser_express_take_status_failed, R.string.browser_express_take_failed,
                reason != null && reason.length() > 0
                        ? reason
                        : context().getString(R.string.browser_express_take_failed_body),
                R.string.browser_express_take_retry, retry, false);
        mProgress.setVisibility(View.GONE);
        mBanner.setOnClickListener(retry);
        show();
    }

    void hide() {
        mBanner.removeCallbacks(mHide);
        mPostedId = null;
        mUploadingId = null;
        if (mBanner.getVisibility() != View.VISIBLE || mBanner.isSwiping()) return;
        mBanner.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mBanner.setVisibility(View.GONE);
            return;
        }
        mBanner.animate().alpha(0f).translationY(-SLIDE_DP * mDp).setDuration(ANIMATION_MS)
                .withEndAction(() -> mBanner.setVisibility(View.GONE));
    }

    /** Gone at once, e.g. after being swiped off; ready to show again from where it sits. */
    private void hideNow() {
        mBanner.removeCallbacks(mHide);
        mBanner.animate().cancel();
        mPostedId = null;
        mUploadingId = null;
        mBanner.setVisibility(View.GONE);
        mBanner.setTranslationX(0f);
        mBanner.setTranslationY(0f);
        mBanner.setAlpha(1f);
    }

    private void scheduleHide() {
        mBanner.removeCallbacks(mHide);
        if (mTimeoutMs > 0 && mBanner.getVisibility() == View.VISIBLE && !mBanner.isSwiping()) {
            mBanner.postDelayed(mHide, mTimeoutMs);
        }
    }

    private void dismissPosted() {
        if (mPostedId != null) markDismissed(mPostedId);
        hide();
    }

    private void bind(int dotColor, int titleId, CharSequence sub, int actionId,
            @Nullable View.OnClickListener action, boolean closable) {
        Context context = context();
        // Only a failed banner is a button as a whole (see showFailed).
        mBanner.setOnClickListener(null);
        mBanner.setClickable(false);
        mDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColor)));
        mTitle.setText(titleId);
        mSub.setText(sub);
        mAction.setVisibility(action != null ? View.VISIBLE : View.GONE);
        if (action != null) {
            mAction.setText(actionId);
            mAction.setOnClickListener(action);
        }
        mClose.setVisibility(closable ? View.VISIBLE : View.GONE);
    }

    private void show() {
        // Mid-swipe the new text is enough: the swipe decides where the banner goes.
        if (mBanner.isSwiping()) return;
        boolean settled = mBanner.getTranslationX() == 0f && mBanner.getTranslationY() == 0f;
        if (mBanner.getVisibility() == View.VISIBLE && mBanner.getAlpha() == 1f && settled) {
            scheduleHide();
            return;
        }
        mBanner.animate().cancel();
        mBanner.setVisibility(View.VISIBLE);
        mBanner.setTranslationX(0f);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mBanner.setAlpha(1f);
            mBanner.setTranslationY(0f);
        } else {
            mBanner.setAlpha(0f);
            mBanner.setTranslationY(-SLIDE_DP * mDp);
            mBanner.animate().alpha(1f).translationY(0f).setDuration(ANIMATION_MS);
        }
        scheduleHide();
    }

    private Context context() {
        return mBanner.getContext();
    }

    private static boolean isDismissed(String commentId) {
        return prefs().getStringSet(PREF_DISMISSED, new HashSet<>()).contains(commentId);
    }

    private static void markDismissed(String commentId) {
        Set<String> dismissed = new HashSet<>(prefs().getStringSet(PREF_DISMISSED, new HashSet<>()));
        // A set keeps no order, so when full it simply starts again; a stale entry only ever
        // hides a banner whose 4s are long gone.
        if (dismissed.size() >= MAX_DISMISSED) dismissed.clear();
        dismissed.add(commentId);
        prefs().edit().putStringSet(PREF_DISMISSED, dismissed).apply();
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(PREFS, 0);
    }
}
