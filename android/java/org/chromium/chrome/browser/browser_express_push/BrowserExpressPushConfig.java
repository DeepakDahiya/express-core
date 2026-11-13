/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.browser.browser_express_config.BrowserExpressConfigUtil;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

/**
 * Constants and switches for backend-sent (FCM) like/reply notifications.
 * See browser-express-node/docs/push-notifications-implementation.md.
 */
public final class BrowserExpressPushConfig {
    private BrowserExpressPushConfig() {}

    /**
     * Firebase project number ("Sender ID") of the Express Browser Firebase project.
     * Until this is replaced with the real value the feature is inert: no token is
     * minted and incoming messages are never claimed.
     */
    public static final String FCM_SENDER_ID = "REPLACE_WITH_FIREBASE_PROJECT_NUMBER";

    /** InstanceID subtype: keeps our token separate from Chrome's Web Push tokens. */
    public static final String INSTANCE_ID_SUBTYPE = "browser_express";

    public static final String INSTANCE_ID_SCOPE = "FCM";

    public static final String API_BASE_URL = "https://api.browser.express/v1";

    /** Payload contract version (docs §6). Unknown versions are still shown. */
    public static final String PAYLOAD_VERSION = "1";

    /** Prefix for the intent extras copied from the FCM data payload on tap. */
    public static final String EXTRA_PREFIX = "browser_express_push_";

    public static final String NOTIFICATION_GROUP = "browser_express_comments";

    /** Notification id; the tag (collapse key) distinguishes individual notifications. */
    public static final int NOTIFICATION_ID = 0x4E505348; // "NPSH"

    public static final String TYPE_COMMENT_LIKE = "comment_like";
    public static final String TYPE_COMMENT_REPLY = "comment_reply";
    public static final String TYPE_POST_LIKE = "post_like";

    public static final String PARENT_TYPE_PAGE = "page";
    public static final String PARENT_TYPE_YOUTUBE = "youtube";
    public static final String PARENT_TYPE_POST = "post";

    /** Data keys copied verbatim from the FCM payload into the tap intent. */
    public static final String[] PAYLOAD_KEYS = {
        "v", "type", "notificationId", "collapseKey", "title", "body", "commentId",
        "parentCommentId", "parentType", "url", "videoId", "postId", "postUsername",
        "postContent", "postAvatarUrl", "postVideoUrl", "actorUsername", "actorAvatarUrl",
        "sentAt"
    };

    /** True once a real sender id has been configured. */
    public static boolean isSenderConfigured() {
        return FCM_SENDER_ID != null && FCM_SENDER_ID.matches("\\d{6,}");
    }

    /** Remote-config flag, or the developer-preference override for internal testing. */
    public static boolean isFeatureEnabled() {
        return BrowserExpressConfigUtil.isPushNotificationsEnabled()
                || ChromeSharedPreferences.getInstance()
                        .readBoolean(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_DEV_FORCE_ENABLED, false);
    }

    /** The user's own "notify me about likes and replies" switch (default on). */
    public static boolean isUserEnabled() {
        return ChromeSharedPreferences.getInstance()
                .readBoolean(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_USER_ENABLED, true);
    }

    public static void setUserEnabled(boolean enabled) {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.BROWSER_EXPRESS_PUSH_USER_ENABLED, enabled);
    }

    /** Everything that must be true before we mint a token or show a push. */
    public static boolean isActive() {
        return isSenderConfigured() && isFeatureEnabled() && isUserEnabled();
    }
}
