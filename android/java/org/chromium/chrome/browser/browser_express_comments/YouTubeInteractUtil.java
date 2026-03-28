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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Handles POST /v1/comment/yt_interact — atomically registers YouTube ancestor comments
 * in our DB and records the user interaction (upvote, downvote, or reply).
 */
public class YouTubeInteractUtil {
    private static final String TAG = "YouTubeInteract";
    private static final String YT_INTERACT_URL =
            "https://api.browser.express/v1/comment/yt_interact";

    public interface Callback {
        void onSuccess(Map<String, String> resolvedIds, String targetId,
                int upvoteCount, int downvoteCount, int commentCount, Vote didVote);
        void onFailure(String error);
    }

    public static class Task extends AsyncTask<Void> {
        private final String mPageUrl;
        private final List<Comment> mAncestors;
        private final String mInteractionType;
        private final String mReplyContent;
        private final String mAccessToken;
        private final Callback mCallback;

        private Map<String, String> mResolvedIds;
        private String mTargetId;
        private int mUpvoteCount;
        private int mDownvoteCount;
        private int mCommentCount;
        private Vote mDidVote;
        private String mError;

        public Task(String pageUrl, List<Comment> ancestors, String interactionType,
                String replyContent, String accessToken, Callback callback) {
            mPageUrl = pageUrl;
            mAncestors = ancestors;
            mInteractionType = interactionType;
            mReplyContent = replyContent;
            mAccessToken = accessToken;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(YT_INTERACT_URL);
                conn = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                        url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json");
                if (mAccessToken != null && !mAccessToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", mAccessToken);
                }
                conn.connect();

                JSONObject body = new JSONObject();
                body.put("pageUrl", mPageUrl);
                body.put("interactionType", mInteractionType);
                if (mReplyContent != null) body.put("replyContent", mReplyContent);

                JSONArray ancestorsArray = new JSONArray();
                for (Comment ancestor : mAncestors) {
                    JSONObject a = new JSONObject();
                    a.put("youtubeId", ancestor.getYoutubeId());
                    a.put("content", ancestor.getContent());
                    // Prefer explicit youtubeAuthorName, fall back to user object
                    String authorName = ancestor.getYoutubeAuthorName();
                    if (authorName == null && ancestor.getUser() != null) {
                        authorName = ancestor.getUser().getUsername();
                    }
                    if (authorName != null) a.put("youtubeAuthorName", authorName);
                    String avatarUrl = ancestor.getYoutubeAvatarUrl();
                    if (avatarUrl == null && ancestor.getUser() != null) {
                        avatarUrl = ancestor.getUser().getAvatar();
                    }
                    if (avatarUrl != null) a.put("youtubeAvatarUrl", avatarUrl);
                    ancestorsArray.put(a);
                }
                body.put("ancestors", ancestorsArray);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.close();

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
                    mError = resp.optString("error", "Unknown error");
                    return null;
                }

                mResolvedIds = new HashMap<>();
                JSONObject resolvedIdsObj = resp.optJSONObject("resolvedIds");
                if (resolvedIdsObj != null) {
                    Iterator<String> keys = resolvedIdsObj.keys();
                    while (keys.hasNext()) {
                        String ytId = keys.next();
                        mResolvedIds.put(ytId, resolvedIdsObj.getString(ytId));
                    }
                }

                mTargetId = resp.optString("targetId", null);
                mUpvoteCount = resp.optInt("upvoteCount", 0);
                mDownvoteCount = resp.optInt("downvoteCount", 0);
                mCommentCount = resp.optInt("commentCount", 0);

                JSONObject didVoteObj = resp.optJSONObject("didVote");
                if (didVoteObj != null) {
                    mDidVote = new Vote(null, didVoteObj.optString("type", null));
                }
            } catch (Exception e) {
                mError = e.getMessage();
                Log.e(TAG, "yt_interact error: " + e.getMessage());
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
                mCallback.onSuccess(mResolvedIds, mTargetId,
                        mUpvoteCount, mDownvoteCount, mCommentCount, mDidVote);
            }
        }
    }
}
