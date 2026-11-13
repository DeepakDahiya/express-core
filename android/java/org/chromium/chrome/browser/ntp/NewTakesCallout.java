/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.icu.text.CompactDecimalFormat;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.browser_express_comments.ReactionMix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "12 new takes since you last looked" on a feed card (design_handoff_new_takes §2b and §3): the
 * count, chips for the formats the takes came in, and the reactions on them. The card shows it
 * centred over a photo or video, under a {@link DimDrawable}, or as a panel under a text post's
 * caption. The contents are the same, only the title is a size smaller in the panel.
 */
public class NewTakesCallout extends LinearLayout {
    private static final float TITLE_SP = 24f;
    private static final float TITLE_LINE_SP = 28f;
    private static final float PANEL_TITLE_SP = 22f;
    private static final float PANEL_TITLE_LINE_SP = 26f;
    private static final float CHIP_PADDING_DP = 10f;
    /** With all four formats the chips drop their words to fit 320dp-wide media. */
    private static final float COMPACT_CHIP_PADDING_DP = 12f;
    private static final float CHIP_GAP_DP = 6f;
    private static final float EMOJI_OVERLAP_DP = 2f;
    /** The demoted rail's emoji: CSS saturate(.4). */
    private static final float DEMOTED_SATURATION = 0.4f;

    private final TextView mTitle;
    private final TextView mSub;
    private final View[] mChips;
    private final TextView[] mChipLabels;
    private final View mQuality;
    private final View mEmoji;
    private final TextView[] mEmojiSlots;
    private final TextView mQualityLabel;

    public NewTakesCallout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutInflater.from(context).inflate(R.layout.browser_express_post_new_takes, this, true);
        mTitle = findViewById(R.id.new_takes_title);
        mSub = findViewById(R.id.new_takes_sub);
        mChips = new View[] {
            findViewById(R.id.new_takes_chip_video),
            findViewById(R.id.new_takes_chip_photo),
            findViewById(R.id.new_takes_chip_voice),
            findViewById(R.id.new_takes_chip_text)
        };
        // The icon chips are their own labels (the icon is a compound drawable); the text chip's
        // "Aa" is a second text beside its label.
        mChipLabels = new TextView[] {
            (TextView) mChips[0],
            (TextView) mChips[1],
            (TextView) mChips[2],
            findViewById(R.id.new_takes_chip_text_label)
        };
        mQuality = findViewById(R.id.new_takes_quality);
        mEmoji = findViewById(R.id.new_takes_emoji);
        mEmojiSlots = new TextView[] {
            findViewById(R.id.new_takes_emoji_1),
            findViewById(R.id.new_takes_emoji_2),
            findViewById(R.id.new_takes_emoji_3)
        };
        mQualityLabel = findViewById(R.id.new_takes_quality_label);
        // Read out as one sentence (see bind), not piece by piece.
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    /**
     * @param inPanel whether this is the text post's panel, whose title is a size smaller than
     *     the one over media.
     */
    void bind(NewTakes takes, boolean inPanel) {
        Resources res = getResources();
        mTitle.setText(takes.count > NewTakes.MAX_COUNT_SHOWN
                        ? res.getString(R.string.browser_express_new_takes_title_max)
                        : res.getQuantityString(
                                R.plurals.browser_express_new_takes_title, takes.count, takes.count));
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, inPanel ? PANEL_TITLE_SP : TITLE_SP);
        mTitle.setLineHeight(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                inPanel ? PANEL_TITLE_LINE_SP : TITLE_LINE_SP, res.getDisplayMetrics())));

        int[] counts = {takes.videos, takes.photos, takes.voice, takes.text};
        int[] plurals = {
            R.plurals.browser_express_new_takes_videos,
            R.plurals.browser_express_new_takes_photos,
            R.plurals.browser_express_new_takes_voice,
            R.plurals.browser_express_new_takes_text
        };
        boolean compact = takes.formatCount() == counts.length;
        int padding = dp(compact ? COMPACT_CHIP_PADDING_DP : CHIP_PADDING_DP);
        List<String> spoken = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < mChips.length; i++) {
            View chip = mChips[i];
            if (counts[i] <= 0) {
                chip.setVisibility(GONE);
                continue;
            }
            String label = res.getQuantityString(plurals[i], counts[i], counts[i]);
            spoken.add(label);
            mChipLabels[i].setText(compact ? String.format(Locale.getDefault(), "%d", counts[i]) : label);
            chip.setPaddingRelative(padding, 0, padding, 0);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chip.getLayoutParams();
            // The first chip shown starts the row, so the row stays centred.
            params.setMarginStart(first ? 0 : dp(CHIP_GAP_DP));
            chip.setLayoutParams(params);
            chip.setVisibility(VISIBLE);
            first = false;
        }

        String quality = null;
        if (takes.reactionsTotal > 0) {
            String number = CompactDecimalFormat.getInstance(
                            Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                    .format(takes.reactionsTotal);
            quality = res.getQuantityString(R.plurals.browser_express_new_takes_reactions,
                    takes.reactionsTotal, number);
            SpannableString label = new SpannableString(quality);
            int at = quality.indexOf(number);
            if (at >= 0) {
                int end = at + number.length();
                label.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), at, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                label.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            mQualityLabel.setText(label);
            ReactionMix.bind(mEmoji, mEmojiSlots, takes.topEmoji, EMOJI_OVERLAP_DP);
            mQuality.setVisibility(VISIBLE);
        } else {
            mQuality.setVisibility(GONE);
        }

        StringBuilder description = new StringBuilder(res.getString(
                R.string.browser_express_new_takes_description, mTitle.getText(), mSub.getText(),
                String.join(", ", spoken)));
        if (quality != null) description.append(". ").append(quality);
        setContentDescription(description);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Fades {@code view}'s colours to the demoted rail's 40% saturation (CSS saturate(.4)), or
     * back to full colour.
     */
    static void setDesaturated(View view, boolean desaturated) {
        if (!desaturated) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(DEMOTED_SATURATION);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    /**
     * The layer over the media while the callout shows (§2a): an even 60% dim, plus a soft
     * vignette that keeps white text readable on very bright media. The vignette is the CSS
     * radial-gradient(60% 55% at 50% 45%, …): an ellipse 60% of the width across and 55% of the
     * height tall, centred 45% of the way down.
     */
    static final class DimDrawable extends Drawable {
        private static final float CENTER_X = 0.5f;
        private static final float CENTER_Y = 0.45f;
        private static final float RADIUS_X = 0.6f;
        private static final float RADIUS_Y = 0.55f;

        private final Paint mDim = new Paint();
        private final Paint mVignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mDimAlpha;
        private final int mVignetteColor;

        DimDrawable(Context context) {
            mDim.setColor(ContextCompat.getColor(context, R.color.browser_express_post_new_takes_dim));
            mDimAlpha = mDim.getAlpha();
            mVignetteColor =
                    ContextCompat.getColor(context, R.color.browser_express_post_new_takes_vignette);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            if (bounds.isEmpty()) {
                mVignette.setShader(null);
                return;
            }
            float cx = bounds.left + bounds.width() * CENTER_X;
            float cy = bounds.top + bounds.height() * CENTER_Y;
            float rx = bounds.width() * RADIUS_X;
            float ry = bounds.height() * RADIUS_Y;
            RadialGradient gradient = new RadialGradient(cx, cy, rx,
                    new int[] {mVignetteColor, mVignetteColor & 0x00FFFFFF}, null,
                    Shader.TileMode.CLAMP);
            // A circle of radius rx, squashed to ry tall about the centre.
            Matrix squash = new Matrix();
            squash.setScale(1f, ry / rx, cx, cy);
            gradient.setLocalMatrix(squash);
            mVignette.setShader(gradient);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRect(bounds, mDim);
            if (mVignette.getShader() != null) canvas.drawRect(bounds, mVignette);
        }

        @Override
        public void setAlpha(int alpha) {
            // Scales the colours' own alpha rather than replacing it.
            mDim.setAlpha(mDimAlpha * alpha / 255);
            mVignette.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mDim.setColorFilter(colorFilter);
            mVignette.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
