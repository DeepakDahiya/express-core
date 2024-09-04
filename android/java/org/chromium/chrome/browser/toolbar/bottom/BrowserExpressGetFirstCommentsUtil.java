   
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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
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

public class BrowserExpressGetFirstCommentsUtil {
    private static final String TAG = "Get_First_Comments_Browser_Express";
    private static final String GET_FIRST_COMMENTS_URL = "https://api.browser.express/v1/comment/first_load";

    public interface GetFirstCommentsCallback {
        void getFirstCommentsSuccessful(JSONArray comments, int commentCount);
        void getFirstCommentsFailed(String error);
    }

    public static class GetFirstCommentsWorkerTask extends AsyncTask<Void> {
        private GetFirstCommentsCallback mCallback;
        private static Boolean getFirstCommentsStatus;
        private static String mErrorMessage;
        private static String mUrl;
        private static int mPage;
        private static int mPerPage;
        private static JSONArray mComments;
        private static int mCommentCount;

        public GetFirstCommentsWorkerTask(String url, GetFirstCommentsCallback callback) {
            mCallback = callback;
            getFirstCommentsStatus = false;
            mErrorMessage = "";
            mUrl = url;
            mComments = new JSONArray();
            mCommentCount = 0;
        }

        public static void setComments(JSONArray comments, int commentCount){
            mComments = comments;
            mCommentCount = commentCount;
        }

        public static void setGetFirstCommentsSuccessStatus(Boolean status){
            getFirstCommentsStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetFirstCommentsRequest(mUrl, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getFirstCommentsStatus){
                mCallback.getFirstCommentsSuccessful(mComments, mCommentCount);
            }else{
                mCallback.getFirstCommentsFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetFirstCommentsRequest(String pageUrl, GetFirstCommentsCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            try {
                pageUrl = URLEncoder.encode(pageUrl, "UTF-8");
            } catch (UnsupportedEncodingException e) {
            }
            URL url = new URL(GET_FIRST_COMMENTS_URL + "?url=" + pageUrl);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setRequestMethod("GET");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
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
                    GetFirstCommentsWorkerTask.setGetFirstCommentsSuccessStatus(true);
                    JSONArray comments = responseObject.getJSONArray("comments");
                    int commentCount = responseObject.getInt("commentCount");
                    GetFirstCommentsWorkerTask.setComments(comments, commentCount);
                }else{
                    GetFirstCommentsWorkerTask.setGetFirstCommentsSuccessStatus(false);
                    GetFirstCommentsWorkerTask.setErrorMessage(responseObject.getString("error"));
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
