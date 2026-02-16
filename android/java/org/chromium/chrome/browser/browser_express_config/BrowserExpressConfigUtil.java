/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_config;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.base.BravePreferenceKeys;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Utility to fetch remote configuration/feature flags.
 */
public class BrowserExpressConfigUtil {
    private static final String TAG = "BrowserExpressConfig";
    private static final String API_URL = "https://api.browser.express/v1/public/config";
    private static final long COOLDOWN_MS = 6 * 60 * 60 * 1000; // 24 hours

    // Preference keys for feature flags (should likely be in BravePreferenceKeys, but defining here for now or assuming existence)
    // We will use string literals for keys if not present in BravePreferenceKeys to avoid modifying that giant file right now unless necessary.
    public static final String PREF_ENABLE_DEFAULT_BROWSER = "browser_express_enable_default_browser";
    public static final String PREF_ENABLE_YOUTUBE_PREMIUM = "browser_express_enable_youtube_premium";
    public static final String PREF_NTP_LAUNCH_DELAY = "browser_express_ntp_launch_delay";
    public static final String PREF_YOUTUBE_PREMIUM_COOLDOWN = "browser_express_youtube_premium_cooldown";
    public static final String PREF_LAST_CONFIG_CHECK = "browser_express_last_config_check";

    public static void fetchConfigIfNeeded() {
        long lastCheck = ChromeSharedPreferences.getInstance().readLong(PREF_LAST_CONFIG_CHECK, 0);
        long now = System.currentTimeMillis();

        if (now - lastCheck < COOLDOWN_MS) {
            Log.i(TAG, "Config fetch skipped (cooldown active)");
            return;
        }

        new FetchConfigTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class FetchConfigTask extends AsyncTask<Void> {
        @Override
        protected Void doInBackground() {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(response.toString());
                    boolean enableDefaultBrowser = json.optBoolean("enableDefaultBrowser", true);
                    boolean enableYouTubePremium = json.optBoolean("enableYouTubePremium", true);
                    long ntpLaunchDelay = json.optLong("ntpLaunchDelay", 30000);
                    long youtubePremiumCooldown = json.optLong("youtubePremiumCooldown", 30000);

                    ChromeSharedPreferences.getInstance().writeBoolean(PREF_ENABLE_DEFAULT_BROWSER, enableDefaultBrowser);
                    ChromeSharedPreferences.getInstance().writeBoolean(PREF_ENABLE_YOUTUBE_PREMIUM, enableYouTubePremium);
                    ChromeSharedPreferences.getInstance().writeLong(PREF_NTP_LAUNCH_DELAY, ntpLaunchDelay);
                    ChromeSharedPreferences.getInstance().writeLong(PREF_YOUTUBE_PREMIUM_COOLDOWN, youtubePremiumCooldown);
                    ChromeSharedPreferences.getInstance().writeLong(PREF_LAST_CONFIG_CHECK, System.currentTimeMillis());

                    Log.i(TAG, "Config updated: defaultBrowser=" + enableDefaultBrowser + ", ytPremium=" + enableYouTubePremium);
                } else {
                    Log.e(TAG, "Config fetch failed: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching config: " + e.getMessage());
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            // No-op
        }
    }

    public static boolean isDefaultBrowserPromptEnabled() {
        return ChromeSharedPreferences.getInstance().readBoolean(PREF_ENABLE_DEFAULT_BROWSER, true);
    }

    public static boolean isYouTubePremiumEnabled() {
        return ChromeSharedPreferences.getInstance().readBoolean(PREF_ENABLE_YOUTUBE_PREMIUM, true);
    }

    public static long getNtpLaunchDelay() {
        return ChromeSharedPreferences.getInstance().readLong(PREF_NTP_LAUNCH_DELAY, 30000);
    }

    public static long getYouTubePremiumCooldown() {
        return ChromeSharedPreferences.getInstance().readLong(PREF_YOUTUBE_PREMIUM_COOLDOWN, 30000);
    }
}
