/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches the NTP ticker payload from the backend. Caches the last successful
 * response in SharedPreferences so the next NTP open can paint instantly while
 * a fresh fetch happens in the background.
 */
public class NtpTickerUtil {
    private static final String TAG = "NtpTickerUtil";
    private static final String TICKER_URL = "https://api.browser.express/v1/public/home_ticker";

    // SharedPreferences keys.
    private static final String PREF_CACHED_RESPONSE = "ntp_ticker_cached_response";
    private static final String PREF_DISMISSED_TEXT_HASH = "ntp_ticker_dismissed_text_hash";

    /** Parsed ticker payload. */
    public static class TickerData {
        public final boolean visible;
        public final String text;
        public final String url;
        public final boolean dismissable;

        public TickerData(boolean visible, String text, String url, boolean dismissable) {
            this.visible = visible;
            this.text = text == null ? "" : text;
            this.url = url == null ? "" : url;
            this.dismissable = dismissable;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("visible", visible);
            o.put("text", text);
            o.put("url", url);
            o.put("dismissable", dismissable);
            return o;
        }

        static TickerData fromJson(JSONObject o) throws JSONException {
            return new TickerData(
                    o.optBoolean("visible", false),
                    o.optString("text", ""),
                    o.optString("url", ""),
                    o.optBoolean("dismissable", false));
        }
    }

    public interface Callback {
        void onResult(TickerData data);
    }

    /** Returns the cached payload, or null if nothing is cached. */
    public static TickerData getCached() {
        String raw = ContextUtils.getAppSharedPreferences()
                .getString(PREF_CACHED_RESPONSE, null);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            return TickerData.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Returns true if the user has already dismissed this exact text. The
     * dismissal is keyed on the text hash so a content rotation on the
     * backend brings the ticker back automatically.
     */
    public static boolean isDismissed(String text) {
        if (TextUtils.isEmpty(text)) return false;
        int hash = text.hashCode();
        return ContextUtils.getAppSharedPreferences()
                .getInt(PREF_DISMISSED_TEXT_HASH, 0) == hash;
    }

    /** Persist a dismissal for the given text. */
    public static void markDismissed(String text) {
        if (TextUtils.isEmpty(text)) return;
        ContextUtils.getAppSharedPreferences().edit()
                .putInt(PREF_DISMISSED_TEXT_HASH, text.hashCode())
                .apply();
    }

    /** Kick off a background fetch; result delivered to {@code callback} on the UI thread. */
    public static void fetch(Callback callback) {
        new FetchTask(callback).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class FetchTask extends AsyncTask<Void> {
        private final Callback mCallback;
        private TickerData mResult;

        FetchTask(Callback callback) {
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            mResult = doFetch();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled() || mCallback == null) return;
            mCallback.onResult(mResult);
        }
    }

    private static TickerData doFetch() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TICKER_URL);
            conn = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            TickerData data = TickerData.fromJson(new JSONObject(sb.toString()));
            cache(data);
            return data;
        } catch (Exception e) {
            Log.w(TAG, "ticker fetch failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void cache(TickerData data) {
        try {
            SharedPreferences.Editor ed = ContextUtils.getAppSharedPreferences().edit();
            ed.putString(PREF_CACHED_RESPONSE, data.toJson().toString());
            ed.apply();
        } catch (JSONException ignored) {
        }
    }
}
