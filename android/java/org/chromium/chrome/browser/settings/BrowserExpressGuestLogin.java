/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ObserverList;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_signup.UgcGuestToast;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;
import org.chromium.chrome.browser.referral.ReferralHelper;

/**
 * The guest session a user gets by closing the sign-in screen: a new account from
 * /v1/auth/generate-username, with no phone number on it.
 *
 * <p>The account is made in the background. The server can take 15 seconds over it, and waiting
 * for it on the sign-in screen made the close button look broken, so closing goes straight back to
 * the browser and the session follows. Until it arrives the guest login is pending. That is kept
 * across restarts, so a later run finishes it instead of showing the sign-in screen again to a
 * user who closed it.
 */
public final class BrowserExpressGuestLogin {
    /** Told on the UI thread once a guest session is saved. */
    public interface Observer {
        void onGuestSignedIn(String accessToken);
    }

    private static final String TAG = "BEGuestLogin";
    /** The sign-in flow the user closed, while their guest login is pending. */
    private static final String PREF_PENDING_FLOW = "GuestLoginPendingFlow";
    private static final int MAX_RETRIES = 3;

    private static final ObserverList<Observer> sObservers = new ObserverList<>();
    private static boolean sInFlight;

    private BrowserExpressGuestLogin() {}

    /** Whether a user closed the sign-in screen and their guest session has not arrived yet. */
    public static boolean isPending() {
        return getPendingFlow() != null;
    }

    public static void addObserver(Observer observer) {
        sObservers.addObserver(observer);
    }

    public static void removeObserver(Observer observer) {
        sObservers.removeObserver(observer);
    }

    /** Starts the guest login for a user who closed the sign-in screen during {@code flow}. */
    static void start(String flow) {
        getTokenPrefs().edit().putString(PREF_PENDING_FLOW, flow).apply();
        request(0);
    }

    /** Asks again for a pending guest session: one a request failed for, or an earlier run left. */
    public static void resumeIfPending() {
        if (isPending()) request(0);
    }

    private static void request(int retry) {
        if (sInFlight || !isPending()) return;
        if (hasSession()) {
            // Signed in some other way meanwhile, by phone most likely.
            clearPending();
            return;
        }
        sInFlight = true;
        new BrowserExpressClaimUsernameUtil.ClaimUsernameWorkerTask(
                        new BrowserExpressClaimUsernameUtil.ClaimUsernameCallback() {
                            @Override
                            public void claimUsernameSuccessful(
                                    String accessToken, String refreshToken) {
                                sInFlight = false;
                                onSessionCreated(accessToken, refreshToken);
                            }

                            @Override
                            public void claimUsernameFailed(String error) {
                                sInFlight = false;
                                Log.e(TAG, "Guest login failed: " + error);
                                // Past the retries it stays pending: the next new tab page asks
                                // again.
                                if (retry >= MAX_RETRIES) return;
                                PostTask.postDelayedTask(
                                        TaskTraits.UI_DEFAULT,
                                        () -> request(retry + 1),
                                        1000L << retry);
                            }
                        },
                        /* forceAutoLogin= */ true)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static void onSessionCreated(String accessToken, String refreshToken) {
        String flow = getPendingFlow();
        if (flow == null || hasSession()) {
            // The user signed in while this was on its way. Keep that session: this one would
            // replace it with an empty guest account.
            clearPending();
            return;
        }

        // Persist the session the way BraveActivity.setAccessToken/setRefreshToken do, but on the
        // application context: routing through BraveActivity.getBraveActivity() used to drop the
        // tokens whenever that lookup failed.
        Context appContext = ContextUtils.getApplicationContext();
        getTokenPrefs()
                .edit()
                .putString(BraveActivity.ACCESS_TOKEN_KEY, accessToken)
                .putString("RefreshToken", refreshToken)
                .remove(PREF_PENDING_FLOW)
                .apply();
        ReferralHelper.linkReferralToAccount(appContext, accessToken);
        // Attach this device's push token to the new account.
        org.chromium.chrome.browser.browser_express_push.BrowserExpressPushTokenManager
                .syncIfNeeded("login");

        // Journey completion via the auto-login path. Fired after the token write so it lands on
        // the mongo user id.
        try {
            JSONObject props = new JSONObject();
            props.put("flow", flow);
            props.put("method", "auto_login");
            BrowserExpressAuthJourney.track(PostHogEventKeys.USER_SIGNED_UP, props);
        } catch (JSONException ignored) {
        }

        for (Observer observer : sObservers) {
            observer.onGuestSignedIn(accessToken);
        }
        showGuestToast(accessToken);
    }

    /** "You're @name for now" over the browser, when it is in front. */
    private static void showGuestToast(String accessToken) {
        String username = UgcGuestToast.usernameFromAccessToken(accessToken);
        if (username == null) return;
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (activity.isFinishing()
                    || ApplicationStatus.getStateForActivity(activity) != ActivityState.RESUMED) {
                return;
            }
            UgcGuestToast.show(activity, username);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            // No browser to show it on; the session is saved all the same.
        }
    }

    /** Whether this device holds a session, guest or signed in. */
    static boolean hasSession() {
        return getTokenPrefs().getString(BraveActivity.ACCESS_TOKEN_KEY, null) != null;
    }

    private static String getPendingFlow() {
        return getTokenPrefs().getString(PREF_PENDING_FLOW, null);
    }

    private static void clearPending() {
        getTokenPrefs().edit().remove(PREF_PENDING_FLOW).apply();
    }

    private static SharedPreferences getTokenPrefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
    }
}
