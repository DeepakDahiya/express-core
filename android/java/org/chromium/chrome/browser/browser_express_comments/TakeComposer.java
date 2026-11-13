/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.icu.text.CompactDecimalFormat;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.OneShotPreDrawListener;

import org.chromium.chrome.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Add your take" (design handoff "Add your take" §1–3): a new take on what the comments are
 * about, never a reply to one comment. It is the comment box grown into a sheet — a handle, the
 * title and ×, and a card with the topic and a stream of the people in the conversation — over
 * the comments, dimmed; behind it a reel is also paused and blurred. The input row is the box's
 * own, so drafts, files, voice notes and sending work as they always have. The paperclip puts the
 * attachment picker where the keyboard was, and closing with something written or picked asks
 * first. With animations off everything only fades, over 150ms.
 */
final class TakeComposer {
    interface Host {
        /** Something is typed or picked. */
        boolean hasTakeDraft();

        /** Throws away what is typed and picked. */
        void discardTakeDraft();

        /** Shows the comment box at once wherever it was (e.g. put away over a video). */
        void showComposerNow();

        void focusTakeInput();

        void hideTakeKeyboard();

        void onTakeAttachmentChosen(@CommentAttachmentSheet.Kind int kind);

        /** Whether a full-page video is behind the sheet, rather than a text page. */
        boolean isTakeOverReel();

        /** The pages under the sheet, blurred over a video. */
        @Nullable
        View takePages();

        /** The box is back to itself; the page under it may play its video again. */
        void onTakeClosed();
    }

    /** What the comments are about, for the topic card. */
    static final class Topic {
        /** The hashtag ("#KohliKaKamaal"), or the caption or page title when there is none. */
        final String title;
        /** "Reactions" on the card: how many comments the topic has. */
        final int reactions;
        /** "Emotes": every emoji given to those comments. */
        final int emotes;
        final List<String> faces;
        final List<String> emoji;

        Topic(String title, int reactions, int emotes, List<String> faces, List<String> emoji) {
            this.title = title;
            this.reactions = reactions;
            this.emotes = emotes;
            this.faces = faces;
            this.emoji = emoji;
        }
    }

    private static final long OPEN_MS = 500;
    private static final long CLOSE_MS = 400;
    private static final long PICKER_MS = 400;
    private static final long PICKER_DELAY_MS = 150;
    private static final long REDUCED_MS = 150;
    private static final float BLUR_DP = 16f;
    /** What the sheet gives up, in order, to keep the box above the keyboard (fitToRoom). */
    @IntDef({Squeeze.NONE, Squeeze.NO_TOPIC, Squeeze.COMPACT_PREVIEW})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Squeeze {
        int NONE = 0;
        int NO_TOPIC = 1;
        int COMPACT_PREVIEW = 2;
    }

    /** A picked file's preview when even without the topic card there is no room for it. */
    private static final int COMPACT_PREVIEW_DP = 96;

    /** The share of the topic card the cover fills, from the right. */
    private static final float COVER_SHARE = 0.62f;
    /**
     * The cover is the handoff's fixed artwork, the same on every topic: hard-edged rings
     * centred at 70% across and 120% down the cover, out to its farthest corner.
     */
    private static final int[] COVER_RINGS = {
        0xFFC7372A, 0xFFE0742A, 0xFFE8B923, 0xFFB9A640, 0xFF9AA0A6, 0xFFD38A86, 0xFFB24A2A
    };
    /** Where each ring after the first starts, as a share of the radius. */
    private static final float[] COVER_RING_STARTS = {0.14f, 0.24f, 0.36f, 0.46f, 0.58f, 0.70f};
    /** The stats line's dot: 3dp across, 6dp clear on each side. */
    private static final float STATS_DOT_DP = 3f;
    private static final float STATS_GAP_DP = 6f;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.215f, 0.61f, 0.355f, 1f);
    private static final Interpolator EASE_IN_OUT =
            new PathInterpolator(0.645f, 0.045f, 0.355f, 1f);

    private final Host mHost;
    private final Context mContext;
    private final float mDp;
    private final View mBar;
    private final View mHeader;
    private final View mScrim;
    private final View mPicker;
    private final View mTopic;
    /** The comments body the sheet sits at the bottom of: the room it has above the keyboard. */
    private final View mBody;
    /** A picked photo or video's preview above the box, and its thumbnail. */
    private final View mPreview;
    private final View mPreviewFrame;
    private final int mPreviewHeight;
    private final int mPreviewThumb;
    private final Runnable mFitTopic = this::fitToRoom;
    /** How much the sheet gave up to keep the box above the keyboard; see {@link #fitToRoom}. */
    private @Squeeze int mSqueeze = Squeeze.NONE;
    private final View mCover;
    private final View mFade;
    private final TextView mTitle;
    private final TextView mStats;
    private final TakeStreamView mStream;
    private final EditText mInput;
    private final Drawable mBarBackground;
    private final int[] mBarPadding;
    private final float mBarElevation;
    private @Nullable CharSequence mBoxHint;
    private boolean mOpen;
    private boolean mPickerShown;

    /**
     * @param root the comments sheet, holding the comment box ({@code sheet_button}) with the
     *     sheet's header in it, the picker under it and the scrim behind it.
     */
    TakeComposer(View root, Host host) {
        mHost = host;
        mContext = root.getContext();
        mDp = mContext.getResources().getDisplayMetrics().density;
        mBar = root.findViewById(R.id.sheet_button);
        mHeader = root.findViewById(R.id.take_header);
        mScrim = root.findViewById(R.id.take_scrim);
        mPicker = root.findViewById(R.id.take_picker);
        mTopic = root.findViewById(R.id.take_topic);
        mBody = root.findViewById(R.id.comment_sheet_body);
        mPreview = root.findViewById(R.id.composer_video_preview);
        mPreviewFrame = root.findViewById(R.id.composer_video_frame);
        mPreviewHeight = mPreview.getLayoutParams().height;
        mPreviewThumb = mPreviewFrame.getLayoutParams().height;
        mCover = root.findViewById(R.id.take_topic_cover);
        mFade = root.findViewById(R.id.take_topic_fade);
        mTitle = root.findViewById(R.id.take_topic_title);
        mStats = root.findViewById(R.id.take_topic_stats);
        mStream = root.findViewById(R.id.take_stream);
        mInput = root.findViewById(R.id.comment_content_input);
        mBarBackground = mBar.getBackground();
        mBarPadding = new int[] {mBar.getPaddingLeft(), mBar.getPaddingTop(),
            mBar.getPaddingRight(), mBar.getPaddingBottom()};
        mBarElevation = mBar.getElevation();

        mTopic.setClipToOutline(true);
        mCover.setBackground(coverDrawable());
        mTopic.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) fitCover(r - l);
        });
        // The keyboard, the picker and an attachment preview all change how much fits. Changes to
        // the layout wait for the pass that reported them to finish, or they would be dropped.
        View.OnLayoutChangeListener refit = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (!mOpen) return;
            mBar.removeCallbacks(mFitTopic);
            mBar.post(mFitTopic);
        };
        mBar.addOnLayoutChangeListener(refit);
        mBody.addOnLayoutChangeListener(refit);
        root.findViewById(R.id.take_close).setOnClickListener(v -> requestClose());
        mScrim.setOnClickListener(v -> requestClose());
        root.findViewById(R.id.take_picker_close).setOnClickListener(v -> hidePicker(true));
        bindTile(root, R.id.take_pick_photos, CommentAttachmentSheet.Kind.PHOTO);
        bindTile(root, R.id.take_pick_camera, CommentAttachmentSheet.Kind.CAMERA);
        bindTile(root, R.id.take_pick_video, CommentAttachmentSheet.Kind.VIDEO);
        bindTile(root, R.id.take_pick_voice, CommentAttachmentSheet.Kind.VOICE);
        bindTile(root, R.id.take_pick_gif, CommentAttachmentSheet.Kind.GIF);
        bindTile(root, R.id.take_pick_document, CommentAttachmentSheet.Kind.DOCUMENT);
    }

    boolean isOpen() {
        return mOpen;
    }

    boolean isPickerShown() {
        return mPickerShown;
    }

    /** Opens the sheet over the comments, the keyboard (or with {@code picker}, the picker) up. */
    void open(Topic topic, boolean picker) {
        if (mOpen) {
            if (picker) showPicker();
            return;
        }
        mOpen = true;
        setSqueeze(Squeeze.NONE);
        bindTopic(topic);
        keepTouches(true);
        mHost.showComposerNow();
        mBar.animate().cancel();
        mBar.setBackgroundResource(R.drawable.browser_express_take_sheet_bg);
        mBar.setPadding(dp(16), 0, dp(16), dp(12));
        mBar.setElevation(dp(12));
        mHeader.setVisibility(View.VISIBLE);
        mBoxHint = mInput.getHint();
        mInput.setHint(R.string.browser_express_take_hint);

        boolean overReel = mHost.isTakeOverReel();
        // Paused, and kept paused while the keyboard or picker lays the page out again.
        GlobalVideoPlaybackManager.getInstance().setTakeComposerOpen(true);
        if (overReel) blurPages(true);
        mScrim.setBackgroundColor(ContextCompat.getColor(mContext,
                overReel ? R.color.browser_express_take_backdrop_reel
                         : R.color.browser_express_take_backdrop));
        mScrim.animate().cancel();
        mScrim.setVisibility(View.VISIBLE);
        mScrim.setAlpha(0f);
        boolean motion = ValueAnimator.areAnimatorsEnabled();
        mScrim.animate().alpha(1f).setDuration(motion ? OPEN_MS : REDUCED_MS)
                .setInterpolator(EASE_OUT);
        if (motion) {
            // The sheet rises as the keyboard does, from where its new height puts it.
            OneShotPreDrawListener.add(mBar, () -> {
                mBar.setTranslationY(mBar.getHeight());
                mBar.animate().translationY(0f).setDuration(OPEN_MS).setInterpolator(EASE_OUT);
            });
        }
        mStream.start();
        if (picker) {
            showPicker();
        } else {
            mHost.focusTakeInput();
        }
    }

    /** × or the backdrop: closes, asking first if a take is half written. */
    void requestClose() {
        if (!mOpen) return;
        if (!mHost.hasTakeDraft()) {
            close();
            return;
        }
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.browser_express_take_discard_title)
                .setPositiveButton(R.string.browser_express_take_discard, (dialog, which) -> {
                    mHost.discardTakeDraft();
                    close();
                })
                .setNegativeButton(R.string.browser_express_take_keep_editing, null)
                .show();
    }

    /** Closes at once: after sending, or once the reader agreed to discard. */
    void close() {
        if (!mOpen) return;
        mOpen = false;
        hidePicker(false);
        mHost.hideTakeKeyboard();
        mStream.stop();
        mBar.animate().cancel();
        mBar.setTranslationY(0f);
        mBar.setBackground(mBarBackground);
        mBar.setPadding(mBarPadding[0], mBarPadding[1], mBarPadding[2], mBarPadding[3]);
        mBar.setElevation(mBarElevation);
        mHeader.setVisibility(View.GONE);
        mInput.setHint(mBoxHint);

        blurPages(false);
        mScrim.animate().cancel();
        boolean motion = ValueAnimator.areAnimatorsEnabled();
        mScrim.animate().alpha(0f).setDuration(motion ? CLOSE_MS : REDUCED_MS)
                .setInterpolator(EASE_IN_OUT).withEndAction(() -> {
                    if (!mOpen) mScrim.setVisibility(View.GONE);
                });
        GlobalVideoPlaybackManager.getInstance().setTakeComposerOpen(false);
        keepTouches(false);
        mBar.removeCallbacks(mFitTopic);
        setSqueeze(Squeeze.NONE);
        mHost.onTakeClosed();
    }

    /**
     * While the sheet is up, a tap on its own background (or the picker's) stays there. The scrim
     * lies under the whole sheet, so such a tap would otherwise reach it and close the sheet,
     * asking to discard what was written or picked.
     */
    @SuppressLint("ClickableViewAccessibility") // Swallows touches; it never clicks anything.
    private void keepTouches(boolean keep) {
        View.OnTouchListener swallow = keep ? (v, event) -> true : null;
        mBar.setOnTouchListener(swallow);
        mPicker.setOnTouchListener(swallow);
    }

    /**
     * Keeps the comment box on screen. With the keyboard up, an attachment preview can leave the
     * sheet taller than the room above the keyboard, which cut the box — and its send button —
     * off at the keyboard's edge. While that is so the sheet gives way a step at a time, one per
     * layout: first the topic card goes, then (on a small phone) the preview shrinks. Each step
     * is undone once there is room for it again (the keyboard down, the file removed).
     */
    private void fitToRoom() {
        if (!mOpen) return;
        View panel = (View) mBar.getParent();
        // Measured smaller than it asked for: its bottom, the box, is cut off.
        boolean cramped = (mBar.getMeasuredHeightAndState() & View.MEASURED_STATE_TOO_SMALL) != 0
                || (panel.getMeasuredHeightAndState() & View.MEASURED_STATE_TOO_SMALL) != 0;
        int spare = mBody.getHeight() - panel.getHeight();
        if (cramped) {
            setSqueeze(Math.min(mSqueeze + 1, Squeeze.COMPACT_PREVIEW));
        } else if (mSqueeze == Squeeze.COMPACT_PREVIEW
                && spare >= mPreviewHeight - dp(COMPACT_PREVIEW_DP)) {
            setSqueeze(Squeeze.NO_TOPIC);
        } else if (mSqueeze == Squeeze.NO_TOPIC && spare >= mTopic.getLayoutParams().height) {
            setSqueeze(Squeeze.NONE);
        }
    }

    private void setSqueeze(@Squeeze int squeeze) {
        if (squeeze == mSqueeze) return;
        mSqueeze = squeeze;
        mTopic.setVisibility(squeeze >= Squeeze.NO_TOPIC ? View.GONE : View.VISIBLE);
        boolean compact = squeeze >= Squeeze.COMPACT_PREVIEW;
        ViewGroup.LayoutParams preview = mPreview.getLayoutParams();
        preview.height = compact ? dp(COMPACT_PREVIEW_DP) : mPreviewHeight;
        mPreview.setLayoutParams(preview);
        // The thumbnail keeps the full preview's 6dp to spare.
        ViewGroup.LayoutParams frame = mPreviewFrame.getLayoutParams();
        frame.width = frame.height = compact
                ? dp(COMPACT_PREVIEW_DP) - (mPreviewHeight - mPreviewThumb)
                : mPreviewThumb;
        mPreviewFrame.setLayoutParams(frame);
    }

    /** The picker takes the keyboard's place: the keyboard goes down as the picker comes up. */
    void showPicker() {
        if (!mOpen || mPickerShown) return;
        mPickerShown = true;
        mHost.hideTakeKeyboard();
        mPicker.animate().cancel();
        mPicker.setVisibility(View.VISIBLE);
        if (ValueAnimator.areAnimatorsEnabled()) {
            mPicker.setTranslationY(dp(300));
            mPicker.animate().translationY(0f).setStartDelay(PICKER_DELAY_MS)
                    .setDuration(PICKER_MS).setInterpolator(EASE_OUT);
        } else {
            mPicker.setTranslationY(0f);
        }
    }

    /** Puts the picker away; {@code keyboard} brings the keyboard back to keep writing. */
    void hidePicker(boolean keyboard) {
        if (!mPickerShown) return;
        mPickerShown = false;
        mPicker.animate().cancel();
        mPicker.setVisibility(View.GONE);
        if (keyboard && mOpen) mHost.focusTakeInput();
    }

    private void bindTile(View root, int id, @CommentAttachmentSheet.Kind int kind) {
        root.findViewById(id).setOnClickListener(v -> {
            hidePicker(false);
            mHost.onTakeAttachmentChosen(kind);
        });
    }

    private void bindTopic(Topic topic) {
        mTitle.setText(topic.title);
        CharSequence stats = statsLine(topic);
        mStats.setText(stats);
        mTopic.setContentDescription(topic.title + ", " + stats);
        fitCover(mTopic.getWidth());
        mStream.setContent(topic.faces, topic.emoji);
    }

    /** The cover's rings (see {@link #COVER_RINGS}), drawn to whatever size the cover is. */
    private static Drawable coverDrawable() {
        int rings = COVER_RINGS.length;
        // Each ring's colour runs from its start to the next one's, so the edges are hard.
        int[] colors = new int[rings * 2];
        float[] stops = new float[rings * 2];
        for (int i = 0; i < rings; i++) {
            colors[2 * i] = COVER_RINGS[i];
            colors[2 * i + 1] = COVER_RINGS[i];
            stops[2 * i] = i == 0 ? 0f : COVER_RING_STARTS[i - 1];
            stops[2 * i + 1] = i == rings - 1 ? 1f : COVER_RING_STARTS[i];
        }
        ShapeDrawable cover = new ShapeDrawable(new RectShape());
        cover.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int w, int h) {
                float cx = 0.7f * w;
                float cy = 1.2f * h;
                // CSS's "circle" with no size: out to the farthest corner, here a top one.
                float radius = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
                return new RadialGradient(cx, cy, Math.max(radius, 1f), colors, stops,
                        Shader.TileMode.CLAMP);
            }
        });
        return cover;
    }

    /**
     * Sizes the cover to the right 62% of the card and lays the card's tone over it, solid to 38%
     * of the way across, 82% at 58% and gone by the right edge, so the title reads on the left.
     */
    private void fitCover(int width) {
        if (width <= 0) return;
        int coverWidth = Math.round(width * COVER_SHARE);
        if (mCover.getLayoutParams().width != coverWidth) {
            mCover.getLayoutParams().width = coverWidth;
            mCover.requestLayout();
        }
        int tone = ContextCompat.getColor(mContext, R.color.browser_express_take_topic_bg);
        ShapeDrawable fade = new ShapeDrawable(new RectShape());
        fade.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, w, 0,
                        new int[] {tone, tone, ColorUtils.setAlphaComponent(tone, 209),
                            Color.TRANSPARENT},
                        new float[] {0f, 0.38f, 0.58f, 1f}, Shader.TileMode.CLAMP);
            }
        });
        mFade.setBackground(fade);
    }

    /**
     * "148 reactions · 2.3K emotes": the numbers 600 in the primary colour, the words in the
     * line's own, and a 3dp dot between them.
     */
    private CharSequence statsLine(Topic topic) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        appendCount(line, R.plurals.browser_express_take_stats_reactions, topic.reactions);
        int dotStart = line.length();
        line.append("·");
        line.setSpan(new DotSpan(dp(STATS_DOT_DP), dp(STATS_GAP_DP),
                             ContextCompat.getColor(mContext, R.color.browser_express_take_stats_dot)),
                dotStart, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendCount(line, R.plurals.browser_express_take_stats_emotes, topic.emotes);
        return line;
    }

    private void appendCount(SpannableStringBuilder line, int pluralId, int count) {
        String number = CompactDecimalFormat
                .getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                .format(count);
        String text = mContext.getResources().getQuantityString(pluralId, count, number);
        int start = line.length();
        line.append(text);
        int at = text.indexOf(number);
        if (at < 0) return;
        line.setSpan(new TypefaceSpan(Typeface.create(Typeface.SANS_SERIF, 600, false)),
                start + at, start + at + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new ForegroundColorSpan(ContextCompat.getColor(
                             mContext, R.color.browser_express_take_stats_number)),
                start + at, start + at + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void blurPages(boolean on) {
        View pages = mHost.takePages();
        if (pages == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        pages.setRenderEffect(on
                ? RenderEffect.createBlurEffect(BLUR_DP * mDp, BLUR_DP * mDp,
                        Shader.TileMode.CLAMP)
                : null);
    }

    private int dp(float value) {
        return Math.round(value * mDp);
    }

    /**
     * The topic card's content. {@code commentCount} and {@code emoteCount} are the server's
     * totals, or -1 when it gave none, and then come from the comments loaded so far; the faces
     * and emoji always do.
     */
    static Topic topicFrom(
            String title, int commentCount, int emoteCount, List<Comment> comments) {
        int emotes = 0;
        List<String> faces = new ArrayList<>();
        List<String> emoji = new ArrayList<>();
        for (Comment comment : comments) {
            emotes += Math.max(0, comment.getReactionCount());
            String face = CommentListAdapter.avatarUrlFor(comment);
            if (face != null && faces.size() < 4 && !faces.contains(face)) faces.add(face);
            for (Comment.ReactionCount reaction : comment.getReactions()) {
                if (emoji.size() < 4 && !emoji.contains(reaction.getEmoji())) {
                    emoji.add(reaction.getEmoji());
                }
            }
        }
        return new Topic(title,
                commentCount >= 0 ? commentCount : comments.size(),
                emoteCount >= 0 ? emoteCount : emotes,
                faces, emoji);
    }
    /** A filled circle standing in for its text, centred on the line, with room either side. */
    private static final class DotSpan extends ReplacementSpan {
        private final int mSize;
        private final int mGap;
        private final int mColor;

        DotSpan(int size, int gap, int color) {
            mSize = size;
            mGap = gap;
            mColor = color;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                @Nullable Paint.FontMetricsInt fm) {
            return mGap + mSize + mGap;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            int color = paint.getColor();
            Paint.Style style = paint.getStyle();
            paint.setColor(mColor);
            paint.setStyle(Paint.Style.FILL);
            // Centred on the text, as the design's row centres it between the words.
            float centerY = y + (paint.ascent() + paint.descent()) / 2f;
            canvas.drawCircle(x + mGap + mSize / 2f, centerY, mSize / 2f, paint);
            paint.setColor(color);
            paint.setStyle(style);
        }
    }
}
