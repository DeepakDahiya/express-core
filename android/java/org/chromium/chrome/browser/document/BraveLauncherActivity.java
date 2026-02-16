/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.document;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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

        // Handle incoming referral deep links (e.g. https://browser.express/refer?code=a1b2c3d4)
        handleReferralDeepLink(getIntent());

        // Check for referral early in app launch (Play Store Install Referrer)
        Log.i(TAG, "Checking referral in BraveLauncherActivity");
        ReferralHelper.checkAndProcessReferral(this);

        // Fetch remote configuration (feature flags)
        org.chromium.chrome.browser.browser_express_config.BrowserExpressConfigUtil.fetchConfigIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleReferralDeepLink(intent);
    }

    private void handleReferralDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;

        Uri uri = intent.getData();
        if (uri == null) return;

        String host = uri.getHost();
        String path = uri.getPath();

        if ("browser.express".equals(host) && path != null && path.startsWith("/refer")) {
            String referralCode = uri.getQueryParameter("code");
            if (referralCode != null && !referralCode.isEmpty()) {
                Log.i(TAG, "Received referral deep link with code: " + referralCode);
                ReferralHelper.processDeepLinkReferral(this, referralCode);
            }
        }
    }
}
