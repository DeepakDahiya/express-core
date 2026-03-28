/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches our DB's YouTube-sourced comments for a given page URL.
 * Calls GET /v1/comment/youtube?pageUrl=...
 */
public class BrowserExpressGetYouTubeDbCommentsUtil {
    private static final String TAG = "YTDbComments";
    private static final String URL_BASE = "https://api.browser.express/v1/comment/youtube";

    public interface Callback {
        void onSuccess(List<Comment> comments);
        void onFailure(String error);
    }

    public static class Task extends AsyncTask<Void> {
        private final String mPageUrl;
        private final String mAccessToken;
        private final Callback mCallback;

        private List<Comment> mComments;
        private String mError;

        public Task(String pageUrl, String accessToken, Callback callback) {
            mPageUrl = pageUrl;
            mAccessToken = accessToken;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            HttpURLConnection conn = null;
            try {
                String encoded = URLEncoder.encode(mPageUrl, "UTF-8");
                URL url = new URL(URL_BASE + "?pageUrl=" + encoded);
                conn = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                        url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json");
                if (mAccessToken != null && !mAccessToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", mAccessToken);
                }
                conn.connect();

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    mError = "HTTP " + code;
                    return null;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject resp = new JSONObject(sb.toString());
                if (!resp.optBoolean("success", false)) {
                    mError = resp.optString("error", "Unknown");
                    return null;
                }

                mComments = new ArrayList<>();
                JSONArray arr = resp.optJSONArray("comments");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        Comment c = parseComment(arr.optJSONObject(i));
                        if (c != null) mComments.add(c);
                    }
                }
            } catch (Exception e) {
                mError = e.getMessage();
                Log.e(TAG, "fetch error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (mError != null) {
                mCallback.onFailure(mError);
            } else {
                mCallback.onSuccess(mComments);
            }
        }

        private Comment parseComment(JSONObject obj) {
            if (obj == null) return null;
            try {
                String id = obj.getString("_id");
                String content = obj.optString("content", "");
                int upvotes = obj.optInt("upvoteCount", 0);
                int downvotes = obj.optInt("downvoteCount", 0);
                int commentCount = obj.optInt("commentCount", 0);
                int trendingScore = obj.optInt("trendingScore", 0);
                String pageParent = obj.optString("pageParent", null);
                String commentParent = obj.isNull("commentParent") ? null : obj.optString("commentParent", null);
                String youtubeId = obj.optString("youtubeId", null);
                String youtubeAuthorName = obj.optString("youtubeAuthorName", null);
                String youtubeAvatarUrl = obj.optString("youtubeAvatarUrl", null);

                Vote vote = null;
                JSONObject didVoteObj = obj.optJSONObject("didVote");
                if (didVoteObj != null) {
                    vote = new Vote(didVoteObj.optString("_id", null),
                            didVoteObj.optString("type", null));
                }

                // user is null for YouTube-sourced comments (no native app user)
                User user = null;
                if (!obj.isNull("user")) {
                    JSONObject userObj = obj.optJSONObject("user");
                    if (userObj != null) {
                        user = new User(userObj.optString("_id", null),
                                userObj.optString("username", null),
                                userObj.optString("avatar", null));
                    }
                }

                Comment c = new Comment(id, content, upvotes, downvotes, commentCount,
                        pageParent, null, commentParent, user, vote,
                        null, null, null, null, null);
                c.setTrendingScore(trendingScore);
                if (youtubeId != null) {
                    c.setYoutubeId(youtubeId);
                    c.setYoutubeAuthorName(youtubeAuthorName);
                    c.setYoutubeAvatarUrl(youtubeAvatarUrl);
                }
                return c;
            } catch (Exception e) {
                Log.e(TAG, "parse error: " + e.getMessage());
                return null;
            }
        }
    }
}
