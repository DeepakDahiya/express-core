/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Posts the reader marked "Not interested". The feed leaves them out on this device whatever the
 * server sends, so a hidden post stays gone even before the server ranks on the {@code hide}
 * events {@link FeedEvents} reports. Only the newest {@link #MAX_HIDDEN} are kept; posts that old
 * have long left the feed.
 */
public final class HiddenFeedPosts {
    private static final String TAG = "HiddenFeedPosts";
    private static final String PREFS = "browser_express_hidden_posts";
    /** A JSON object of post id → when it was hidden (epoch ms). */
    private static final String PREF_HIDDEN = "hidden";
    private static final int MAX_HIDDEN = 500;

    /** Post id → when it was hidden; loaded on first use. */
    @Nullable private static Map<String, Long> sHidden;

    private HiddenFeedPosts() {}

    public static boolean isHidden(@Nullable String postId) {
        return postId != null && hidden().containsKey(postId);
    }

    public static void hide(String postId) {
        Map<String, Long> hidden = hidden();
        hidden.put(postId, System.currentTimeMillis());
        if (hidden.size() > MAX_HIDDEN) dropOldest(hidden);
        save(hidden);
    }

    public static void unhide(String postId) {
        Map<String, Long> hidden = hidden();
        if (hidden.remove(postId) != null) save(hidden);
    }

    private static Map<String, Long> hidden() {
        if (sHidden != null) return sHidden;
        sHidden = new HashMap<>();
        String raw = prefs().getString(PREF_HIDDEN, null);
        if (raw == null) return sHidden;
        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> ids = json.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                sHidden.put(id, json.optLong(id, 0));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Dropping unreadable hidden posts: " + e.getMessage());
            prefs().edit().remove(PREF_HIDDEN).apply();
        }
        return sHidden;
    }

    private static void dropOldest(Map<String, Long> hidden) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(hidden.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(a.getValue(), b.getValue()));
        for (int i = 0; i < entries.size() - MAX_HIDDEN; i++) {
            hidden.remove(entries.get(i).getKey());
        }
    }

    private static void save(Map<String, Long> hidden) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Long> entry : hidden.entrySet()) {
                json.put(entry.getKey(), (long) entry.getValue());
            }
            prefs().edit().putString(PREF_HIDDEN, json.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Could not save hidden posts: " + e.getMessage());
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(PREFS, 0);
    }
}
