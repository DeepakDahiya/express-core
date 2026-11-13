/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.referral.ReferralHelper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the reader does with the feed, so the server can rank it for them and stop repeating
 * posts: which posts were really on screen and for how long, how much of a video they watched,
 * and which posts they opened, read in full, shared or hid. Sent in batches to {@code POST
 * /v1/post/feed/events}:
 *
 * <pre>
 * { batchId, deviceId, appVersion, sentAt, events: [
 *     { type, postId, session?, at, lastAt, count, ...fields } ] }
 * </pre>
 *
 * <ul>
 *   <li>{@code impression}: the card was at least half on screen for {@code visibleMs}, at
 *       {@code position} in the feed (0 = first post). Only reported once a post has had a
 *       second on screen in the feed session.
 *   <li>{@code video}: {@code watchMs} of the video played, {@code audibleMs} of it with sound,
 *       out of {@code durationMs}; {@code surface} "feed" (autoplay on the card) or "viewer"
 *       (full screen). Feed videos loop, so watchMs can pass durationMs.
 *   <li>{@code open}: the card was tapped through to its {@code target}: "comments", "video"
 *       (full screen, comments under it) or "article".
 *   <li>{@code expand}: the reader opened the rest of a cut caption.
 *   <li>{@code share}: shared, from {@code surface}.
 *   <li>{@code hide} / {@code unhide}: "Not interested", and its undo. The later {@code lastAt}
 *       wins.
 * </ul>
 *
 * <p>{@code session} names one showing of the feed (one new tab page visit); feed-surface events
 * carry it. Events of the same type for the same post, session and surface or target merge
 * before they are sent: their counts and milliseconds add up, {@code at} is the first and {@code
 * lastAt} the last. Times are epoch ms on the device; {@code sentAt} lets the server correct for
 * a wrong clock. The server should dedupe on {@code batchId}, since a batch whose answer got lost
 * is sent again as it was.
 *
 * <p>Events gather in memory and are written to prefs before each send and when the app goes to
 * the background, so a process killed there keeps them. The rest follows
 * ReferralUsageTracker: a batch being sent waits in an in-flight slot under its batchId, failures
 * back off up to {@link #RETRY_MAX_MS}, a 400 drops the batch and a 401/403 keeps it for the
 * next session. Only the newest {@link #MAX_PENDING_EVENTS} events are kept while nothing can be
 * sent.
 */
public final class FeedEvents {
    private static final String TAG = "FeedEvents";
    private static final String EVENTS_URL = "https://api.browser.express/v1/post/feed/events";

    private static final String TYPE_IMPRESSION = "impression";
    private static final String TYPE_VIDEO = "video";
    private static final String TYPE_OPEN = "open";
    private static final String TYPE_EXPAND = "expand";
    private static final String TYPE_SHARE = "share";
    private static final String TYPE_HIDE = "hide";
    private static final String TYPE_UNHIDE = "unhide";

    public static final String SURFACE_FEED = "feed";
    public static final String SURFACE_VIEWER = "viewer";

    public static final String OPEN_COMMENTS = "comments";
    public static final String OPEN_VIDEO = "video";
    public static final String OPEN_ARTICLE = "article";

    /** Fields that add up when two events merge. */
    private static final Set<String> SUMMED_FIELDS =
            new HashSet<>(Arrays.asList("count", "visibleMs", "watchMs", "audibleMs"));

    private static final String PREFS = "browser_express_feed_events";
    /** A JSON array of events not yet taken into a batch. */
    private static final String PREF_PENDING = "pending";
    /** The batch being sent: {batchId, events, attempts}. */
    private static final String PREF_INFLIGHT = "inflight";
    private static final String PREF_FAILURES = "failures";
    private static final String PREF_NEXT_RETRY_MS = "next_retry_ms";

    private static final int MAX_PENDING_EVENTS = 300;
    /** Sends once this many events are waiting, rather than at the next interval. */
    private static final int FLUSH_AT_EVENTS = 50;
    private static final long FLUSH_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long RETRY_BASE_MS = 5 * 60 * 1000L;
    private static final long RETRY_MAX_MS = 6 * 60 * 60 * 1000L;
    /** A batch that keeps failing is dropped rather than holding up every later one. */
    private static final int MAX_BATCH_ATTEMPTS = 40;
    private static final int HTTP_TIMEOUT_MS = 10000;

    private static final Object sLock = new Object();
    private static final AtomicBoolean sFlushRunning = new AtomicBoolean(false);
    /** Events waiting to be sent, by merge key, oldest first; null until loaded from prefs. */
    @Nullable private static LinkedHashMap<String, JSONObject> sPending;
    private static long sLastFlushAttemptMs;
    private static boolean sWatchingAppState;

    private FeedEvents() {}

    // ---------------------------------------------------------------------
    // Recording (UI thread)
    // ---------------------------------------------------------------------

    /** The card for {@code postId} was at least half on screen for another {@code visibleMs}. */
    public static void recordImpression(
            String postId, String session, int position, long visibleMs) {
        JSONObject event = event(TYPE_IMPRESSION, postId, session);
        put(event, "position", position);
        put(event, "visibleMs", visibleMs);
        record(event, false);
    }

    /** {@code watchMs} more of a post's video played, {@code audibleMs} of it with sound. */
    public static void recordVideoWatch(
            String postId,
            @Nullable String session,
            String surface,
            long watchMs,
            long audibleMs,
            long durationMs) {
        if (watchMs <= 0) return;
        JSONObject event = event(TYPE_VIDEO, postId, session);
        put(event, "surface", surface);
        put(event, "watchMs", watchMs);
        put(event, "audibleMs", audibleMs);
        if (durationMs > 0) put(event, "durationMs", durationMs);
        record(event, false);
    }

    public static void recordOpen(String postId, String session, String target) {
        JSONObject event = event(TYPE_OPEN, postId, session);
        put(event, "target", target);
        record(event, false);
    }

    public static void recordExpand(String postId, String session) {
        record(event(TYPE_EXPAND, postId, session), false);
    }

    public static void recordShare(String postId, String surface) {
        JSONObject event = event(TYPE_SHARE, postId, null);
        put(event, "surface", surface);
        record(event, false);
    }

    /** "Not interested" ({@code hidden}) or its undo. Sent straight away. */
    public static void recordHide(String postId, String session, boolean hidden) {
        record(event(hidden ? TYPE_HIDE : TYPE_UNHIDE, postId, session), true);
    }

    private static JSONObject event(String type, String postId, @Nullable String session) {
        long now = System.currentTimeMillis();
        JSONObject event = new JSONObject();
        put(event, "type", type);
        put(event, "postId", postId);
        if (session != null) put(event, "session", session);
        put(event, "at", now);
        put(event, "lastAt", now);
        put(event, "count", 1);
        return event;
    }

    private static void put(JSONObject event, String key, Object value) {
        try {
            event.put(key, value);
        } catch (JSONException e) {
            Log.e(TAG, "Could not set " + key + ": " + e.getMessage());
        }
    }

    private static void record(JSONObject event, boolean urgent) {
        if (event.optString("postId", "").isEmpty()) return;
        watchAppState();
        int waiting;
        synchronized (sLock) {
            LinkedHashMap<String, JSONObject> pending = pending();
            String key = keyOf(event);
            JSONObject earlier = pending.get(key);
            if (earlier != null) {
                merge(earlier, event);
            } else {
                pending.put(key, event);
                Iterator<String> oldest = pending.keySet().iterator();
                while (pending.size() > MAX_PENDING_EVENTS && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            waiting = pending.size();
        }
        long now = System.currentTimeMillis();
        if (urgent
                || waiting >= FLUSH_AT_EVENTS
                || now - sLastFlushAttemptMs >= FLUSH_INTERVAL_MS) {
            flushAsync(urgent ? "hide" : "batch");
        }
    }

    private static String keyOf(JSONObject event) {
        return event.optString("type")
                + '|' + event.optString("postId")
                + '|' + event.optString("session")
                + '|' + event.optString("surface")
                + '|' + event.optString("target");
    }

    private static void merge(JSONObject into, JSONObject from) {
        Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (SUMMED_FIELDS.contains(key)) {
                put(into, key, into.optLong(key, 0) + from.optLong(key, 0));
            } else if ("lastAt".equals(key) || "durationMs".equals(key)) {
                put(into, key, Math.max(into.optLong(key, 0), from.optLong(key, 0)));
            } else if (!into.has(key)) {
                put(into, key, from.opt(key));
            }
        }
    }

    /** Sends what is waiting each time the app goes to the background. */
    private static void watchAppState() {
        if (sWatchingAppState || !ThreadUtils.runningOnUiThread()) return;
        sWatchingAppState = true;
        ApplicationStatus.registerApplicationStateListener(
                state -> {
                    if (state == ApplicationState.HAS_STOPPED_ACTIVITIES) {
                        flushAsync("background");
                    }
                });
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    /**
     * Saves what is waiting and sends it, off the calling thread. Cheap to call often: with a
     * send already under way, an active backoff or no session it only saves.
     */
    public static void flushAsync(String reason) {
        sLastFlushAttemptMs = System.currentTimeMillis();
        savePending();
        if (!sFlushRunning.compareAndSet(false, true)) return;
        new Thread(
                        () -> {
                            try {
                                flush(reason);
                            } catch (Throwable t) {
                                Log.e(TAG, "Sending feed events failed: " + t.getMessage());
                            } finally {
                                sFlushRunning.set(false);
                            }
                        },
                        "FeedEventsFlush")
                .start();
    }

    private static void flush(String reason) {
        String token = accessToken();
        if (token == null) return;
        if (prefs().getLong(PREF_NEXT_RETRY_MS, 0) > System.currentTimeMillis()) return;

        // At most two batches: one left in flight by an earlier attempt, then what has gathered
        // since.
        for (int i = 0; i < 2; i++) {
            JSONObject batch = takeBatch();
            if (batch == null) return;
            if (!handleResult(batch, send(token, batch), reason)) return;
        }
    }

    /**
     * The batch to send: one an earlier send left unresolved, else what is waiting, moved to the
     * in-flight slot under a new batchId. Null when there is nothing to send.
     *
     * <p>The new batch is committed, not applied: it must be on disk under its batchId before it
     * is sent, or a process killed mid-send would send the same events again under a new one.
     * This runs on the sending thread, so the write blocks nothing.
     */
    @SuppressLint("ApplySharedPref")
    @Nullable
    private static JSONObject takeBatch() {
        synchronized (sLock) {
            JSONObject inflight = readJson(PREF_INFLIGHT);
            if (inflight != null) {
                JSONArray events = inflight.optJSONArray("events");
                if (events != null && events.length() > 0) return inflight;
                prefs().edit().remove(PREF_INFLIGHT).apply();
            }

            LinkedHashMap<String, JSONObject> pending = pending();
            if (pending.isEmpty()) return null;
            JSONObject batch = new JSONObject();
            put(batch, "batchId", UUID.randomUUID().toString());
            put(batch, "events", new JSONArray(pending.values()));
            put(batch, "attempts", 0);
            pending.clear();
            prefs().edit()
                    .putString(PREF_INFLIGHT, batch.toString())
                    .remove(PREF_PENDING)
                    .commit();
            return batch;
        }
    }

    /** @return whether the flush may go on to the next batch. */
    private static boolean handleResult(JSONObject batch, int code, String reason) {
        SharedPreferences prefs = prefs();
        if (code >= 200 && code < 300) {
            synchronized (sLock) {
                prefs.edit()
                        .remove(PREF_INFLIGHT)
                        .remove(PREF_FAILURES)
                        .remove(PREF_NEXT_RETRY_MS)
                        .apply();
            }
            return true;
        }
        if (code == HttpURLConnection.HTTP_BAD_REQUEST
                || code == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) {
            // Sending it again cannot help, and it would hold up every later batch.
            Log.e(TAG, "Feed events rejected (" + code + "), dropping the batch");
            synchronized (sLock) {
                prefs.edit().remove(PREF_INFLIGHT).apply();
            }
            return true;
        }
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN) {
            // The session ran out or was replaced; the next one sends it.
            Log.w(TAG, "Feed events kept, session rejected (" + code + ")");
            return false;
        }

        // No answer, or the server failed: keep the batch and back off.
        int attempts = batch.optInt("attempts", 0) + 1;
        synchronized (sLock) {
            if (attempts >= MAX_BATCH_ATTEMPTS) {
                Log.e(TAG, "Feed events dropped after " + attempts + " attempts");
                prefs.edit().remove(PREF_INFLIGHT).apply();
            } else {
                put(batch, "attempts", attempts);
                prefs.edit().putString(PREF_INFLIGHT, batch.toString()).apply();
            }
        }
        int failures = prefs.getInt(PREF_FAILURES, 0) + 1;
        long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(failures - 1, 6));
        prefs.edit()
                .putInt(PREF_FAILURES, failures)
                .putLong(PREF_NEXT_RETRY_MS, System.currentTimeMillis() + delay)
                .apply();
        Log.w(TAG, "Feed events failed (" + code + ", " + reason + "), retry in "
                + (delay / 60000) + "m");
        return false;
    }

    /** @return the HTTP status, or -1 when there was no answer. */
    private static int send(String accessToken, JSONObject batch) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("batchId", batch.optString("batchId"));
            body.put("deviceId", ReferralHelper.getStableDeviceId());
            String version = appVersion();
            if (version != null) body.put("appVersion", version);
            body.put("sentAt", System.currentTimeMillis());
            body.put("events", batch.optJSONArray("events"));

            connection = (HttpURLConnection) new URL(EVENTS_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return connection.getResponseCode();
        } catch (Exception e) {
            Log.e(TAG, "Feed events not sent: " + e.getMessage());
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------

    /** The waiting events, loaded from prefs on first use. Call under {@link #sLock}. */
    private static LinkedHashMap<String, JSONObject> pending() {
        if (sPending != null) return sPending;
        sPending = new LinkedHashMap<>();
        String raw = prefs().getString(PREF_PENDING, null);
        if (raw == null) return sPending;
        try {
            JSONArray events = new JSONArray(raw);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) sPending.put(keyOf(event), event);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Dropping unreadable feed events: " + e.getMessage());
        }
        return sPending;
    }

    private static void savePending() {
        synchronized (sLock) {
            Map<String, JSONObject> pending = pending();
            SharedPreferences.Editor editor = prefs().edit();
            if (pending.isEmpty()) {
                editor.remove(PREF_PENDING);
            } else {
                editor.putString(PREF_PENDING, new JSONArray(pending.values()).toString());
            }
            editor.apply();
        }
    }

    @Nullable
    private static JSONObject readJson(String key) {
        String raw = prefs().getString(key, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            prefs().edit().remove(key).apply();
            return null;
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** The session the app signed in with, read the way ReferralHelper reads it. */
    @Nullable
    private static String accessToken() {
        String token =
                ContextUtils.getApplicationContext()
                        .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0)
                        .getString("AccessToken", null);
        return token == null || token.isEmpty() ? null : token;
    }

    @Nullable
    private static String appVersion() {
        try {
            Context context = ContextUtils.getApplicationContext();
            PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info != null ? info.versionName : null;
        } catch (Exception e) {
            return null;
        }
    }
}
