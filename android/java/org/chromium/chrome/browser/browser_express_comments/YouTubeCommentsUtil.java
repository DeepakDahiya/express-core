/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class YouTubeCommentsUtil {
    private static final String TAG = "YouTubeComments";

    private static final String YT_API_KEY = "AIzaSyC-LdqrxY9etQklNNbfK1lNLkozljiixQg";
    private static final String COMMENT_THREADS_URL =
            "https://www.googleapis.com/youtube/v3/commentThreads";

    public static class GetYouTubeCommentsTask extends AsyncTask<Void> {
        private final String mVideoId;
        private final BrowserExpressGetCommentsUtil.GetCommentsCallback mCallback;
        private List<Comment> mComments;
        private String mError;

        public GetYouTubeCommentsTask(
                String videoId, BrowserExpressGetCommentsUtil.GetCommentsCallback callback) {
            mVideoId = videoId;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            Log.e(TAG, "[Step 1] Starting YouTube v3 comments fetch for videoId=" + mVideoId);
            try {
                mComments = fetchComments();
                Log.e(TAG, "[Done] Fetch complete — total comments parsed: " + mComments.size());
            } catch (Exception e) {
                mError = e.getMessage();
                Log.e(TAG, "[ERROR] Exception during YouTube comments fetch: " + e.getMessage());
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (mComments != null) {
                Log.e(TAG, "[UI] Delivering " + mComments.size() + " YouTube comments to adapter");
                mCallback.getCommentsSuccessful(mComments, null, null);
            } else {
                Log.e(TAG, "[UI] Delivering failure: " + mError);
                mCallback.getCommentsFailed(mError != null ? mError : "Unknown error");
            }
        }

        /**
         * Fetches comment threads from the YouTube Data API v3 commentThreads endpoint.
         * Handles pagination via nextPageToken, collecting up to 200 comments per page.
         */
        private List<Comment> fetchComments() throws Exception {
            List<Comment> comments = new ArrayList<>();
            String pageToken = null;

            do {
                StringBuilder urlBuilder = new StringBuilder(COMMENT_THREADS_URL)
                        .append("?key=").append(YT_API_KEY)
                        .append("&textFormat=plainText")
                        .append("&part=snippet")
                        .append("&videoId=").append(mVideoId)
                        .append("&maxResults=200")
                        .append("&order=relevance");
                if (pageToken != null) {
                    urlBuilder.append("&pageToken=").append(pageToken);
                }

                Log.e(TAG, "[HTTP] GET " + urlBuilder);
                JSONObject response = getJson(urlBuilder.toString());
                if (response == null) {
                    Log.e(TAG, "[HTTP] Null response from v3 API");
                    break;
                }

                JSONArray items = response.optJSONArray("items");
                if (items == null) {
                    Log.e(TAG, "[Parse] No 'items' in response");
                    break;
                }
                Log.e(TAG, "[Parse] Received " + items.length() + " comment threads");

                for (int i = 0; i < items.length(); i++) {
                    Comment comment = parseCommentThread(items.optJSONObject(i));
                    if (comment != null) comments.add(comment);
                }

                pageToken = response.optString("nextPageToken", null);
                if (pageToken != null && pageToken.isEmpty()) pageToken = null;
            } while (pageToken != null);

            return comments;
        }

        /**
         * Parses a single YouTube v3 commentThread item into our Comment model.
         * Shape:
         * {
         *   "id": "...",
         *   "snippet": {
         *     "totalReplyCount": N,
         *     "topLevelComment": {
         *       "id": "...",
         *       "snippet": {
         *         "textDisplay": "...",
         *         "authorDisplayName": "...",
         *         "authorProfileImageUrl": "...",
         *         "authorChannelId": { "value": "..." },
         *         "likeCount": N,
         *         "publishedAt": "..."
         *       }
         *     }
         *   }
         * }
         */
        private Comment parseCommentThread(JSONObject item) {
            if (item == null) return null;
            try {
                JSONObject threadSnippet = item.optJSONObject("snippet");
                if (threadSnippet == null) return null;

                int totalReplyCount = threadSnippet.optInt("totalReplyCount", 0);

                JSONObject topLevel = threadSnippet.optJSONObject("topLevelComment");
                if (topLevel == null) return null;

                String id = topLevel.optString("id", null);
                if (id == null || id.isEmpty()) {
                    Log.e(TAG, "[Parse] topLevelComment missing id, skipping");
                    return null;
                }

                JSONObject snippet = topLevel.optJSONObject("snippet");
                if (snippet == null) return null;

                String text = snippet.optString("textDisplay", "");
                String authorName = snippet.optString("authorDisplayName", null);
                String avatarUrl = snippet.optString("authorProfileImageUrl", null);
                int likeCount = snippet.optInt("likeCount", 0);

                String authorChannelId = id;
                JSONObject channelIdObj = snippet.optJSONObject("authorChannelId");
                if (channelIdObj != null) {
                    String val = channelIdObj.optString("value", null);
                    if (val != null && !val.isEmpty()) authorChannelId = val;
                }

                Log.e(TAG, "[Parse] id=" + id
                        + " | author='" + authorName + "'"
                        + " | likes=" + likeCount
                        + " | replies=" + totalReplyCount
                        + " | text='" + (text.length() > 80 ? text.substring(0, 80) + "…" : text) + "'");

                User user = new User(authorChannelId, authorName, avatarUrl);
                return new Comment(id, text, likeCount, 0, totalReplyCount,
                        null, null, null, user, null, null, null, null, null, null);
            } catch (Exception e) {
                Log.e(TAG, "[Parse] parseCommentThread error: " + e.getMessage());
                return null;
            }
        }

        /** Issues a GET request and returns the parsed JSON response. */
        private JSONObject getJson(String urlString) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);

                int code = conn.getResponseCode();
                Log.e(TAG, "[HTTP] Response code: " + code);
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "[HTTP] Non-200 response from v3 API: " + code);
                    return null;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                Log.e(TAG, "[HTTP] Response body length: " + sb.length() + " chars");
                return new JSONObject(sb.toString());
            } catch (Exception e) {
                Log.e(TAG, "[HTTP] getJson failed: " + e.getMessage());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
