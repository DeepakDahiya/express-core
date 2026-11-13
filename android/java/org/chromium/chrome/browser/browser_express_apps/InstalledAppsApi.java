/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_apps;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Authenticated inventory transport. Never logs request/response bodies or session tokens. */
final class InstalledAppsApi {
    private static final String ENDPOINT = "https://api.browser.express/v1/user/installed-apps";

    private InstalledAppsApi() {}

    static SharedPreferences tokenPrefs(Context context) {
        return context.getSharedPreferences(
                BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, Context.MODE_PRIVATE);
    }

    static String userId(String token) {
        if (token == null) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String payload =
                    new String(
                            Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP),
                            StandardCharsets.UTF_8);
            String id = new JSONObject(payload).optString("_id", "");
            // This is only for local account scoping. The backend verifies the JWT signature.
            return id.matches("[a-fA-F0-9]{24}") ? id : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static final class Session {
        final String accessToken;
        final String refreshToken;
        final String userId;

        private Session(String accessToken, String refreshToken, String userId) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.userId = userId;
        }

        static Session read(Context context) {
            SharedPreferences prefs = tokenPrefs(context);
            String token = prefs.getString("AccessToken", null);
            String id = InstalledAppsApi.userId(token);
            return id == null
                    ? null
                    : new Session(token, prefs.getString("RefreshToken", null), id);
        }
    }

    /** Blocking request; credentials belong to the captured account, including during logout. */
    static boolean send(Context context, Session session, String method, JSONObject body)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection)
                        ChromiumNetworkAdapter.openConnection(
                                new URL(ENDPOINT),
                                NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken);
            if (session.refreshToken != null && !session.refreshToken.isEmpty()) {
                connection.setRequestProperty("x-refresh", session.refreshToken);
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            String access = connection.getHeaderField("x-access-token");
            String refresh = connection.getHeaderField("x-refresh-token");
            // A late response must not sign a logged-out user back in or change accounts.
            if (session.userId.equals(userId(access))) {
                PostTask.runOrPostTask(
                        TaskTraits.UI_DEFAULT,
                        () -> {
                            SharedPreferences prefs = tokenPrefs(context);
                            if (!session.accessToken.equals(prefs.getString("AccessToken", null))
                                    || !Objects.equals(
                                            session.refreshToken,
                                            prefs.getString("RefreshToken", null))) return;
                            SharedPreferences.Editor editor =
                                    prefs.edit().putString("AccessToken", access);
                            if (refresh != null && !refresh.isEmpty())
                                editor.putString("RefreshToken", refresh);
                            editor.apply();
                        });
            }
            if (code != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (response.size() + count > 8192) return false;
                    response.write(buffer, 0, count);
                }
                return new JSONObject(response.toString("UTF-8")).optBoolean("success", false);
            }
        } finally {
            connection.disconnect();
        }
    }
}
