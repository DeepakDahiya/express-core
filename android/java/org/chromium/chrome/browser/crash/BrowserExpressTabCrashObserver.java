/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;

/** Observes tab renderer crashes and reports a privacy-safe Browser Express event. */
@NullMarked
public final class BrowserExpressTabCrashObserver extends TabModelSelectorTabObserver {
    private static final String COMPONENT_RENDERER = "renderer";
    private static final String CODE_TAB_CRASH = "tab_crash";
    private static final String SEVERITY_ERROR = "error";

    public BrowserExpressTabCrashObserver(TabModelSelector selector) {
        super(selector);
    }

    @Override
    public void onCrash(Tab tab) {
        super.onCrash(tab);
        BrowserExpressCrashBreadcrumbs.recordEvent(CODE_TAB_CRASH);
        BrowserExpressPendingCrashReportStore.recordNonFatal(
                COMPONENT_RENDERER, CODE_TAB_CRASH, SEVERITY_ERROR);
    }
}
