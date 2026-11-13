   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_generate_username;

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
import org.chromium.chrome.browser.util.BraveConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;

public class BrowserExpressClaimUsernameUtil {
    private static final String TAG = "Claim_Username_Browser_Express";
    public static final String ERROR_MANUAL_AUTH_REQUIRED = "manual_auth_required";
    private static final String CLAIM_USERNAME_URL = "https://api.browser.express/v1/auth/generate-username";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private BrowserExpressGenerateUsernameBottomSheetFragment mBottomSheetDialog;

    public interface ClaimUsernameCallback {
        void claimUsernameSuccessful(String accessToken, String refreshToken);
        void claimUsernameFailed(String error);
    }

    public static class ClaimUsernameWorkerTask extends AsyncTask<Void> {
        private final ClaimUsernameCallback mCallback;
        // When true, skip the India manual-auth gate and auto-login anyway. Used
        // by the register screen's "close" fallback so a user who declines to
        // register still gets the same generate-username auto-login non-India
        // users get automatically.
        private final boolean mForceAutoLogin;
        private Boolean claimUsernameStatus;
        private String mErrorMessage;
        private String mAccessToken;
        private String mRefreshToken;

        public ClaimUsernameWorkerTask(ClaimUsernameCallback callback) {
            this(callback, false);
        }

        public ClaimUsernameWorkerTask(ClaimUsernameCallback callback, boolean forceAutoLogin) {
            mCallback = callback;
            mForceAutoLogin = forceAutoLogin;
            claimUsernameStatus = false;
            mErrorMessage = "";
            mAccessToken = null;
            mRefreshToken = null;
        }

        boolean isForceAutoLogin() {
            return mForceAutoLogin;
        }

        public void setAuthTokens(String accessToken, String refreshToken){
            mAccessToken = accessToken;
            mRefreshToken = refreshToken;
        }

        public void setClaimUsernameSuccessStatus(Boolean status){
            claimUsernameStatus = status;
        }

        public void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            if (!mForceAutoLogin && shouldRequireManualAuth()) {
                setClaimUsernameSuccessStatus(false);
                setErrorMessage(ERROR_MANUAL_AUTH_REQUIRED);
                return null;
            }
            sendClaimUsernameRequest(this);
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
            if (openManualAuthIfRequired()) {
                return;
            }
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

    private static void sendClaimUsernameRequest(ClaimUsernameWorkerTask task) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        try {
            if (!task.isForceAutoLogin() && shouldRequireManualAuth()) {
                task.setClaimUsernameSuccessStatus(false);
                task.setErrorMessage(ERROR_MANUAL_AUTH_REQUIRED);
                return;
            }
            URL url = new URL(CLAIM_USERNAME_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
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
                    String accessToken1 = responseObject.optString("accessToken", "");
                    String refreshToken1 = responseObject.optString("refreshToken", "");
                    if(!accessToken1.isEmpty() && !refreshToken1.isEmpty()){
                        task.setClaimUsernameSuccessStatus(true);
                        task.setAuthTokens(accessToken1, refreshToken1);
                    } else {
                        task.setClaimUsernameSuccessStatus(false);
                        task.setErrorMessage("Missing auth tokens");
                    }
                }else{
                    task.setClaimUsernameSuccessStatus(false);
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

    public static boolean shouldRequireManualAuth() {
        String countryCode = Locale.getDefault().getCountry();
        String timeZoneId = TimeZone.getDefault().getID();
        return BraveConstants.INDIA_COUNTRY_CODE.equalsIgnoreCase(countryCode)
                || "Asia/Kolkata".equalsIgnoreCase(timeZoneId);
    }

    public static boolean openManualAuthIfRequired() {
        if (!shouldRequireManualAuth()) return false;
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (activity.getAccessToken() != null) {
                Log.e(TAG, "Manual auth navigation skipped because the user is authenticated");
                return true;
            }
            activity.openBrowserExpressSignupSettings(true);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Unable to open manual auth: " + e.getMessage());
        }
        return true;
    }
}
