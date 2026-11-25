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
 *   <li>Text comes first. The page directly under a reel packs like any other, into the full
 *       viewport, and only the space its rows leave at the bottom goes to a preview of the video
 *       waiting below, so the reader can see a full-page video is coming. The preview is at most
 *       {@link #MAX_NEXT_REEL_PEEK_FRACTION} of the page, and a page with less room left than
 *       the smallest preview worth drawing gets none.
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
     * Most of the page the sneak peek may take on a slide that sits under a reel. It only ever
     * fills space the comments left, so it never costs a row.
     */
    public static final float MAX_NEXT_REEL_PEEK_FRACTION = 0.15f;

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
    /** Tallest the peek strip gets. */
    private final int mMaxPeekHeightPx;
    /** Leftover space shorter than this gets no peek strip at all. */
    private final int mMinPeekHeightPx;

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
        this(measurer, viewportHeightPx, pageHeightPx, maxCommentsPerSlide, lookaheadWindow, 0);
    }

    /**
     * @param viewportHeightPx height text rows can fill.
     * @param pageHeightPx height of a whole page, at least the viewport.
     * @param minPeekHeightPx smallest peek strip worth drawing; less room than this shows none.
     */
    public CommentSlidePacker(Measurer measurer, int viewportHeightPx, int pageHeightPx,
            int maxCommentsPerSlide, int lookaheadWindow, int minPeekHeightPx) {
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
        mMaxPeekHeightPx = Math.round(mPageHeightPx * MAX_NEXT_REEL_PEEK_FRACTION);
        mMinPeekHeightPx = Math.max(1, minPeekHeightPx);
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
                    // This page is about to become the one under a reel. Its comments fill it
                    // first; the preview of the reel only gets the space they leave.
                    pendingHeight = fillFromLookahead(
                            remaining, pending, pendingHeight, mViewportHeightPx);
                    int peek = peekHeightFor(pendingHeight);
                    slides.add(new CommentSlide(CommentSlide.TYPE_TEXT, pending, mPageHeightPx,
                            peek > 0 ? comment : null, peek));
                    pending = new ArrayList<>();
                    pendingHeight = 0;
                }
                slides.add(reelPage(comment));
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
     * The page {@link #pack} gives a portrait video: the video alone, filling the page.
     *
     * <p>A stream that opens on one starts {@code pack(comments)} with exactly this page, followed
     * by {@code pack(comments.subList(1, n))}: nothing is pending ahead of the first comment, so
     * nothing is pulled forward past it or previews it. The pager relies on that to show the
     * opening reel before it has measured the rest.
     */
    public CommentSlide reelPage(Comment reel) {
        // The reel row fills its page regardless of measured height, so the measurement only
        // keeps the Item's fields honest.
        return new CommentSlide(CommentSlide.TYPE_REEL,
                Collections.singletonList(mMeasurer.measure(reel)), mPageHeightPx);
    }

    /**
     * The peek strip for a page whose rows take {@code rowsHeightPx}: the room left under them,
     * up to {@link #mMaxPeekHeightPx}, or 0 when that room is under {@link #mMinPeekHeightPx}.
     */
    private int peekHeightFor(int rowsHeightPx) {
        int peek = Math.min(mMaxPeekHeightPx, mViewportHeightPx - rowsHeightPx);
        return peek >= mMinPeekHeightPx ? peek : 0;
    }

    /**
     * Tops up the page being closed with upcoming comments that fit its leftover space, pulling
     * them out of {@code remaining}. Scans at most {@link #mLookaheadWindow} comments; portrait
     * videos are never pulled (they own their pages). Pulled comments keep their relative order.
     *
     * @param budgetPx height the page has to fill: the full viewport.
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
