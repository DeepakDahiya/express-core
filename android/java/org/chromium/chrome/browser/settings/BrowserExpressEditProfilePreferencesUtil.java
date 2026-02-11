
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

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

public class BrowserExpressEditProfilePreferencesUtil {
    private static final String TAG = "Edit_Profile_Browser_Express";
    private static final String EDIT_PROFILE_URL = "https://api.browser.express/v1/user/me";

    public interface EditProfileCallback {
        void editProfileSuccessful(String accessToken, String refreshToken);
        void editProfileFailed(String error);
    }

    public static class EditProfileWorkerTask extends AsyncTask<Void> {
        private final String mEmail;
        private final String mName;
        private final String mUsername;
        private final EditProfileCallback mCallback;
        private static Boolean editProfileStatus;
        private static String mErrorMessage;
        private static String mAccessToken;
        private static String mRefreshToken;

        public EditProfileWorkerTask(
                String email, String username, String name, String accessToken, EditProfileCallback callback) {
            mEmail = email;
            mName = name;
            mUsername = username;
            mCallback = callback;
            editProfileStatus = false;
            mErrorMessage = "";
            mAccessToken = accessToken;
            mRefreshToken = null;
        }

        public static void setAuthTokens(String accessToken, String refreshToken){
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
        }

        public static void setEditProfileSuccessStatus(Boolean status){
            editProfileStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendEditProfileRequest(mEmail, mUsername, mName, mAccessToken);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(editProfileStatus){
                mCallback.editProfileSuccessful(mAccessToken, mRefreshToken);
            }else{
                mCallback.editProfileFailed(mErrorMessage);
            }
        }
    }

    private static void sendEditProfileRequest(String email, String username, String name, String accessToken) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(EDIT_PROFILE_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("PATCH");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");

            if(accessToken != null && !accessToken.equals("")){
                urlConnection.setRequestProperty ("Authorization", accessToken);
            }

            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("email", email);
            jsonParam.put("name", name);
            jsonParam.put("username", username);

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
                if(responseObject.getBoolean("success")){
                    EditProfileWorkerTask.setEditProfileSuccessStatus(true);
                    String accessToken1 = responseObject.optString("accessToken", "");
                    String refreshToken1 = responseObject.optString("refreshToken", "");
                    if(!accessToken1.isEmpty() && !refreshToken1.isEmpty()){
                        EditProfileWorkerTask.setAuthTokens(accessToken1, refreshToken1);
                    }
                }else{
                    EditProfileWorkerTask.setEditProfileSuccessStatus(false);
                    EditProfileWorkerTask.setErrorMessage(responseObject.getString("error"));
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
