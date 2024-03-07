   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import android.net.Uri;
import java.util.List;
import java.util.ArrayList;

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

import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressGenerateUsernameBottomSheetFragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class BrowserExpressGetLatestApkUtil {
    private static final String TAG = "Get_First_Comments_Browser_Express";
    private static final String GET_LATEST_APK_URL = "https://api.browser.express/v1/public/latest_apk";
    private static final String KEY = "fc91c3a9-1a2e-4555-aef2-a62d64397c88";

    public interface GetLatestApkCallback {
        void getLatestApkSuccessful(String version, String url);
        void getLatestApkFailed(String error);
    }

    public static class GetLatestApkWorkerTask extends AsyncTask<Void> {
        private GetLatestApkCallback mCallback;
        private static Boolean getLatestApkStatus;
        private static String mErrorMessage;
        private static String mVersion;
        private static String mUrl;

        public GetLatestApkWorkerTask(GetLatestApkCallback callback) {
            mCallback = callback;
            getLatestApkStatus = false;
            mErrorMessage = "";
            mVersion = "";
            mUrl = "";
        }

        public static void setDetails(String version, String url){
            mVersion = version;
            mUrl = url;
        }

        public static void setGetLatestApkSuccessStatus(Boolean status){
            getLatestApkStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetLatestApkRequest(mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getLatestApkStatus){
                mCallback.getLatestApkSuccessful(mVersion, mUrl);
            }else{
                mCallback.getLatestApkFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetLatestApkRequest(GetLatestApkCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GET_LATEST_APK_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setRequestMethod("GET");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty ("x-api-key", KEY);
            urlConnection.connect();

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
                    GetLatestApkWorkerTask.setGetLatestApkSuccessStatus(true);
                    String version = responseObject.getString("version");
                    String downloadUrl = responseObject.getString("url");
                    GetLatestApkWorkerTask.setDetails(version, downloadUrl);
                }else{
                    GetLatestApkWorkerTask.setGetLatestApkSuccessStatus(false);
                    GetLatestApkWorkerTask.setErrorMessage(responseObject.getString("error"));
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
