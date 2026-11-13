/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.res.Resources;
import android.icu.text.CompactDecimalFormat;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The overlapping stack of the most used reaction emoji on a page or post, drawn by the floating
 * comments bar and by the feed cards' reaction rails. Each slot is a 16dp TextView (style
 * BrowserExpressCommentsBarReaction): 👍, ❤️ and 😮 use the design's icons, other emoji are drawn
 * as text.
 */
public final class ReactionMix {
    /** How many emoji the stack shows. */
    public static final int MAX_SHOWN = 3;

    /** How far each emoji tucks under the next one, in dp. */
    private static final int OVERLAP_DP = 5;

    /** The XP unit's size against its number's: 9sp beside 11sp. */
    private static final float XP_UNIT_SCALE = 9f / 11f;

    private ReactionMix() {}

    /**
     * The most used emoji from a server {@code reactions: [{emoji, count}]} array, which comes
     * sorted most used first; at most {@link #MAX_SHOWN}.
     */
    public static List<String> topEmoji(@Nullable JSONArray reactions) {
        List<String> top = new ArrayList<>();
        for (int i = 0; reactions != null && i < reactions.length() && top.size() < MAX_SHOWN; i++) {
            JSONObject reaction = reactions.optJSONObject(i);
            String emoji = reaction == null ? "" : reaction.optString("emoji", "");
            if (!emoji.isEmpty()) top.add(emoji);
        }
        return top;
    }

    /**
     * A post's reaction-rail headline: its comment count as "N reactions", or an invitation when
     * it has no comments.
     */
    public static String railHeadline(Resources res, int commentCount) {
        if (commentCount > 0) {
            return res.getQuantityString(
                    R.plurals.browser_express_comments_bar_reaction_count,
                    commentCount,
                    commentCount);
        }
        return res.getString(R.string.browser_express_comments_bar_join_description);
    }

    /**
     * Shows a post's XP, every emoji given to its comments, as "1.2K XP" with the unit a size
     * smaller than the number (the design's 9sp against 11sp); hides {@code xp} at none.
     */
    public static void bindXp(TextView xp, int emojiCount) {
        if (emojiCount <= 0) {
            xp.setVisibility(View.GONE);
            return;
        }
        String number =
                CompactDecimalFormat.getInstance(
                                Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                        .format(emojiCount);
        String text = xp.getResources().getString(R.string.browser_express_post_xp, number);
        SpannableString label = new SpannableString(text);
        int at = text.indexOf(number);
        if (at >= 0) {
            // Everything but the number, and the space beside it, is the smaller unit.
            int start = at;
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
            int end = at + number.length();
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
            if (start > 0) shrinkUnit(label, 0, start);
            if (end < text.length()) shrinkUnit(label, end, text.length());
        }
        xp.setText(label);
        xp.setVisibility(View.VISIBLE);
    }

    private static void shrinkUnit(SpannableString label, int start, int end) {
        label.setSpan(
                new RelativeSizeSpan(XP_UNIT_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Shows {@code emoji} in {@code slots}, hiding the unused ones, and {@code mix} if none. */
    public static void bind(View mix, TextView[] slots, List<String> emoji) {
        bind(mix, slots, emoji, OVERLAP_DP);
    }

    /** As {@link #bind(View, TextView[], List)}, each emoji tucking {@code overlapDp} under the next. */
    public static void bind(View mix, TextView[] slots, List<String> emoji, float overlapDp) {
        int shown = Math.min(emoji.size(), slots.length);
        mix.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        Resources res = mix.getResources();
        int overlap = -Math.round(overlapDp * res.getDisplayMetrics().density);
        for (int i = 0; i < slots.length; i++) {
            TextView slot = slots[i];
            if (i >= shown) {
                slot.setVisibility(View.GONE);
                continue;
            }
            String value = emoji.get(i);
            int icon = iconFor(value);
            slot.setBackgroundResource(icon);
            slot.setText(icon != 0 ? "" : value);
            slot.setVisibility(View.VISIBLE);
            // Each emoji tucks under the next one; the last one ends the row.
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) slot.getLayoutParams();
            params.setMarginEnd(i < shown - 1 ? overlap : 0);
            slot.setLayoutParams(params);
        }
    }

    /** The design's icon for {@code emoji}, or 0 for emoji it has none for. */
    private static int iconFor(String emoji) {
        switch (emoji.replace("️", "")) {
            case "👍": // 👍
                return R.drawable.ic_browser_express_reaction_like;
            case "❤": // ❤
                return R.drawable.ic_browser_express_reaction_love;
            case "😮": // 😮
                return R.drawable.ic_browser_express_reaction_wow;
            default:
                return 0;
        }
    }
}
