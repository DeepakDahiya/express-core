/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sets or removes the reader's emoji reaction on a comment: {@code PUT} and {@code DELETE
 * /v1/comment/:id/reaction}. YouTube comments that are not in our database yet go through
 * {@link YouTubeInteractUtil} instead, which registers them first.
 */
public class CommentReactionUtil {
    private static final String TAG = "CommentReaction";
    private static final String REACTION_URL = "https://api.browser.express/v1/comment/%s/reaction";
    private static final int TIMEOUT_MS = 15000;

    public interface Callback {
        /**
         * @param result the server's reaction state for the comment; apply it with
         *     {@link Comment#readReactions}.
         * @param newAccessToken set when the server minted a guest account for the reader.
         */
        void onReactionSaved(JSONObject result, @Nullable String newAccessToken,
                @Nullable String newRefreshToken);

        void onReactionFailed(String error);
    }

    /** @param emoji the reaction to set, or null to remove the reader's reaction. */
    public static void setReaction(String commentId, @Nullable String emoji,
            @Nullable String accessToken, Callback callback) {
        new Task(commentId, emoji, accessToken, callback)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class Task extends AsyncTask<Void> {
        private final String mCommentId;
        private final @Nullable String mEmoji;
        private final @Nullable String mAccessToken;
        private final Callback mCallback;

        private JSONObject mResult;
        private String mError;

        Task(String commentId, @Nullable String emoji, @Nullable String accessToken,
                Callback callback) {
            mCommentId = commentId;
            mEmoji = emoji;
            mAccessToken = accessToken;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(String.format(REACTION_URL, mCommentId));
                connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                        url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
                connection.setRequestMethod(mEmoji != null ? "PUT" : "DELETE");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "application/json");
                if (mAccessToken != null && !mAccessToken.isEmpty()) {
                    connection.setRequestProperty("Authorization", mAccessToken);
                }
                if (mEmoji != null) {
                    connection.setDoOutput(true);
                    JSONObject body = new JSONObject();
                    body.put("emoji", mEmoji);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }

                int code = connection.getResponseCode();
                InputStream stream = code < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                StringBuilder text = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) text.append(line);
                    }
                }
                JSONObject response = text.length() > 0 ? new JSONObject(text.toString())
                                                        : new JSONObject();
                if (code == HttpURLConnection.HTTP_OK && response.optBoolean("success")) {
                    mResult = response;
                } else {
                    mError = response.optString("error", "HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "Reaction request failed: " + e.getMessage());
                mError = e.getMessage() != null ? e.getMessage() : "Network error";
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (mResult != null) {
                String accessToken = mResult.optString("accessToken", "");
                String refreshToken = mResult.optString("refreshToken", "");
                mCallback.onReactionSaved(mResult, accessToken.isEmpty() ? null : accessToken,
                        refreshToken.isEmpty() ? null : refreshToken);
            } else {
                mCallback.onReactionFailed(mError != null ? mError : "Unknown error");
            }
        }
    }
}
