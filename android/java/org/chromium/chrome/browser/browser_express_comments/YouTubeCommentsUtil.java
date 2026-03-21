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
    private static final String TAG = "YouTubeComments";

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
            Log.e(TAG, "[Step 1] Starting YouTube comments fetch for videoId=" + mVideoId);
            try {
                String token = fetchContinuationToken();
                if (token == null) {
                    mError = "Could not locate comment section token";
                    Log.e(TAG, "[Step 1] FAILED — no continuation token found for videoId=" + mVideoId);
                    return null;
                }
                Log.e(TAG, "[Step 1] Got continuation token (length=" + token.length() + ")");
                mComments = fetchComments(token);
                Log.e(TAG, "[Step 2] Fetch complete — total comments parsed: " + mComments.size());
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

        // -----------------------------------------------------------------------------------------
        // Step 1: fetch the comments continuation token by loading the YouTube watch page HTML.
        //
        // The InnerTube /next API is unreliable for returning engagementPanels with
        // comment-item-section (depends on A/B tests, client, region). The page HTML always
        // embeds ytInitialData which contains the same engagement panels. We find the
        // comment-item-section marker in the raw HTML and extract the nearby token string —
        // no full JSON parse needed.
        // -----------------------------------------------------------------------------------------
        private String fetchContinuationToken() throws Exception {
            String pageUrl = "https://www.youtube.com/watch?v=" + mVideoId + "&hl=en";
            Log.e(TAG, "[Step 1] Fetching YouTube page HTML: " + pageUrl);

            HttpURLConnection conn = null;
            String html;
            try {
                URL url = new URL(pageUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                // Desktop UA — avoids YouTube redirecting to a stripped mobile page
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(20_000);

                int code = conn.getResponseCode();
                Log.e(TAG, "[Step 1] Page HTTP response code: " + code);
                if (code != HttpURLConnection.HTTP_OK) return null;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                html = sb.toString();
                Log.e(TAG, "[Step 1] Page HTML length: " + html.length() + " chars");
            } finally {
                if (conn != null) conn.disconnect();
            }

            Log.e(TAG, html);

            // Find the comment-item-section marker in ytInitialData (embedded in the HTML).
            // From that anchor, scan forward for the first "token":"..." — that is the
            // comments continuation token used in step 2.
            String sectionMarker = "\"comment-item-section\"";
            int sectionIdx = html.indexOf(sectionMarker);
            if (sectionIdx == -1) {
                Log.e(TAG, "[Step 1] \"comment-item-section\" not found in page HTML — comments may be disabled for this video");
                return null;
            }
            Log.e(TAG, "[Step 1] Found comment-item-section at index " + sectionIdx);

            // Prefer "continuationCommand":{"token":"..." — specific to browse continuations.
            // In modern YouTube page HTML the comment continuation token can appear hundreds of
            // kilobytes after the comment-item-section identifier (ytInitialData is large), so
            // we search the remainder of the document without a size limit.
            String contCmdMarker = "\"continuationCommand\":{\"token\":\"";
            int tokenStart;
            int tokenEnd;
            int cmdIdx = html.indexOf(contCmdMarker, sectionIdx);
            if (cmdIdx != -1) {
                tokenStart = cmdIdx + contCmdMarker.length();
                Log.e(TAG, "[Step 1] Found continuationCommand token at index " + cmdIdx
                        + " (+" + (cmdIdx - sectionIdx) + " chars from section)");
            } else {
                // Fallback: plain "token":"..."
                String tokenMarker = "\"token\":\"";
                int idx = html.indexOf(tokenMarker, sectionIdx);
                if (idx == -1) {
                    Log.e(TAG, "[Step 1] No token found after comment-item-section");
                    return null;
                }
                Log.e(TAG, "[Step 1] Found plain token at index " + idx
                        + " (+" + (idx - sectionIdx) + " chars from section)");
                tokenStart = idx + tokenMarker.length();
            }
            tokenEnd = html.indexOf("\"", tokenStart);
            if (tokenEnd == -1) {
                Log.e(TAG, "[Step 1] Could not find closing quote for token value");
                return null;
            }

            String token = html.substring(tokenStart, tokenEnd);
            Log.e(TAG, "[Step 1] Extracted continuation token (length=" + token.length() + ")");
            return token;
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

            Log.e(TAG, "[Step 2] POST /next with continuation token to fetch comments");
            JSONObject response = postToInnertube("next", body);
            Log.e(TAG, "[Step 2] body: " + body);
            List<Comment> comments = new ArrayList<>();
            if (response == null) {
                Log.e(TAG, "[Step 2] /next returned null response");
                return comments;
            }

            JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
            if (endpoints == null) {
                Log.e(TAG, "[Step 2] No onResponseReceivedEndpoints in response");
                return comments;
            }
            Log.e(TAG, "[Step 2] Found " + endpoints.length() + " onResponseReceivedEndpoints");

            for (int i = 0; i < endpoints.length(); i++) {
                JSONObject endpoint = endpoints.getJSONObject(i);
                String endpointShape = endpoint.has("reloadContinuationItemsCommand")
                        ? "reloadContinuationItemsCommand"
                        : endpoint.has("appendContinuationItemsAction")
                                ? "appendContinuationItemsAction" : "unknown";
                JSONArray items = getContinuationItems(endpoint);
                if (items == null) {
                    Log.e(TAG, "[Step 2] endpoint[" + i + "] (" + endpointShape + ") — no continuationItems, skipping");
                    continue;
                }
                Log.e(TAG, "[Step 2] endpoint[" + i + "] (" + endpointShape + ") — " + items.length() + " items");

                int threadCount = 0;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);

                    // Log keys + first 600 chars of first item to diagnose response structure
                    if (j == 0) {
                        StringBuilder keys = new StringBuilder();
                        java.util.Iterator<String> keyIt = item.keys();
                        while (keyIt.hasNext()) keys.append(keyIt.next()).append(", ");
                        Log.e(TAG, "[Step 2] item[0] keys: " + keys);
                        String itemJson = item.toString();
                        Log.e(TAG, "[Step 2] item[0] json(600): "
                                + itemJson.substring(0, Math.min(600, itemJson.length())));
                    }

                    Log.e(TAG, "[Step 2] item: " + item);

                    // Classic format: commentThreadRenderer > comment > commentRenderer
                    JSONObject thread = item.optJSONObject("commentThreadRenderer");
                    // log thread
                    Log.e(TAG, "[Step 2] thread: " + thread);
                    if (thread != null) {
                        threadCount++;
                        Comment comment = parseCommentThread(thread);
                        if (comment != null) comments.add(comment);
                        continue;
                    }

                    // Newer format: commentViewModel at the item level
                    JSONObject cvm = item.optJSONObject("commentViewModel");
                    if (cvm != null) {
                        threadCount++;
                        Comment comment = parseCommentViewModel(cvm);
                        if (comment != null) comments.add(comment);
                        continue;
                    }

                    // Newer format: lockupViewModel wrapping a comment
                    JSONObject lvm = item.optJSONObject("lockupViewModel");
                    if (lvm != null) {
                        threadCount++;
                        Comment comment = parseLockupViewModel(lvm);
                        if (comment != null) comments.add(comment);
                        continue;
                    }
                }
                Log.e(TAG, "[Step 2] endpoint[" + i + "] — parsed " + threadCount + " commentThreadRenderers, "
                        + comments.size() + " valid Comment objects so far");
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
                if (commentObj == null) {
                    Log.e(TAG, "[Parse] commentThreadRenderer missing 'comment' key");
                    return null;
                }
                // Classic inner shape: commentRenderer
                JSONObject cr = commentObj.optJSONObject("commentRenderer");
                if (cr != null) return parseCommentRenderer(cr, null);

                // Newer inner shape: commentViewModel
                JSONObject cvm = commentObj.optJSONObject("commentViewModel");
                if (cvm != null) return parseCommentViewModel(cvm);

                Log.e(TAG, "[Parse] comment missing both 'commentRenderer' and 'commentViewModel'");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "[Parse] parseCommentThread error: " + e.getMessage());
                return null;
            }
        }

        /**
         * Parses a YouTube commentViewModel (newer InnerTube format introduced ~2024-2025).
         * Shape (may vary by region/A/B test):
         * {
         *   "commentId": "...",
         *   "properties": {
         *     "commentId": "...",
         *     "authorChannelId": "...",
         *     "authorDisplayName": "...",
         *     "authorThumbnail": { "thumbnails": [...] },
         *     "content": { "runs": [...] } | { "content": "..." },
         *     "likeCountNotliked": "123",
         *     "replyCount": 5,
         *     "publishedTime": "..."
         *   }
         * }
         */
        private Comment parseCommentViewModel(JSONObject cvm) {
            try {
                // commentId may be at the top level or inside "properties"
                String id = cvm.optString("commentId", null);
                JSONObject props = cvm.optJSONObject("properties");
                if ((id == null || id.isEmpty()) && props != null) {
                    id = props.optString("commentId", null);
                }
                if (id == null || id.isEmpty()) {
                    Log.e(TAG, "[Parse] commentViewModel missing commentId, skipping");
                    return null;
                }

                // Author name
                String authorName = null;
                if (props != null) authorName = props.optString("authorDisplayName", null);

                // Author channel id
                String authorChannelId = null;
                if (props != null) authorChannelId = props.optString("authorChannelId", id);
                if (authorChannelId == null || authorChannelId.isEmpty()) authorChannelId = id;

                // Avatar
                String avatarUrl = null;
                if (props != null) avatarUrl = extractBestThumbnailUrl(props.optJSONObject("authorThumbnail"));

                // Text: "content" can be a runs object or have a plain "content" string
                String content = "";
                if (props != null) {
                    JSONObject contentObj = props.optJSONObject("content");
                    if (contentObj != null) {
                        content = extractRuns(contentObj);
                        if (content.isEmpty()) content = contentObj.optString("content", "");
                    }
                }

                // Likes
                int likes = 0;
                if (props != null) {
                    String rawLikes = props.optString("likeCountNotliked", "0");
                    if (rawLikes.isEmpty()) rawLikes = "0";
                    likes = parseYouTubeCount(rawLikes);
                }

                // Reply count
                int replyCount = 0;
                if (props != null) replyCount = props.optInt("replyCount", 0);

                Log.e(TAG, "[Parse/VM] Comment id=" + id
                        + " | author='" + authorName + "'"
                        + " | likes=" + likes
                        + " | replies=" + replyCount
                        + " | text='" + (content.length() > 80 ? content.substring(0, 80) + "…" : content) + "'");

                User user = new User(authorChannelId, authorName, avatarUrl);
                return new Comment(id, content, likes, 0, replyCount,
                        null, null, null, user, null, null, null, null, null, null);
            } catch (Exception e) {
                Log.e(TAG, "[Parse] parseCommentViewModel error: " + e.getMessage());
                return null;
            }
        }

        /**
         * Parses a YouTube lockupViewModel item that wraps a comment.
         * The exact structure varies — we log it fully for the first item so the developer
         * can refine the field paths below once the actual shape is known.
         *
         * Known candidate shapes (update as confirmed by logs):
         *   lockupViewModel.contentId           → comment id
         *   lockupViewModel.metadata.lockupMetadataViewModel.title.content → comment text
         *   lockupViewModel.contentImage.collectionThumbnailViewModel
         *       .primaryThumbnail.thumbnailViewModel.image.sources[last].url → avatar
         */
        private Comment parseLockupViewModel(JSONObject lvm) {
            try {
                // Log the full structure on first call so we can refine the field paths
                String lvmJson = lvm.toString();
                Log.e(TAG, "[Parse/LVM] lockupViewModel json(800): "
                        + lvmJson.substring(0, Math.min(800, lvmJson.length())));

                // Content ID (comment id)
                String id = lvm.optString("contentId", null);
                if (id == null || id.isEmpty()) {
                    Log.e(TAG, "[Parse/LVM] lockupViewModel missing contentId, skipping");
                    return null;
                }

                // Comment text — title inside nested lockupMetadataViewModel
                String content = "";
                JSONObject metadata = lvm.optJSONObject("metadata");
                if (metadata != null) {
                    JSONObject metaVM = metadata.optJSONObject("lockupMetadataViewModel");
                    if (metaVM != null) {
                        JSONObject titleObj = metaVM.optJSONObject("title");
                        if (titleObj != null) {
                            content = titleObj.optString("content", "");
                            if (content.isEmpty()) content = extractRuns(titleObj);
                        }
                    }
                }

                // Avatar URL — inside contentImage.collectionThumbnailViewModel
                String avatarUrl = null;
                JSONObject contentImage = lvm.optJSONObject("contentImage");
                if (contentImage != null) {
                    JSONObject collThumb = contentImage.optJSONObject("collectionThumbnailViewModel");
                    if (collThumb != null) {
                        JSONObject primary = collThumb.optJSONObject("primaryThumbnail");
                        if (primary != null) {
                            JSONObject thumbVM = primary.optJSONObject("thumbnailViewModel");
                            if (thumbVM != null) {
                                JSONObject image = thumbVM.optJSONObject("image");
                                if (image != null) {
                                    JSONArray sources = image.optJSONArray("sources");
                                    if (sources != null && sources.length() > 0) {
                                        String url = sources.optJSONObject(sources.length() - 1)
                                                .optString("url", null);
                                        if (url != null) {
                                            avatarUrl = url.startsWith("//") ? "https:" + url : url;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Log.e(TAG, "[Parse/LVM] id=" + id
                        + " | text='" + (content.length() > 80 ? content.substring(0, 80) + "…" : content) + "'"
                        + " | avatar=" + (avatarUrl != null ? "present" : "null"));

                User user = new User(id, null, avatarUrl);
                return new Comment(id, content, 0, 0, 0,
                        null, null, null, user, null, null, null, null, null, null);
            } catch (Exception e) {
                Log.e(TAG, "[Parse] parseLockupViewModel error: " + e.getMessage());
                return null;
            }
        }

        private Comment parseCommentRenderer(JSONObject cr, String parentCommentId) {
            String id = cr.optString("commentId", null);
            if (id == null || id.isEmpty()) {
                Log.e(TAG, "[Parse] commentRenderer missing commentId, skipping");
                return null;
            }

            // Text: join all runs (handles formatted text, links, emoji text)
            String content = extractRuns(cr.optJSONObject("contentText"));

            int likes = 0;
            JSONObject voteCount = cr.optJSONObject("voteCount");
            if (voteCount != null) {
                String rawLikes = extractRuns(voteCount); // reads from runs[].text
                if (rawLikes.isEmpty()) rawLikes = voteCount.optString("simpleText", "0"); // WEB client fallback
                likes = parseYouTubeCount(rawLikes);
                Log.e(TAG, "[Parse] id=" + id + " rawLikes='" + rawLikes + "' parsed=" + likes);
            }

            int replyCount = cr.optInt("replyCount", 0);

            String authorName = extractRuns(cr.optJSONObject("authorText"));
            if (authorName.isEmpty()) {
                JSONObject authorEndpoint = cr.optJSONObject("authorEndpoint");
                if (authorEndpoint != null) {
                    JSONObject browseEndpoint = authorEndpoint.optJSONObject("browseEndpoint");
                    if (browseEndpoint != null) {
                        String canonicalUrl = browseEndpoint.optString("canonicalBaseUrl", "");
                        // canonicalBaseUrl is "/@mehulmpt" — strip leading slash
                        if (!canonicalUrl.isEmpty()) {
                            authorName = canonicalUrl.startsWith("/") ? canonicalUrl.substring(1) : canonicalUrl;
                        }
                    }
                }
            }

            // FIX 2: channel ID is nested under authorEndpoint → browseEndpoint → browseId
            String authorChannelId = cr.optString("authorExternalChannelId", null);
            if (authorChannelId == null || authorChannelId.isEmpty()) {
                JSONObject authorEndpoint = cr.optJSONObject("authorEndpoint");
                if (authorEndpoint != null) {
                    JSONObject browseEndpoint = authorEndpoint.optJSONObject("browseEndpoint");
                    if (browseEndpoint != null) {
                        authorChannelId = browseEndpoint.optString("browseId", id);
                    }
                }
            }

            if (authorChannelId == null || authorChannelId.isEmpty()) authorChannelId = id;

            // Avatar: last thumbnail = highest resolution; fix scheme-relative URLs
            String avatarUrl = extractBestThumbnailUrl(cr.optJSONObject("authorThumbnail"));

            Log.e(TAG, "[Parse] Comment id=" + id
                    + " | author='" + authorName + "'"
                    + " | likes=" + likes
                    + " | replies=" + replyCount
                    + " | avatar=" + (avatarUrl != null ? "present" : "null")
                    + " | text='" + (content.length() > 80 ? content.substring(0, 80) + "…" : content) + "'");

            User user = new User(authorChannelId, authorName, avatarUrl);

            return new Comment(
                    id,
                    content,
                    likes,
                    0,               // downvoteCount — YouTube does not expose this
                    replyCount,      // commentCount used for reply count
                    null,            // pageParent
                    null,            // postParent
                    parentCommentId, // commentParent — null for top-level
                    user,
                    null,            // didVote — not applicable for YouTube
                    null,            // mediaImageUrl
                    null,            // mediaVideoUrl
                    null,            // postContent
                    null,            // postUsername
                    null             // postAvatarUrl
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
                Log.e(TAG, "[HTTP] POST " + url);
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
                Log.e(TAG, "[HTTP] Response code: " + code + " for " + endpoint);
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "[HTTP] Non-200 response from InnerTube: " + code);
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
                Log.e(TAG, "[HTTP] postToInnertube failed (" + endpoint + "): " + e.getMessage());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
