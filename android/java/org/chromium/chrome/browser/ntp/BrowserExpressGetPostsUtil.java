package org.chromium.chrome.browser.ntp;

import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.chromium.chrome.browser.browser_express_comments.BrowserExpressGetCommentsUtil;
import org.chromium.chrome.browser.browser_express_comments.ReactionMix;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.json.JSONArray;
import android.net.Uri;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

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

import org.chromium.chrome.browser.browser_express_comments.Vote;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import org.chromium.chrome.browser.browser_express_comments.User;

public class BrowserExpressGetPostsUtil {
    private static final String TAG = "Get_Posts_Browser_Express";
    private static final String GET_POSTS_URL = "https://api.browser.express/v1/post/feed";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final String TWITTER_TYPE = "Twitter";
    private static final String INSTAGRAM_TYPE = "Instagram";

    public interface GetPostsCallback {
        /**
         * One page of the feed. {@code nextCursor} asks for the page after it; null after the last
         * page, and from servers that predate paging.
         */
        void getPostsSuccessful(List<Post> posts, @Nullable String nextCursor);

        void getPostsFailed(String error);
    }

    public static class GetPostsWorkerTask extends AsyncTask<Void> {
        private final GetPostsCallback mCallback;
        private Boolean getPostsStatus;
        private String mErrorMessage;
        @Nullable private final String mCursor;
        private int mPerPage;
        private List<Post> mPosts;
        @Nullable private String mNextCursor;
        private String mAccessToken;

        /**
         * Loads the feed page that {@code cursor} names: the previous page's next cursor, or null
         * for the first page.
         */
        public GetPostsWorkerTask(
                @Nullable String cursor, int perPage, String accessToken, GetPostsCallback callback) {
            mCallback = callback;
            getPostsStatus = false;
            mErrorMessage = "";
            mPosts = new ArrayList<Post>();
            mCursor = cursor;
            mPerPage = perPage;
            mAccessToken = accessToken;
        }

        public void setPosts(List<Post> posts, @Nullable String nextCursor) {
            mPosts = posts;
            mNextCursor = nextCursor;
        }

        public void setGetPostsSuccessStatus(Boolean status){
            getPostsStatus = status;
        }

        public void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetPostsRequest(this, mCursor, mPerPage, mAccessToken);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getPostsStatus){
                mCallback.getPostsSuccessful(mPosts, mNextCursor);
            }else{
                mCallback.getPostsFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetPostsRequest(
            GetPostsWorkerTask task, @Nullable String cursor, int perPage, String accessToken) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            Uri.Builder query =
                    Uri.parse(GET_POSTS_URL)
                            .buildUpon()
                            .appendQueryParameter("per_page", Integer.toString(perPage))
                            .appendQueryParameter("country", Locale.getDefault().getCountry());
            if (cursor != null) query.appendQueryParameter("cursor", cursor);
            URL url = new URL(query.build().toString());
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
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
                Log.e("BE_GET_POST", "1");
                if(responseObject.getBoolean("success")){
                    task.setGetPostsSuccessStatus(true);
                    JSONArray postsArray = responseObject.getJSONArray("posts");
                    List<Post> posts = new ArrayList<Post>();
                    Log.e("BE_GET_POST", "2");
                    for (int i = 0; i < postsArray.length(); i++) {
                        JSONObject post = postsArray.optJSONObject(i);
                        if (post == null) continue;
                        try {
                            posts.add(parsePost(post));
                        } catch (JSONException e) {
                            // One malformed post must not cost the reader the whole feed.
                            Log.e(TAG, "Skipping post " + post.optString("_id") + ": " + e.getMessage());
                        }
                    }
                    Log.e("BE_GET_POST", "8"); 
                    String nextCursor =
                            responseObject.isNull("nextCursor")
                                    ? null
                                    : responseObject.optString("nextCursor", null);
                    task.setPosts(
                            posts, nextCursor == null || nextCursor.isEmpty() ? null : nextCursor);
                }else{
                    task.setGetPostsSuccessStatus(false);
                    task.setErrorMessage(responseObject.getString("error"));
                }
                br.close();
            } else {
                String error = urlConnection.getResponseMessage();
                if (error == null) error = "HTTP " + HttpResult;
                task.setErrorMessage(error);
                Log.e(TAG, error);
            }
        } catch (MalformedURLException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            task.setErrorMessage(getErrorMessage(e));
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }

    private static String getErrorMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    /**
     * One feed post. Only its id is required: feed sources differ in what they fill in (the
     * India feed sends media without dimensions, for one), so every other field falls back.
     */
    private static Post parsePost(JSONObject post) throws JSONException {
        JSONObject didVote = post.optJSONObject("didVote");
        JSONObject publisher = post.optJSONObject("publisher");
        JSONObject tsp = post.optJSONObject("subPost");
        Vote vote =
                didVote != null
                        ? new Vote(didVote.optString("_id"), didVote.optString("type"))
                        : null;

        String postType = post.optString("type", "");
        SubPost subPost = null;
        if (tsp != null && (postType.equals(TWITTER_TYPE) || postType.equals(INSTAGRAM_TYPE))) {
            JSONObject author = tsp.optJSONObject("author");
            if (author == null) author = new JSONObject();
            JSONObject media = tsp.optJSONObject("media");
            subPost =
                    new SubPost(
                            tsp.optString("id"),
                            tsp.optString("url"),
                            tsp.optString("content"),
                            author.optString("name"),
                            author.optString("username"),
                            author.optBoolean("isVerified", false),
                            author.optString("profilePicture", null),
                            media != null ? media.optString("type", null) : null,
                            media != null ? media.optString("imageUrl", null) : null,
                            media != null ? media.optString("videoUrl", null) : null,
                            // 0 when unknown: the card then takes the shape of the loaded media.
                            media != null ? media.optInt("height", 0) : 0,
                            media != null ? media.optInt("width", 0) : 0);
        }

        Post result =
                new Post(
                        post.getString("_id"),
                        post.optString("content"),
                        postType,
                        post.optString("title"),
                        post.optString("imageUrl", null),
                        post.optString("url", null),
                        post.optInt("upvoteCount", 0),
                        post.optInt("downvoteCount", 0),
                        post.optInt("commentCount", 0),
                        publisher != null ? publisher.optString("name") : "",
                        publisher != null ? publisher.optString("imageUrl", null) : null,
                        post.optBoolean("redirect", false),
                        post.optBoolean("showFull", false),
                        vote,
                        subPost);
        result.setComments(parseTopComments(post.optJSONArray("comments"), subPost));
        readCardFields(post, tsp, result);
        return result;
    }

    /** The post's top comments; a malformed one is left out rather than failing the post. */
    private static List<Comment> parseTopComments(JSONArray comments, SubPost subPost) {
        List<Comment> result = new ArrayList<>();
        String postContent = subPost != null ? subPost.getContent() : null;
        String postUsername = subPost != null ? subPost.getAuthorUsername() : null;
        String postAvatarUrl = subPost != null ? subPost.getAuthorProfilePicture() : null;
        for (int j = 0; comments != null && j < comments.length(); j++) {
            JSONObject comment = comments.optJSONObject(j);
            JSONObject user = comment != null ? comment.optJSONObject("user") : null;
            if (comment == null || user == null) continue;
            try {
                Comment parsed =
                        new Comment(
                                comment.getString("_id"),
                                comment.optString("content"),
                                comment.optInt("upvoteCount", 0),
                                comment.optInt("downvoteCount", 0),
                                comment.optInt("commentCount", 0),
                                comment.has("pageParent") ? comment.optString("pageParent") : null,
                                comment.has("postParent") ? comment.optString("postParent") : null,
                                comment.has("commentParent")
                                        ? comment.optString("commentParent")
                                        : null,
                                new User(
                                        user.optString("_id"),
                                        user.optString("username"),
                                        user.optString("avatar", null)),
                                null,
                                null,
                                null,
                                postContent,
                                postUsername,
                                postAvatarUrl);
                parsed.setTrendingScore(comment.optInt("trendingScore", 0));
                result.add(parsed);
            } catch (JSONException e) {
                Log.e(TAG, "Skipping top comment: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * The fields the feed card needs beyond the original post shape, all optional: the publish
     * time, the post's reactions (summed over its top-level comments), its hashtag and a voice
     * post's audio.
     */
    private static void readCardFields(JSONObject post, JSONObject subPost, Post target) {
        long createdAtMs = subPost != null ? subPost.optLong("createdAt", 0) : 0;
        if (createdAtMs <= 0) createdAtMs = parseIsoMillis(post.optString("createdAt", ""));
        target.setCreatedAtMs(createdAtMs);

        target.setReactions(
                Math.max(0, post.optInt("reactionCount", 0)),
                ReactionMix.topEmoji(post.optJSONArray("reactions")));
        target.setHashtag(BrowserExpressGetCommentsUtil.hashtagOf(post, "hashtag"));
        // Signed-in readers only, and only for posts with enough takes since their last open.
        target.setNewTakes(NewTakes.fromJson(post.optJSONObject("newTakes")));

        // No server writer sends audio yet; a voice post will carry it on its media, or on the
        // post itself for admin-written posts.
        JSONObject media = subPost != null ? subPost.optJSONObject("media") : null;
        String audioUrl = media != null ? optUrl(media, "audioUrl") : null;
        long durationMs = media != null ? media.optLong("durationMs", 0) : 0;
        if (audioUrl == null) {
            audioUrl = optUrl(post, "audioUrl");
            durationMs = post.optLong("audioDurationMs", 0);
        }
        target.setAudio(audioUrl, Math.max(0, durationMs));
    }

    /** {@code key} as a URL, or null when it is missing, empty or the string "null". */
    private static String optUrl(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** An ISO-8601 instant ("2026-09-25T10:00:00.000Z") in epoch millis, or 0. */
    private static long parseIsoMillis(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
