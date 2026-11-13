/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.content.Context;
import android.os.Bundle;

import androidx.core.app.NotificationManagerCompat;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.chrome.browser.settings.PostHogEventKeys;

/**
 * Entry point patched into ChromeGcmListenerServiceImpl.onMessageReceived. Only one
 * FirebaseMessagingService can own MESSAGING_EVENT and Chrome's already does, so our
 * messages are claimed here — before Chrome parses them as Web Push (which would
 * throw on the missing "subtype" key and drop them). Docs §5.3.
 */
public final class BrowserExpressPushReceiver {
    private static final String TAG = "ExpressPush";

    private BrowserExpressPushReceiver() {}

    /**
     * @return true when the message was ours (shown, or intentionally dropped). False lets
     *     Chrome's Web Push path handle it untouched.
     */
    public static boolean maybeHandle(String from, Bundle data) {
        if (from == null || !from.equals(BrowserExpressPushConfig.FCM_SENDER_ID)) {
            if (looksLikeOurPayload(data)) {
                // Surfaces a sender-id mismatch in Phase 1 testing without swallowing
                // anything that might legitimately belong to Web Push.
                Log.w(TAG, "Express-shaped push from unexpected sender: " + from);
            }
            return false;
        }
        if (data == null) return true;
        String type = data.getString("type");
        if (type == null || type.isEmpty()) {
            Log.w(TAG, "Dropping push without type");
            return true;
        }
        if (!BrowserExpressPushConfig.isFeatureEnabled() || !BrowserExpressPushConfig.isUserEnabled()) {
            return true;
        }
        Context ctx = ContextUtils.getApplicationContext();
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return true;

        BrowserExpressPushAnalytics.fire(PostHogEventKeys.PUSH_RECEIVED, "type", type);
        try {
            BrowserExpressPushNotifier.show(ctx, data);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show push: " + e.getMessage());
        }
        return true;
    }

    private static boolean looksLikeOurPayload(Bundle data) {
        return data != null
                && data.containsKey("v")
                && data.containsKey("type")
                && data.containsKey("notificationId");
    }
}
