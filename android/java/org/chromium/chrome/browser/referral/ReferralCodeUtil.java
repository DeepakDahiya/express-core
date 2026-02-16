/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.referral;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Utility to fetch the user's referral code from the backend.
 * Used as a fallback when the JWT access token doesn't contain referralCode
 * (e.g. tokens issued before referralCode was added to the payload).
 */
public class ReferralCodeUtil {
    private static final String TAG = "ReferralCodeUtil";
    private static final String API_URL = "https://api.browser.express/v1/referral/code";

    public interface ReferralCodeCallback {
        void onSuccess(String referralCode);
        void onError(String error);
    }

    public static class GetReferralCodeWorkerTask extends AsyncTask<String> {
        private final String mAccessToken;
        private final ReferralCodeCallback mCallback;
        private String mErrorMessage;

        public GetReferralCodeWorkerTask(String accessToken, ReferralCodeCallback callback) {
            this.mAccessToken = accessToken;
            this.mCallback = callback;
        }

        @Override
        protected String doInBackground() {
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
                Log.i(TAG, "API response code: " + responseCode);

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
                    if (jsonResponse.optBoolean("success", false)) {
                        return jsonResponse.optString("referralCode", null);
                    }
                    mErrorMessage = "API returned success=false";
                    return null;
                } else {
                    mErrorMessage = "Server returned error: " + responseCode;
                    return null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch referral code: " + e.getMessage());
                mErrorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
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
