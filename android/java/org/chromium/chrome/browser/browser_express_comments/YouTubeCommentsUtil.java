/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class YouTubeCommentsUtil {
    private static final String TAG = "YouTubeCommentsUtil";

    // Well-known public InnerTube WEB client key — stable across years.
    private static final String INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";
    private static final String CLIENT_NAME = "WEB";
    private static final String CLIENT_VERSION = "2.20231219.01.00";

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
            try {
                String token = fetchContinuationToken();
                if (token == null) {
                    mError = "Could not locate comment section token";
                    return null;
                }
                mComments = fetchComments(token);
            } catch (Exception e) {
                mError = e.getMessage();
                Log.e(TAG, "Error fetching YouTube comments: " + e.getMessage());
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (mComments != null) {
                mCallback.getCommentsSuccessful(mComments, null, null);
            } else {
                mCallback.getCommentsFailed(mError != null ? mError : "Unknown error");
            }
        }

        // -----------------------------------------------------------------------------------------
        // Step 1: fetch the comments continuation token from /next with the video ID.
        // Path: engagementPanels → comment-item-section → itemSectionRenderer →
        //        continuationItemRenderer → continuationEndpoint.continuationCommand.token
        // -----------------------------------------------------------------------------------------
        private String fetchContinuationToken() throws Exception {
            JSONObject body = new JSONObject()
                    .put("context", buildClientContext())
                    .put("videoId", mVideoId);

            JSONObject response = postToInnertube("next", body);
            if (response == null) return null;

            JSONArray panels = response.optJSONArray("engagementPanels");
            if (panels == null) return null;

            for (int i = 0; i < panels.length(); i++) {
                JSONObject panel = panels.getJSONObject(i)
                        .optJSONObject("engagementPanelSectionListRenderer");
                if (panel == null) continue;
                if (!"comment-item-section".equals(panel.optString("panelIdentifier"))) continue;

                String token = extractTokenFromPanel(panel);
                if (token != null) return token;
            }
            return null;
        }

        private String extractTokenFromPanel(JSONObject panel) {
            try {
                JSONObject content = panel.optJSONObject("content");
                if (content == null) return null;
                JSONObject sectionList = content.optJSONObject("sectionListRenderer");
                if (sectionList == null) return null;
                JSONArray contents = sectionList.optJSONArray("contents");
                if (contents == null) return null;

                for (int j = 0; j < contents.length(); j++) {
                    JSONObject section = contents.getJSONObject(j)
                            .optJSONObject("itemSectionRenderer");
                    if (section == null) continue;
                    JSONArray items = section.optJSONArray("contents");
                    if (items == null) continue;
                    for (int k = 0; k < items.length(); k++) {
                        String token = extractTokenFromItem(items.getJSONObject(k));
                        if (token != null) return token;
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "extractTokenFromPanel error: " + e.getMessage());
            }
            return null;
        }

        // -----------------------------------------------------------------------------------------
        // Step 2: POST /next with the continuation token to get the actual comment list.
        // Handles both reloadContinuationItemsCommand and appendContinuationItemsAction shapes
        // that YouTube uses depending on client/session state.
        // -----------------------------------------------------------------------------------------
        private List<Comment> fetchComments(String token) throws Exception {
            JSONObject body = new JSONObject()
                    .put("context", buildClientContext())
                    .put("continuation", token);

            JSONObject response = postToInnertube("next", body);
            List<Comment> comments = new ArrayList<>();
            if (response == null) return comments;

            JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
            if (endpoints == null) return comments;

            for (int i = 0; i < endpoints.length(); i++) {
                JSONObject endpoint = endpoints.getJSONObject(i);
                JSONArray items = getContinuationItems(endpoint);
                if (items == null) continue;

                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    JSONObject thread = item.optJSONObject("commentThreadRenderer");
                    if (thread == null) continue;
                    Comment comment = parseCommentThread(thread);
                    if (comment != null) comments.add(comment);
                }
            }
            return comments;
        }

        /** Extracts the continuationItems array from either response shape. */
        private JSONArray getContinuationItems(JSONObject endpoint) {
            // Shape A: reloadContinuationItemsCommand (initial comment load)
            JSONObject reload = endpoint.optJSONObject("reloadContinuationItemsCommand");
            if (reload != null) {
                JSONArray items = reload.optJSONArray("continuationItems");
                if (items != null) return items;
            }
            // Shape B: appendContinuationItemsAction (subsequent pages)
            JSONObject append = endpoint.optJSONObject("appendContinuationItemsAction");
            if (append != null) {
                JSONArray items = append.optJSONArray("continuationItems");
                if (items != null) return items;
            }
            return null;
        }

        // -----------------------------------------------------------------------------------------
        // Comment parsing — maps a YouTube commentThreadRenderer to our Comment model.
        // -----------------------------------------------------------------------------------------
        private Comment parseCommentThread(JSONObject threadRenderer) {
            try {
                JSONObject commentObj = threadRenderer.optJSONObject("comment");
                if (commentObj == null) return null;
                JSONObject cr = commentObj.optJSONObject("commentRenderer");
                if (cr == null) return null;
                return parseCommentRenderer(cr, null);
            } catch (Exception e) {
                Log.e(TAG, "parseCommentThread error: " + e.getMessage());
                return null;
            }
        }

        private Comment parseCommentRenderer(JSONObject cr, String parentCommentId) {
            String id = cr.optString("commentId", null);
            if (id == null || id.isEmpty()) return null;

            // Text: join all runs (handles formatted text, links, emoji text)
            String content = extractRuns(cr.optJSONObject("contentText"));

            // Like count: YouTube shows "1.2K", "500", or omits the field for 0
            int likes = 0;
            JSONObject voteCount = cr.optJSONObject("voteCount");
            if (voteCount != null) {
                likes = parseYouTubeCount(voteCount.optString("simpleText", "0"));
            }

            // Reply count: integer field, absent means 0
            int replyCount = cr.optInt("replyCount", 0);

            // Author name
            String authorName = extractRuns(cr.optJSONObject("authorText"));

            // Channel ID as stable user ID
            String authorChannelId = cr.optString("authorExternalChannelId", id);

            // Avatar: last thumbnail = highest resolution; fix scheme-relative URLs
            String avatarUrl = extractBestThumbnailUrl(cr.optJSONObject("authorThumbnail"));

            User user = new User(authorChannelId, authorName, avatarUrl);

            return new Comment(
                    id,
                    content,
                    likes,
                    0,              // downvoteCount — YouTube does not expose this
                    replyCount,     // commentCount used for reply count
                    null,           // pageParent
                    null,           // postParent
                    parentCommentId, // commentParent — null for top-level
                    user,
                    null,           // didVote — not applicable for YouTube
                    null,           // mediaImageUrl
                    null,           // mediaVideoUrl
                    null,           // postContent
                    null,           // postUsername
                    null            // postAvatarUrl
            );
        }

        // -----------------------------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------------------------

        /**
         * Concatenates all "text" values from a runs array.
         * Falls back to simpleText for renderers that use it instead.
         * Handles null gracefully throughout.
         */
        private String extractRuns(JSONObject textObj) {
            if (textObj == null) return "";
            JSONArray runs = textObj.optJSONArray("runs");
            if (runs == null) return textObj.optString("simpleText", "");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null) sb.append(run.optString("text", ""));
            }
            return sb.toString().trim();
        }

        /**
         * Parses YouTube's human-readable counts into integers.
         * Examples: "1.2K" → 1200, "3.5M" → 3500000, "500" → 500, "" → 0.
         */
        private int parseYouTubeCount(String text) {
            if (text == null || text.isEmpty()) return 0;
            try {
                String clean = text.trim().replace(",", "");
                char last = clean.charAt(clean.length() - 1);
                if (last == 'K' || last == 'k') {
                    return (int) (Double.parseDouble(clean.substring(0, clean.length() - 1)) * 1_000);
                } else if (last == 'M' || last == 'm') {
                    return (int) (Double.parseDouble(clean.substring(0, clean.length() - 1)) * 1_000_000);
                } else if (last == 'B' || last == 'b') {
                    return (int) (Double.parseDouble(clean.substring(0, clean.length() - 1)) * 1_000_000_000);
                }
                return Integer.parseInt(clean);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * Returns the highest-resolution thumbnail URL from a YouTube thumbnail object.
         * Handles scheme-relative URLs (//yt3.ggpht.com/...) by prepending "https:".
         * thumbnail object shape: { "thumbnails": [ {"url": "...", "width": n, "height": n}, ... ] }
         */
        private String extractBestThumbnailUrl(JSONObject thumbObj) {
            if (thumbObj == null) return null;
            JSONArray thumbs = thumbObj.optJSONArray("thumbnails");
            if (thumbs == null || thumbs.length() == 0) return null;
            // Last entry is highest resolution
            String url = thumbs.optJSONObject(thumbs.length() - 1) != null
                    ? thumbs.optJSONObject(thumbs.length() - 1).optString("url", null)
                    : null;
            if (url == null) return null;
            return url.startsWith("//") ? "https:" + url : url;
        }

        /**
         * Extracts the continuation token from a single item object.
         * Handles the continuationItemRenderer shape used in both step 1 and step 2.
         */
        private String extractTokenFromItem(JSONObject item) {
            JSONObject continuationItem = item.optJSONObject("continuationItemRenderer");
            if (continuationItem == null) return null;
            JSONObject endpoint = continuationItem.optJSONObject("continuationEndpoint");
            if (endpoint == null) return null;
            JSONObject cmd = endpoint.optJSONObject("continuationCommand");
            if (cmd == null) return null;
            String token = cmd.optString("token", null);
            return (token != null && !token.isEmpty()) ? token : null;
        }

        /**
         * Builds the InnerTube client context object used in all requests.
         * "hl" and "gl" ensure English responses for consistent parsing.
         */
        private JSONObject buildClientContext() throws JSONException {
            return new JSONObject().put("client", new JSONObject()
                    .put("clientName", CLIENT_NAME)
                    .put("clientVersion", CLIENT_VERSION)
                    .put("hl", "en")
                    .put("gl", "US"));
        }

        /** POSTs a JSON body to a YouTube InnerTube endpoint and returns the parsed response. */
        private JSONObject postToInnertube(String endpoint, JSONObject body) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(INNERTUBE_BASE_URL + endpoint + "?key=" + INNERTUBE_API_KEY);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);

                byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "InnerTube HTTP " + code + " for " + endpoint);
                    return null;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                return new JSONObject(sb.toString());
            } catch (Exception e) {
                Log.e(TAG, "postToInnertube failed (" + endpoint + "): " + e.getMessage());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
