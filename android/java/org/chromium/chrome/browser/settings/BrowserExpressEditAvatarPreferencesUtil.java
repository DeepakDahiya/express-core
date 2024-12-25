
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

public class BrowserExpressEditAvatarPreferencesUtil {
    private static final String TAG = "Edit_Avatar_Browser_Express";
    private static final String EDIT_AVATAR_URL = "https://api.browser.express/v1/user/avatar";

    public interface EditAvatarCallback {
        void editAvatarSuccessful();
        void editAvatarFailed(String error);
    }

    public static class EditAvatarWorkerTask extends AsyncTask<Void> {
        private String mImagePath;
        private EditAvatarCallback mCallback;
        private static Boolean editAvatarStatus;
        private static String mErrorMessage;
        private static String mAccessToken;

        public EditAvatarWorkerTask(String imagePath, String accessToken, EditAvatarCallback callback) {
            mImagePath = imagePath;
            mCallback = callback;
            editAvatarStatus = false;
            mErrorMessage = "";
            mAccessToken = accessToken;
        }

        public static void setAuthTokens(){
        }

        public static void setEditAvatarSuccessStatus(Boolean status){
            editAvatarStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendEditAvatarRequest(mImagePath, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(editAvatarStatus){
                mCallback.editAvatarSuccessful();
            }else{
                mCallback.editAvatarFailed(mErrorMessage);
            }
        }
    }

    private static void sendEditAvatarRequest(String imagePath, String accessToken, EditAvatarCallback callback) {
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";
        HttpURLConnection urlConnection = null;
        StringBuilder sb = new StringBuilder();
        try {
            URL url = new URL(EDIT_AVATAR_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("PATCH");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            if (accessToken != null && !accessToken.isEmpty()) {
                urlConnection.setRequestProperty("Authorization", accessToken);
            }

            OutputStream outputStream = urlConnection.getOutputStream();

            // Add text fields
            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"email\"" + LINE_FEED + LINE_FEED + email + LINE_FEED).getBytes());

            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"name\"" + LINE_FEED + LINE_FEED + name + LINE_FEED).getBytes());

            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"username\"" + LINE_FEED + LINE_FEED + username + LINE_FEED).getBytes());

            // Add the image
            if (imagePath != null && !imagePath.isEmpty()) {
                outputStream.write(("--" + boundary + LINE_FEED).getBytes());
                outputStream.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + imagePath.substring(imagePath.lastIndexOf("/") + 1) + "\"" + LINE_FEED).getBytes());
                outputStream.write(("Content-Type: image/jpeg" + LINE_FEED + LINE_FEED).getBytes());

                // Read the image file
                InputStream inputStream = new FileInputStream(imagePath);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                outputStream.write(LINE_FEED.getBytes());
            }

            // End of multipart
            outputStream.write(("--" + boundary + "--" + LINE_FEED).getBytes());
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
                    EditAvatarWorkerTask.setEditAvatarSuccessStatus(true);
                    // String accessToken1 = responseObject.getString("accessToken");
                    // String refreshToken = responseObject.getString("refreshToken");
                    // EditAvatarWorkerTask.setAuthTokens(accessToken1, refreshToken);
                }else{
                    EditAvatarWorkerTask.setEditAvatarSuccessStatus(false);
                    EditAvatarWorkerTask.setErrorMessage(responseObject.getString("error"));
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
