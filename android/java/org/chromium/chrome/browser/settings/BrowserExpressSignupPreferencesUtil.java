
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
import org.chromium.chrome.browser.app.BraveActivity;
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

public class BrowserExpressSignupPreferencesUtil {
    private static final String TAG = "Signup_Browser_Express";
    private static final String SIGNUP_URL = "https://api.browser.express/v1/auth/register";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public interface SignupCallback {
        void signupSuccessful(String phoneNumber, String purpose);
        void signupFailed(String error);
    }

    public static class SignupWorkerTask extends AsyncTask<Void> {
        private final String mPhoneNumber;
        private final String mName;
        private final SignupCallback mCallback;
        private static Boolean signupStatus;
        private static String mErrorMessage;
        private static String mOtpPurpose;

        public SignupWorkerTask(String phoneNumber, String name, SignupCallback callback) {
            mPhoneNumber = phoneNumber;
            mName = name;
            mCallback = callback;
            signupStatus = false;
            mErrorMessage = "";
            mOtpPurpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
        }

        public static void setSignupSuccessStatus(Boolean status){
            signupStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        public static void setOtpPurpose(String purpose) {
            if (BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN.equals(purpose)) {
                mOtpPurpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN;
            } else {
                mOtpPurpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
            }
        }

        @Override
        protected Void doInBackground() {
            sendSignupRequest(mPhoneNumber, mName);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(signupStatus){
                mCallback.signupSuccessful(mPhoneNumber, mOtpPurpose);
            }else{
                Log.e(TAG, "FAILURE ERROR MESSAGE: " + mErrorMessage);
                mCallback.signupFailed(mErrorMessage);
            }
        }
    }

    private static void sendSignupRequest(String phoneNumber, String name) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(SIGNUP_URL);
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
            jsonParam.put("country", "IN");
            jsonParam.put("name", name);

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
            if (isSuccessfulResponse(HttpResult)) {
                if(responseObject.optBoolean("ok", false)
                        && responseObject.optBoolean("otpSent", false)){
                    SignupWorkerTask.setSignupSuccessStatus(true);
                    SignupWorkerTask.setOtpPurpose(responseObject.optString(
                            "purpose", BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP));
                    Log.e(TAG, "INSIDE SUCCESS TRUE");
                }else{
                    SignupWorkerTask.setSignupSuccessStatus(false);
                    SignupWorkerTask.setErrorMessage(getResponseErrorMessage(
                            responseObject, "OTP could not be sent"));
                    Log.e(TAG, "INSIDE FAILURE");
                }
            } else {
                String error = getResponseErrorMessage(
                        responseObject, urlConnection.getResponseMessage());
                if (error == null) error = "HTTP " + HttpResult;
                SignupWorkerTask.setErrorMessage(error);
                Log.e(TAG, error);
            }
        } catch (MalformedURLException e) {
            SignupWorkerTask.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            SignupWorkerTask.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            SignupWorkerTask.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            SignupWorkerTask.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private static String getErrorMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
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

    private static String getResponseErrorMessage(JSONObject responseObject, String fallback) {
        String message = responseObject.optString("message", "");
        if (!message.isEmpty()) return message;
        String error = responseObject.optString("error", "");
        if (!error.isEmpty()) return error;
        return fallback;
    }
}
