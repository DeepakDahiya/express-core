/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The phone sign-in calls the sign-up screen makes: send a code (/v1/auth/register), send another
 * (/v1/auth/resend-phone-otp) and check one (/v1/auth/verify-phone). Each answers on the UI thread
 * with a {@link Result} that keeps the server's error code, so the screen can pick its own words.
 */
final class BrowserExpressPhoneAuthApi {
    private static final String TAG = "BEPhoneAuthApi";
    private static final String BASE_URL = "https://api.browser.express/v1/auth/";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** What a call came back with. */
    static final class Result {
        boolean ok;
        /** The request never got an answer: no connection, or a timeout. */
        boolean network;
        int httpCode;
        /** The server's machine-readable error, "invalid_otp" for one; empty when none. */
        String error = "";
        /** The server's own sentence for the error; empty when none. */
        String message = "";
        int retryAfterSeconds = -1;
        /** Sending: "signup" for a new number, "login" for one with an account. */
        @Nullable String purpose;
        int resendAvailableInSeconds = -1;
        /** Verifying: the new session. */
        @Nullable String accessToken;
        @Nullable String refreshToken;
    }

    interface Callback {
        void onResult(Result result);
    }

    private BrowserExpressPhoneAuthApi() {}

    /** Sends the first code to {@code phone} (E.164), with the name when one was given. */
    static void sendCode(String phone, @Nullable String name, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("country", "IN");
            if (name != null && !name.isEmpty()) body.put("name", name);
        } catch (JSONException ignored) {
        }
        post("register", body, false, callback, (result, response) -> {
            result.ok = response.optBoolean("ok", false) && response.optBoolean("otpSent", false);
            result.purpose = response.optString(
                    "purpose", BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP);
            result.resendAvailableInSeconds = response.optInt("resendAvailableInSeconds", -1);
        });
    }

    /** Sends another code for a sign-up or login already under way. */
    static void resendCode(String phone, String purpose, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("purpose", purpose);
        } catch (JSONException ignored) {
        }
        post("resend-phone-otp", body, false, callback, (result, response) -> {
            result.ok = response.optBoolean("ok", false) && response.optBoolean("otpSent", false);
            result.resendAvailableInSeconds = response.optInt("resendAvailableInSeconds", -1);
        });
    }

    /**
     * Checks {@code code}. Sends the guest session this device holds, if any, so the server keeps
     * that account (its referral code, referrals and comments) for the verified number.
     */
    static void verifyCode(String phone, String code, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("otp", code);
        } catch (JSONException ignored) {
        }
        post("verify-phone", body, true, callback, (result, response) -> {
            String access = response.optString("accessToken", "");
            String refresh = response.optString("refreshToken", "");
            result.ok = !access.isEmpty() && !refresh.isEmpty();
            result.accessToken = access;
            result.refreshToken = refresh;
        });
    }

    /** Reads a successful (2xx) response into the result. */
    private interface SuccessParser {
        void parse(Result result, JSONObject response);
    }

    private static void post(String path, JSONObject body, boolean withSession, Callback callback,
            SuccessParser parser) {
        new AsyncTask<Result>() {
            @Override
            protected Result doInBackground() {
                return request(path, body, withSession, parser);
            }

            @Override
            protected void onPostExecute(Result result) {
                callback.onResult(result);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static Result request(
            String path, JSONObject body, boolean withSession, SuccessParser parser) {
        Result result = new Result();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    new URL(BASE_URL + path),
                    NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            if (withSession) attachSession(connection);

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            result.httpCode = connection.getResponseCode();
            boolean success = result.httpCode >= 200 && result.httpCode < 300;
            String text = readBody(success ? connection.getInputStream()
                                           : connection.getErrorStream());
            JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (success) {
                parser.parse(result, response);
            } else {
                result.error = response.optString("error", "");
                result.message = response.optString("message", "");
                result.retryAfterSeconds = retryAfter(response, connection);
            }
            if (!result.ok) {
                Log.w(TAG, "%s failed: HTTP %d %s", path, result.httpCode, result.error);
            }
        } catch (IOException e) {
            result.network = true;
            Log.w(TAG, "%s: no answer: %s", path, e.toString());
        } catch (JSONException e) {
            Log.w(TAG, "%s: unreadable answer: %s", path, e.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private static String readBody(@Nullable InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
        }
        return text.toString();
    }

    private static int retryAfter(JSONObject response, HttpURLConnection connection) {
        int seconds = response.optInt("retryAfterSeconds", -1);
        if (seconds > 0) return seconds;
        String header = connection.getHeaderField("Retry-After");
        if (header == null) return -1;
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void attachSession(HttpURLConnection connection) {
        SharedPreferences tokens = ContextUtils.getApplicationContext().getSharedPreferences(
                BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
        String accessToken = tokens.getString(BraveActivity.ACCESS_TOKEN_KEY, null);
        String refreshToken = tokens.getString("RefreshToken", null);
        if (accessToken != null && !accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (refreshToken != null && !refreshToken.isEmpty()) {
            // Lets the server still recognise the session if the access token expired while the
            // user was on the sign-up screen.
            connection.setRequestProperty("x-refresh", refreshToken);
        }
    }
}
