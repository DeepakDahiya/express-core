/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.chromium.chrome.browser;

import android.content.res.Configuration;

import com.wireguard.android.backend.GoBackend;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.base.SplitCompatApplication;
import org.chromium.chrome.browser.crash.BrowserExpressCrashBreadcrumbs;
import org.chromium.chrome.browser.crash.BrowserExpressPendingCrashReportStore;
import org.chromium.chrome.browser.vpn.utils.BraveVpnProfileUtils;
import org.chromium.components.safe_browsing.BraveSafeBrowsingApiHandler;
import org.chromium.components.safe_browsing.SafeBrowsingApiBridge;

@NullMarked
public class BraveApplicationImplBase extends SplitCompatApplication.Impl {
    @Override
    public void onCreate() {
        super.onCreate();
        if (SplitCompatApplication.isBrowserProcess()) {
            BrowserExpressCrashBreadcrumbs.initialize();
            BrowserExpressPendingCrashReportStore.initialize();
            GoBackend.setAlwaysOnCallback(
                    new GoBackend.AlwaysOnCallback() {
                        @Override
                        public void alwaysOnTriggered() {
                            BraveVpnProfileUtils.getInstance().startVpn(getApplication());
                        }
                    });
            // Set a handler for SafeBrowsing. It has to be done only once for a process lifetime.
            SafeBrowsingApiBridge.setSafeBrowsingApiHandler(
                    BraveSafeBrowsingApiHandler.getInstance());
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (SplitCompatApplication.isBrowserProcess()) {
            BrowserExpressCrashBreadcrumbs.recordTrimMemory(level);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (SplitCompatApplication.isBrowserProcess()) {
            BrowserExpressCrashBreadcrumbs.recordConfigurationChanged(newConfig);
        }
    }
}
