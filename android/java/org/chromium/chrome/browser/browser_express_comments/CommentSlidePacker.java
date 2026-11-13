/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Groups a flat comment list into the full-screen pages of the reel-style pager.
 *
 * <p>The rules:
 * <ul>
 *   <li>A portrait video gets a page to itself and is rendered TikTok-style, full-bleed. It is
 *       the only comment kind that does; a video whose dimensions are not known yet packs inline
 *       and is promoted to its own page by the re-pack that follows the dimension probe.
 *   <li>An image or a landscape video packs inline like text, drawn as a 16:9 media card row.
 *   <li>An audio comment packs inline like text: a voice note draws as a fixed-height player
 *       row, and a whole page given over to one would be almost entirely empty.
 *   <li>Inline comments are packed greedily until the next one would overflow the viewport, or
 *       until {@link #DEFAULT_MAX_COMMENTS_PER_SLIDE} is reached — whichever comes first.
 *   <li>Before a page is closed with space left over — because the next comment is too tall, or
 *       a portrait video is about to take its own page — the packer looks ahead up to
 *       {@link #DEFAULT_LOOKAHEAD_WINDOW} comments and pulls forward any that fit. Score order
 *       is deliberately bent by at most that window, which is invisible in a page-snapping UI
 *       but keeps pages from ending half blank. Comments that are not pulled keep their order.
 *   <li>The page directly under a reel packs into a viewport short by
 *       {@link #NEXT_REEL_PEEK_FRACTION} of the page: that bottom strip is handed to a preview of
 *       the video waiting below, so the reader can see a full-page video is coming. Rows that no
 *       longer fit are pushed back into the queue and start the page that ends up carrying the
 *       strip.
 * </ul>
 *
 * <p>This is a pure function of (comments, viewport height, measurer), so re-packing after a
 * rotation or font-scale change is just another {@link #pack} call, and packing is deterministic:
 * the same inputs always produce the same pages.
 */
public class CommentSlidePacker {
    /**
     * Cap on how many comments share one text page, now that pager rows clamp to two lines. The
     * viewport usually runs out first; the cap keeps a page of one-liners from turning into an
     * endless feed.
     */
    public static final int DEFAULT_MAX_COMMENTS_PER_SLIDE = 8;

    /** How far ahead {@link #pack} may reach to fill leftover space on the page being closed. */
    public static final int DEFAULT_LOOKAHEAD_WINDOW = 10;

    /**
     * Share of the page the sneak peek takes on a slide that sits under a reel. Enough of the
     * video to read as a video and as "there is another page", small enough that the comments
     * above it still own the screen.
     */
    public static final float NEXT_REEL_PEEK_FRACTION = 0.15f;

    /** Supplies the rendered size of a single comment row. */
    public interface Measurer {
        /**
         * Measures one comment as it would be drawn on a text page, truncating the content if it
         * is too long to fit.
         */
        CommentSlide.Item measure(Comment comment);
    }

    private final Measurer mMeasurer;
    private final int mViewportHeightPx;
    /**
     * Height of a whole page. Rows get less than this ({@link #mViewportHeightPx}) because the
     * comments header sits over the top of text pages; a reel, which runs under the header, and
     * the peek strip's frame are sized to the whole page.
     */
    private final int mPageHeightPx;
    private final int mMaxCommentsPerSlide;
    private final int mLookaheadWindow;
    /** Height of the peek strip, and so the height the page under a reel loses. */
    private final int mPeekHeightPx;

    public CommentSlidePacker(Measurer measurer, int viewportHeightPx) {
        this(measurer, viewportHeightPx, DEFAULT_MAX_COMMENTS_PER_SLIDE);
    }

    public CommentSlidePacker(Measurer measurer, int viewportHeightPx, int maxCommentsPerSlide) {
        this(measurer, viewportHeightPx, maxCommentsPerSlide, DEFAULT_LOOKAHEAD_WINDOW);
    }

    public CommentSlidePacker(Measurer measurer, int viewportHeightPx, int maxCommentsPerSlide,
            int lookaheadWindow) {
        this(measurer, viewportHeightPx, viewportHeightPx, maxCommentsPerSlide, lookaheadWindow);
    }

    /**
     * @param viewportHeightPx height text rows can fill.
     * @param pageHeightPx height of a whole page, at least the viewport.
     */
    public CommentSlidePacker(Measurer measurer, int viewportHeightPx, int pageHeightPx,
            int maxCommentsPerSlide, int lookaheadWindow) {
        if (measurer == null) {
            throw new IllegalArgumentException("measurer must not be null");
        }
        if (viewportHeightPx <= 0) {
            throw new IllegalArgumentException("viewportHeightPx must be positive");
        }
        if (maxCommentsPerSlide <= 0) {
            throw new IllegalArgumentException("maxCommentsPerSlide must be positive");
        }
        if (lookaheadWindow < 0) {
            throw new IllegalArgumentException("lookaheadWindow must not be negative");
        }
        mMeasurer = measurer;
        mViewportHeightPx = viewportHeightPx;
        mPageHeightPx = Math.max(pageHeightPx, viewportHeightPx);
        mMaxCommentsPerSlide = maxCommentsPerSlide;
        mLookaheadWindow = lookaheadWindow;
        mPeekHeightPx = Math.round(mPageHeightPx * NEXT_REEL_PEEK_FRACTION);
    }

    /**
     * Splits {@code comments} into pages. Every input comment lands on exactly one page; order
     * is preserved except where the lookahead pulls a fitting comment forward.
     */
    public List<CommentSlide> pack(List<Comment> comments) {
        List<CommentSlide> slides = new ArrayList<>();
        if (comments == null || comments.isEmpty()) {
            return slides;
        }

        // Work queue: the head is the next comment in score order; the lookahead may pluck
        // fitting comments out from just behind it.
        List<Comment> remaining = new ArrayList<>();
        for (Comment comment : comments) {
            if (comment != null) remaining.add(comment);
        }

        List<CommentSlide.Item> pending = new ArrayList<>();
        int pendingHeight = 0;

        while (!remaining.isEmpty()) {
            Comment comment = remaining.remove(0);

            if (comment.isPortraitVideo()) {
                if (!pending.isEmpty()) {
                    // This page is about to become the one under a reel, so its usable height
                    // drops by the peek strip.
                    int budget = mViewportHeightPx - mPeekHeightPx;
                    List<Comment> displaced = trimToBudget(pending, budget);
                    if (!displaced.isEmpty()) {
                        // The rows that no longer fit start a fresh page — and since the reel
                        // still follows them, that page is the one that gets the strip. Both go
                        // back on the queue, displaced rows first, and the loop re-runs this
                        // branch for them.
                        remaining.add(0, comment);
                        remaining.addAll(0, displaced);
                        slides.add(new CommentSlide(
                                CommentSlide.TYPE_TEXT, pending, mPageHeightPx));
                        pending = new ArrayList<>();
                        pendingHeight = 0;
                        continue;
                    }
                    pendingHeight = fillFromLookahead(remaining, pending, pendingHeight, budget);
                    slides.add(new CommentSlide(CommentSlide.TYPE_TEXT, pending,
                            mPageHeightPx, comment, mPeekHeightPx));
                    pending = new ArrayList<>();
                    pendingHeight = 0;
                }
                // The reel row fills its page regardless of measured height, so the measurement
                // only keeps the Item's fields honest.
                slides.add(new CommentSlide(CommentSlide.TYPE_REEL,
                        Collections.singletonList(mMeasurer.measure(comment)),
                        mPageHeightPx));
                continue;
            }

            CommentSlide.Item item = mMeasurer.measure(comment);

            boolean roomOnPage = pendingHeight + item.getHeightPx() <= mViewportHeightPx
                    && pending.size() < mMaxCommentsPerSlide;
            // An empty page always accepts the comment, even one taller than the viewport. The
            // measurer truncates, so that should not happen — but dropping a comment or looping
            // forever would be worse than a page that overflows slightly.
            if (!pending.isEmpty() && !roomOnPage) {
                pendingHeight =
                        fillFromLookahead(remaining, pending, pendingHeight, mViewportHeightPx);
                slides.add(new CommentSlide(CommentSlide.TYPE_TEXT, pending, mPageHeightPx));
                pending = new ArrayList<>();
                pendingHeight = 0;
            }

            pending.add(item);
            pendingHeight += item.getHeightPx();
        }

        if (!pending.isEmpty()) {
            slides.add(new CommentSlide(CommentSlide.TYPE_TEXT, pending, mPageHeightPx));
        }
        return slides;
    }

    /**
     * Drops trailing rows until the page fits {@code budgetPx}, returning their comments in their
     * original order so the caller can put them back at the head of the queue. The first row is
     * never dropped: a single row taller than the budget has nowhere better to go, and dropping
     * it would leave an empty page and loop forever.
     */
    private List<Comment> trimToBudget(List<CommentSlide.Item> pending, int budgetPx) {
        int height = 0;
        for (CommentSlide.Item item : pending) {
            height += item.getHeightPx();
        }
        List<Comment> displaced = new ArrayList<>();
        while (pending.size() > 1 && height > budgetPx) {
            CommentSlide.Item last = pending.remove(pending.size() - 1);
            height -= last.getHeightPx();
            displaced.add(0, last.getComment());
        }
        return displaced;
    }

    /**
     * Tops up the page being closed with upcoming comments that fit its leftover space, pulling
     * them out of {@code remaining}. Scans at most {@link #mLookaheadWindow} comments; portrait
     * videos are never pulled (they own their pages). Pulled comments keep their relative order.
     *
     * @param budgetPx height the page has to fill — the full viewport, or that minus the peek
     *     strip when a reel follows.
     * @return the pending height after any pulls.
     */
    private int fillFromLookahead(List<Comment> remaining, List<CommentSlide.Item> pending,
            int pendingHeight, int budgetPx) {
        int scanned = 0;
        Iterator<Comment> iterator = remaining.iterator();
        while (iterator.hasNext() && scanned < mLookaheadWindow
                && pending.size() < mMaxCommentsPerSlide) {
            Comment candidate = iterator.next();
            scanned++;
            if (candidate.isPortraitVideo()) continue;

            CommentSlide.Item item = mMeasurer.measure(candidate);
            if (pendingHeight + item.getHeightPx() <= budgetPx) {
                pending.add(item);
                pendingHeight += item.getHeightPx();
                iterator.remove();
            }
        }
        return pendingHeight;
    }
}
