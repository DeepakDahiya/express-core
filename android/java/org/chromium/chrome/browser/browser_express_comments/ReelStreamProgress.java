/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

import java.text.NumberFormat;

/**
 * Where the reader is in the stream, shown briefly under a reel page's hashtag
 * (design_handoff_video_progress, option 1b). It is a 3dp line and a counter such as "38 / 214",
 * using the same numbers as the always-visible header on text pages.
 *
 * <p>Each time a video page becomes the reader's page, the row fades in, the line and counter move
 * from the previous page's position to this one, and about 2.7s later the row fades out. Only its
 * opacity changes: the hashtag and the rest of the page never move. The row takes no touches.
 *
 * <p>The row only draws. {@link Sequence} runs the timing. A sequence can outlive the row it
 * started on, because a re-pack rebinds the page, and each frame it draws on whichever row that
 * page has at that moment.
 */
public class ReelStreamProgress extends View {
    private static final float MIN_HEIGHT_DP = 16f;
    private static final float TRACK_HEIGHT_DP = 3f;
    private static final float TRACK_RADIUS_DP = 2f;
    private static final float TRACK_TO_COUNTER_DP = 8f;
    private static final float COUNTER_SP = 12f;
    /** The row's top sits this far below the hashtag's line box. */
    private static final float BELOW_ANCHOR_DP = 3f;
    /** Even the first take shows a sliver of fill. */
    private static final float MIN_FILL = 0.03f;
    /** The design's text-shadow, 0 1px 3px: a 3px CSS blur is a Gaussian sigma of 1.5px. */
    private static final float SHADOW_SIGMA_DP = 1.5f;
    private static final float SHADOW_DY_DP = 1f;

    private final float mDp;
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCounter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics mCounterMetrics = new Paint.FontMetrics();
    private final RectF mTrackRect = new RectF();
    private final NumberFormat mNumbers = NumberFormat.getIntegerInstance();

    /** How far through: fractional while the line moves between two positions. */
    private float mSeen;
    private int mTotal;
    /** What the counter says, rebuilt only when either number in it changes. */
    private String mText = "";
    private int mTextSeen = -1;
    private int mTextTotal = -1;
    private @Nullable View mAnchor;

    public ReelStreamProgress(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = context.getResources().getDisplayMetrics().density;
        mTrack.setColor(ContextCompat.getColor(context, R.color.browser_express_reel_stream_track));
        mFill.setColor(ContextCompat.getColor(context, R.color.browser_express_reel_stream_fill));
        mCounter.setColor(
                ContextCompat.getColor(context, R.color.browser_express_reel_stream_counter));
        mCounter.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, COUNTER_SP,
                context.getResources().getDisplayMetrics()));
        mCounter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Tabular figures, so the counter doesn't change width as it ticks.
        mCounter.setFontFeatureSettings("tnum");
        // Skia turns a shadow radius into a sigma as 0.57735 * radius + 0.5 (px).
        float shadowRadius = Math.max(0.5f, (SHADOW_SIGMA_DP * mDp - 0.5f) / 0.57735f);
        mCounter.setShadowLayer(shadowRadius, 0f, SHADOW_DY_DP * mDp,
                ContextCompat.getColor(context, R.color.browser_express_reel_stream_shadow));
        mCounter.getFontMetrics(mCounterMetrics);
    }

    /**
     * Keeps the row's top {@link #BELOW_ANCHOR_DP} under {@code anchor}, the hashtag. The anchor
     * must be a descendant of this row's parent. The offset is a translation, so following the
     * hashtag never lays anything out again.
     */
    void placeBelow(View anchor) {
        mAnchor = anchor;
        anchor.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> followAnchor());
    }

    /** Hides the row at once. Pages call this when they are bound or rebound. */
    void reset() {
        setAlpha(0f);
    }

    /**
     * @param seen how far through the stream the line and counter stand. Fractional while the
     *     line moves; the counter shows it rounded.
     * @param total how many takes the stream holds.
     */
    void setPosition(float seen, int total) {
        if (seen == mSeen && total == mTotal) return;
        mSeen = seen;
        mTotal = total;
        int shown = Math.round(seen);
        if (shown != mTextSeen || total != mTextTotal) {
            mTextSeen = shown;
            mTextTotal = total;
            mText = getResources().getString(R.string.browser_express_reel_stream_position,
                    mNumbers.format(shown), mNumbers.format(total));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // At large font sizes the counter outgrows the design's 16dp.
        int text = (int) Math.ceil(mCounterMetrics.descent - mCounterMetrics.ascent);
        int height = Math.max(Math.round(MIN_HEIGHT_DP * mDp), text);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        followAnchor();
    }

    private void followAnchor() {
        if (mAnchor == null) return;
        int anchorBottom = mAnchor.getBottom();
        ViewParent parent = mAnchor.getParent();
        while (parent instanceof View && parent != getParent()) {
            anchorBottom += ((View) parent).getTop();
            parent = parent.getParent();
        }
        if (parent != getParent()) return; // Not in this row's tree.
        setTranslationY(anchorBottom + BELOW_ANCHOR_DP * mDp - getTop());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float width = getWidth();
        float centerY = getHeight() / 2f;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        float textWidth = mCounter.measureText(mText);
        float trackLength = Math.max(0f, width - textWidth - TRACK_TO_COUNTER_DP * mDp);
        float trackStart = rtl ? width - trackLength : 0f;
        float half = TRACK_HEIGHT_DP * mDp / 2f;
        float radius = TRACK_RADIUS_DP * mDp;
        mTrackRect.set(trackStart, centerY - half, trackStart + trackLength, centerY + half);
        canvas.drawRoundRect(mTrackRect, radius, radius, mTrack);

        float fraction = mTotal > 0 ? Math.min(1f, Math.max(MIN_FILL, mSeen / mTotal)) : MIN_FILL;
        float fillLength = trackLength * fraction;
        if (fillLength > 0f) {
            // The fill is the track's shape cut off at the fill's end: rounded where the track
            // starts, square where the fill stops (the design clips it with overflow:hidden).
            canvas.save();
            if (rtl) {
                canvas.clipRect(mTrackRect.right - fillLength, mTrackRect.top, mTrackRect.right,
                        mTrackRect.bottom);
            } else {
                canvas.clipRect(mTrackRect.left, mTrackRect.top, mTrackRect.left + fillLength,
                        mTrackRect.bottom);
            }
            canvas.drawRoundRect(mTrackRect, radius, radius, mFill);
            canvas.restore();
        }

        float textX = rtl ? 0f : width - textWidth;
        float baseline = centerY - (mCounterMetrics.ascent + mCounterMetrics.descent) / 2f;
        canvas.drawText(mText, textX, baseline, mCounter);
    }

    /**
     * One showing of the row (README §3), from the page settling to the row fading out. Only one
     * runs at a time. Starting another, on the next page, cancels the last; nothing stacks or
     * queues.
     *
     * <table>
     *   <tr><td>0–250ms</td><td>fades in, ease-out</td></tr>
     *   <tr><td>200–600ms</td><td>the line and counter move from the last page's position,
     *       ease-out</td></tr>
     *   <tr><td>250–2300ms</td><td>holds</td></tr>
     *   <tr><td>2300–2700ms</td><td>fades out, ease-in-out</td></tr>
     * </table>
     *
     * <p>Something opening over the reel (the take sheet, a picker or a viewer) folds it in
     * 150ms. The clock runs on {@link Choreographer} rather than an animator, so turning
     * animations off doesn't end the showing early. With animations off the line skips straight
     * to the new position; the row still fades.
     */
    static final class Sequence implements Choreographer.FrameCallback {
        interface Host {
            /** The row on the page holding {@code reelId}, while that page is bound; else null. */
            @Nullable
            ReelStreamProgress rowFor(String reelId);

            /** Something covers the reel, so the row must not show. */
            boolean isCovered();
        }

        private static final long FADE_IN_MS = 250;
        private static final long MOVE_START_MS = 200;
        private static final long MOVE_MS = 400;
        private static final long FADE_OUT_START_MS = 2300;
        private static final long FADE_OUT_MS = 400;
        /**
         * From the page settling to the row gone. The "Add your take" pill keeps its label folded
         * away until then, since the row reaches the folded pill (ReelTakeBubble).
         */
        static final long SHOWING_MS = FADE_OUT_START_MS + FADE_OUT_MS;
        private static final long COLLAPSE_MS = 150;
        private static final Interpolator EASE_OUT = new PathInterpolator(0.33f, 1f, 0.68f, 1f);
        private static final Interpolator EASE_IN_OUT = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

        private final Host mHost;
        private @Nullable String mReelId;
        private float mFrom;
        private int mTo;
        private int mTotal;
        private boolean mMoves;
        /** Frame time of the first frame, or -1 until it comes. */
        private long mStartNanos = -1;
        /** When the fold started, or -1 while not folding. */
        private long mCollapseNanos = -1;
        private float mCollapseFrom;
        private float mAlpha;
        /** The row drawn on last frame, so a rebound page's old row is hidden. */
        private @Nullable ReelStreamProgress mRow;
        /**
         * Kept up past the usual fade-out, e.g. while the first-launch walkthrough points at the
         * row; let go, it fades from then.
         */
        private boolean mHeld;

        Sequence(Host host) {
            mHost = host;
        }

        /**
         * Shows the row on the page holding {@code reelId}.
         *
         * @param from where the line starts: the position of the page the reader came from.
         * @param to this page's position.
         */
        void start(String reelId, int from, int to, int total) {
            stop();
            mReelId = reelId;
            mFrom = from;
            mTo = to;
            mTotal = total;
            mMoves = from != to && ValueAnimator.areAnimatorsEnabled();
            mStartNanos = -1;
            mCollapseNanos = -1;
            mAlpha = 0f;
            Choreographer.getInstance().postFrameCallback(this);
        }

        /** True while the row shows (or is about to) for {@code reelId}. */
        boolean isShowing(@Nullable String reelId) {
            return mReelId != null && mReelId.equals(reelId);
        }

        /** New numbers for the page being shown, e.g. when takes arrive; the clock carries on. */
        void update(int to, int total) {
            if (mReelId == null) return;
            mTo = to;
            mTotal = total;
        }

        /** Ends the showing at once, hiding the row. */
        void stop() {
            Choreographer.getInstance().removeFrameCallback(this);
            if (mRow != null) mRow.setAlpha(0f);
            mRow = null;
            mReelId = null;
            mHeld = false;
        }

        /**
         * Keeps the row showing (true) for as long as needed, or lets it fade as usual from now
         * (false). Only a showing in progress can be held; start one first.
         */
        void setHeld(boolean held) {
            mHeld = held && mReelId != null;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (mReelId == null) return;
            if (mStartNanos < 0) mStartNanos = frameTimeNanos;
            if (mCollapseNanos < 0 && mHost.isCovered()) {
                mCollapseNanos = frameTimeNanos;
                mCollapseFrom = mAlpha;
            }

            long t = (frameTimeNanos - mStartNanos) / 1_000_000L;
            if (mHeld && t > FADE_OUT_START_MS) {
                // Held: the clock waits at the fade-out's start, so a release fades from there.
                mStartNanos = frameTimeNanos - FADE_OUT_START_MS * 1_000_000L;
                t = FADE_OUT_START_MS;
            }
            boolean done;
            float alpha;
            if (mCollapseNanos >= 0) {
                float k = clamp((frameTimeNanos - mCollapseNanos) / 1_000_000f / COLLAPSE_MS);
                alpha = mCollapseFrom * (1f - EASE_IN_OUT.getInterpolation(k));
                done = k >= 1f;
            } else if (t < FADE_IN_MS) {
                alpha = EASE_OUT.getInterpolation(clamp((float) t / FADE_IN_MS));
                done = false;
            } else if (t < FADE_OUT_START_MS) {
                alpha = 1f;
                done = false;
            } else {
                float k = clamp((float) (t - FADE_OUT_START_MS) / FADE_OUT_MS);
                alpha = 1f - EASE_IN_OUT.getInterpolation(k);
                done = k >= 1f;
            }
            float seen = mTo;
            if (mMoves) {
                float k = clamp((float) (t - MOVE_START_MS) / MOVE_MS);
                seen = mFrom + (mTo - mFrom) * EASE_OUT.getInterpolation(k);
            }

            ReelStreamProgress row = mHost.rowFor(mReelId);
            if (row != mRow) {
                if (mRow != null) mRow.setAlpha(0f);
                mRow = row;
            }
            if (row != null) {
                row.setPosition(seen, mTotal);
                row.setAlpha(alpha);
            }
            mAlpha = alpha;

            if (done) {
                stop();
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private static float clamp(float k) {
            return Math.max(0f, Math.min(1f, k));
        }
    }
}
