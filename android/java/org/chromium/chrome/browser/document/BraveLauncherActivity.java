/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.document;

import android.app.Activity;
import android.os.Bundle;

import org.chromium.base.Log;
import org.chromium.chrome.browser.BraveHelper;
import org.chromium.chrome.browser.referral.ReferralHelper;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarConfiguration;

/**
 * Base class for ChromeLauncherActivity
 */
public class BraveLauncherActivity extends Activity {
    private static final String TAG = "BraveLauncherActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BottomToolbarConfiguration.isBraveBottomControlsEnabled();
        BraveHelper.disableFREDRP();

        // Check for referral early in app launch
        Log.d(TAG, "Checking referral in BraveLauncherActivity");
        ReferralHelper.checkAndProcessReferral(this);
    }
}
