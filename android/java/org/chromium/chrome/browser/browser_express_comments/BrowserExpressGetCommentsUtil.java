   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

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

public class BrowserExpressGetCommentsUtil {
    private static final String TAG = "Get_Comments_Browser_Express";
    private static final String GET_COMMENTS_URL = "https://api.browser.express/v1/comment";

    public interface GetCommentsCallback {
        void getCommentsSuccessful(List<Comment> comments, Comment parentComment);
        void getCommentsFailed(String error);
    }

    public static class GetCommentsWorkerTask extends AsyncTask<Void> {
        private GetCommentsCallback mCallback;
        private static Boolean getCommentsStatus;
        private static String mErrorMessage;
        private static String mUrl;
        private static String mCommentId;
        private static String mPostId;
        private static int mPage;
        private static int mPerPage;
        private static List<Comment> mComments;
        private static String mAccessToken;
        private static Comment mParentComment;

        public GetCommentsWorkerTask(String url, String commentId, String postId, int page, int perPage, String accessToken, GetCommentsCallback callback) {
            mCallback = callback;
            getCommentsStatus = false;
            mErrorMessage = "";
            mUrl = url;
            mComments = new ArrayList<Comment>();
            mPage = page;
            mPerPage = perPage;
            mAccessToken = accessToken;
            mCommentId = commentId;
            mPostId = postId;
            mParentComment = null;
        }

        public static void setComments(List<Comment> comments){
            mComments = comments;
        }

        public static void setParentComment(Comment comment){
            mParentComment = comment;
        }

        public static void setGetCommentsSuccessStatus(Boolean status){
            getCommentsStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetCommentsRequest(mUrl, mCommentId, mPostId, mPage, mPerPage, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getCommentsStatus){
                mCallback.getCommentsSuccessful(mComments, mParentComment);
            }else{
                mCallback.getCommentsFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetCommentsRequest(String pageUrl, String commentId, String postId, int page, int perPage, String accessToken, GetCommentsCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            String searchQuery = "";
            if(commentId != null && !commentId.equals("")){
                searchQuery =  "?commentId=" + commentId;
            }else if(postId != null && !postId.equals("")){
                searchQuery =  "?postId=" + postId;
            }else{
                try {
                    String encodedUrl = URLEncoder.encode(pageUrl, "UTF-8");
                    searchQuery =  "?url=" + encodedUrl;
                } catch (UnsupportedEncodingException e) {
                }
            }

            searchQuery = searchQuery + "&page=" + Integer.toString(page) + "&per_page=" + Integer.toString(perPage);

            URL url = new URL(GET_COMMENTS_URL + searchQuery);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setRequestMethod("GET");
            urlConnection.setUseCaches(false);
            if(accessToken != null && !accessToken.equals("")){
                urlConnection.setRequestProperty ("Authorization", accessToken);
            }
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
                    JSONArray commentsArray = responseObject.getJSONArray("comments");
                    if (!responseObject.isNull("parentComment")) {
                        JSONObject parentComment = responseObject.getJSONObject("parentComment");
                        Log.e("PARENT COMMENT", parentComment.toString());
                        if(parentComment != null){
                            JSONObject user = parentComment.getJSONObject("user");
                            JSONObject didVote = parentComment.optJSONObject("didVote");
                            Vote v = null;
                            if(didVote != null){
                                v = new Vote(didVote.getString("_id"), didVote.getString("type"));
                            }
                            User u = new User(user.getString("_id"), user.getString("username"));
                            GetCommentsWorkerTask.setParentComment(new Comment(
                                parentComment.getString("_id"), 
                                parentComment.getString("content"),
                                parentComment.getInt("upvoteCount"),
                                parentComment.getInt("downvoteCount"),
                                parentComment.getInt("commentCount"),
                                null,
                                null,
                                u, 
                                v));
                        }
                    }

                    Log.e("GET API RESPONSE FROM SERVER", commentsArray.toString());
                    List<Comment> comments = new ArrayList<Comment>();
                    for (int i = 0; i < commentsArray.length(); i++) {
                        JSONObject comment = commentsArray.getJSONObject(i);
                        JSONObject user = comment.getJSONObject("user");
                        JSONObject didVote = comment.optJSONObject("didVote");
                        Vote v = null;
                        if(didVote != null){
                            v = new Vote(didVote.getString("_id"), didVote.getString("type"));
                        }
                        String pageParent = null;
                        String commentParent = null;
                        if(comment.has("pageParent")){
                            pageParent = comment.getString("pageParent");
                        }

                        if(comment.has("commentParent")){
                            commentParent = comment.getString("commentParent");
                        }

                        User u = new User(user.getString("_id"), user.getString("username"));
                        comments.add(new Comment(
                            comment.getString("_id"), 
                            comment.getString("content"),
                            comment.getInt("upvoteCount"),
                            comment.getInt("downvoteCount"),
                            comment.getInt("commentCount"),
                            pageParent,
                            commentParent,
                            u, 
                            v));
                    }
                    Log.e("GET API RESPONSE", comments.toString());

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
