   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import androidx.annotation.Nullable;

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
import java.util.Locale;

public class BrowserExpressGetCommentsUtil {
    private static final String TAG = "Get_Comments_Browser_Express";
    private static final String GET_COMMENTS_URL = "https://api.browser.express/v1/comment";

    public interface GetCommentsCallback {
        void getCommentsSuccessful(List<Comment> comments, Comment parentComment, Comment grandParentComment);
        void getCommentsFailed(String error);

        /**
         * Just before {@link #getCommentsSuccessful}: the post's or page's totals as the server
         * gave them, -1 or null for what it left out. {@code commentCount} counts top-level
         * comments, {@code emoteCount} every emoji on them, and {@code hashtag} is the post's
         * hashtag (postHashtag), without the "#".
         */
        default void getCommentsTotals(int commentCount, int emoteCount, @Nullable String hashtag) {}
    }

    /**
     * The post hashtag a server object carries under {@code key} ("hashtag" on a feed post,
     * "postHashtag" on its comments), without any "#"; null when it is missing, null or empty.
     */
    public static @Nullable String hashtagOf(@Nullable JSONObject json, String key) {
        if (json == null || json.isNull(key)) return null;
        String hashtag = json.optString(key, "").trim();
        while (hashtag.startsWith("#")) hashtag = hashtag.substring(1);
        return hashtag.isEmpty() || hashtag.contains(" ") ? null : hashtag;
    }

    public static class GetCommentsWorkerTask extends AsyncTask<Void> {
        private GetCommentsCallback mCallback;
        private Boolean getCommentsStatus;
        private String mErrorMessage;
        private String mUrl;
        private String mCommentId;
        private String mPostId;
        private int mPage;
        private int mPerPage;
        private List<Comment> mComments;
        private String mAccessToken;
        private Comment mParentComment;
        private Comment mGrandParentComment;
        private int mTotalComments = -1;
        private int mTotalEmotes = -1;
        private @Nullable String mHashtag;

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
            mGrandParentComment = null;
        }

        public void setComments(List<Comment> comments){
            mComments = comments;
        }

        public void setParentComment(Comment comment){
            mParentComment = comment;
        }

        public void setGrandParentComment(Comment comment){
            mGrandParentComment = comment;
        }

        public void setGetCommentsSuccessStatus(Boolean status){
            getCommentsStatus = status;
        }

        void setTotals(int commentCount, int emoteCount, @Nullable String hashtag) {
            mTotalComments = commentCount;
            mTotalEmotes = emoteCount;
            mHashtag = hashtag;
        }

        public void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetCommentsRequest(this, mUrl, mCommentId, mPostId, mPage, mPerPage, mAccessToken);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getCommentsStatus){
                mCallback.getCommentsTotals(mTotalComments, mTotalEmotes, mHashtag);
                mCallback.getCommentsSuccessful(mComments, mParentComment, mGrandParentComment);
            }else{
                mCallback.getCommentsFailed(mErrorMessage);
            }
        }
    }

    /**
     * Parses a comment that may originate from a YouTube-sourced row (user can be JSON null,
     * youtubeId/youtubeAuthorName/youtubeAvatarUrl may be present) or a normal native row.
     * Tolerates JSON null in any optional field. Returns null on hard parse errors.
     */
    private static Comment parseCommentSafe(JSONObject obj) {
        if (obj == null) return null;
        try {
            String id = obj.getString("_id");
            String content = obj.optString("content", "");
            int upvotes = obj.optInt("upvoteCount", 0);
            int downvotes = obj.optInt("downvoteCount", 0);
            int commentCount = obj.optInt("commentCount", 0);
            int trendingScore = obj.optInt("trendingScore", 0);
            String pageParent = obj.isNull("pageParent") ? null : obj.optString("pageParent", null);
            String postParent = obj.isNull("postParent") ? null : obj.optString("postParent", null);
            String commentParent = obj.isNull("commentParent") ? null : obj.optString("commentParent", null);
            String mediaImageUrl = obj.isNull("mediaImageUrl") ? null : obj.optString("mediaImageUrl", null);
            String mediaGifUrl = obj.isNull("mediaGifUrl") ? null : obj.optString("mediaGifUrl", null);
            String mediaVideoUrl = obj.isNull("mediaVideoUrl") ? null : obj.optString("mediaVideoUrl", null);
            String mediaAudioUrl = obj.isNull("mediaAudioUrl") ? null : obj.optString("mediaAudioUrl", null);
            int mediaDurationMs = obj.optInt("mediaDurationMs", 0);
            String youtubeId = obj.isNull("youtubeId") ? null : obj.optString("youtubeId", null);
            String youtubeAuthorName = obj.isNull("youtubeAuthorName") ? null : obj.optString("youtubeAuthorName", null);
            String youtubeAvatarUrl = obj.isNull("youtubeAvatarUrl") ? null : obj.optString("youtubeAvatarUrl", null);

            Vote vote = null;
            JSONObject didVoteObj = obj.optJSONObject("didVote");
            if (didVoteObj != null) {
                vote = new Vote(didVoteObj.optString("_id", null), didVoteObj.optString("type", null));
            }

            User user = BrowserExpressGetYouTubeDbCommentsUtil.parseUser(obj.opt("user"));

            Comment c = new Comment(id, content, upvotes, downvotes, commentCount,
                    pageParent, postParent, commentParent, user, vote,
                    mediaImageUrl, mediaVideoUrl, null, null, null);
            c.setTrendingScore(trendingScore);
            c.setMediaGifUrl(mediaGifUrl);
            c.setMediaAudioUrl(mediaAudioUrl);
            c.setMediaDurationMs(mediaDurationMs);
            // The API does not send these today; when it starts to, the client-side probe in
            // CommentVideoDimensions becomes a fallback rather than the source of truth.
            c.setMediaWidth(obj.optInt("mediaWidth", 0));
            c.setMediaHeight(obj.optInt("mediaHeight", 0));
            c.readExtras(obj);
            if (youtubeId != null) {
                c.setYoutubeId(youtubeId);
                c.setYoutubeAuthorName(youtubeAuthorName);
                c.setYoutubeAvatarUrl(youtubeAvatarUrl);
            }
            return c;
        } catch (Exception e) {
            Log.e(TAG, "parseCommentSafe error: " + e.getMessage());
            return null;
        }
    }

    private static void sendGetCommentsRequest(GetCommentsWorkerTask task, String pageUrl, String commentId, String postId, int page, int perPage, String accessToken) {
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
            String countryCode = Locale.getDefault().getCountry();
            searchQuery = searchQuery + "&page=" + Integer.toString(page) + "&per_page=" + Integer.toString(perPage) + "&country=" + countryCode;

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
                    task.setGetCommentsSuccessStatus(true);
                    JSONArray commentsArray = responseObject.getJSONArray("comments");
                    if (!responseObject.isNull("grandParentComment")) {
                        Comment gp = parseCommentSafe(responseObject.getJSONObject("grandParentComment"));
                        if (gp != null) task.setGrandParentComment(gp);
                    }

                    if (!responseObject.isNull("parentComment")) {
                        Comment p = parseCommentSafe(responseObject.getJSONObject("parentComment"));
                        if (p != null) task.setParentComment(p);
                    }

                    List<Comment> comments = new ArrayList<Comment>();
                    for (int i = 0; i < commentsArray.length(); i++) {
                        Comment c = parseCommentSafe(commentsArray.getJSONObject(i));
                        if (c != null) comments.add(c);
                    }

                    task.setComments(comments);

                    // A post's totals come as postReactions (newer servers), a page's as
                    // pageReactions; both are {reactions, reactionCount}.
                    JSONObject reactions = responseObject.optJSONObject("postReactions");
                    if (reactions == null) reactions = responseObject.optJSONObject("pageReactions");
                    task.setTotals(responseObject.optInt("commentCount", -1),
                            reactions != null ? reactions.optInt("reactionCount", -1) : -1,
                            hashtagOf(responseObject, "postHashtag"));
                }else{
                    task.setGetCommentsSuccessStatus(false);
                    task.setErrorMessage(responseObject.getString("error"));
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
