/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import org.chromium.chrome.R;

/**
 * Measures comment rows for {@link CommentSlidePacker} by measuring the real layout.
 *
 * <p>A single detached copy of {@code browser_express_comment.xml} is inflated once and re-used:
 * each call sets the text on it and runs a measure pass. That costs a fraction of a millisecond
 * per comment and — unlike reproducing the layout's height arithmetic in Java — cannot drift when
 * the XML changes.
 *
 * <p>What it sets on the template has to match what {@link CommentListAdapter.CommentHolder#bind}
 * sets in pager mode, or pages get measured with text they do not end up drawing. Both go through
 * {@link Comment#getPagerDisplayContent()} / {@link Comment#isPagerContentClamped()} and both
 * apply {@link #applyPagerRowSpacing}, for exactly that reason.
 *
 * <p>Not thread-safe, and must run on the UI thread: {@link View#measure} requires it.
 */
public class CommentRowMeasurer implements CommentSlidePacker.Measurer {
    private final View mTemplate;
    private final TextView mUsername;
    private final TextView mContent;
    private final View mReadMore;
    private final View mAudioRow;
    private final View mMediaCard;
    private final View mDocumentRow;
    private final TextView mTime;
    private final View mMediaRatioSpacer;
    /** The ratio the spacer was last set to, so an unchanged one skips the relayout. */
    private String mMediaRatio;
    private final int mRowWidthPx;
    private final int mRowVerticalMarginPx;
    /** The card's own width: the page's less the margins that inset it from the edges. */
    private final int mCardWidthPx;

    /**
     * @param rowWidthPx width the row will be laid out at — the page's content width.
     */
    public CommentRowMeasurer(Context context, int rowWidthPx) {
        if (rowWidthPx <= 0) {
            throw new IllegalArgumentException("rowWidthPx must be positive");
        }
        mRowWidthPx = rowWidthPx;

        // Inflating against a scratch parent (without attaching) is what generates the root's
        // MarginLayoutParams from the XML, so the row's own margins can be accounted for.
        LinearLayout scratchParent = new LinearLayout(context);
        mTemplate = LayoutInflater.from(context).inflate(
                R.layout.browser_express_comment, scratchParent, false);
        // Before the margin is read below: pager rows sit tighter than flat-list rows.
        applyPagerRowSpacing(mTemplate);
        ViewGroup.LayoutParams params = mTemplate.getLayoutParams();
        mRowVerticalMarginPx = params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).topMargin
                        + ((ViewGroup.MarginLayoutParams) params).bottomMargin
                : 0;
        int horizontalMarginPx = params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).getMarginStart()
                        + ((ViewGroup.MarginLayoutParams) params).getMarginEnd()
                : 0;
        mCardWidthPx = Math.max(1, mRowWidthPx - horizontalMarginPx);

        mUsername = mTemplate.findViewById(R.id.username);
        mContent = mTemplate.findViewById(R.id.comment_content);
        mReadMore = mTemplate.findViewById(R.id.btn_read_more_comment);
        mAudioRow = mTemplate.findViewById(R.id.comment_audio_row);
        mMediaCard = mTemplate.findViewById(R.id.comment_media_card);
        mDocumentRow = mTemplate.findViewById(R.id.comment_document_row);
        mTime = mTemplate.findViewById(R.id.comment_time);
        mMediaRatioSpacer = mTemplate.findViewById(R.id.media_aspect_ratio_spacer);

        // Mirror the steady state of a bound row. The header and the actions row are fixed
        // height, so neither the time nor the reactions change what a row measures.
        mTemplate.findViewById(R.id.posting_progress_bar).setVisibility(View.GONE);
        mTemplate.findViewById(R.id.failed_text_view).setVisibility(View.GONE);
        mTemplate.findViewById(R.id.action_items).setVisibility(View.VISIBLE);
        mTime.setVisibility(View.GONE);
    }

    @Override
    public CommentSlide.Item measure(Comment comment) {
        String displayText = comment.getPagerDisplayContent();
        boolean truncated = comment.isPagerContentClamped();

        mUsername.setText(usernameOf(comment));
        mContent.setText(displayText);
        mReadMore.setVisibility(truncated ? View.VISIBLE : View.GONE);
        // A voice note packs inline, so its player row is part of the height being measured.
        mAudioRow.setVisibility(comment.hasAudioMedia() ? View.VISIBLE : View.GONE);
        // Images and landscape videos pack inline too, drawn at the design preset nearest their
        // shape — the template's spacer gets the same ratio the binder applies. Portrait videos never reach a shared page (the packer gives
        // them a reel page of their own), so their card stays out of the measurement.
        boolean inlineMediaCard = comment.hasVisualMedia() && !comment.isPortraitVideo();
        mMediaCard.setVisibility(inlineMediaCard ? View.VISIBLE : View.GONE);
        if (inlineMediaCard) setMediaRatio(comment.getInlineMediaRatio());
        mDocumentRow.setVisibility(comment.hasDocumentMedia() ? View.VISIBLE : View.GONE);
        // Several files, or a comment being sent, swap those slots for their own rows; the
        // actions row is put back first because an outgoing card hides it.
        mTemplate.findViewById(R.id.action_items).setVisibility(View.VISIBLE);
        CommentAttachmentRows.applyLayout(mTemplate, comment);

        mTemplate.measure(View.MeasureSpec.makeMeasureSpec(mCardWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        return new CommentSlide.Item(comment, displayText, truncated,
                mTemplate.getMeasuredHeight() + mRowVerticalMarginPx);
    }

    /**
     * Holds a stacked pager row to the card spacing (Figma "Express/Comment Card"): 12dp inside,
     * 16dp in from the page edges, 4dp between cards. The XML says the same; this re-asserts it
     * on recycled rows, whose margins other surfaces may have changed. Applied to the measuring
     * template here and to every bound pager row by {@link CommentListAdapter.CommentHolder#bind},
     * keeping measured and drawn heights identical. The reel overlay has its own layout and never
     * goes through this.
     */
    public static void applyPagerRowSpacing(View row) {
        float density = row.getResources().getDisplayMetrics().density;
        int padding = Math.round(12 * density);
        row.setPadding(padding, padding, padding, padding);
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int edge = Math.round(16 * density);
            margins.setMarginStart(edge);
            margins.setMarginEnd(edge);
            margins.bottomMargin = Math.round(4 * density);
            row.setLayoutParams(params);
        }
    }

    /** Sizes the template's media card to the preset the card binder will draw. */
    private void setMediaRatio(int[] ratio) {
        String value = "H," + ratio[0] + ":" + ratio[1];
        if (value.equals(mMediaRatio)) return;
        mMediaRatio = value;
        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) mMediaRatioSpacer.getLayoutParams();
        params.dimensionRatio = value;
        mMediaRatioSpacer.setLayoutParams(params);
    }

    /** Username exactly as {@link CommentListAdapter.CommentHolder#bind} would show it. */
    private static String usernameOf(Comment comment) {
        if (comment.getUser() != null) {
            return comment.getUser().getUsername();
        }
        String youtubeAuthor = comment.getYoutubeAuthorName();
        return youtubeAuthor != null ? youtubeAuthor : "";
    }
}
