/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Base64;

import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.settings.PostHogUtil;

/**
 * PostHog events for the push funnel (token registered → sent (server) → received → opened).
 * Mirrors BraveActivity.firePostHogUserEvent but works without an activity, because
 * "received" fires inside the FCM service while the app may be dead.
 */
public final class BrowserExpressPushAnalytics {
    private static final String TAG = "ExpressPush";

    private BrowserExpressPushAnalytics() {}

    public static void fire(String eventKey, JSONObject props) {
        try {
            final JSONObject payload = props != null ? props : new JSONObject();
            payload.put("app_version", appVersion());
            final String userId = currentUserIdOrAnonymous();
            PostTask.runOrPostTask(
                    TaskTraits.UI_DEFAULT,
                    () ->
                            new PostHogUtil.PostHogWorkerTask(eventKey, userId, payload)
                                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR));
        } catch (Exception e) {
            Log.e(TAG, "PostHog fire error: " + e.getMessage());
        }
    }

    public static void fire(String eventKey, String key, String value) {
        JSONObject props = new JSONObject();
        try {
            props.put(key, value);
        } catch (Exception ignored) {
        }
        fire(eventKey, props);
    }

    /** The Express Browser session token, or null when signed out. */
    public static String readAccessToken() {
        SharedPreferences prefs =
                ContextUtils.getApplicationContext()
                        .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
        String token = prefs.getString(BraveActivity.ACCESS_TOKEN_KEY, null);
        return token != null && !token.isEmpty() ? token : null;
    }

    /** Mongo user id from the JWT payload's "_id", or null. */
    public static String userIdFromJwt(String accessToken) {
        if (accessToken == null) return null;
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded;
            try {
                decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            } catch (IllegalArgumentException e) {
                decoded = Base64.decode(parts[1], Base64.DEFAULT);
            }
            String id = new JSONObject(new String(decoded, "UTF-8")).optString("_id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    public static String currentUserIdOrAnonymous() {
        String id = userIdFromJwt(readAccessToken());
        return id != null ? id : "ANONYMOUS";
    }

    public static String appVersion() {
        try {
            Context ctx = ContextUtils.getApplicationContext();
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }
}
