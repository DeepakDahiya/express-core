   
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

public class BrowserExpressAddVoteUtil {
    private static final String TAG = "Add_Vote_Browser_Express";
    private static final String ADD_COMMENT_BASE_URL = "https://api.browser.express/v1/comment";

    public interface AddVoteCallback {
        void addVoteSuccessful(Comment comment);
        void addVoteFailed(String error);
    }

    public static class AddVoteWorkerTask extends AsyncTask<Void> {
        private AddVoteCallback mCallback;
        private static Boolean addVoteStatus;
        private static String mErrorMessage;
        private static String mCommentId;
        private static String mType;
        private static String mAccessToken;

        public AddVoteWorkerTask(String commentId, String type, String accessToken, AddVoteCallback callback) {
            mCallback = callback;
            addVoteStatus = false;
            mErrorMessage = "";
            mCommentId = commentId;
            mType = type;
            mAccessToken = accessToken;
        }

        public static void setAddVoteSuccessStatus(Boolean status){
            addVoteStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendAddVoteRequest(mCommentId, mType, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(addVoteStatus){
                mCallback.addVoteSuccessful();
            }else{
                mCallback.addVoteFailed(mErrorMessage);
            }
        }
    }

    private static void sendAddVoteRequest(String commentId, String type, String accessToken, AddVoteCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(ADD_COMMENT_BASE_URL + "/" + commentId + "/vote");
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("PATCH");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty ("Authorization", accessToken);
            // urlConnection.setRequestProperty ("x-refresh", refreshToken);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("type", type);
            jsonParam.put("platform", "Android");

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
                    AddVoteWorkerTask.setAddVoteSuccessStatus(true);

                    // JSONObject comment = responseObject.getJSONObject("comment");
                    // JSONObject user = comment.getJSONObject("user");
                    // User u = new User(user.getString("_id"), user.getString("username"));
                    // AddVoteWorkerTask.setComment(new Comment(
                    //     comment.getString("_id"), 
                    //     comment.getString("content"),
                    //     comment.getInt("upvoteCount"),
                    //     comment.getInt("downvoteCount"),
                    //     comment.getInt("commentCount"),
                    //     u));
                }else{
                    AddVoteWorkerTask.setAddVoteSuccessStatus(false);
                    AddVoteWorkerTask.setErrorMessage(responseObject.getString("error"));
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
