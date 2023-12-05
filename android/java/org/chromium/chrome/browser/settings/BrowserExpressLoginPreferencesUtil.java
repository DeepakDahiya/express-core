
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.rate;

import android.content.Context;
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

public class BrowserExpressLoginPreferencesUtil {
    private static final String TAG = "Login_Browser_Express";
    private static final String LOGIN_URL = "https://be.bargnbay.com/v1/auth/login";

    public interface LoginCallback {
        void loginSuccessful();
    }

    public static class LoginWorkerTask extends AsyncTask<Void> {
        private String mEmail;
        private String mPassword;
        private LoginCallback mCallback;

        public RateFeedbackWorkerTask(
                String email, String password, LoginCallback callback) {
            mEmail = email;
            mPassword = password;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            sendLoginRequest(mEmail, mPassword);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            mCallback.loginSuccessful();
        }
    }

    private static void sendLoginRequest(String email, String password) {
        Context context = ContextUtils.getApplicationContext();
        String appVersion = AboutChromeSettings.getApplicationVersion(
                context, AboutSettingsBridge.getApplicationVersion());
        StringBuilder sb = new StringBuilder();

        Profile mProfile = Profile.getLastUsedRegularProfile();
        NTPBackgroundImagesBridge mNTPBackgroundImagesBridge =
                NTPBackgroundImagesBridge.getInstance(mProfile);

        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(LOGIN_URL);
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
            jsonParam.put("os_version", String.valueOf(Build.VERSION.SDK_INT));
            jsonParam.put("phone_make", Build.MANUFACTURER);
            jsonParam.put("phone_model", Build.MODEL);
            jsonParam.put("phone_arch", Build.CPU_ABI);
            jsonParam.put("password", password);
            jsonParam.put("app_version", appVersion);
            jsonParam.put("api_key", mNTPBackgroundImagesBridge.getReferralApiKey());

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
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}
