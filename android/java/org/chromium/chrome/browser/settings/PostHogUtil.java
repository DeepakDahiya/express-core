
package org.chromium.chrome.browser.settings;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.Integer;

public class PostHogUtil {
    private static final String TAG = "Post_Hog_Util";
    private static final String GET_PROFILE_URL = "https://posthog.dd-fb2.workers.dev/i/v0/e/";
    private static final String POST_HOG_API_KEY = "phc_Nn8HVrhAeFqo35ymuvUKOKe59RhXnShdushDuyDn3vL";

    public static class PostHogWorkerTask extends AsyncTask<Void> {
        private final String mEvent;
        private final String mUserId;
        private final JSONObject mProperties;

        public PostHogWorkerTask(String event, String userId, JSONObject properties) {
            mEvent = event;
            mUserId = userId;
            mProperties = properties;
        }

        @Override
        protected Void doInBackground() {
            sendPostHogRequest(mEvent, mUserId, mProperties);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
        }
    }

    private static void sendPostHogRequest(String event, String userId, JSONObject properties) {
        Log.e("Express Browser", "GETTING USER PROFILE 2");
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GET_PROFILE_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + POST_HOG_API_KEY);
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("api_key", POST_HOG_API_KEY);
            jsonParam.put("event", event);
            jsonParam.put("distinct_id", userId);
            // JSONObject properties = new JSONObject();
            // properties.put("account_type", "pro");
            jsonParam.put("properties", properties);

            Log.e(TAG, "Sending PostHog request: " + jsonParam.toString());

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            if (HttpResult == HttpURLConnection.HTTP_OK) {
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
