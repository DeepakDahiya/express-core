/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.referral;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.RemoteException;
import android.provider.Settings;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Log;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Helper class to handle referral tracking.
 * Called early in app launch from BraveLauncherActivity.
 */
public class ReferralHelper {
    private static final String TAG = "ReferralHelper";

    /**
     * Check and process referral. Called from BraveLauncherActivity.onCreate()
     * This ensures referral is captured before any other activity routing.
     */
    private static Context sAppContext;

    public static void checkAndProcessReferral(Context context) {
        Log.i(TAG, "checkAndProcessReferral called");
        sAppContext = context.getApplicationContext();

        // Only process referral once (on first install)
        boolean alreadyProcessed = ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, false);
        if (alreadyProcessed) {
            Log.i(TAG, "Referral already processed, skipping");
            return;
        }

        // Check for debug test referral first (set via DebugReferralReceiver broadcast)
        String debugReferral = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.DEBUG_TEST_REFERRAL, null);
        if (debugReferral != null && !debugReferral.isEmpty()) {
            Log.i(TAG, "Processing debug test referral: " + debugReferral);
            // Clear it so it only processes once
            ChromeSharedPreferences.getInstance().removeKey(BravePreferenceKeys.DEBUG_TEST_REFERRAL);
            processReferrerString(debugReferral);
            return;
        }

        // Check Install Referrer API (for real Play Store installs)
        checkInstallReferrer(context);
    }

    private static void checkInstallReferrer(Context context) {
        InstallReferrerClient referrerClient = InstallReferrerClient.newBuilder(context).build();
        referrerClient.startConnection(
                new InstallReferrerStateListener() {
                    @Override
                    public void onInstallReferrerSetupFinished(int responseCode) {
                        switch (responseCode) {
                            case InstallReferrerResponse.OK:
                                try {
                                    ReferrerDetails response = referrerClient.getInstallReferrer();
                                    String referrerUrl = response.getInstallReferrer();
                                    Log.i(TAG, "Raw referrer URL: " + referrerUrl);

                                    if (referrerUrl == null || referrerUrl.isEmpty()) {
                                        markReferralProcessed();
                                        return;
                                    }

                                    processReferrerString(referrerUrl);

                                } catch (RemoteException e) {
                                    Log.e(TAG, "Could not get referral: " + e.getMessage());
                                } finally {
                                    referrerClient.endConnection();
                                }
                                break;
                            case InstallReferrerResponse.FEATURE_NOT_SUPPORTED:
                                Log.e(TAG, "Install Referrer API not supported");
                                markReferralProcessed();
                                break;
                            case InstallReferrerResponse.SERVICE_UNAVAILABLE:
                                Log.e(TAG, "Install Referrer service unavailable");
                                // Don't mark as processed - might be temporary
                                break;
                        }
                    }

                    @Override
                    public void onInstallReferrerServiceDisconnected() {
                        Log.i(TAG, "Install referrer service disconnected");
                    }
                });
    }

    /**
     * Process a referral from a deep link URL.
     * Extracts referralCode, gets deviceId, and sends to backend.
     */
    public static void processDeepLinkReferral(Context context, String referralCode) {
        if (referralCode == null || referralCode.isEmpty()) return;

        Log.i(TAG, "Processing deep link referral code: " + referralCode);

        // Check if this referral code was already tracked
        String lastTracked = ChromeSharedPreferences.getInstance()
                .readString(BravePreferenceKeys.EXPRESS_REFERRAL_CODE, null);
        if (referralCode.equals(lastTracked)) {
            Log.i(TAG, "Referral code already tracked, skipping");
            return;
        }

        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.EXPRESS_REFERRAL_CODE, referralCode);

        String deviceId = getDeviceId(context);
        sendReferralToBackend(referralCode, deviceId);
    }

    private static void processReferrerString(String referrerUrl) {
        // Parse referral code from URL parameters
        String referralCode = getReferrerParameter(referrerUrl, "referral_code");

        if (referralCode != null && !referralCode.isEmpty()) {
            Log.i(TAG, "Found referral code: " + referralCode);

            // Save referral code locally
            ChromeSharedPreferences.getInstance()
                    .writeString(BravePreferenceKeys.EXPRESS_REFERRAL_CODE, referralCode);

            // Send to backend
            String deviceId = getDeviceId(sAppContext);
            sendReferralToBackend(referralCode, deviceId);
        }

        markReferralProcessed();
    }

    private static String getReferrerParameter(String referrer, String paramName) {
        try {
            String decoded = URLDecoder.decode(referrer, StandardCharsets.UTF_8.name());
            String[] pairs = decoded.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                    return keyValue[1];
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing referrer parameter: " + e.getMessage());
        }
        return null;
    }

    private static void markReferralProcessed() {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, true);
        Log.i(TAG, "Marked referral as processed");
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

    private static void sendReferralToBackend(String referralCode, String deviceId) {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.browser.express/v1/referral/track");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String jsonPayload = String.format(
                        java.util.Locale.US,
                        "{\"referralCode\":\"%s\",\"deviceId\":\"%s\"}",
                        referralCode,
                        deviceId
                );

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                Log.i(TAG, "Backend response code: " + responseCode);

            } catch (Exception e) {
                Log.e(TAG, "Failed to send referral to backend: " + e.getMessage());
            }
        }).start();
    }
}
