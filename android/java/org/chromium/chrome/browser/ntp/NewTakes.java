/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A post's "12 new takes since you last looked" (design_handoff_new_takes): the takes posted
 * since the reader last opened its stream, as the feed sends them in {@code newTakes}. The server
 * leaves it null until there are enough of them (browser-express-node docs/new-takes.md).
 */
public final class NewTakes {
    /** Above this the title reads "99+ new takes". */
    public static final int MAX_COUNT_SHOWN = 99;
    private static final int MAX_EMOJI = 3;

    public final int count;
    public final int videos;
    public final int photos;
    public final int voice;
    public final int text;
    /** Every reaction on the new takes. */
    public final int reactionsTotal;
    /** The most-used emoji among them, most first. */
    public final List<String> topEmoji;
    /** The oldest new take: where the stream opens when the card is tapped. */
    public final @Nullable String firstNewTakeId;

    private NewTakes(int count, int videos, int photos, int voice, int text, int reactionsTotal,
            List<String> topEmoji, @Nullable String firstNewTakeId) {
        this.count = count;
        this.videos = videos;
        this.photos = photos;
        this.voice = voice;
        this.text = text;
        this.reactionsTotal = reactionsTotal;
        this.topEmoji = Collections.unmodifiableList(topEmoji);
        this.firstNewTakeId = firstNewTakeId;
    }

    /** The feed's {@code newTakes}, or null when it is missing, null or empty. */
    static @Nullable NewTakes fromJson(@Nullable JSONObject json) {
        if (json == null) return null;
        int count = json.optInt("count", 0);
        if (count <= 0) return null;
        JSONObject byFormat = json.optJSONObject("byFormat");
        if (byFormat == null) byFormat = new JSONObject();
        List<String> emoji = new ArrayList<>();
        JSONArray top = json.optJSONArray("topEmoji");
        for (int i = 0; top != null && i < top.length() && emoji.size() < MAX_EMOJI; i++) {
            String value = top.optString(i, "");
            if (!value.isEmpty()) emoji.add(value);
        }
        String first = json.isNull("firstNewTakeId") ? "" : json.optString("firstNewTakeId", "");
        return new NewTakes(count,
                Math.max(0, byFormat.optInt("video", 0)),
                Math.max(0, byFormat.optInt("photo", 0)),
                Math.max(0, byFormat.optInt("voice", 0)),
                Math.max(0, byFormat.optInt("text", 0)),
                Math.max(0, json.optInt("reactionsTotal", 0)),
                emoji,
                first.isEmpty() ? null : first);
    }

    /** How many of the four formats have takes; with all four the chips go compact. */
    int formatCount() {
        return (videos > 0 ? 1 : 0) + (photos > 0 ? 1 : 0) + (voice > 0 ? 1 : 0)
                + (text > 0 ? 1 : 0);
    }
}
