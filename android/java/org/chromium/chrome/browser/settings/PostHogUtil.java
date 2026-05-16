
package org.chromium.chrome.browser.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.about_settings.AboutChromeSettings;
import org.chromium.chrome.browser.about_settings.AboutSettingsBridge;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.Integer;
import java.util.UUID;

public class PostHogUtil {
    private static final String TAG = "Post_Hog_Util";
    private static final String GET_PROFILE_URL = "https://posthog.dd-fb2.workers.dev/i/v0/e/";
    private static final String POST_HOG_API_KEY = "phc_Nn8HVrhAeFqo35ymuvUKOKe59RhXnShdushDuyDn3vL";

    // Disk-backed queue for events fired right as the app is being backgrounded
    // (PIP, onUserLeaveHint). The process can be frozen before the HTTP POST
    // finishes — we persist the payload first, drop it on success, and flush
    // anything still pending on the next send / next app start.
    private static final String PENDING_PREFS = "posthog_pending_events";
    private static final String PENDING_KEY = "events";
    private static final int MAX_PENDING = 50;
    private static final Object sQueueLock = new Object();

    public static class PostHogWorkerTask extends AsyncTask<Void> {
        private final String mEvent;
        private final String mUserId;
        private final JSONObject mProperties;

        public PostHogWorkerTask(String event, String userId, JSONObject properties) {
            mEvent = event;
            mUserId = userId;
            mProperties = properties;
        }

        @Override
        protected Void doInBackground() {
            String pendingId = enqueue(mEvent, mUserId, mProperties);
            boolean ok = sendPostHogRequest(mEvent, mUserId, mProperties);
            if (ok && pendingId != null) {
                removePending(pendingId);
            }
            // Opportunistically retry anything that was stuck from prior runs.
            flushPending();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
        }
    }

    /** Flush queued events. Safe to call from app startup. */
    public static void flushPendingEvents() {
        new AsyncTask<Void>() {
            @Override
            protected Void doInBackground() {
                flushPending();
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {}
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static SharedPreferences pendingPrefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE);
    }

    // commit() (not apply()) is intentional: apply() writes async and is
    // exactly what gets dropped when the OS freezes the process — the very
    // failure mode this queue exists to work around.
    @SuppressLint("ApplySharedPref")
    private static String enqueue(String event, String userId, JSONObject properties) {
        try {
            synchronized (sQueueLock) {
                SharedPreferences prefs = pendingPrefs();
                JSONArray arr = new JSONArray(prefs.getString(PENDING_KEY, "[]"));
                String id = UUID.randomUUID().toString();
                JSONObject entry = new JSONObject();
                entry.put("id", id);
                entry.put("event", event);
                entry.put("distinct_id", userId);
                entry.put("properties", properties != null ? properties : new JSONObject());
                arr.put(entry);
                // Cap the queue so a long offline stretch can't grow it without bound.
                while (arr.length() > MAX_PENDING) {
                    arr.remove(0);
                }
                prefs.edit().putString(PENDING_KEY, arr.toString()).commit();
                return id;
            }
        } catch (JSONException e) {
            Log.e(TAG, "enqueue error: " + e.getMessage());
            return null;
        }
    }

    @SuppressLint("ApplySharedPref") // See enqueue() — durability over speed.
    private static void removePending(String id) {
        if (id == null) return;
        try {
            synchronized (sQueueLock) {
                SharedPreferences prefs = pendingPrefs();
                JSONArray arr = new JSONArray(prefs.getString(PENDING_KEY, "[]"));
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e != null && !id.equals(e.optString("id"))) out.put(e);
                }
                prefs.edit().putString(PENDING_KEY, out.toString()).commit();
            }
        } catch (JSONException e) {
            Log.e(TAG, "removePending error: " + e.getMessage());
        }
    }

    private static void flushPending() {
        JSONArray snapshot;
        synchronized (sQueueLock) {
            try {
                snapshot = new JSONArray(pendingPrefs().getString(PENDING_KEY, "[]"));
            } catch (JSONException e) {
                return;
            }
        }
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject entry = snapshot.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id", null);
            String event = entry.optString("event", null);
            String userId = entry.optString("distinct_id", "ANONYMOUS");
            JSONObject props = entry.optJSONObject("properties");
            if (event == null) {
                removePending(id);
                continue;
            }
            if (sendPostHogRequest(event, userId, props)) {
                removePending(id);
            } else {
                // Stop on first failure — likely offline; try again next time.
                break;
            }
        }
    }

    private static boolean sendPostHogRequest(String event, String userId, JSONObject properties) {
        Log.e("Express Browser", "GETTING USER PROFILE 2");
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GET_PROFILE_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setConnectTimeout(8000);
            urlConnection.setReadTimeout(8000);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + POST_HOG_API_KEY);
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("api_key", POST_HOG_API_KEY);
            jsonParam.put("event", event);
            jsonParam.put("distinct_id", userId);
            jsonParam.put("properties", properties);

            Log.e(TAG, "Sending PostHog request: " + jsonParam.toString());

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int httpResult = urlConnection.getResponseCode();
            if (httpResult >= 200 && httpResult < 300) {
                return true;
            }
            Log.e(TAG, urlConnection.getResponseMessage());
            // 4xx is unrecoverable (bad payload / auth) — treat as success so we
            // don't retry forever. Only 5xx / network failure should re-queue.
            return httpResult >= 400 && httpResult < 500;
        } catch (MalformedURLException e) {
            Log.e(TAG, e.getMessage());
            return true; // URL is hard-coded — retrying won't help.
        } catch (IOException e) {
            Log.e(TAG, e.getMessage() != null ? e.getMessage() : "IOException");
            return false;
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            return true;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage() != null ? e.getMessage() : e.toString());
            return false;
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}
