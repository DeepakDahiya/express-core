/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.referral;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Base64;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;

import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Referral attribution. Layered sources, in order of confidence:
 *   1. Debug pref override (DEBUG_TEST_REFERRAL).
 *   2. Deep link via VIEW intent (handled in BraveLauncherActivity / BraveActivity.onNewIntent).
 *   3. Play Install Referrer API (retried with backoff if transient failure).
 *   4. Server-side fingerprint match (catches Play-Store gaps + sideloads).
 *   5. Clipboard token (last-resort fallback, only on Android < 12 or known-good cases).
 *
 * The first source to yield a code wins; subsequent sources short-circuit.
 */
public class ReferralHelper {
    private static final String TAG = "ReferralHelper";

    private static final String BACKEND_BASE = "https://api.browser.express";
    private static final String TRACK_URL = BACKEND_BASE + "/v1/referral/track";
    private static final String FINGERPRINT_URL = BACKEND_BASE + "/v1/referral/fingerprint-match";

    private static final int MAX_INSTALL_REFERRER_ATTEMPTS = 5;
    private static final long MIN_RETRY_INTERVAL_MS = 30L * 1000L; // 30s between attempts
    private static final Pattern CLIPBOARD_TOKEN =
            Pattern.compile("express-ref:([A-Za-z0-9_-]{4,64})");

    private static Context sAppContext;

    public static void checkAndProcessReferral(Context context) {
        Log.w(TAG, "checkAndProcessReferral called");
        sAppContext = context.getApplicationContext();

        if (isAttributed()) {
            Log.w(TAG, "Already attributed, skipping");
            return;
        }

        // Debug override
        String debugReferral = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.DEBUG_TEST_REFERRAL, null);
        if (debugReferral != null && !debugReferral.isEmpty()) {
            Log.w(TAG, "Processing debug test referral: " + debugReferral);
            ChromeSharedPreferences.getInstance()
                    .removeKey(BravePreferenceKeys.DEBUG_TEST_REFERRAL);
            processReferrerString(debugReferral, "debug");
            return;
        }

        // Install Referrer API (rate-limited retry)
        long now = System.currentTimeMillis();
        long lastAttempt = ChromeSharedPreferences.getInstance()
                .readLong(BravePreferenceKeys.EXPRESS_REFERRAL_LAST_ATTEMPT_MS, 0L);
        int attemptCount = ChromeSharedPreferences.getInstance()
                .readInt(BravePreferenceKeys.EXPRESS_REFERRAL_ATTEMPT_COUNT, 0);
        if (attemptCount < MAX_INSTALL_REFERRER_ATTEMPTS
                && (now - lastAttempt) >= MIN_RETRY_INTERVAL_MS) {
            ChromeSharedPreferences.getInstance()
                    .writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_LAST_ATTEMPT_MS, now);
            ChromeSharedPreferences.getInstance()
                    .writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_ATTEMPT_COUNT,
                            attemptCount + 1);
            checkInstallReferrer(sAppContext);
        } else {
            Log.w(TAG, "Skipping Install Referrer (attempts=" + attemptCount + ")");
            // Still try the layered fallbacks
            attemptFingerprintMatchIfNeeded();
            attemptClipboardFallbackIfNeeded(context);
        }
    }

    private static void checkInstallReferrer(Context context) {
        Log.w(TAG, "Starting InstallReferrerClient connection");
        final InstallReferrerClient referrerClient =
                InstallReferrerClient.newBuilder(context).build();
        try {
            referrerClient.startConnection(
                    new InstallReferrerStateListener() {
                        @Override
                        public void onInstallReferrerSetupFinished(int responseCode) {
                            Log.w(TAG, "onInstallReferrerSetupFinished responseCode="
                                    + responseCode);
                            handleInstallReferrerResponse(referrerClient, responseCode);
                        }

                        @Override
                        public void onInstallReferrerServiceDisconnected() {
                            Log.w(TAG, "Install referrer service disconnected");
                            firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                                    "service_disconnected", null);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "InstallReferrerClient.startConnection threw: " + e.getMessage());
            firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                    "start_threw", e.getClass().getSimpleName());
            attemptFingerprintMatchIfNeeded();
        }
    }

    private static void handleInstallReferrerResponse(
            InstallReferrerClient referrerClient, int responseCode) {
        try {
            switch (responseCode) {
                case InstallReferrerResponse.OK:
                    try {
                        ReferrerDetails response = referrerClient.getInstallReferrer();
                        String referrerUrl = response.getInstallReferrer();
                        Log.w(TAG, "Raw referrer URL: " + referrerUrl);

                        if (referrerUrl == null || referrerUrl.isEmpty()) {
                            firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                                    "ok_empty", null);
                            // Empty referrer means Play didn't get one — try other sources.
                            // Don't mark processed; allow future retries until cap.
                            attemptFingerprintMatchIfNeeded();
                            return;
                        }

                        firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                                "ok", null);
                        processReferrerString(referrerUrl, "install_referrer");
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not get referral: " + e.getMessage());
                        firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                                "remote_exception", e.getMessage());
                        attemptFingerprintMatchIfNeeded();
                    }
                    break;
                case InstallReferrerResponse.FEATURE_NOT_SUPPORTED:
                    Log.e(TAG, "Install Referrer API not supported");
                    firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                            "feature_not_supported", null);
                    // Bump attempt count to cap so we stop retrying Install Referrer,
                    // but still try fingerprint match and clipboard.
                    ChromeSharedPreferences.getInstance().writeInt(
                            BravePreferenceKeys.EXPRESS_REFERRAL_ATTEMPT_COUNT,
                            MAX_INSTALL_REFERRER_ATTEMPTS);
                    attemptFingerprintMatchIfNeeded();
                    break;
                case InstallReferrerResponse.SERVICE_UNAVAILABLE:
                    Log.e(TAG, "Install Referrer service unavailable");
                    firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                            "service_unavailable", null);
                    // Transient — leave attempt counter in place; next launch will retry.
                    attemptFingerprintMatchIfNeeded();
                    break;
                default:
                    Log.e(TAG, "Install Referrer unexpected response: " + responseCode);
                    firePostHog(PostHogEventKeys.REFERRAL_INSTALL_REFERRER_RESULT,
                            "unexpected_" + responseCode, null);
                    attemptFingerprintMatchIfNeeded();
                    break;
            }
        } finally {
            try {
                referrerClient.endConnection();
            } catch (Exception ignored) { }
        }
    }

    /** Called by BraveLauncherActivity / BraveActivity.onNewIntent for VIEW deep links. */
    public static void processDeepLinkReferral(Context context, String referralCode) {
        if (referralCode == null || referralCode.isEmpty()) return;

        Log.w(TAG, "Deep link referral code: " + referralCode);

        JSONObject props = new JSONObject();
        try { props.put("code", referralCode); } catch (Exception ignored) { }
        firePostHog(PostHogEventKeys.REFERRAL_DEEP_LINK_RECEIVED, null, null, props);

        if (isAttributed()) {
            Log.w(TAG, "Already attributed, deep link ignored");
            return;
        }

        if (sAppContext == null) sAppContext = context.getApplicationContext();
        attribute(referralCode, "deep_link");
    }

    private static void processReferrerString(String referrerUrl, String source) {
        Log.w(TAG, "processReferrerString source=" + source + " url=" + referrerUrl);
        String referralCode = getReferrerParameter(referrerUrl, "referral_code");
        if (referralCode == null || referralCode.isEmpty()) {
            referralCode = getReferrerParameter(referrerUrl, "code");
        }
        if (referralCode != null && !referralCode.isEmpty()) {
            attribute(referralCode, source);
        } else {
            Log.w(TAG, "No referral_code in referrer string (" + source + ")");
            // No code in this source; try the next layer.
            attemptFingerprintMatchIfNeeded();
        }
    }

    /**
     * Single point of attribution. Once called with a real code, all later sources
     * short-circuit so we never double-count.
     */
    private static synchronized void attribute(String referralCode, String source) {
        if (isAttributed()) return;

        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.EXPRESS_REFERRAL_CODE, referralCode);
        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.EXPRESS_REFERRAL_SOURCE, source);
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, true);

        JSONObject props = new JSONObject();
        try {
            props.put("code", referralCode);
            props.put("source", source);
        } catch (Exception ignored) { }
        firePostHog(PostHogEventKeys.REFERRAL_ATTRIBUTED, null, null, props);

        String deviceId = getDeviceId(sAppContext);
        sendReferralToBackend(referralCode, deviceId, source);
    }

    private static boolean isAttributed() {
        return ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, false);
    }

    private static String getReferrerParameter(String referrer, String paramName) {
        try {
            String decoded = URLDecoder.decode(referrer, StandardCharsets.UTF_8.name());
            String[] pairs = decoded.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                if (key.equals(paramName)) return value;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing referrer parameter: " + e.getMessage());
        }
        return null;
    }

    @SuppressLint("HardwareIds")
    private static String getDeviceId(Context context) {
        try {
            if (context != null) {
                return Settings.Secure.getString(
                        context.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device ID: " + e.getMessage());
        }
        return "unknown";
    }

    private static void sendReferralToBackend(
            String referralCode, String deviceId, String source) {
        Log.w(TAG, "Sending referral to backend: code=" + referralCode + " source=" + source);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(TRACK_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("referralCode", referralCode);
                body.put("deviceId", deviceId);
                body.put("source", source);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                Log.w(TAG, "Backend response code: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send referral to backend: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ---------------------------------------------------------------------
    // Step 4: Server-side fingerprint match
    // ---------------------------------------------------------------------

    private static void attemptFingerprintMatchIfNeeded() {
        if (isAttributed()) return;
        if (sAppContext == null) return;
        boolean tried = ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_TRIED, false);
        if (tried) return;
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_TRIED, true);

        new Thread(() -> sendFingerprintMatch(sAppContext)).start();
    }

    private static void sendFingerprintMatch(Context context) {
        HttpURLConnection conn = null;
        try {
            JSONObject fp = buildFingerprint(context);
            URL url = new URL(FINGERPRINT_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(fp.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            Log.w(TAG, "Fingerprint match HTTP " + code);
            if (code == HttpURLConnection.HTTP_OK) {
                String body = readBody(conn);
                JSONObject resp = new JSONObject(body == null ? "{}" : body);
                String referralCode = resp.optString("referralCode", "");
                if (!referralCode.isEmpty()) {
                    firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT, "matched", null);
                    attribute(referralCode, "fingerprint");
                    return;
                }
                firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT, "no_match", null);
            } else {
                firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT,
                        "http_" + code, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint match failed: " + e.getMessage());
            firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT,
                    "exception", e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject buildFingerprint(Context context) throws Exception {
        // Field names mirror the landing page JS payload (camelCase) so the
        // backend can match across web→app without a second normalization path.
        // See docs/referral-landing-page.md §4(a).
        JSONObject fp = new JSONObject();
        fp.put("deviceId", getDeviceId(context));
        // Match-critical (also emitted by web):
        fp.put("language", Locale.getDefault().toLanguageTag());
        fp.put("timezone", TimeZone.getDefault().getID());
        fp.put("timezoneOffset",
                -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000);
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            fp.put("screenWidth", dm.widthPixels);
            fp.put("screenHeight", dm.heightPixels);
            fp.put("devicePixelRatio", dm.density);
        } catch (Exception ignored) { }
        // Diagnostics — Android-only, not used for match but useful for debugging:
        fp.put("platform", "android");
        fp.put("osVersion", Build.VERSION.RELEASE);
        fp.put("sdkInt", Build.VERSION.SDK_INT);
        fp.put("manufacturer", Build.MANUFACTURER);
        fp.put("model", Build.MODEL);
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            fp.put("firstInstallTime", pm.getPackageInfo(pkg, 0).firstInstallTime);
        } catch (Exception ignored) { }
        fp.put("matchAt", System.currentTimeMillis());
        return fp;
    }

    private static String readBody(HttpURLConnection conn) {
        try (java.io.InputStream is = conn.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Step 5: Clipboard fallback
    // ---------------------------------------------------------------------

    /**
     * Read clipboard once on first foreground after install, looking for an
     * "express-ref:CODE" token written by the referral landing page.
     * Caller must invoke from an Activity (clipboard reads on Android 10+ require
     * focused activity).
     */
    public static void attemptClipboardFallback(Activity activity) {
        if (activity == null) return;
        if (sAppContext == null) sAppContext = activity.getApplicationContext();
        attemptClipboardFallbackIfNeeded(activity);
    }

    private static void attemptClipboardFallbackIfNeeded(Context context) {
        if (isAttributed()) return;
        boolean tried = ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, false);
        if (tried) return;
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, true);

        try {
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "empty", null);
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "no_items", null);
                return;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text == null) {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "no_text", null);
                return;
            }
            Matcher m = CLIPBOARD_TOKEN.matcher(text);
            if (m.find()) {
                String code = m.group(1);
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "matched", null);
                attribute(code, "clipboard");
            } else {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "no_match", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Clipboard fallback error: " + e.getMessage());
            firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT,
                    "exception", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------------
    // PostHog plumbing — fires anonymous events without depending on BraveActivity.
    // ---------------------------------------------------------------------

    private static void firePostHog(String event, String result, String detail) {
        firePostHog(event, result, detail, null);
    }

    private static void firePostHog(String event, String result, String detail,
            JSONObject extra) {
        try {
            JSONObject props = extra != null ? extra : new JSONObject();
            if (result != null) props.put("result", result);
            if (detail != null) props.put("detail", detail);
            new PostHogUtil.PostHogWorkerTask(event, resolveDistinctId(), props)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (Exception e) {
            Log.e(TAG, "firePostHog error: " + e.getMessage());
        }
    }

    private static String resolveDistinctId() {
        try {
            Context ctx = sAppContext != null
                    ? sAppContext : ContextUtils.getApplicationContext();
            if (ctx == null) return "ANONYMOUS";
            SharedPreferences tokenPref = ctx.getSharedPreferences(
                    BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
            String token = tokenPref.getString("AccessToken", null);
            if (token != null) {
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    byte[] decoded = Base64.decode(parts[1], Base64.DEFAULT);
                    JSONObject jwt = new JSONObject(new String(decoded, "UTF-8"));
                    String id = jwt.optString("_id", "");
                    if (!id.isEmpty()) return id;
                }
            }
        } catch (Exception ignored) { }
        // Fall back to ANDROID_ID so anonymous referral funnel events for the same
        // device de-duplicate in PostHog.
        return "anon-" + getDeviceId(
                sAppContext != null ? sAppContext : ContextUtils.getApplicationContext());
    }
}
