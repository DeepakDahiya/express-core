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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import android.webkit.WebView;

import org.chromium.chrome.browser.browser_express_comments.Vote;

public class TwitterGetOEmbedDataUtil {
    private static final String TAG = "Twitter_oEmbed";
    private static final String GET_OEMBED_URL = "https://publish.twitter.com/oembed";

    public interface GetTwitterOEmbedDataCallback {
        void getTwitterOEmbedDataSuccessful(WebView wv, String html);
        void getTwitterOEmbedDataFailed(String error);
    }

    public static class GetTwitterOEmbedDataWorkerTask extends AsyncTask<Void> {
        private GetTwitterOEmbedDataCallback mCallback;
        private static Boolean getTwitterOEmbedDataStatus;
        private static String mErrorMessage;
        private static String mTweetUrl;
        private static String mTweetHtml;
        private static WebView mPostWebView;

        public GetTwitterOEmbedDataWorkerTask(WebView postWebView, String tweetUrl,  GetTwitterOEmbedDataCallback callback) {
            mCallback = callback;
            getTwitterOEmbedDataStatus = false;
            mErrorMessage = "";
            mTweetUrl = tweetUrl;
            mTweetHtml = "";
            mPostWebView = postWebView;
        }

        public static void setHtml(String html){
            mTweetHtml = html;
        }

        public static void setGetTwitterOEmbedDataSuccessStatus(Boolean status){
            getTwitterOEmbedDataStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetTwitterOEmbedDataRequest(mTweetUrl, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getTwitterOEmbedDataStatus){
                mCallback.getTwitterOEmbedDataSuccessful(mPostWebView, mTweetHtml);
            }else{
                mCallback.getTwitterOEmbedDataFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetTwitterOEmbedDataRequest(String tweetUrl, GetTwitterOEmbedDataCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            String searchQuery = "?url=" + URLEncoder.encode(tweetUrl, "UTF-8");
            URL url = new URL(GET_OEMBED_URL + searchQuery);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setRequestMethod("GET");
            urlConnection.setUseCaches(false);
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
                String htmlString = responseObject.getString("html");
                if(htmlString.length() > 0){
                    GetTwitterOEmbedDataWorkerTask.setGetTwitterOEmbedDataSuccessStatus(true);
                    try {
                        GetTwitterOEmbedDataWorkerTask.setHtml(URLDecoder.decode(responseObject.getString("html"), "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }else{
                    GetTwitterOEmbedDataWorkerTask.setGetTwitterOEmbedDataSuccessStatus(false);
                    GetTwitterOEmbedDataWorkerTask.setErrorMessage(responseObject.getString("error"));
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
