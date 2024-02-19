   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

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

import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressGenerateUsernameBottomSheetFragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BrowserExpressAddCommentUtil {
    private static final String TAG = "Add_Comment_Browser_Express";
    private static final String ADD_COMMENT_URL = "https://api.browser.express/v1/comment";

    public interface AddCommentCallback {
        void addCommentSuccessful(Comment comment);
        void addCommentFailed(String error);
    }

    public static class AddCommentWorkerTask extends AsyncTask<Void> {
        private AddCommentCallback mCallback;
        private static Boolean addCommentStatus;
        private static String mErrorMessage;
        private static String mContent;
        private static String mParentType;
        private static String mParentId;
        private static String mUrl;
        private static String mAccessToken;
        private static Comment mComment;

        public AddCommentWorkerTask(String content, String parentType, String url, String parentId, String accessToken, AddCommentCallback callback) {
            mCallback = callback;
            addCommentStatus = false;
            mErrorMessage = "";
            mContent = content;
            mParentType = parentType;
            mParentId = parentId;
            mUrl = url;
            mAccessToken = accessToken;
        }

        public static void setComment(Comment comment){
            mComment = comment;
        }

        public static void setAddCommentSuccessStatus(Boolean status){
            addCommentStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendAddCommentRequest(mContent, mParentType, mParentId, mUrl, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(addCommentStatus){
                mCallback.addCommentSuccessful(mComment);
            }else{
                mCallback.addCommentFailed(mErrorMessage);
            }
        }
    }

    private static void sendAddCommentRequest(String content, String parentType, String parentId, String pageUrl, String accessToken, AddCommentCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(ADD_COMMENT_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty ("Authorization", accessToken);
            // urlConnection.setRequestProperty ("x-refresh", refreshToken);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("content", content);
            jsonParam.put("platform", "Android");
            jsonParam.put("parentType", parentType);
            jsonParam.put("parentId", parentId);
            jsonParam.put("url", pageUrl);

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
                    AddCommentWorkerTask.setAddCommentSuccessStatus(true);

                    JSONObject comment = responseObject.getJSONObject("comment");
                    JSONObject user = comment.getJSONObject("user");
                    User u = new User(user.getString("_id"), user.getString("username"));
                    Vote v = null;
                    String pageParent = null;
                    String commentParent = null;
                    if(comment.has("pageParent")){
                        pageParent = comment.getString("pageParent");
                    }

                    if(comment.has("commentParent")){
                        commentParent = comment.getString("commentParent");
                    }
                    AddCommentWorkerTask.setComment(new Comment(
                        comment.getString("_id"), 
                        comment.getString("content"),
                        comment.getInt("upvoteCount"),
                        comment.getInt("downvoteCount"),
                        comment.getInt("commentCount"),
                        pageParent, 
                        commentParent,
                        u,
                        v));
                }else{
                    AddCommentWorkerTask.setAddCommentSuccessStatus(false);
                    AddCommentWorkerTask.setErrorMessage(responseObject.getString("error"));
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
