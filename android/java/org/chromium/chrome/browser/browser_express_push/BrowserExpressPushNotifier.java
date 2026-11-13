/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import org.chromium.base.IntentUtils;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.notifications.NotificationUmaTracker;
import org.chromium.chrome.browser.notifications.NotificationWrapperBuilderFactory;
import org.chromium.chrome.browser.notifications.channels.BraveChannelDefinitions;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.components.browser_ui.notifications.BaseNotificationManagerProxyFactory;
import org.chromium.components.browser_ui.notifications.NotificationMetadata;
import org.chromium.components.browser_ui.notifications.NotificationWrapper;
import org.chromium.components.browser_ui.notifications.NotificationWrapperBuilder;
import org.chromium.components.browser_ui.notifications.PendingIntentProvider;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Renders an FCM data payload (docs §6) as a system notification. Docs §5.5. */
public final class BrowserExpressPushNotifier {
    private static final String TAG = "ExpressPush";
    private static final int AVATAR_TIMEOUT_MS = 3000;

    private BrowserExpressPushNotifier() {}

    /** Safe to call on the FCM service's background thread. */
    public static void show(Context ctx, Bundle d) {
        String title = d.getString("title", "");
        String body = d.getString("body", "");
        String notificationId = d.getString("notificationId", String.valueOf(System.currentTimeMillis()));
        // Tag = collapse key, so repeated likes on one comment replace the earlier
        // notification instead of stacking.
        String tag = d.getString("collapseKey");
        if (TextUtils.isEmpty(tag)) tag = notificationId;

        Intent tap = buildTapIntent(ctx, d);
        PendingIntentProvider contentIntent =
                PendingIntentProvider.getActivity(
                        ctx,
                        notificationId.hashCode(),
                        tap,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | IntentUtils.getPendingIntentMutabilityFlag(false));

        NotificationMetadata metadata =
                new NotificationMetadata(
                        NotificationUmaTracker.SystemNotificationType.UNKNOWN,
                        tag,
                        BrowserExpressPushConfig.NOTIFICATION_ID);

        // The factory makes sure the channel exists even if the browser has not run
        // since the update that introduced it.
        NotificationWrapperBuilder builder =
                NotificationWrapperBuilderFactory.createNotificationWrapperBuilder(
                                BraveChannelDefinitions.ChannelId.BROWSER_EXPRESS_COMMENTS, metadata)
                        .setSmallIcon(R.drawable.ic_chrome)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setBigTextStyle(body)
                        .setCategory(Notification.CATEGORY_SOCIAL)
                        .setPriorityBeforeO(Notification.PRIORITY_HIGH)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .setGroup(BrowserExpressPushConfig.NOTIFICATION_GROUP)
                        .setAutoCancel(true)
                        .setShowWhen(true)
                        .setContentIntent(contentIntent);

        long sentAt = parseLong(d.getString("sentAt"));
        if (sentAt > 0) builder.setWhen(sentAt);

        Bitmap avatar = loadAvatarBlocking(
                ProfileAvatar.resolve(d.getString("actorAvatarUrl"), d.getString("actorUsername")));
        if (avatar != null) builder.setLargeIcon(avatar);

        NotificationWrapper wrapper = builder.buildNotificationWrapper();
        BaseNotificationManagerProxyFactory.create().notify(wrapper);
    }

    /**
     * page / youtube: ACTION_VIEW the URL so the browser opens (or focuses) the tab; the
     * comments sheet is opened by BrowserExpressPushOpenHandler once that tab is up.
     * post: plain launch; the handler opens the feed-post sheet from the extras.
     */
    static Intent buildTapIntent(Context ctx, Bundle d) {
        String parentType = d.getString("parentType", "");
        String url = d.getString("url", "");
        Intent intent;
        if (BrowserExpressPushConfig.PARENT_TYPE_POST.equals(parentType) || TextUtils.isEmpty(url)) {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (intent == null) intent = new Intent(Intent.ACTION_MAIN);
        } else {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        }
        intent.setPackage(ctx.getPackageName());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (String key : BrowserExpressPushConfig.PAYLOAD_KEYS) {
            if (d.containsKey(key)) {
                intent.putExtra(BrowserExpressPushConfig.EXTRA_PREFIX + key, d.getString(key));
            }
        }
        return intent;
    }

    private static Bitmap loadAvatarBlocking(String avatarUrl) {
        if (TextUtils.isEmpty(avatarUrl) || !avatarUrl.startsWith("https://")) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(avatarUrl).openConnection();
            conn.setConnectTimeout(AVATAR_TIMEOUT_MS);
            conn.setReadTimeout(AVATAR_TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            Log.d(TAG, "avatar load skipped: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long parseLong(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
