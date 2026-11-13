/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.notifications.BravePermissionUtils;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.referral.ReferralHelper;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Mints the FCM registration token through the InstanceID client Chrome already ships
 * (no FirebaseApp / google-services.json needed) and keeps it attached to the signed-in
 * Express user on the backend. Docs §5.2.
 */
public final class BrowserExpressPushTokenManager {
    private static final String TAG = "ExpressPush";
    private static final long RESYNC_INTERVAL_MS = DateUtils.DAY_IN_MILLIS;
    private static volatile boolean sSyncInFlight;

    private BrowserExpressPushTokenManager() {}

    /**
     * Registers (or re-registers) this device's token for the current user when needed.
     * Cheap to call often: it returns immediately unless the token, the user, or the
     * 24h re-sync window changed. Safe from any thread.
     *
     * @param reason free-form tag for analytics ("app_start", "login", ...).
     */
    public static void syncIfNeeded(final String reason) {
        if (!BrowserExpressPushConfig.isActive()) return;
        final String accessToken = BrowserExpressPushAnalytics.readAccessToken();
        if (accessToken == null) return; // nobody to attach the token to
        final Context ctx = ContextUtils.getApplicationContext();
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;
        if (sSyncInFlight) return;
        sSyncInFlight = true;

        AsyncTask.THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        syncBlocking(accessToken, reason);
                    } catch (IOException e) {
                        // No Google Play services, or no network: try again next launch.
                        Log.w(TAG, "token sync failed (" + reason + "): " + e.getMessage());
                        BrowserExpressPushAnalytics.fire(
                                PostHogEventKeys.PUSH_TOKEN_SYNC_FAILED,
                                props(reason, "error", e.getClass().getSimpleName()));
                    } catch (Exception e) {
                        Log.e(TAG, "token sync error (" + reason + "): " + e.getMessage());
                        BrowserExpressPushAnalytics.fire(
                                PostHogEventKeys.PUSH_TOKEN_SYNC_FAILED,
                                props(reason, "error", e.getClass().getSimpleName()));
                    } finally {
                        sSyncInFlight = false;
                    }
                });
    }

    private static void syncBlocking(String accessToken, String reason) throws Exception {
        String token =
                InstanceIDWithSubtype.getInstance(BrowserExpressPushConfig.INSTANCE_ID_SUBTYPE)
                        .getToken(
                                BrowserExpressPushConfig.FCM_SENDER_ID,
                                BrowserExpressPushConfig.INSTANCE_ID_SCOPE);
        if (TextUtils.isEmpty(token)) throw new IOException("empty token");

        String userId = BrowserExpressPushAnalytics.userIdFromJwt(accessToken);
        String syncKey = sha256(token + "|" + (userId != null ? userId : ""));
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        long lastSync = prefs.readLong(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_LAST_SYNC_MS, 0);
        boolean unchanged =
                syncKey.equals(prefs.readString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_SYNC_KEY, ""));
        if (unchanged && System.currentTimeMillis() - lastSync < RESYNC_INTERVAL_MS) return;

        boolean ok =
                BrowserExpressPushApi.registerToken(
                        accessToken,
                        token,
                        ReferralHelper.getStableDeviceId(),
                        BrowserExpressPushAnalytics.appVersion(),
                        Locale.getDefault().toLanguageTag());
        if (!ok) {
            BrowserExpressPushAnalytics.fire(
                    PostHogEventKeys.PUSH_TOKEN_SYNC_FAILED, props(reason, "error", "http"));
            return;
        }
        prefs.writeString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_TOKEN, token);
        prefs.writeString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_SYNC_KEY, syncKey);
        prefs.writeLong(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_LAST_SYNC_MS, System.currentTimeMillis());
        Log.i(TAG, "push token registered (" + reason + ")");
        BrowserExpressPushAnalytics.fire(
                PostHogEventKeys.PUSH_TOKEN_REGISTERED, props(reason, "changed", unchanged ? "false" : "true"));
    }

    /** FCM rotated the token: forget what the server has and re-register. */
    public static void onTokenRotated() {
        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_SYNC_KEY, "");
        syncIfNeeded("token_rotated");
    }

    /**
     * Detaches this device from the account that is about to be signed out. Must be
     * called while the access token is still stored; the network call runs in the
     * background and the local bookkeeping is cleared right away.
     */
    public static void unregisterBeforeLogout() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        final String token = prefs.readString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_TOKEN, "");
        final String accessToken = BrowserExpressPushAnalytics.readAccessToken();
        clearLocalRegistration();
        if (TextUtils.isEmpty(token) || accessToken == null) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(
                () -> BrowserExpressPushApi.deleteToken(accessToken, token));
    }

    /** The user flipped the settings switch. */
    public static void onUserToggled(boolean enabled) {
        BrowserExpressPushConfig.setUserEnabled(enabled);
        if (enabled) {
            syncIfNeeded("settings");
        } else {
            unregisterBeforeLogout();
        }
    }

    private static void clearLocalRegistration() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.writeString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_TOKEN, "");
        prefs.writeString(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_SYNC_KEY, "");
        prefs.writeLong(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_LAST_SYNC_MS, 0);
    }

    /**
     * Contextual permission ask (docs §5.8): the first time a user posts a comment on
     * Android 13+ without notification permission, ask once — this is the moment the
     * value of "someone replied to you" is obvious. Result lands in
     * BraveActivity.onRequestPermissionsResult, which re-syncs the token.
     */
    public static void maybePromptForPermissionAfterComment(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!BrowserExpressPushConfig.isFeatureEnabled()) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        if (prefs.readBoolean(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_PERMISSION_PROMPTED, false)) {
            return;
        }
        prefs.writeBoolean(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_PERMISSION_PROMPTED, true);
        BrowserExpressPushAnalytics.fire(
                PostHogEventKeys.PUSH_PERMISSION_PROMPTED, "reason", "first_comment");
        BravePermissionUtils.showNotificationPermissionDialog(activity);
    }

    private static JSONObject props(String reason, String key, String value) {
        JSONObject props = new JSONObject();
        try {
            props.put("reason", reason);
            props.put(key, value);
        } catch (Exception ignored) {
        }
        return props;
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
