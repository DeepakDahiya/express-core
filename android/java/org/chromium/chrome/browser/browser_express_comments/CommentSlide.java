/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One full-screen page in the reel-style comment pager.
 *
 * <p>A slide is either a run of comments packed to fill the viewport ({@link #TYPE_TEXT} — plain
 * text, voice notes, and image or landscape-video comments drawn as inline media cards), or a
 * single portrait-video comment rendered TikTok-style with the video owning the whole page
 * ({@link #TYPE_REEL}). Slides are built by {@link CommentSlidePacker} and are immutable.
 *
 * <p>A text slide that sits directly under a reel also carries that reel's comment and a strip
 * height: its bottom band shows the top of the video waiting below, so the swipe up is something
 * the reader chooses rather than stumbles into. See {@link #getNextReelComment()}.
 */
public class CommentSlide {
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_REEL = 1;

    /**
     * A comment as it will actually be rendered on a slide.
     *
     * <p>The packer measures every comment up front, so the view layer never has to guess: it
     * renders {@link #getDisplayText()} verbatim and can trust {@link #getHeightPx()}.
     */
    public static class Item {
        private final Comment mComment;
        private final CharSequence mDisplayText;
        private final boolean mTruncated;
        private final int mHeightPx;

        public Item(Comment comment, CharSequence displayText, boolean truncated, int heightPx) {
            mComment = comment;
            mDisplayText = displayText;
            mTruncated = truncated;
            mHeightPx = heightPx;
        }

        public Comment getComment() {
            return mComment;
        }

        /** Content to render. Already cut down to fit when {@link #isTruncated()} is true. */
        public CharSequence getDisplayText() {
            return mDisplayText;
        }

        public boolean isTruncated() {
            return mTruncated;
        }

        /** Measured height of the whole comment row, in pixels. */
        public int getHeightPx() {
            return mHeightPx;
        }
    }

    private final int mType;
    private final List<Item> mItems;
    private final int mHeightPx;
    private final int mPageHeightPx;
    private final Comment mNextReelComment;
    private final int mPeekHeightPx;

    CommentSlide(int type, List<Item> items, int pageHeightPx) {
        this(type, items, pageHeightPx, null, 0);
    }

    CommentSlide(int type, List<Item> items, int pageHeightPx, Comment nextReelComment,
            int peekHeightPx) {
        mType = type;
        mItems = Collections.unmodifiableList(new ArrayList<>(items));
        int height = 0;
        for (Item item : mItems) {
            height += item.getHeightPx();
        }
        mHeightPx = height;
        mPageHeightPx = pageHeightPx;
        mNextReelComment = nextReelComment;
        mPeekHeightPx = nextReelComment != null ? peekHeightPx : 0;
    }

    /** {@link #TYPE_TEXT} or {@link #TYPE_REEL}. */
    public int getType() {
        return mType;
    }

    public boolean isReel() {
        return mType == TYPE_REEL;
    }

    public List<Item> getItems() {
        return mItems;
    }

    public int getItemCount() {
        return mItems.size();
    }

    public Item getItem(int index) {
        return mItems.get(index);
    }

    /**
     * Combined height of this slide's rows. Smaller than the viewport whenever the next comment
     * would not have fit — the remaining space is intentionally left blank.
     */
    public int getHeightPx() {
        return mHeightPx;
    }

    /** The portrait-video comment a reel slide is built around, or null on a text slide. */
    public Comment getReelComment() {
        return mType == TYPE_REEL && !mItems.isEmpty() ? mItems.get(0).getComment() : null;
    }

    /**
     * Height the rows were packed into — the page less the list's top padding, which is what a
     * slide actually has to fill. The peek strip also frames its video against this rather than
     * the page's exact height; the padding's worth of difference moves the crop by about a
     * pixel.
     */
    public int getPageHeightPx() {
        return mPageHeightPx;
    }

    /**
     * The portrait-video comment on the page immediately after this one, or null when nothing
     * is coming or the next page is not a reel. Present means this slide gives up its bottom
     * {@link #getPeekHeightPx()} pixels to a preview of that video.
     */
    public Comment getNextReelComment() {
        return mNextReelComment;
    }

    /**
     * Height of the sneak-peek strip along the bottom of this slide, or 0 when there is none.
     * The packer has already kept the rows clear of it, so the strip never covers a comment.
     */
    public int getPeekHeightPx() {
        return mPeekHeightPx;
    }

    /**
     * Id of this slide's first comment. Stable across re-packs (rotation, font-scale change), so
     * it is what scroll restore should anchor on rather than a slide index.
     */
    public String getAnchorCommentId() {
        return mItems.isEmpty() ? null : mItems.get(0).getComment().getId();
    }
}
