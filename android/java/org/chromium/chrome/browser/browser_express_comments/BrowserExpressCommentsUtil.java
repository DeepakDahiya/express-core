   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

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

import org.chromium.chrome.browser.app.BraveActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BrowserExpressCommentsUtil {
    private static final String TAG = "Claim_Username_Browser_Express";
    private static final String CLAIM_USERNAME_URL = "https://api.browser.express/v1/auth/generate-username";

    private BrowserExpressGenerateUsernameBottomSheetFragment mBottomSheetDialog;

    public interface ClaimUsernameCallback {
        void claimUsernameSuccessful(String accessToken, String refreshToken);
        void claimUsernameFailed(String error);
    }

    public static class ClaimUsernameWorkerTask extends AsyncTask<Void> {
        private ClaimUsernameCallback mCallback;
        private static Boolean claimUsernameStatus;
        private static String mErrorMessage;
        private static String mAccessToken;
        private static String mRefreshToken;

        public ClaimUsernameWorkerTask(ClaimUsernameCallback callback) {
            mCallback = callback;
            claimUsernameStatus = false;
            mErrorMessage = "";
            mAccessToken = null;
            mRefreshToken = null;
        }

        public static void setAuthTokens(String accessToken, String refreshToken){
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
        }

        public static void setClaimUsernameSuccessStatus(Boolean status){
            claimUsernameStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendClaimUsernameRequest(mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(claimUsernameStatus){
                mCallback.claimUsernameSuccessful(mAccessToken, mRefreshToken);
            }else{
                mCallback.claimUsernameFailed(mErrorMessage);
            }
        }
    }


    public void showGenerateUsernameBottomSheet() {
        try {
            if(mBottomSheetDialog == null){
                BrowserExpressGenerateUsernameBottomSheetFragment bottomSheetDialog =
                        BrowserExpressGenerateUsernameBottomSheetFragment.newInstance(true);
                
                bottomSheetDialog.show(BraveActivity.getBraveActivity().getSupportFragmentManager(), "BrowserExpressGenerateUsernameBottomSheetFragment");
                mBottomSheetDialog = bottomSheetDialog;
            }else{
                mBottomSheetDialog.show(BraveActivity.getBraveActivity().getSupportFragmentManager(), "BrowserExpressGenerateUsernameBottomSheetFragment");
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }

    public void dismissGenerateUsernameBottomSheet() {
        if (mBottomSheetDialog != null) {
            mBottomSheetDialog.dismiss();
        }
    }

    private static void sendClaimUsernameRequest(ClaimUsernameCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(CLAIM_USERNAME_URL);
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
                if(responseObject.getBoolean("success")){
                    ClaimUsernameWorkerTask.setClaimUsernameSuccessStatus(true);
                    String accessToken = responseObject.getString("accessToken");
                    String refreshToken = responseObject.getString("refreshToken");
                    ClaimUsernameWorkerTask.setAuthTokens(accessToken, refreshToken);
                }else{
                    ClaimUsernameWorkerTask.setClaimUsernameSuccessStatus(false);
                    ClaimUsernameWorkerTask.setErrorMessage(responseObject.getString("error"));
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
