/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.referral.ReferralHelper;

/**
 * Fires PostHog events for the phone-OTP registration/login journey so the whole
 * funnel — auth screen viewed, OTP requested, OTP screen viewed, OTP submitted,
 * signed up / logged in — is trackable per user.
 *
 * <p>The steps before OTP verification succeed have no mongo user id yet, so
 * every event is keyed by {@link ReferralHelper#resolveDistinctId()}: the mongo
 * {@code _id} once a token exists, otherwise the anonymous install id
 * {@code anon-<deviceId>}. That is the same identity the referral funnel uses, and
 * {@code ReferralHelper.maybeIdentifyInstall} sends PostHog's {@code $identify} on
 * the first successful auth — so the anonymous pre-auth journey stitches onto the
 * signed-in person instead of stranding on an orphan anonymous profile.
 */
final class BrowserExpressAuthJourney {
    private static final String TAG = "BrowserExpressAuthJourney";

    private BrowserExpressAuthJourney() {}

    /** Track an event with no extra properties. */
    static void track(String event) {
        fire(event, new JSONObject());
    }

    /** Track an event tagged with the auth flow ("login" or "signup"). */
    static void track(String event, String flow) {
        JSONObject props = new JSONObject();
        try {
            if (flow != null) props.put("flow", flow);
        } catch (JSONException ignored) {
        }
        fire(event, props);
    }

    /** Track a failure event with the flow and a human-readable error. */
    static void trackError(String event, String flow, String error) {
        JSONObject props = new JSONObject();
        try {
            if (flow != null) props.put("flow", flow);
            if (error != null && !error.isEmpty()) props.put("error", error);
        } catch (JSONException ignored) {
        }
        fire(event, props);
    }

    /** Track an event with caller-supplied properties. */
    static void track(String event, JSONObject props) {
        fire(event, props != null ? props : new JSONObject());
    }

    private static void fire(String event, JSONObject props) {
        try {
            new PostHogUtil.PostHogWorkerTask(
                    event, ReferralHelper.resolveDistinctId(), props)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (Exception e) {
            Log.e(TAG, "track error: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }
}
