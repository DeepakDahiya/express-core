package org.chromium.chrome.browser.ntp;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TutorialVideoUtil {
    private static final String TAG = "TutorialVideoUtil";
    private static final String API_URL = "https://api.browser.express/v1/public/tutorial-video";

    public interface TutorialVideoCallback {
        void onResult(boolean enabled, String videoUrl, String imageUrl);
    }

    public static void fetch(TutorialVideoCallback callback) {
        new FetchTask(callback).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class FetchTask extends AsyncTask<Void> {
        private final TutorialVideoCallback mCallback;
        private boolean mEnabled;
        private String mVideoUrl;
        private String mImageUrl;

        FetchTask(TutorialVideoCallback callback) {
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    mEnabled = json.optBoolean("enabled", false);
                    mVideoUrl = json.optString("videoUrl", null);
                    mImageUrl = json.optString("imageUrl", null);
                } else {
                    Log.e(TAG, "HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching tutorial video config", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (mCallback != null) {
                mCallback.onResult(mEnabled, mVideoUrl, mImageUrl);
            }
        }
    }
}
