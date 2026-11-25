/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_comments.BrowserExpressGetCommentsUtil;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import org.chromium.chrome.browser.browser_express_comments.CommentVideoDimensions;
import org.chromium.chrome.browser.ntp.Post;
import org.chromium.chrome.browser.ntp.PostListAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the post the walkthrough features in steps 2 and 3 (README §3, step 3): the one the
 * server marks ({@code walkthroughPostId} on the feed), else the first post near the top of the
 * feed whose stream starts with a vertical video, so step 4 can run as soon as it opens.
 *
 * <p>Finding that out takes each candidate's first take and its video's shape, which the app may
 * have to probe. The checks start with step 1 and run while the reader reads it; if they aren't
 * done when step 2 needs an answer, the best guess so far wins.
 */
final class FeaturedPostPicker {
    interface Callback {
        void onPicked(@Nullable Post post);
    }

    /** How far down the feed to look. */
    private static final int CANDIDATES = 6;

    private static final int PENDING = 0;
    /** Its stream opens on a vertical video. */
    private static final int PORTRAIT = 1;
    /** It opens on a video of unknown or landscape shape. */
    private static final int VIDEO = 2;
    private static final int OTHER = 3;

    private final Context mContext;
    private final List<Post> mCandidates = new ArrayList<>();
    private final int[] mResults;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private @Nullable Post mServerChoice;
    private @Nullable Callback mWaiting;
    private boolean mStarted;
    private boolean mCancelled;
    private final Runnable mDeadline = this::deliver;

    FeaturedPostPicker(Context context, List<Post> posts) {
        mContext = context;
        for (Post post : posts) {
            if (post == null) continue;
            if (post.isWalkthroughFeatured() && mServerChoice == null) mServerChoice = post;
            if (mCandidates.size() < CANDIDATES) mCandidates.add(post);
        }
        mResults = new int[mCandidates.size()];
    }

    /** Starts looking at the candidates' streams, unless the server already chose. */
    void start() {
        if (mStarted) return;
        mStarted = true;
        if (mServerChoice != null) return;
        String token = accessToken();
        for (int i = 0; i < mCandidates.size(); i++) {
            final int index = i;
            new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(null, null,
                    mCandidates.get(i).getId(), 1, 1, token,
                    new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                        @Override
                        public void getCommentsSuccessful(List<Comment> comments,
                                Comment parentComment, Comment grandParentComment) {
                            onFirstTake(index,
                                    comments == null || comments.isEmpty() ? null
                                                                           : comments.get(0));
                        }

                        @Override
                        public void getCommentsFailed(String error) {
                            resolve(index, OTHER);
                        }
                    })
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    private void onFirstTake(int index, @Nullable Comment first) {
        if (mCancelled) return;
        if (first == null || !first.hasVideoMedia()) {
            resolve(index, OTHER);
            return;
        }
        if (CommentVideoDimensions.applyCached(first)) {
            resolve(index, first.isPortraitVideo() ? PORTRAIT : VIDEO);
            return;
        }
        boolean pending = CommentVideoDimensions.fetchAsync(mContext, first,
                () -> resolve(index, first.isPortraitVideo() ? PORTRAIT : VIDEO));
        if (!pending) resolve(index, VIDEO);
    }

    private void resolve(int index, int result) {
        if (mCancelled || mResults[index] != PENDING) return;
        mResults[index] = result;
        if (mWaiting != null && isDecided()) deliver();
    }

    /**
     * Calls back with the featured post once it is known, or after {@code maxWaitMs} with the
     * best guess so far. Null only when there are no posts.
     */
    void whenReady(long maxWaitMs, Callback callback) {
        start();
        mWaiting = callback;
        if (isDecided()) {
            deliver();
        } else {
            mHandler.postDelayed(mDeadline, maxWaitMs);
        }
    }

    void cancel() {
        mCancelled = true;
        mWaiting = null;
        mHandler.removeCallbacks(mDeadline);
    }

    private boolean isDecided() {
        if (mServerChoice != null) return true;
        for (int result : mResults) {
            if (result == PENDING) return false; // An earlier post may still win.
            if (result == PORTRAIT) return true;
        }
        return true;
    }

    private void deliver() {
        mHandler.removeCallbacks(mDeadline);
        Callback callback = mWaiting;
        mWaiting = null;
        if (callback != null && !mCancelled) callback.onPicked(pick());
    }

    private @Nullable Post pick() {
        if (mServerChoice != null) return mServerChoice;
        for (int i = 0; i < mResults.length; i++) {
            if (mResults[i] == PORTRAIT) return mCandidates.get(i);
        }
        for (int i = 0; i < mResults.length; i++) {
            if (mResults[i] == VIDEO) return mCandidates.get(i);
        }
        // Nothing heard back: a post with a video of its own is the likeliest to open on one.
        for (Post post : mCandidates) {
            if (PostListAdapter.hasCardVideo(post)) return post;
        }
        return mCandidates.isEmpty() ? null : mCandidates.get(0);
    }

    private static @Nullable String accessToken() {
        try {
            return BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return null;
        }
    }
}
