/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

/**
 * Everything a comment row needs from the screen hosting it.
 *
 * <p>Comment rows are shown by two different surfaces — the bottom sheet (still used for YouTube
 * watch pages, where covering the video would be wrong) and the full-screen reel pager. This is
 * what lets {@link CommentListAdapter} bind into either one.
 *
 * <p>{@code getChildFragmentManager()} and {@code isAdded()} are declared here but not implemented
 * by anyone: both hosts are Fragments, which already provide them.
 */
public interface CommentActionHost {
    void hideKeyboard();

    void showKeyboardWithFocus();

    void openReplies(String commentId, String parentCommentJson);

    void openRepliesToReply(String commentId, String parentCommentJson);

    /** Retry on a comment this reader is sending whose upload failed. */
    void retryUpload(String commentId);

    /** Cancel on a comment this reader is sending while its files upload. */
    void cancelUpload(String commentId);

    /**
     * The reader just added a reaction (or switched to another emoji) on {@code row}, a card or,
     * when {@code overReel}, a reel page: their Karma moment plays after the burst.
     */
    void onReactionAdded(View row, boolean overReel);

    /** Opens "Add your take", a new take on what the comments are about (never a reply). */
    void openTakeComposer();

    /** What the way into "Add your take" says to accessibility: "Add your take to {topic}". */
    CharSequence takeEntryLabel();

    /** The post's hashtag with its "#" ("#KohliKaKamaal"); null when it has none, or for a page. */
    @Nullable
    String topicHashtag();

    FragmentManager getChildFragmentManager();

    boolean isAdded();
}
