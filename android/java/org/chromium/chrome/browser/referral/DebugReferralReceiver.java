/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.referral;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Log;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

/**
 * Debug receiver to test referral system without Google Play Store.
 *
 * Usage:
 * adb shell am broadcast -a com.discourse.browser.DEBUG_REFERRAL \
 *     --es referrer "utm_source=referral&referral_code=TEST123"
 *
 * Then restart the app to trigger the referral processing.
 */
public class DebugReferralReceiver extends BroadcastReceiver {
    private static final String TAG = "DebugReferralReceiver";
    public static final String ACTION_DEBUG_REFERRAL = "com.discourse.browser.DEBUG_REFERRAL";
    public static final String EXTRA_REFERRER = "referrer";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DEBUG_REFERRAL.equals(intent.getAction())) {
            return;
        }

        String referrer = intent.getStringExtra(EXTRA_REFERRER);
        if (referrer == null || referrer.isEmpty()) {
            Log.e(TAG, "No referrer provided. Use: --es referrer \"your_referrer_string\"");
            return;
        }

        Log.i(TAG, "Received debug referrer: " + referrer);

        // Reset the processed flag so it will be processed on next app start
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.EXPRESS_REFERRAL_PROCESSED, false);

        // Store the test referral
        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.DEBUG_TEST_REFERRAL, referrer);

        Log.i(TAG, "Debug referral saved. Restart the app to process it.");
    }
}
