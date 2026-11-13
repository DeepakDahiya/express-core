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
import android.util.Base64;
import android.util.DisplayMetrics;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;

import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
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
import java.util.UUID;
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
    private static final String LINK_URL = BACKEND_BASE + "/v1/referral/link";

    private static final int MAX_INSTALL_REFERRER_ATTEMPTS = 5;
    // Fallbacks are bounded-retry (not one-shot): the web /click row may not be
    // visible yet, the device may not be focused for a clipboard read, etc.
    private static final int MAX_FINGERPRINT_ATTEMPTS = 5;
    private static final int MAX_CLIPBOARD_ATTEMPTS = 3;
    // The referral row for this device can take a long time to be tracked (the
    // fingerprint fallback alone retries with backoff for up to 6h), and each
    // auth burns one link attempt while the backend still reports done=false.
    // Keep the cap high enough that a genuinely-referred user who authenticates a
    // few times isn't permanently dropped from the graph before /track lands.
    private static final int MAX_LINK_ATTEMPTS = 25;
    // The fingerprint and clipboard fallbacks exist to recover a referral for a
    // *fresh* install whose Play referrer was lost. On a long-standing install
    // they can only misfire: match a stranger's click on the same network, or
    // read a token the user copied for a completely different reason.
    private static final long FALLBACK_INSTALL_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long MIN_RETRY_INTERVAL_MS = 30L * 1000L; // 30s between attempts
    private static final long INITIAL_RETRY_BACKOFF_MS = 30L * 1000L;
    private static final long MAX_RETRY_BACKOFF_MS = 6L * 60L * 60L * 1000L;
    private static final String STATE_PENDING_INSTALL_REFERRER_TRACK =
            "pending_install_referrer_track";
    private static final String STATE_PENDING_FINGERPRINT_MATCH =
            "pending_fingerprint_match";
    private static final String STATE_PENDING_FINGERPRINT_TRACK =
            "pending_fingerprint_track";
    private static final String STATE_COMPLETED = "completed";
    private static final Pattern CLIPBOARD_TOKEN =
            Pattern.compile("express-ref:([A-Za-z0-9_-]{4,64})");

    private static Context sAppContext;

    public static void checkAndProcessReferral(Context context) {
        Log.w(TAG, "checkAndProcessReferral called");
        sAppContext = context.getApplicationContext();

        if (retryPendingReferralWorkIfDue()) {
            return;
        }

        if (isCompleted() || hasPendingTrack()) {
            Log.w(TAG, "Referral already completed or waiting for backend retry, skipping");
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

        if (isCompleted() || hasPendingTrack()) {
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
        if (isCompleted() || hasPendingTrack()) return;

        // A referrer opening their own link (to test it, to share it from the
        // in-app browser, or via the clipboard token the landing page writes)
        // used to be tracked and rewarded, then flagged and clawed back at
        // /link — the "+1 … −1" that users report as a reset. Refuse it here.
        String ownCode = ownReferralCode();
        if (ownCode != null && ownCode.equalsIgnoreCase(referralCode)) {
            Log.w(TAG, "Ignoring own referral code from " + source);
            JSONObject props = new JSONObject();
            try {
                props.put("code", referralCode);
                props.put("source", source);
            } catch (Exception ignored) { }
            firePostHog(PostHogEventKeys.REFERRAL_SELF_REFERRAL_SKIPPED, null, null, props);
            clearPendingReferralWork();
            return;
        }

        String state = "fingerprint".equals(source)
                ? STATE_PENDING_FINGERPRINT_TRACK
                : STATE_PENDING_INSTALL_REFERRER_TRACK;
        persistPendingTrack(state, referralCode, source);

        sendPendingTrackAsync(state);
    }

    private static boolean isCompleted() {
        return STATE_COMPLETED.equals(getReferralState())
                || ChromeSharedPreferences.getInstance()
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

    /** The signed-in user's own referral code (JWT claim), or null when signed out. */
    private static String ownReferralCode() {
        try {
            JSONObject jwt = decodeAccessTokenPayload(storedAccessToken());
            if (jwt == null) return null;
            String code = jwt.optString("referralCode", "");
            return code.isEmpty() ? null : code;
        } catch (Exception e) {
            return null;
        }
    }

    // Package-private: ReferralUsageTracker sends its batches with the same
    // session token and must never diverge from how the referral funnel reads it.
    static String storedAccessToken() {
        try {
            Context ctx = sAppContext != null
                    ? sAppContext : ContextUtils.getApplicationContext();
            if (ctx == null) return null;
            SharedPreferences tokenPref = ctx.getSharedPreferences(
                    BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
            String token = tokenPref.getString("AccessToken", null);
            return (token == null || token.isEmpty()) ? null : token;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject decodeAccessTokenPayload(String token) {
        try {
            if (token == null) return null;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            // JWT segments are base64url; a payload containing '-' or '_' is
            // rejected by the DEFAULT decoder, which would silently disable the
            // self-referral guard for that token.
            byte[] decoded;
            try {
                decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP);
            } catch (IllegalArgumentException e) {
                decoded = Base64.decode(parts[1], Base64.DEFAULT);
            }
            return new JSONObject(new String(decoded, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True while this install is young enough for the recovery fallbacks to be
     * meaningful. Unknown install time counts as fresh (fail open, bounded by
     * the attempt caps).
     */
    private static boolean isWithinFallbackInstallWindow() {
        try {
            Context ctx = sAppContext != null
                    ? sAppContext : ContextUtils.getApplicationContext();
            if (ctx == null) return true;
            long firstInstall = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).firstInstallTime;
            if (firstInstall <= 0) return true;
            return System.currentTimeMillis() - firstInstall <= FALLBACK_INSTALL_WINDOW_MS;
        } catch (Exception e) {
            return true;
        }
    }

    /** The persisted install id this device is known by on the backend (login, referral, push). */
    public static String getStableDeviceId() {
        return getDeviceId();
    }

    private static String getDeviceId() {
        String persisted = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.EXPRESS_REFERRAL_DEVICE_ID, null);
        if (persisted != null && !persisted.isEmpty()) {
            return persisted;
        }

        String deviceId = stableDeviceId();
        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.EXPRESS_REFERRAL_DEVICE_ID, deviceId);
        return deviceId;
    }

    /**
     * Prefer the SSAID (Settings.Secure.ANDROID_ID): it is stable across app
     * reinstalls on the same device+signing key, so a reinstall reuses the same
     * deviceId and the backend's (referralCode, deviceId) unique index collapses
     * it into a 409 duplicate instead of granting a second reward. Falls back to
     * a random UUID when SSAID is unavailable or the known-bad emulator value.
     */
    @SuppressLint("HardwareIds")
    private static String stableDeviceId() {
        try {
            Context ctx = sAppContext != null
                    ? sAppContext : ContextUtils.getApplicationContext();
            if (ctx != null) {
                String ssaid = Settings.Secure.getString(
                        ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (ssaid != null && !ssaid.isEmpty()
                        && !"9774d56d682e549c".equals(ssaid)) {
                    return "ssaid-" + ssaid;
                }
            }
        } catch (Exception ignored) { }
        return UUID.randomUUID().toString();
    }

    private static String getReferralState() {
        return ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE, "");
    }

    private static boolean hasPendingTrack() {
        String state = getReferralState();
        return STATE_PENDING_INSTALL_REFERRER_TRACK.equals(state)
                || STATE_PENDING_FINGERPRINT_TRACK.equals(state);
    }

    private static boolean isRetryDue() {
        long nextRetryMs = ChromeSharedPreferences.getInstance()
                .readLong(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS, 0L);
        return nextRetryMs <= System.currentTimeMillis();
    }

    private static boolean retryPendingReferralWorkIfDue() {
        String state = getReferralState();
        if (state == null || state.isEmpty()) return false;
        if (STATE_COMPLETED.equals(state)) return true;

        if (hasPendingTrack()) {
            if (isRetryDue()) {
                sendPendingTrackAsync(state);
            }
            return true;
        }

        if (STATE_PENDING_FINGERPRINT_MATCH.equals(state) && isRetryDue()) {
            sendFingerprintMatchAsync();
            return true;
        }

        return false;
    }

    private static void persistPendingTrack(
            String state, String referralCode, String source) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE, state);
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_CODE, referralCode);
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_SOURCE, source);
        prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT, 0);
        prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS, 0L);
    }

    private static void persistPendingFingerprintMatch() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        if (!STATE_PENDING_FINGERPRINT_MATCH.equals(getReferralState())) {
            prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT, 0);
            prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS, 0L);
        }
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE,
                STATE_PENDING_FINGERPRINT_MATCH);
    }

    private static void clearPendingReferralWork() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_STATE);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_CODE);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_SOURCE);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS);
    }

    private static void scheduleRetry(String state) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int retryCount = prefs.readInt(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT, 0) + 1;
        long delayMs = INITIAL_RETRY_BACKOFF_MS
                * (1L << Math.min(retryCount - 1, 8));
        delayMs = Math.min(delayMs, MAX_RETRY_BACKOFF_MS);
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE, state);
        prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT, retryCount);
        prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS,
                System.currentTimeMillis() + delayMs);
        Log.w(TAG, "Referral retry scheduled state=" + state + " retryCount=" + retryCount
                + " delayMs=" + delayMs);
    }

    private static void markReferralWorkInFlight(String state) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE, state);
        prefs.writeLong(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS,
                System.currentTimeMillis() + INITIAL_RETRY_BACKOFF_MS);
    }

    private static void markCompleted(String referralCode, String source) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_CODE, referralCode);
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_SOURCE, source);
        prefs.writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, true);
        prefs.writeString(BravePreferenceKeys.EXPRESS_REFERRAL_STATE, STATE_COMPLETED);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_CODE);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_SOURCE);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_RETRY_COUNT);
        prefs.removeKey(BravePreferenceKeys.EXPRESS_REFERRAL_NEXT_RETRY_MS);

        JSONObject props = new JSONObject();
        try {
            props.put("code", referralCode);
            props.put("source", source);
            // deviceId is the backend's join key between /track and /link — emit
            // it so this event can be tied to the referred user in PostHog too.
            props.put("device_id", getDeviceId());
        } catch (Exception ignored) { }
        firePostHog(PostHogEventKeys.REFERRAL_ATTRIBUTED, null, null, props);
    }

    private static void sendPendingTrackAsync(String state) {
        String referralCode = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_CODE, null);
        String source = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.EXPRESS_REFERRAL_PENDING_SOURCE, null);
        if (referralCode == null || referralCode.isEmpty()
                || source == null || source.isEmpty()) {
            clearPendingReferralWork();
            return;
        }
        markReferralWorkInFlight(state);
        String deviceId = getDeviceId();
        new Thread(() -> sendReferralToBackend(state, referralCode, deviceId, source)).start();
    }

    private static void sendReferralToBackend(
            String state, String referralCode, String deviceId, String source) {
        Log.w(TAG, "Sending referral to backend: code=" + referralCode + " source=" + source);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TRACK_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // Optional: lets the backend refuse a referrer's own code before a
            // row and a reward exist, instead of clawing them back later.
            String token = storedAccessToken();
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("referralCode", referralCode);
            body.put("deviceId", deviceId);
            body.put("source", source);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            Log.w(TAG, "Backend response code: " + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == 409) {
                markCompleted(referralCode, source);
                return;
            }
            if (responseCode >= 500) {
                scheduleRetry(state);
                return;
            }
            if (responseCode == 422) {
                // Backend recognised our own session as the code's owner.
                Log.w(TAG, "Backend rejected self-referral for code " + referralCode);
                JSONObject props = new JSONObject();
                try {
                    props.put("code", referralCode);
                    props.put("source", source);
                    props.put("rejected_by", "backend");
                } catch (Exception ignored) { }
                firePostHog(PostHogEventKeys.REFERRAL_SELF_REFERRAL_SKIPPED, null, null, props);
                clearPendingReferralWork();
                return;
            }
            Log.e(TAG, "Referral track failed with non-retryable HTTP " + responseCode);
            clearPendingReferralWork();
        } catch (Exception e) {
            Log.e(TAG, "Failed to send referral to backend: " + e.getMessage());
            scheduleRetry(state);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Account linkage: bind this install's referral to the user id created at
    // signup/login, so the backend can build the who-referred-whom graph by
    // user id (not just anonymous device id).
    // ---------------------------------------------------------------------

    /**
     * Called from BraveActivity.setAccessToken after every successful auth.
     * No-ops once the backend has reported a terminal outcome (linked, self,
     * already-linked, malformed, or the retry cap for the not-yet-tracked case).
     */
    public static void linkReferralToAccount(Context context, String accessToken) {
        if (context == null || accessToken == null || accessToken.isEmpty()) return;
        if (sAppContext == null) sAppContext = context.getApplicationContext();

        final String deviceId = getDeviceId();
        // Stitch the anonymous install identity into the mongo user (once), so the
        // pre-login referral funnel merges onto this person in PostHog. Runs before
        // the LINK_SENT short-circuit so it still fires for already-linked users.
        maybeIdentifyInstall(accessToken, deviceId);

        if (ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_LINK_SENT, false)) {
            return;
        }
        final String token = accessToken;
        new Thread(() -> sendReferralLink(token, deviceId)).start();
    }

    /** Once per install: merge {@code anon-<deviceId>} into the mongo user id. */
    private static void maybeIdentifyInstall(String accessToken, String deviceId) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        // V2 guard (not the original key): the device-id format changed from a
        // raw ANDROID_ID to a "ssaid-"-prefixed value, orphaning anon persons
        // created by older builds. Re-running under a fresh key stitches those
        // installs once more, for BOTH the current and the legacy anon id.
        if (prefs.readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_IDENTIFY_SENT_V2, false)) {
            return;
        }
        String userId = userIdFromToken(accessToken);
        if (userId == null) return;

        PostHogUtil.identify(userId, "anon-" + deviceId);
        // Legacy format: old builds used the raw ANDROID_ID with no "ssaid-"
        // prefix, so the pre-update orphan person is "anon-<ssaid>". Merge it too.
        // Do NOT reconstruct "anon-unknown": on the old build that was the shared
        // fallback for every device with a null context, so merging it would
        // collapse many unrelated users into one.
        if (deviceId != null && deviceId.startsWith("ssaid-")) {
            PostHogUtil.identify(userId, "anon-" + deviceId.substring("ssaid-".length()));
        }
        prefs.writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_IDENTIFY_SENT_V2, true);
    }

    private static String userIdFromToken(String token) {
        try {
            JSONObject jwt = decodeAccessTokenPayload(token);
            if (jwt == null) return null;
            String id = jwt.optString("_id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendReferralLink(String accessToken, String deviceId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(LINK_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            Log.w(TAG, "Referral link HTTP " + code);

            boolean done;
            if (code == HttpURLConnection.HTTP_OK) {
                // Backend returns done=false only while the referral row hasn't
                // been tracked yet — retry on the next auth, but bounded so
                // organic (non-referred) users don't POST forever.
                Boolean backendDone = readDoneFlag(conn);
                done = (backendDone == null || backendDone)
                        || bumpLinkAttempts() >= MAX_LINK_ATTEMPTS;
            } else if (code == HttpURLConnection.HTTP_BAD_REQUEST) {
                done = true; // malformed — retrying won't help
            } else {
                done = bumpLinkAttempts() >= MAX_LINK_ATTEMPTS;
            }

            if (done) {
                ChromeSharedPreferences.getInstance()
                        .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_LINK_SENT, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Referral link failed: " + e.getMessage());
            if (bumpLinkAttempts() >= MAX_LINK_ATTEMPTS) {
                ChromeSharedPreferences.getInstance()
                        .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_LINK_SENT, true);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Boolean readDoneFlag(HttpURLConnection conn) {
        try {
            String body = readBody(conn);
            if (body == null) return null;
            JSONObject resp = new JSONObject(body);
            if (!resp.has("done")) return null;
            return resp.optBoolean("done", true);
        } catch (Exception e) {
            return null;
        }
    }

    private static int bumpLinkAttempts() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int attempts = prefs.readInt(
                BravePreferenceKeys.EXPRESS_REFERRAL_LINK_ATTEMPTS, 0) + 1;
        prefs.writeInt(BravePreferenceKeys.EXPRESS_REFERRAL_LINK_ATTEMPTS, attempts);
        return attempts;
    }

    // ---------------------------------------------------------------------
    // Step 4: Server-side fingerprint match
    // ---------------------------------------------------------------------

    private static void attemptFingerprintMatchIfNeeded() {
        if (isCompleted() || hasPendingTrack()) return;
        if (sAppContext == null) return;
        boolean tried = ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_TRIED, false);
        if (tried) return;
        if (!isWithinFallbackInstallWindow()) {
            // Too old for a lost-referrer recovery; a match now could only be
            // someone else's click. Retire the fallback for good.
            Log.w(TAG, "Install older than fallback window; skipping fingerprint match");
            ChromeSharedPreferences.getInstance()
                    .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_TRIED, true);
            if (STATE_PENDING_FINGERPRINT_MATCH.equals(getReferralState())) {
                clearPendingReferralWork();
            }
            return;
        }
        if (STATE_PENDING_FINGERPRINT_MATCH.equals(getReferralState()) && !isRetryDue()) {
            return;
        }

        persistPendingFingerprintMatch();
        sendFingerprintMatchAsync();
    }

    private static void sendFingerprintMatchAsync() {
        markReferralWorkInFlight(STATE_PENDING_FINGERPRINT_MATCH);
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
                // Empty code => no_match / ambiguous / no_candidates. The web
                // /click row may not be visible yet (just stored, replica lag,
                // IP still settling), so retry a few times across launches
                // instead of burning out on the first miss.
                firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT, "no_match", null);
                retryOrGiveUpFingerprint();
            } else if (code >= 500) {
                firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT,
                        "http_" + code, null);
                scheduleRetry(STATE_PENDING_FINGERPRINT_MATCH);
            } else {
                // 4xx (e.g. transient 429 device/IP rate-limit) — bounded retry
                // rather than a permanent give-up.
                firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT,
                        "http_" + code, null);
                retryOrGiveUpFingerprint();
            }
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint match failed: " + e.getMessage());
            firePostHog(PostHogEventKeys.REFERRAL_FINGERPRINT_RESULT,
                    "exception", e.getClass().getSimpleName());
            scheduleRetry(STATE_PENDING_FINGERPRINT_MATCH);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * A fingerprint miss (no match, or a transient 4xx) is not final: the web
     * click may land after our first look. Keep the pending-match state and
     * schedule a backed-off retry until MAX_FINGERPRINT_ATTEMPTS, then give up
     * for good so we stop hammering the endpoint.
     */
    private static void retryOrGiveUpFingerprint() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int attempts = prefs.readInt(
                BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_ATTEMPT_COUNT, 0) + 1;
        prefs.writeInt(
                BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_ATTEMPT_COUNT, attempts);
        if (attempts < MAX_FINGERPRINT_ATTEMPTS) {
            scheduleRetry(STATE_PENDING_FINGERPRINT_MATCH);
            return;
        }
        prefs.writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_FINGERPRINT_TRIED, true);
        clearPendingReferralWork();
    }

    private static JSONObject buildFingerprint(Context context) throws Exception {
        // Field names mirror the landing page JS payload (camelCase) so the
        // backend can match across web→app without a second normalization path.
        // See docs/referral-landing-page.md §4(a).
        JSONObject fp = new JSONObject();
        fp.put("deviceId", getDeviceId());
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
        if (isCompleted() || hasPendingTrack()) return;
        if (ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, false)) {
            return;
        }
        if (!isWithinFallbackInstallWindow()) {
            // The token the landing page writes is only evidence of a referral
            // for a brand-new install. Never read an old install's clipboard.
            ChromeSharedPreferences.getInstance()
                    .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, true);
            return;
        }

        try {
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                // Clipboard not readable yet — on Android 10+ this happens when the
                // activity isn't focused. Don't spend the one-shot flag; retry on a
                // later foreground, up to a small cap.
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "empty", null);
                markClipboardAttemptOrGiveUp();
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "no_items", null);
                markClipboardAttemptOrGiveUp();
                return;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text == null) {
                firePostHog(PostHogEventKeys.REFERRAL_CLIPBOARD_RESULT, "no_text", null);
                markClipboardAttemptOrGiveUp();
                return;
            }
            // We actually inspected clipboard content: this is a real, single read.
            // Mark tried so we never re-read the user's clipboard again (privacy,
            // and the Android 12+ "pasted from clipboard" toast).
            ChromeSharedPreferences.getInstance()
                    .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, true);
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
            markClipboardAttemptOrGiveUp();
        }
    }

    /**
     * Count a clipboard attempt that couldn't complete a real read. Give up for
     * good once MAX_CLIPBOARD_ATTEMPTS is reached so we don't loop forever.
     */
    private static void markClipboardAttemptOrGiveUp() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int attempts = prefs.readInt(
                BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_ATTEMPT_COUNT, 0) + 1;
        prefs.writeInt(
                BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_ATTEMPT_COUNT, attempts);
        if (attempts >= MAX_CLIPBOARD_ATTEMPTS) {
            prefs.writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_CLIPBOARD_TRIED, true);
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

    // Public so the phone-OTP registration journey (BrowserExpressAuthJourney)
    // keys its pre-auth events with the exact same identity the referral funnel
    // uses: the mongo _id once signed in, else anon-<deviceId>. That shared id is
    // what maybeIdentifyInstall() later stitches together, so both funnels land on
    // one PostHog person.
    public static String resolveDistinctId() {
        try {
            Context ctx = sAppContext != null
                    ? sAppContext : ContextUtils.getApplicationContext();
            if (ctx == null) return "ANONYMOUS";
            String id = userIdFromToken(storedAccessToken());
            if (id != null) return id;
        } catch (Exception ignored) { }
        // Fall back to the persisted anonymous install id so referral funnel
        // events for the same install de-duplicate in PostHog.
        return "anon-" + getDeviceId();
    }
}
