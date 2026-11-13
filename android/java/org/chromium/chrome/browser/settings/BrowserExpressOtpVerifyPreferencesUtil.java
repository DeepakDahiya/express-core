
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
    private static final String VERIFY_URL = "https://api.browser.express/v1/auth/verify-phone";
    private static final String RESEND_URL =
            "https://api.browser.express/v1/auth/resend-phone-otp";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public interface OtpVerifyCallback {
        void otpVerifySuccessful(String accessToken, String refreshToken);
        void otpVerifyFailed(String error, int retryAfterSeconds);
    }

    public interface ResendOtpCallback {
        void resendOtpSuccessful(int resendAvailableInSeconds);
        void resendOtpFailed(String error, int retryAfterSeconds);
    }

    public static class OtpVerifyWorkerTask extends AsyncTask<Void> {
        private final String mPhoneNumber;
        private final String mOtp;
        private final OtpVerifyCallback mCallback;
        private boolean mOtpVerifyStatus;
        private String mErrorMessage;
        private String mAccessToken;
        private String mRefreshToken;
        private int mRetryAfterSeconds;

        public OtpVerifyWorkerTask(
                String phoneNumber, String otp, OtpVerifyCallback callback) {
            mPhoneNumber = phoneNumber;
            mOtp = otp;
            mCallback = callback;
            mOtpVerifyStatus = false;
            mErrorMessage = "";
            mAccessToken = null;
            mRefreshToken = null;
            mRetryAfterSeconds = -1;
        }

        void setAuthTokens(String accessToken, String refreshToken){
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
        }

        void setOtpVerifySuccessStatus(boolean status){
            mOtpVerifyStatus = status;
        }

        void setErrorMessage(String error){
            mErrorMessage = error;
        }

        void setRetryAfterSeconds(int seconds){
            mRetryAfterSeconds = seconds;
        }

        @Override
        protected Void doInBackground() {
            sendOtpVerifyRequest(this, mPhoneNumber, mOtp);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(mOtpVerifyStatus){
                mCallback.otpVerifySuccessful(mAccessToken, mRefreshToken);
            }else{
                Log.e(TAG, "FAILURE ERROR MESSAGE: " + mErrorMessage);
                mCallback.otpVerifyFailed(mErrorMessage, mRetryAfterSeconds);
            }
        }
    }

    public static class ResendOtpWorkerTask extends AsyncTask<Void> {
        private final String mPhoneNumber;
        private final String mPurpose;
        private final ResendOtpCallback mCallback;
        private boolean mResendOtpStatus;
        private String mErrorMessage;
        private int mRetryAfterSeconds;
        private int mResendAvailableInSeconds;

        public ResendOtpWorkerTask(
                String phoneNumber, String purpose, ResendOtpCallback callback) {
            mPhoneNumber = phoneNumber;
            mPurpose = purpose;
            mCallback = callback;
            mResendOtpStatus = false;
            mErrorMessage = "";
            mRetryAfterSeconds = -1;
            mResendAvailableInSeconds = -1;
        }

        void setResendOtpSuccessStatus(boolean status){
            mResendOtpStatus = status;
        }

        void setErrorMessage(String error){
            mErrorMessage = error;
        }

        void setRetryAfterSeconds(int seconds){
            mRetryAfterSeconds = seconds;
        }

        void setResendAvailableInSeconds(int seconds){
            mResendAvailableInSeconds = seconds;
        }

        @Override
        protected Void doInBackground() {
            sendResendOtpRequest(this, mPhoneNumber, mPurpose);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(mResendOtpStatus){
                mCallback.resendOtpSuccessful(mResendAvailableInSeconds);
            }else{
                Log.e(TAG, "RESEND FAILURE ERROR MESSAGE: " + mErrorMessage);
                mCallback.resendOtpFailed(mErrorMessage, mRetryAfterSeconds);
            }
        }
    }

    private static void attachGuestSessionHeaders(HttpURLConnection connection) {
        try {
            android.content.SharedPreferences tokenPrefs =
                    ContextUtils.getApplicationContext().getSharedPreferences(
                            org.chromium.base.BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN,
                            0);
            String accessToken = tokenPrefs.getString("AccessToken", null);
            String refreshToken = tokenPrefs.getString("RefreshToken", null);
            if (accessToken != null && !accessToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            if (refreshToken != null && !refreshToken.isEmpty()) {
                // Lets the server still recognise the session if the access
                // token expired while the user was on the OTP screen.
                connection.setRequestProperty("x-refresh", refreshToken);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not attach guest session headers: " + e.getMessage());
        }
    }

    private static void sendOtpVerifyRequest(
            OtpVerifyWorkerTask task, String phoneNumber, String otp) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            Log.e(
                    TAG,
                    "OTP verify request start phone="
                            + maskPhone(phoneNumber)
                            + " otpLength="
                            + (otp == null ? 0 : otp.length()));
            URL url = new URL(VERIFY_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            // Identify the pre-login guest session this device already holds, so
            // the backend keeps that identity (its referral code, referrals and
            // comments) for the verified account instead of starting a new one.
            attachGuestSessionHeaders(urlConnection);
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("phone", phoneNumber);
            jsonParam.put("otp", otp);

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            Log.e(TAG, "OTP verify response code=" + HttpResult);
            String responseBody = readResponseBody(urlConnection, HttpResult);
            if (responseBody != null) sb.append(responseBody);
            JSONObject responseObject = sb.length() > 0 ? new JSONObject(sb.toString()) : new JSONObject();
            Log.e(TAG, sb.toString());
            if (isSuccessfulResponse(HttpResult)) {
                String accessToken = responseObject.optString("accessToken", "");
                String refreshToken = responseObject.optString("refreshToken", "");
                if(!accessToken.isEmpty() && !refreshToken.isEmpty()){
                    task.setOtpVerifySuccessStatus(true);
                    task.setAuthTokens(accessToken, refreshToken);
                    Log.e(TAG, "INSIDE SUCCESS TRUE");
                } else {
                    task.setOtpVerifySuccessStatus(false);
                    task.setErrorMessage("Missing auth tokens");
                }
            } else {
                String error = getResponseErrorMessage(
                        responseObject, urlConnection.getResponseMessage());
                if (error == null) error = "HTTP " + HttpResult;
                task.setErrorMessage(error);
                task.setRetryAfterSeconds(
                        parseRetryAfterSeconds(responseObject, urlConnection));
                Log.e(TAG, error);
            }
        } catch (MalformedURLException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private static void sendResendOtpRequest(
            ResendOtpWorkerTask task, String phoneNumber, String purpose) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(RESEND_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("phone", phoneNumber);
            jsonParam.put("purpose", purpose);

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            String responseBody = readResponseBody(urlConnection, HttpResult);
            if (responseBody != null) sb.append(responseBody);
            JSONObject responseObject = sb.length() > 0 ? new JSONObject(sb.toString()) : new JSONObject();
            Log.e(TAG, sb.toString());
            if (isSuccessfulResponse(HttpResult)
                    && responseObject.optBoolean("ok", false)
                    && responseObject.optBoolean("otpSent", false)) {
                task.setResendOtpSuccessStatus(true);
                task.setResendAvailableInSeconds(
                        responseObject.optInt("resendAvailableInSeconds", -1));
            } else {
                String error = getResponseErrorMessage(
                        responseObject, urlConnection.getResponseMessage());
                if (error == null) error = "HTTP " + HttpResult;
                task.setErrorMessage(error);
                task.setRetryAfterSeconds(
                        parseRetryAfterSeconds(responseObject, urlConnection));
            }
        } catch (MalformedURLException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private static String getErrorMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    private static String maskPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 5) return "";
        return phoneNumber.substring(0, 3) + "***"
                + phoneNumber.substring(phoneNumber.length() - 2);
    }

    private static boolean isSuccessfulResponse(int responseCode) {
        return responseCode >= HttpURLConnection.HTTP_OK && responseCode < 300;
    }

    private static String readResponseBody(HttpURLConnection urlConnection, int responseCode)
            throws IOException {
        BufferedReader br = null;
        try {
            if (isSuccessfulResponse(responseCode)) {
                br = new BufferedReader(new InputStreamReader(
                        urlConnection.getInputStream(), StandardCharsets.UTF_8.name()));
            } else if (urlConnection.getErrorStream() != null) {
                br = new BufferedReader(new InputStreamReader(
                        urlConnection.getErrorStream(), StandardCharsets.UTF_8.name()));
            }
            if (br == null) return "";
            StringBuilder response = new StringBuilder();
            String line = null;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            if (br != null) br.close();
        }
    }

    private static int parseRetryAfterSeconds(
            JSONObject responseObject, HttpURLConnection urlConnection) {
        int retryAfter = responseObject.optInt("retryAfterSeconds", -1);
        if (retryAfter > 0) return retryAfter;
        String header = urlConnection.getHeaderField("Retry-After");
        if (header != null) {
            try {
                int headerSeconds = Integer.parseInt(header.trim());
                if (headerSeconds > 0) return headerSeconds;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static String getResponseErrorMessage(JSONObject responseObject, String fallback) {
        String message = responseObject.optString("message", "");
        if (!message.isEmpty()) return message;
        String error = responseObject.optString("error", "");
        if (!error.isEmpty()) return error;
        return fallback;
    }
}
