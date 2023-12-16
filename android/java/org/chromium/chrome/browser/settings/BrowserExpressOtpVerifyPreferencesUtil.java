
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.about_settings.AboutChromeSettings;
import org.chromium.chrome.browser.about_settings.AboutSettingsBridge;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BrowserExpressOtpVerifyPreferencesUtil {
    private static final String TAG = "OtpVerify_Browser_Express";
    private static final String VERIFY_URL = "https://api.browser.express/v1/auth/verify-email";

    public interface OtpVerifyCallback {
        void otpVerifySuccessful(String accessToken, String refreshToken);
        void otpVerifyFailed(String error);
    }

    public static class OtpVerifyWorkerTask extends AsyncTask<Void> {
        private String mEmail;
        private String mOtp;
        private OtpVerifyCallback mCallback;
        private static Boolean otpVerifyStatus;
        private static String mErrorMessage;
        private static String mAccessToken;
        private static String mRefreshToken;

        public OtpVerifyWorkerTask(
                String email, String otp, OtpVerifyCallback callback) {
            mEmail = email;
            mOtp = otp;
            mCallback = callback;
            otpVerifyStatus = false;
            mErrorMessage = "";
            mAccessToken = null;
            mRefreshToken = null;
        }

        public static void setAuthTokens(String accessToken, String refreshToken){
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
        }

        public static void setOtpVerifySuccessStatus(Boolean status){
            otpVerifyStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendOtpVerifyRequest(mEmail, mOtp, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(otpVerifyStatus){
                mCallback.otpVerifySuccessful(mAccessToken, mRefreshToken);
            }else{
                Log.e(TAG, "FAILURE ERROR MESSAGE: " + mErrorMessage);
                mCallback.otpVerifyFailed(mErrorMessage);
            }
        }
    }

    private static void sendOtpVerifyRequest(String email, String otp, OtpVerifyCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(VERIFY_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("email", email);
            jsonParam.put("platform", "Android");
            jsonParam.put("otp", otp);

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            if (HttpResult == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        urlConnection.getInputStream(), StandardCharsets.UTF_8.name()));
                String line = null;
                while ((line = br.readLine()) != null) {
                    sb.append(line + "\n");
                }
                JSONObject responseObject = new JSONObject(sb.toString());
                Log.e(TAG, sb.toString());
                Log.e(TAG, "Success: "+ responseObject.getBoolean("success"));
                if(responseObject.getBoolean("success")){
                    OtpVerifyWorkerTask.setOtpVerifySuccessStatus(true);
                    String accessToken = responseObject.getString("accessToken");
                    String refreshToken = responseObject.getString("refreshToken");
                    OtpVerifyWorkerTask.setAuthTokens(accessToken, refreshToken);
                    Log.e(TAG, "INSIDE SUCCESS TRUE");
                }else{
                    OtpVerifyWorkerTask.setOtpVerifySuccessStatus(false);
                    OtpVerifyWorkerTask.setErrorMessage(responseObject.getString("error"));
                    Log.e(TAG, "INSIDE FAILURE");
                }
                br.close();
            } else {
                Log.e(TAG, urlConnection.getResponseMessage());
            }
        } catch (MalformedURLException e) {
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}
