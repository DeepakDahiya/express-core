/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.referral;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.url.GURL;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage 2 of the referral funnel, client side.
 *
 * {@link ReferralHelper} proves an install (/track) and ties it to an account
 * (/link). This class records what the user then actually does — YouTube
 * sessions and page views, and confirmed uses of the premium features (PiP,
 * background play) — and ships those counters to
 * {@code POST /v1/referral/usage} in batches. The backend marks the referral
 * "activated" on the first premium-feature use, which is what referrer payouts
 * are counted on.
 *
 * Counters are accumulated in SharedPreferences as a pending batch, so a use
 * recorded while the app is being backgrounded (PiP entry, in particular)
 * survives the process being frozen before the POST goes out. A batch being
 * sent is moved to an "in-flight" slot with a stable reportId; the backend
 * dedups on that id, so a lost response is retried without double counting,
 * and events that arrive during the send land in a fresh pending batch.
 */
public final class ReferralUsageTracker {
    private static final String TAG = "ReferralUsage";
    private static final String USAGE_URL = "https://api.browser.express/v1/referral/usage";

    // Counter names — must match USAGE_COUNTERS in the backend
    // (referral-usage.service.ts).
    static final String COUNTER_YT_VISITS = "youtubeVisits";
    static final String COUNTER_YT_PAGE_VIEWS = "youtubePageViews";
    static final String COUNTER_PIP = "pipUses";
    static final String COUNTER_BG_PLAY = "bgPlayUses";

    /** A YouTube navigation this long after the previous one starts a new visit. */
    static final long YOUTUBE_SESSION_GAP_MS = 30 * 60 * 1000L;
    /** Visits alone are not urgent; batch them. Premium uses flush immediately. */
    private static final long VISIT_FLUSH_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long RETRY_BASE_MS = 5 * 60 * 1000L;
    private static final long RETRY_MAX_MS = 6 * 60 * 60 * 1000L;
    /** A batch that keeps failing is dropped rather than poisoning every later flush. */
    private static final int MAX_BATCH_ATTEMPTS = 40;
    private static final int HTTP_TIMEOUT_MS = 10000;

    private static final Object sLock = new Object();
    private static final AtomicBoolean sFlushRunning = new AtomicBoolean(false);

    private ReferralUsageTracker() {}

    // ---------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------

    /**
     * Call for every committed primary-main-frame navigation on the active tab.
     * Counts a page view for any YouTube URL, and a visit when the previous
     * YouTube navigation was more than {@link #YOUTUBE_SESSION_GAP_MS} ago.
     */
    public static void recordYouTubeNavigation(GURL url, boolean errorPage) {
        if (errorPage || !isYouTubeUrl(url)) return;
        final long now = System.currentTimeMillis();
        boolean newVisit;
        synchronized (sLock) {
            SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
            long lastNav = prefs.readLong(
                    BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_LAST_YT_NAV_MS, 0L);
            // A clock that moved backwards also counts as a new visit rather
            // than silently never counting again.
            newVisit = lastNav <= 0 || now < lastNav || now - lastNav > YOUTUBE_SESSION_GAP_MS;
            prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_LAST_YT_NAV_MS, now);

            JSONObject pending = readJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING);
            bump(pending, COUNTER_YT_PAGE_VIEWS, now);
            if (newVisit) bump(pending, COUNTER_YT_VISITS, now);
            writeJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING, pending);
        }
        if (newVisit) Log.w(TAG, "recorded YouTube visit");
        maybeFlushForVisits(now);
    }

    /** A confirmed PiP entry (the framework reported PiP mode for a YouTube tab). */
    public static void recordPipUsed(Context context) {
        recordPremiumUse(context, COUNTER_PIP);
    }

    /** Audio kept playing on a YouTube tab after the app left the foreground. */
    public static void recordBackgroundPlayUsed(Context context) {
        recordPremiumUse(context, COUNTER_BG_PLAY);
    }

    private static void recordPremiumUse(Context context, String counter) {
        final long now = System.currentTimeMillis();
        synchronized (sLock) {
            JSONObject pending = readJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING);
            bump(pending, counter, now);
            writeJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING, pending);
        }
        Log.w(TAG, "recorded " + counter);
        // Activation is the payout signal: send it now, not at the next
        // 15-minute visit flush. The pending write above already survives a
        // process freeze, so an aborted send is only a delay.
        flushAsync(context, "premium:" + counter);
    }

    private static void bump(JSONObject pending, String counter, long now) {
        try {
            JSONObject entry = pending.optJSONObject(counter);
            if (entry == null) {
                entry = new JSONObject();
                pending.put(counter, entry);
            }
            entry.put("count", entry.optLong("count", 0L) + 1);
            long first = entry.optLong("firstAt", 0L);
            if (first <= 0 || now < first) entry.put("firstAt", now);
            if (now > entry.optLong("lastAt", 0L)) entry.put("lastAt", now);
        } catch (JSONException e) {
            Log.e(TAG, "bump error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Flushing
    // ---------------------------------------------------------------------

    /**
     * Sends whatever is pending, off the calling thread. Safe to call often:
     * a flush already in progress, an active backoff, or a missing session
     * all make this a cheap no-op. Counters are never lost — only delayed.
     */
    public static void flushAsync(Context context, String reason) {
        final Context appContext = context != null
                ? context.getApplicationContext() : ContextUtils.getApplicationContext();
        if (appContext == null) return;
        if (!sFlushRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                flush(appContext, reason);
            } catch (Throwable t) {
                Log.e(TAG, "flush error: " + t.getMessage());
            } finally {
                sFlushRunning.set(false);
            }
        }, "ReferralUsageFlush").start();
    }

    private static void maybeFlushForVisits(long now) {
        long lastFlush = ChromeSharedPreferences.getInstance()
                .readLong(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_LAST_FLUSH_MS, 0L);
        if (now - lastFlush >= VISIT_FLUSH_INTERVAL_MS) flushAsync(null, "visits");
    }

    private static void flush(Context context, String reason) {
        String token = ReferralHelper.storedAccessToken();
        if (token == null) {
            Log.w(TAG, "flush skipped, no session (" + reason + ")");
            return;
        }
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        long nextRetry = prefs.readLong(
                BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_NEXT_RETRY_MS, 0L);
        if (nextRetry > System.currentTimeMillis()) {
            Log.w(TAG, "flush deferred by backoff (" + reason + ")");
            return;
        }

        // At most two batches per flush: a batch stuck in flight from an
        // earlier attempt, then whatever accumulated since it was taken.
        for (int i = 0; i < 2; i++) {
            JSONObject batch = takeBatch();
            if (batch == null) return;
            int code = send(context, token, batch);
            if (!handleResult(batch, code, reason)) return;
        }
    }

    /**
     * The batch to send: the in-flight one if a previous send did not
     * conclude, else the pending counters promoted to in-flight under a fresh
     * reportId. Null when there is nothing to send.
     */
    private static JSONObject takeBatch() {
        synchronized (sLock) {
            SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
            JSONObject inflight = readJsonOrNull(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT);
            if (inflight != null && !isEmptyUsage(inflight.optJSONObject("usage"))) {
                return inflight;
            }
            if (inflight != null) {
                prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT);
            }

            JSONObject pending = readJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING);
            if (isEmptyUsage(pending)) return null;
            try {
                JSONObject batch = new JSONObject();
                batch.put("reportId", UUID.randomUUID().toString());
                batch.put("usage", pending);
                batch.put("attempts", 0);
                writeJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT, batch);
                prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_PENDING);
                return batch;
            } catch (JSONException e) {
                Log.e(TAG, "takeBatch error: " + e.getMessage());
                return null;
            }
        }
    }

    /** @return true when the flush loop may go on to the next batch. */
    private static boolean handleResult(JSONObject batch, int code, String reason) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        long now = System.currentTimeMillis();

        if (code >= 200 && code < 300) {
            synchronized (sLock) {
                prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT);
            }
            prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_LAST_FLUSH_MS, now);
            prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_FAILURES, 0);
            prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_NEXT_RETRY_MS);
            return true;
        }
        if (code == HttpURLConnection.HTTP_BAD_REQUEST) {
            // Malformed by this build's standards: retrying cannot help, and
            // keeping it would block every later batch.
            Log.e(TAG, "usage batch rejected (400), dropping it");
            synchronized (sLock) {
                prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT);
            }
            return true;
        }
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN) {
            // Session expired or was replaced; the next login flushes again.
            Log.w(TAG, "usage batch kept, session rejected (" + code + ")");
            return false;
        }

        // Network error or server failure: keep the batch, back off.
        int attempts = batch.optInt("attempts", 0) + 1;
        synchronized (sLock) {
            if (attempts >= MAX_BATCH_ATTEMPTS) {
                Log.e(TAG, "usage batch dropped after " + attempts + " attempts");
                prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT);
            } else {
                try {
                    batch.put("attempts", attempts);
                    writeJson(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_INFLIGHT, batch);
                } catch (JSONException ignored) { }
            }
        }
        int failures = prefs.readInt(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_FAILURES, 0) + 1;
        prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_FAILURES, failures);
        long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(failures - 1, 6));
        prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_USAGE_NEXT_RETRY_MS, now + delay);
        Log.w(TAG, "usage flush failed (" + code + ", " + reason + "), retry in " + (delay / 60000) + "m");
        return false;
    }

    /** @return the HTTP status, or -1 when the request never got a response. */
    private static int send(Context context, String accessToken, JSONObject batch) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("reportId", batch.optString("reportId"));
            body.put("deviceId", ReferralHelper.getStableDeviceId());
            String version = appVersion(context);
            if (version != null) body.put("appVersion", version);
            body.put("usage", batch.optJSONObject("usage"));

            URL url = new URL(USAGE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String response = readBody(conn);
            Log.w(TAG, "usage HTTP " + code + " " + summarize(batch.optJSONObject("usage")));
            if (code == HttpURLConnection.HTTP_OK && response != null) {
                try {
                    JSONObject resp = new JSONObject(response);
                    if (resp.optBoolean("activatedNow", false)) {
                        Log.w(TAG, "referral activated on this report");
                    }
                } catch (JSONException ignored) { }
            }
            return code;
        } catch (Exception e) {
            Log.e(TAG, "usage send failed: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    static boolean isYouTubeUrl(GURL url) {
        if (url == null || !url.isValid()) return false;
        String scheme = url.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = url.getHost();
        return host != null
                && (host.equals("youtube.com") || host.endsWith(".youtube.com")
                        || host.equals("youtu.be") || host.endsWith(".youtu.be"));
    }

    static boolean isEmptyUsage(JSONObject usage) {
        if (usage == null) return true;
        Iterator<String> keys = usage.keys();
        while (keys.hasNext()) {
            JSONObject entry = usage.optJSONObject(keys.next());
            if (entry != null && entry.optLong("count", 0L) > 0) return false;
        }
        return true;
    }

    private static String summarize(JSONObject usage) {
        if (usage == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        Iterator<String> keys = usage.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = usage.optJSONObject(key);
            if (entry == null) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(key).append('=').append(entry.optLong("count", 0L));
        }
        return sb.append('}').toString();
    }

    private static JSONObject readJson(String key) {
        JSONObject value = readJsonOrNull(key);
        return value != null ? value : new JSONObject();
    }

    private static JSONObject readJsonOrNull(String key) {
        try {
            String raw = ChromeSharedPreferences.getInstance().readString(key, null);
            if (raw == null || raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Exception e) {
            Log.e(TAG, "corrupt pref " + key + ", resetting: " + e.getMessage());
            ChromeSharedPreferences.getInstance().removeKey(key);
            return null;
        }
    }

    private static void writeJson(String key, JSONObject value) {
        ChromeSharedPreferences.getInstance().writeString(key, value.toString());
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info != null ? info.versionName : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
