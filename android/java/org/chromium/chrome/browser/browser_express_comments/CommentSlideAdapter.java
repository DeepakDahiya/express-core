/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs the vertical pager: one entry per full-screen page produced by {@link CommentSlidePacker}.
 *
 * <p>Each page hosts a short {@link RecyclerView} of comment rows bound by
 * {@link CommentListAdapter} — ordinary rows on a shared page, the single full-screen TikTok-style
 * row on a reel page. The rows are packed to fit, so the list normally has nothing to scroll; it
 * only does once a comment is expanded, and {@link SlideScrollHost} is what shares the drag with
 * the pager when that happens. Going through the existing adapter means voting, replies, sharing, media and
 * the optimistic upload states all behave here exactly as they do in the bottom sheet, with no
 * second copy of that logic to keep in step.
 *
 * <p>A text page whose successor is a reel may also draw a sneak-peek strip along its bottom, in
 * whatever room its comments left (the packer sizes it, up to 15% of the page), so binding it is
 * only a matter of sizing the views and dropping in the frame. See {@link #bindNextReelPeek}.
 */
public class CommentSlideAdapter extends RecyclerView.Adapter<CommentSlideAdapter.SlideHolder> {
    private static final int VIEW_TYPE_TEXT = 0;
    private static final int VIEW_TYPE_REEL = 1;

    private final CommentActionHost mHost;
    private final EditText mInputEditText;
    private final String mPageUrl;
    private final List<CommentSlide> mSlides = new ArrayList<>();
    /**
     * Height of the comments header floating over the top of every page. Text pages start below
     * it; reel pages run under it (their own white header stands in).
     */
    private int mHeaderInsetPx;
    /**
     * Height of the sheet's comment box floating over the bottom of every page. Text pages stop
     * above it; reel pages run under it while it slides away.
     */
    private int mComposerInsetPx;
    private @Nullable Runnable mOnBack;

    public CommentSlideAdapter(
            CommentActionHost host, EditText inputEditText, String pageUrl) {
        mHost = host;
        mInputEditText = inputEditText;
        mPageUrl = pageUrl;
    }

    /**
     * Replaces the pages after a fetch or a re-pack.
     *
     * <p>Wholesale invalidation is the honest signal here: a re-pack recomputes page boundaries
     * from scratch, so a comment that was the third row on page two can become the first row on
     * page three. There is no item-level diff to express — the pages themselves are new.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setSlides(List<CommentSlide> slides) {
        mSlides.clear();
        if (slides != null) mSlides.addAll(slides);
        notifyDataSetChanged();
    }

    /**
     * Adds pages after the last one, leaving the pages already up bound as they are: the opening
     * reel keeps the player it is loading or playing while the pages behind it arrive.
     */
    public void appendSlides(List<CommentSlide> slides) {
        if (slides == null || slides.isEmpty()) return;
        int start = mSlides.size();
        mSlides.addAll(slides);
        notifyItemRangeInserted(start, slides.size());
    }

    /** Sets how far text pages start below the top, re-laying out the pages on screen. */
    @SuppressLint("NotifyDataSetChanged")
    public void setHeaderInset(int insetPx) {
        if (insetPx == mHeaderInsetPx) return;
        mHeaderInsetPx = insetPx;
        notifyDataSetChanged();
    }

    /** Sets how far above the bottom text pages stop, re-laying out the pages on screen. */
    @SuppressLint("NotifyDataSetChanged")
    public void setComposerInset(int insetPx) {
        if (insetPx == mComposerInsetPx) return;
        mComposerInsetPx = insetPx;
        notifyDataSetChanged();
    }

    /** What a reel page's own back arrow does — the same as the comments header's. */
    public void setOnBackListener(@Nullable Runnable onBack) {
        mOnBack = onBack;
    }

    public CommentSlide getSlide(int position) {
        return position >= 0 && position < mSlides.size() ? mSlides.get(position) : null;
    }

    /**
     * Position of the page holding a given comment, or -1. Re-packing renumbers pages, so this is
     * how the pager gets back to where the reader was.
     */
    public int positionOfComment(String commentId) {
        if (commentId == null) return -1;
        for (int i = 0; i < mSlides.size(); i++) {
            for (CommentSlide.Item item : mSlides.get(i).getItems()) {
                if (commentId.equals(item.getComment().getId())) return i;
            }
        }
        return -1;
    }

    /** How many reel pages come before {@code position}: the reel's index among reels only. */
    int reelIndexOf(int position) {
        int index = 0;
        for (int i = 0; i < position && i < mSlides.size(); i++) {
            if (mSlides.get(i).isReel()) index++;
        }
        return index;
    }

    @Override
    public int getItemViewType(int position) {
        return mSlides.get(position).isReel() ? VIEW_TYPE_REEL : VIEW_TYPE_TEXT;
    }

    @Override
    public int getItemCount() {
        return mSlides.size();
    }

    @NonNull
    @Override
    public SlideHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == VIEW_TYPE_REEL
                ? R.layout.browser_express_comment_slide_reel
                : R.layout.browser_express_comment_slide_text;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new SlideHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SlideHolder holder, int position) {
        CommentSlide slide = mSlides.get(position);

        List<Comment> comments = new ArrayList<>(slide.getItemCount());
        for (CommentSlide.Item item : slide.getItems()) {
            comments.add(item.getComment());
        }

        if (slide.isReel()) {
            if (holder.mReelBack != null) {
                holder.mReelBack.setOnClickListener(v -> {
                    if (mOnBack != null) mOnBack.run();
                });
            }
            if (holder.mReelTitle != null) {
                String hashtag = mHost != null && mHost.isAdded() ? mHost.topicHashtag() : null;
                if (hashtag != null) {
                    holder.mReelTitle.setText(hashtag);
                } else {
                    holder.mReelTitle.setText(R.string.comments);
                }
            }
            // A Karma moment from the reel this page showed before must not carry over.
            KarmaEarned.cancel(holder.itemView.findViewById(R.id.reel_karma_layer));
            // Nor its stream position. A showing still running for this page draws it again on
            // the next frame (CommentReelFragment's ReelStreamProgress.Sequence).
            if (holder.mStreamProgress != null) holder.mStreamProgress.reset();
            if (holder.mTakeBubble != null) {
                // Every third reel (0, 3, 6…) opens with the full pill; CommentReelFragment
                // folds it once its page settles.
                holder.mTakeBubble.show(reelIndexOf(position) % 3 == 0);
                holder.mTakeBubble.setOnClickListener(v -> {
                    if (mHost != null && mHost.isAdded()) mHost.openTakeComposer();
                });
                if (mHost != null && mHost.isAdded()) {
                    holder.mTakeBubble.setContentDescription(mHost.takeEntryLabel());
                }
            }
        } else {
            int top = mHeaderInsetPx + holder.itemView.getResources().getDimensionPixelSize(
                    R.dimen.be_slide_rows_padding_top);
            if (holder.mRows.getPaddingTop() != top) {
                holder.mRows.setPadding(holder.mRows.getPaddingLeft(), top,
                        holder.mRows.getPaddingRight(), holder.mRows.getPaddingBottom());
            }
            // The rows and the peek strip both end above the comment box.
            View page = holder.itemView;
            if (page.getPaddingBottom() != mComposerInsetPx) {
                page.setPadding(page.getPaddingLeft(), page.getPaddingTop(),
                        page.getPaddingRight(), mComposerInsetPx);
            }
        }

        holder.mRows.setLayoutManager(new LinearLayoutManager(holder.itemView.getContext()));
        CommentListAdapter rowsAdapter = new CommentListAdapter(holder.itemView.getContext(),
                comments, mInputEditText, mHost, false, false, mPageUrl, true, slide.isReel());
        holder.mRows.setAdapter(rowsAdapter);

        bindNextReelPeek(holder, slide);
    }

    @Override
    public void onViewRecycled(@NonNull SlideHolder holder) {
        super.onViewRecycled(holder);
        holder.clearPeek();
        // The page's rows go too, releasing their video players; binding gives the page a fresh
        // rows adapter anyway.
        holder.mRows.setAdapter(null);
    }

    /**
     * Draws the top of the reel below into this page's bottom strip, or hides it when nothing is
     * coming.
     *
     * <p>The strip is the room the packer found under the rows; the image inside is laid out at the height
     * of a whole page and clipped to the strip, so the centre-crop lands exactly where the reel
     * page's zoomed video will. The frame arrives asynchronously and the page it was asked for
     * may have been recycled by then, which is what the holder's url tag guards.
     */
    private void bindNextReelPeek(SlideHolder holder, CommentSlide slide) {
        if (holder.mPeek == null) return; // Reel pages have no strip.
        holder.clearPeek();

        Comment nextReel = slide.getNextReelComment();
        int peekHeight = slide.getPeekHeightPx();
        if (nextReel == null || peekHeight <= 0 || slide.getPageHeightPx() <= 0) {
            holder.mPeek.setVisibility(View.GONE);
            setScrollHostBottomInset(holder, 0);
            return;
        }

        setHeight(holder.mPeek, peekHeight);
        setHeight(holder.mPeekImage, slide.getPageHeightPx());
        setScrollHostBottomInset(holder, peekHeight);
        holder.mPeek.setVisibility(View.VISIBLE);

        final String url = nextReel.getMediaVideoUrl();
        holder.mPeekUrl = url;
        holder.mPeekListener = (readyUrl, frame) -> {
            if (!readyUrl.equals(holder.mPeekUrl)) return; // Rebound to another page since.
            holder.mPeekImage.setImageBitmap(frame);
        };

        Bitmap cached = CommentVideoFrames.requestFrame(
                holder.itemView.getContext(), url, holder.mPeekListener);
        if (cached != null) holder.mPeekImage.setImageBitmap(cached);
    }

    /**
     * Keeps the scrollable rows clear of the peek strip. It matters once a comment is expanded:
     * without the inset the extra text would scroll in behind the next page's video.
     */
    private static void setScrollHostBottomInset(SlideHolder holder, int insetPx) {
        if (holder.mScrollHost == null) return;
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) holder.mScrollHost.getLayoutParams();
        if (params.bottomMargin == insetPx) return;
        params.bottomMargin = insetPx;
        holder.mScrollHost.setLayoutParams(params);
    }

    private static void setHeight(View view, int heightPx) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height == heightPx) return;
        params.height = heightPx;
        view.setLayoutParams(params);
    }

    static class SlideHolder extends RecyclerView.ViewHolder {
        final RecyclerView mRows;
        /** Null on a reel page: a full-bleed video has nothing to scroll. */
        final View mScrollHost;
        /** Null on a reel page, which hands its whole surface to the video. */
        final View mPeek;
        final ImageView mPeekImage;
        /** The reel page's own back arrow; null on text pages. */
        final View mReelBack;
        /** Beside the back arrow: the post's "#Hashtag", else "Comments"; null on text pages. */
        final @Nullable TextView mReelTitle;
        /** The reel page's "Add your take" bubble; null on text pages. */
        final @Nullable ReelTakeBubble mTakeBubble;
        /** Under the hashtag: the stream position that fades in briefly; null on text pages. */
        final @Nullable ReelStreamProgress mStreamProgress;

        String mPeekUrl;
        CommentVideoFrames.FrameListener mPeekListener;

        SlideHolder(@NonNull View itemView) {
            super(itemView);
            mRows = itemView.findViewById(R.id.slide_rows);
            mScrollHost = itemView.findViewById(R.id.slide_scroll_host);
            mPeek = itemView.findViewById(R.id.next_reel_peek);
            mPeekImage = itemView.findViewById(R.id.next_reel_peek_image);
            mReelBack = itemView.findViewById(R.id.reel_back_button);
            mReelTitle = itemView.findViewById(R.id.reel_header_title);
            View takeBubble = itemView.findViewById(R.id.reel_take_bubble);
            mTakeBubble = takeBubble != null ? new ReelTakeBubble(takeBubble) : null;
            mStreamProgress = itemView.findViewById(R.id.reel_stream_progress);
            if (mStreamProgress != null && mReelTitle != null) {
                mStreamProgress.placeBelow(mReelTitle);
            }
            if (mPeek != null) roundPeekTopCorners(mPeek);
        }

        /**
         * Curves the strip's top corners so the page below reads as sliding up rather than as a
         * band cut out of this one. An outline is what does it — the strip clips an image laid
         * out taller than itself, so a rounded background would be painted over.
         *
         * <p>The outline runs a radius past the bottom of the view: only the top corners fall
         * inside the view, so only they come out curved.
         */
        private static void roundPeekTopCorners(View peek) {
            final float radius = peek.getResources().getDimensionPixelSize(
                    R.dimen.be_reel_peek_corner_radius);
            peek.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(),
                            view.getHeight() + Math.round(radius), radius);
                }
            });
            peek.setClipToOutline(true);
        }

        /** Stops waiting on a frame this page no longer shows, and drops the one it had. */
        void clearPeek() {
            if (mPeek == null) return;
            if (mPeekListener != null) {
                CommentVideoFrames.cancel(mPeekUrl, mPeekListener);
                mPeekListener = null;
            }
            mPeekUrl = null;
            mPeekImage.setImageDrawable(null);
        }
    }
}
