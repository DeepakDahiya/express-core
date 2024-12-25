
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

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

public class BrowserExpressGetProfilePreferencesUtil {
    private static final String TAG = "Get_Profile_Browser_Express";
    private static final String GET_PROFILE_URL = "https://api.browser.express/v1/user/me";

    public interface GetProfileCallback {
        void getProfileSuccessful(String avatar, String xp, String lg, String lr);
        void getProfileFailed(String error);
    }

    public static class GetProfileWorkerTask extends AsyncTask<Void> {
        private static String mAvatar;
        private static String mXp;
        private static String mLikesGiven;
        private static String mLikesReceived;
        private GetProfileCallback mCallback;
        private static Boolean getProfileStatus;
        private static String mErrorMessage;
        private static String mAccessToken;

        public GetProfileWorkerTask(String accessToken, GetProfileCallback callback) {
            mCallback = callback;
            getProfileStatus = false;
            mErrorMessage = "";
            mAccessToken = accessToken;
            mXp = "0";
            mLikesGiven = "0";
            mLikesReceived = "0";
            mAvatar = "";
        }

        public static void setData(String avatar, String xp, String lg, String lr){
            mAvatar = avatar;
            mXp = xp;
            mLikesGiven = lg;
            mLikesReceived = lr;
        }

        public static void setGetProfileSuccessStatus(Boolean status){
            getProfileStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendGetProfileRequest(mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(getProfileStatus){
                mCallback.getProfileSuccessful(mAvatar, mXp, mLikesGiven, mLikesReceived);
            }else{
                mCallback.getProfileFailed(mErrorMessage);
            }
        }
    }

    private static void sendGetProfileRequest(String accessToken, GetProfileCallback callback) {
        Log.e("Express Browser", "GETTING USER PROFILE 2");
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GET_PROFILE_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);

            urlConnection.setRequestMethod("GET");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "application/json");

            if(accessToken != null && !accessToken.equals("")){
                urlConnection.setRequestProperty ("Authorization", accessToken);
            }

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
                Log.e("GET PROFILE RESPONSE FROM SERVER", responseObject.toString());
                if(responseObject.getBoolean("success")){
                    GetProfileWorkerTask.setGetProfileSuccessStatus(true);
                    // String avatar = "";
                    // Log.e("GET PROFILE EXTRACTION", avatar);
                    // if(responseObject.has("avatar") && !responseObject.isNull("avatar") && responseObject.getString("avatar").length() > 0)
                    // {
                    //     Log.e("GET PROFILE EXTRACTION", "SETTING AVATAR");
                    //     Log.e("GET PROFILE EXTRACTION", responseObject.getString("avatar"));
                    //     avatar = responseObject.getString("avatar");
                    // }
                    // Log.e("GET PROFILE EXTRACTION", "AVATAR SET");
                    // String xp = responseObject.getString("xp");
                    // String likesReceived = responseObject.getString("likesReceived");
                    // String likesGiven = responseObject.getString("likesGiven");
                    // Log.e("GET PROFILE EXTRACTION", "SETTING FINAL DATA");
                    // GetProfileWorkerTask.setData(avatar, xp, likesGiven, likesReceived);
                    GetProfileWorkerTask.setData(
                        responseObject.optString("avatar", "https://api.dicebear.com/9.x/fun-emoji/png?seed=123456&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile"),
                        responseObject.optString("xp", "0"),
                        responseObject.optString("likesGiven", "0"),
                        responseObject.optString("likesReceived", "0")
                    );
                }else{
                    GetProfileWorkerTask.setGetProfileSuccessStatus(false);
                    GetProfileWorkerTask.setErrorMessage(responseObject.getString("error"));
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
