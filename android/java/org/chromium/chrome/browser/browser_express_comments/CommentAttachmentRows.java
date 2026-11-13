/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;

import org.chromium.chrome.R;

import java.util.List;
import java.util.Locale;

/**
 * The comment card's rows for comments with several files and for comments this reader is sending
 * (Figma "Outgoing comment morph", 498:2056): a row of 88dp x 56dp tiles, the larger still of a
 * single video being sent, the upload line and Retry or Cancel.
 *
 * <p>{@link #applyLayout} decides which rows show. {@link CommentListAdapter} and
 * {@link CommentRowMeasurer} both call it, so a page is packed with the rows it is drawn with.
 */
final class CommentAttachmentRows {
    private static final int TILE_WIDTH_DP = 88;
    private static final int TILE_HEIGHT_DP = 56;
    private static final int TILE_GAP_DP = 8;

    /** Opens a tapped tile of a posted comment. */
    interface TileOpener {
        void open(CommentAttachment attachment);
    }

    private CommentAttachmentRows() {}

    /**
     * Shows the rows {@code comment} needs on a card (comment or reel layout; views a layout lacks
     * are skipped) and hides the single-media slots they replace.
     */
    static void applyLayout(View card, Comment comment) {
        boolean rows = comment.usesAttachmentRows();
        List<CommentAttachment> files = comment.getAttachments();
        boolean outgoing = comment.isOutgoing();
        CommentAttachment only = files.size() == 1 ? files.get(0) : null;
        boolean voiceOnly = only != null && CommentAttachment.KIND_AUDIO.equals(only.getKind());
        boolean videoOnly = outgoing && only != null && only.isVideo();
        boolean tiles = rows && !files.isEmpty() && !voiceOnly && !videoOnly;

        setVisible(card, R.id.comment_attachment_tiles_scroll, tiles);
        setVisible(card, R.id.comment_outgoing_video, videoOnly);
        if (rows) {
            setVisible(card, R.id.comment_media_card, false);
            setVisible(card, R.id.comment_document_row, false);
            setVisible(card, R.id.comment_audio_row, voiceOnly);
        }

        boolean uploading = outgoing && comment.getUploadStatus() == Comment.UploadStatus.POSTING;
        boolean failed = outgoing && comment.getUploadStatus() == Comment.UploadStatus.FAILED;
        setVisible(card, R.id.comment_upload_progress, uploading && !files.isEmpty());
        boolean action = failed || (uploading && hasVideo(files));
        setVisible(card, R.id.comment_outgoing_video_action, videoOnly && action);
        setVisible(card, R.id.comment_upload_action, !videoOnly && action);
        // An outgoing comment can't be replied to or reacted to until it exists, and the design
        // keeps it a plain card afterwards too.
        if (outgoing) {
            setVisible(card, R.id.action_items, false);
            setVisible(card, R.id.posting_progress_bar, false);
            setVisible(card, R.id.failed_text_view, false);
        }
    }

    static boolean hasVideo(List<CommentAttachment> files) {
        for (CommentAttachment file : files) {
            if (file.isVideo()) return true;
        }
        return false;
    }

    /**
     * Fills the tile row: photos and video stills (with a play mark), and documents or voice
     * notes as a type-and-size tile.
     *
     * @param opener what a tap does, or null while the files are still the reader's local ones.
     */
    static void bindTiles(LinearLayout row, List<CommentAttachment> files,
            @Nullable TileOpener opener) {
        Context context = row.getContext();
        row.removeAllViews();
        int width = dp(context, TILE_WIDTH_DP);
        int height = dp(context, TILE_HEIGHT_DP);
        for (int i = 0; i < files.size(); i++) {
            CommentAttachment file = files.get(i);
            View tile = file.isVisual() ? pictureTile(context, file) : fileTile(context, file);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
            if (i > 0) params.setMarginStart(dp(context, TILE_GAP_DP));
            row.addView(tile, params);
            if (opener != null) {
                tile.setOnClickListener(v -> opener.open(file));
                tile.setForeground(ContextCompat.getDrawable(context, selectableBackground(context)));
            }
        }
    }

    private static View pictureTile(Context context, CommentAttachment file) {
        FrameLayout tile = new FrameLayout(context);
        tile.setBackgroundResource(R.drawable.browser_express_comment_tile_bg);
        tile.setClipToOutline(true);
        tile.setContentDescription(describe(context, file));
        ImageView picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String url = file.getUrl();
        if (file.isVideo() && url.startsWith("http")) {
            // A posted video's still comes from the frame extractor, which reads only the start
            // of the file; Glide would download all of it.
            android.graphics.Bitmap frame = CommentVideoFrames.requestFrame(
                    context, url, (readyUrl, ready) -> picture.setImageBitmap(ready));
            if (frame != null) picture.setImageBitmap(frame);
        } else {
            Glide.with(picture).load(url).centerCrop().into(picture);
        }
        if (file.isVideo()) {
            ImageView play = new ImageView(context);
            play.setImageResource(R.drawable.ic_browser_express_comment_tile_play_28);
            play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = dp(context, 28);
            tile.addView(play, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }
        return tile;
    }

    /** A document or voice note: its type ("PDF", "Voice") over its size or length. */
    private static View fileTile(Context context, CommentAttachment file) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.browser_express_comment_tile_document_bg);
        int pad = dp(context, 9);
        tile.setPadding(pad, pad, pad, 0);
        tile.setContentDescription(describe(context, file));

        TextView type = new TextView(context);
        type.setText(typeLabel(context, file));
        type.setTextColor(ContextCompat.getColor(context, R.color.browser_express_comments_accent));
        type.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        type.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        type.setMaxLines(1);
        type.setIncludeFontPadding(false);
        type.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(type);

        TextView meta = new TextView(context);
        meta.setText(metaLabel(context, file));
        meta.setTextColor(ContextCompat.getColor(
                context, R.color.browser_express_comment_tile_document_size));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        meta.setMaxLines(1);
        meta.setIncludeFontPadding(false);
        meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 8);
        tile.addView(meta, params);
        return tile;
    }

    private static String typeLabel(Context context, CommentAttachment file) {
        if (CommentAttachment.KIND_AUDIO.equals(file.getKind())) {
            return context.getString(R.string.browser_express_composer_voice);
        }
        String name = file.getName();
        int dot = name != null ? name.lastIndexOf('.') : -1;
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toUpperCase(Locale.ROOT);
        }
        return context.getString(R.string.browser_express_composer_document);
    }

    private static String metaLabel(Context context, CommentAttachment file) {
        if (CommentAttachment.KIND_AUDIO.equals(file.getKind()) && file.getDurationMs() > 0) {
            return CommentComposerController.clock(file.getDurationMs());
        }
        return file.getSize() > 0 ? Formatter.formatShortFileSize(context, file.getSize()) : "";
    }

    private static String describe(Context context, CommentAttachment file) {
        String name = file.getName();
        if (name != null && !name.isEmpty()) return name;
        switch (file.getKind()) {
            case CommentAttachment.KIND_GIF:
                return context.getString(R.string.browser_express_composer_gif);
            case CommentAttachment.KIND_VIDEO:
                return context.getString(R.string.browser_express_composer_video);
            case CommentAttachment.KIND_AUDIO:
                return context.getString(R.string.browser_express_composer_voice);
            case CommentAttachment.KIND_DOCUMENT:
                return context.getString(R.string.browser_express_composer_document);
            default:
                return context.getString(R.string.browser_express_composer_photo);
        }
    }

    /** "18.6 MB · Reel": a sending video's size and whether it is a reel (portrait). */
    static String videoMeta(Context context, CommentAttachment video) {
        String kind = context.getString(video.isPortrait()
                        ? R.string.browser_express_comment_kind_reel
                        : R.string.browser_express_comment_kind_video);
        if (video.getSize() <= 0) return kind;
        return context.getString(R.string.browser_express_comment_video_meta,
                Formatter.formatShortFileSize(context, video.getSize()), kind);
    }

    private static int selectableBackground(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private static void setVisible(View root, int id, boolean visible) {
        View view = root.findViewById(id);
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
