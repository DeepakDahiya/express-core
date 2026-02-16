/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Utility class to fetch YouTube premium access data from backend.
 */
public class YouTubePremiumAccessUtil {
    private static final String TAG = "YouTubePremiumAccess";
    // TODO: Replace with actual backend URL
    private static final String API_URL = "https://api.browser.express/v1/youtube/access";

    /**
     * Callback interface for premium access data fetch results.
     */
    public interface PremiumAccessCallback {
        void onSuccess(PremiumAccessData data);
        void onError(String error);
    }

    /**
     * Data class to hold premium access information.
     */
    public static class PremiumAccessData {
        public final int referralCount;
        public final long accessRemainingInSeconds;
        public final String message;
        public final boolean isBlocked;
        public final String referralCode;
        public final boolean newReferrals;

        public PremiumAccessData(int referralCount, long accessRemainingInSeconds,
                                 String message, boolean isBlocked, String referralCode, boolean newReferrals) {
            this.referralCount = referralCount;
            this.accessRemainingInSeconds = accessRemainingInSeconds;
            this.message = message;
            this.isBlocked = isBlocked;
            this.referralCode = referralCode;
            this.newReferrals = newReferrals;
        }
    }

    /**
     * AsyncTask to fetch premium access data from backend.
     */
    public static class GetPremiumAccessWorkerTask extends AsyncTask<PremiumAccessData> {
        private final String mAccessToken;
        private final PremiumAccessCallback mCallback;
        private String mErrorMessage;

        public GetPremiumAccessWorkerTask(String accessToken, PremiumAccessCallback callback) {
            this.mAccessToken = accessToken;
            this.mCallback = callback;
        }

        @Override
        protected PremiumAccessData doInBackground() {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Content-Type", "application/json");
                if (mAccessToken != null && !mAccessToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + mAccessToken);
                }
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "API response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    
                    int referralCount = jsonResponse.optInt("referralCount", 0);
                    // Default to 0 if not present
                    long accessRemainingInSeconds = jsonResponse.optLong("accessRemainingInSeconds", 0);
                    // Fallback to days if seconds not present (transition period)
                    if (accessRemainingInSeconds == 0 && jsonResponse.has("accessDaysRemaining")) {
                         accessRemainingInSeconds = jsonResponse.optInt("accessDaysRemaining", 0) * 86400L;
                    }
                    
                    String message = jsonResponse.optString("message",
                            "Share the app with friends to extend your premium access!");
                    boolean isBlocked = jsonResponse.optBoolean("isBlocked", false);
                    String referralCode = jsonResponse.optString("referralCode", null);
                    boolean newReferrals = jsonResponse.optBoolean("newReferrals", false);

                    return new PremiumAccessData(referralCount, accessRemainingInSeconds,
                                                 message, isBlocked, referralCode, newReferrals);
                } else {
                    mErrorMessage = "Server returned error: " + responseCode;
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch premium access data: " + e.getMessage());
                mErrorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(PremiumAccessData result) {
            if (mCallback != null) {
                if (result != null) {
                    mCallback.onSuccess(result);
                } else {
                    mCallback.onError(mErrorMessage != null ? mErrorMessage : "Unknown error");
                }
            }
        }
    }
}
