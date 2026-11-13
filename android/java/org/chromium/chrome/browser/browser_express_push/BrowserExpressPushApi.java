/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Blocking HTTP calls for the push endpoints (docs §4.3). Every method must run off
 * the UI thread; callers use {@link AsyncTask#THREAD_POOL_EXECUTOR}.
 */
public final class BrowserExpressPushApi {
    private static final String TAG = "ExpressPush";
    private static final int TIMEOUT_MS = 10000;

    private BrowserExpressPushApi() {}

    /** PUT /v1/user/push-token — true on HTTP 200 with success:true. */
    public static boolean registerToken(
            String accessToken, String token, String deviceId, String appVersion, String lang) {
        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("platform", "android");
            if (deviceId != null && !deviceId.isEmpty()) body.put("deviceId", deviceId);
            if (appVersion != null && !appVersion.isEmpty()) body.put("appVersion", appVersion);
            if (lang != null && !lang.isEmpty()) body.put("lang", lang);
            Response r = send("PUT", "/user/push-token", accessToken, body);
            return r.isSuccess();
        } catch (Exception e) {
            Log.w(TAG, "registerToken failed: " + e.getMessage());
            return false;
        }
    }

    /** DELETE /v1/user/push-token — the token is also passed as a query param for proxies that strip DELETE bodies. */
    public static boolean deleteToken(String accessToken, String token) {
        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            Response r =
                    send(
                            "DELETE",
                            "/user/push-token?token=" + java.net.URLEncoder.encode(token, "UTF-8"),
                            accessToken,
                            body);
            return r.isSuccess();
        } catch (Exception e) {
            Log.w(TAG, "deleteToken failed: " + e.getMessage());
            return false;
        }
    }

    /** POST /v1/user/notifications/read {ids:[id]} — fire-and-forget from the UI thread. */
    public static void markReadAsync(final String notificationId) {
        if (notificationId == null || notificationId.isEmpty()) return;
        final String accessToken = BrowserExpressPushAnalytics.readAccessToken();
        if (accessToken == null) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("ids", new JSONArray().put(notificationId));
                        send("POST", "/user/notifications/read", accessToken, body);
                    } catch (Exception e) {
                        Log.w(TAG, "markRead failed: " + e.getMessage());
                    }
                });
    }

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean isSuccess() {
            if (code != HttpURLConnection.HTTP_OK) return false;
            try {
                return new JSONObject(body).optBoolean("success", false);
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static Response send(String method, String path, String accessToken, JSONObject body)
            throws Exception {
        URL url = new URL(BrowserExpressPushConfig.API_BASE_URL + path);
        HttpURLConnection conn =
                (HttpURLConnection)
                        ChromiumNetworkAdapter.openConnection(
                                url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            SharedPreferences tokenPrefs =
                    ContextUtils.getApplicationContext()
                            .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
            String refreshToken = tokenPrefs.getString("RefreshToken", null);
            if (refreshToken != null && !refreshToken.isEmpty()) {
                conn.setRequestProperty("x-refresh", refreshToken);
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();

            // The server rotates an expired session transparently; keep the new tokens.
            String rotated = conn.getHeaderField("x-access-token");
            if (rotated != null && !rotated.isEmpty()) {
                SharedPreferences.Editor editor =
                        tokenPrefs.edit().putString(BraveActivity.ACCESS_TOKEN_KEY, rotated);
                String newRefresh = conn.getHeaderField("x-refresh-token");
                if (newRefresh != null && !newRefresh.isEmpty()) {
                    editor.putString("RefreshToken", newRefresh);
                }
                editor.apply();
            }

            InputStream stream =
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = stream != null ? readAll(stream) : "";
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, method + " " + path + " -> HTTP " + code);
            }
            return new Response(code, text);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
