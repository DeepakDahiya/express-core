/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.notifications.permissions;

import org.chromium.base.supplier.Supplier;
import org.chromium.ui.permissions.AndroidPermissionDelegate;

/**
 * Drops Chromium's startup notification permission request. The NTP asks instead, once the reader
 * has scrolled past enough Home feed posts (see NtpScrollPrompts).
 */
public class BraveNotificationPermissionController extends NotificationPermissionController {
    public BraveNotificationPermissionController(
            AndroidPermissionDelegate androidPermissionDelegate,
            Supplier<RationaleDelegate> rationaleDelegateSupplier) {
        super(androidPermissionDelegate, rationaleDelegateSupplier);
    }

    @Override
    public boolean requestPermissionIfNeeded(boolean contextual) {
        // Only the startup call is non-contextual; in-context asks (e.g. downloads) still go on.
        if (!contextual) return false;
        return super.requestPermissionIfNeeded(contextual);
    }
}
