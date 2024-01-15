   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
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

public class BrowserExpressGetCommentsUtil {
    private static final String TAG = "Get_Comments_Browser_Express";
    private static final String GET_COMMENTS_URL = "https://api.browser.express/v1/comment";

    public interface GetCommentsCallback {
        void getCommentsSuccessful(List<Comment> comments);
        void getCommentsFailed(String error);
    }

    public static class GetCommentsWorkerTask extends AsyncTask<Void> {
        private GetCommentsCallback mCallback;
        private static Boolean getCommentsStatus;
        private static String mErrorMessage;
        private static String mUrl;
        private static int mPage;
        private static int mPerPage;
        private static List<Comment> mComments;

        public GetCommentsWorkerTask(String url, int page, int perPage, GetCommentsCallback callback) {
            mCallback = callback;
            getCommentsStatus = false;
            mErrorMessage = "";
            mUrl = url;
            mComments = new ArrayList<Comment>();
            mPage = page;
            mPerPage = perPage;
        }

        public static void setComments(List<Comment> comments){
            mComments = comments;
        }

        public static void setGetCommentsSuccessStatus(Boolean status){
            getCommentsStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetCommentsRequest(mUrl, mPage, mPerPage, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getCommentsStatus){
                mCallback.getCommentsSuccessful(mComments);
            }else{
                mCallback.getCommentsFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetCommentsRequest(String pageUrl, int page, int perPage, GetCommentsCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URI url = new URI(GET_COMMENTS_URL);
            Uri.Builder builder = url.buildUpon();
            builder.appendQueryParameter("url", pageUrl);
            builder.appendQueryParameter("page", Integer.toString(page));
            builder.appendQueryParameter("per_page", Integer.toString(perPage));
            url =  builder.build();

            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url.toURL(), NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
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
                    GetCommentsWorkerTask.setGetCommentsSuccessStatus(true);
                    List<Comment> comments = responseObject.getObject("comments");
                    GetCommentsWorkerTask.setComments(comments);
                }else{
                    GetCommentsWorkerTask.setGetCommentsSuccessStatus(false);
                    GetCommentsWorkerTask.setErrorMessage(responseObject.getString("error"));
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
