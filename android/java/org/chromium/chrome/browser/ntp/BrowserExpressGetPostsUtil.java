package org.chromium.chrome.browser.ntp;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import android.net.Uri;
import java.util.List;
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

import org.chromium.chrome.browser.browser_express_comments.Vote;

public class BrowserExpressGetPostsUtil {
    private static final String TAG = "Get_Posts_Browser_Express";
    private static final String GET_POSTS_URL = "https://api.browser.express/v1/post/feed";
    private static final String TWITTER_TYPE = "Twitter";

    public interface GetPostsCallback {
        void getPostsSuccessful(List<Post> posts);
        void getPostsFailed(String error);
    }

    public static class GetPostsWorkerTask extends AsyncTask<Void> {
        private GetPostsCallback mCallback;
        private static Boolean getPostsStatus;
        private static String mErrorMessage;
        private static int mPage;
        private static int mPerPage;
        private static List<Post> mPosts;
        private static String mAccessToken;

        public GetPostsWorkerTask(int page, int perPage, String accessToken, GetPostsCallback callback) {
            mCallback = callback;
            getPostsStatus = false;
            mErrorMessage = "";
            mPosts = new ArrayList<Post>();
            mPage = page;
            mPerPage = perPage;
            mAccessToken = accessToken;
        }

        public static void setPosts(List<Post> posts){
            mPosts = posts;
        }

        public static void setGetPostsSuccessStatus(Boolean status){
            getPostsStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetPostsRequest(mPage, mPerPage, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getPostsStatus){
                mCallback.getPostsSuccessful(mPosts);
            }else{
                mCallback.getPostsFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetPostsRequest(int page, int perPage, String accessToken, GetPostsCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            String searchQuery = "?page=" + Integer.toString(page) + "&per_page=" + Integer.toString(perPage);
            URL url = new URL(GET_POSTS_URL + searchQuery);
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
                    GetPostsWorkerTask.setGetPostsSuccessStatus(true);
                    JSONArray postsArray = responseObject.getJSONArray("posts");
                    List<Post> posts = new ArrayList<Post>();
                    for (int i = 0; i < postsArray.length(); i++) {
                        JSONObject post = postsArray.getJSONObject(i);
                        JSONObject didVote = post.optJSONObject("didVote");
                        JSONObject publisher = post.optJSONObject("publisher");
                        JSONObject tsp = post.optJSONObject("tweetSubPost");
                        Vote v = null;
                        if(didVote != null){
                            v = new Vote(didVote.getString("_id"), didVote.getString("type"));
                        }

                        String postType = post.getString("type");
                        TweetSubPost tweetSubPost = null;
                        if (postType.equals(TWITTER_TYPE)) {
                            JSONObject author = tsp.optJSONObject("author");
                            JSONObject media = tsp.optJSONObject("media");
                            String videoUrl = null; // Initialize videoUrl as null
                            if (media != null) {
                                videoUrl = media.getString("videoUrl"); // Assign the actual value of videoUrl
                            }
                            tweetSubPost = new TweetSubPost(
                                tsp.getString("tweetId"),
                                tsp.getString("linkToTweet"),
                                tsp.getString("content"),
                                author.getString("name"),
                                author.getString("username"),
                                author.getBoolean("isVerified"),
                                author.getString("profilePicture"),
                                media != null ? media.getString("type") : null,
                                media != null ? media.getString("imageUrl") : null,
                                videoUrl, // Use the actual value of videoUrl
                                media != null ? media.getInt("height") : 0,
                                media != null ? media.getInt("width") : 0
                            );
                        } 
                        }

                        posts.add(new Post(
                            post.getString("_id"), 
                            post.getString("content"),
                            post.getString("type"),
                            post.getString("title"),
                            post.getString("imageUrl"),
                            post.getString("url"),
                            post.getInt("upvoteCount"),
                            post.getInt("downvoteCount"),
                            post.getInt("commentCount"),
                            publisher.getString("name"),
                            publisher.getString("imageUrl"),
                            post.getBoolean("redirect"),
                            post.getBoolean("showFull"),
                            v,
                            tweetSubPost));
                    }

                    GetPostsWorkerTask.setPosts(posts);
                }else{
                    GetPostsWorkerTask.setGetPostsSuccessStatus(false);
                    GetPostsWorkerTask.setErrorMessage(responseObject.getString("error"));
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
