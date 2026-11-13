/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_push;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.chromium.base.Log;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.tab.Tab;

import java.util.Locale;

/**
 * Turns a notification tap (the extras written by BrowserExpressPushNotifier) into the
 * comments sheet scrolled to the right comment. Docs §5.6.
 *
 * <p>Runs from BraveActivity both on cold start and on onNewIntent. In both cases the
 * URL from the ACTION_VIEW intent is processed by ChromeTabbedActivity *after* our code
 * runs, so instead of touching the current tab directly we poll until the active tab is
 * showing the target URL and has finished loading.
 */
public final class BrowserExpressPushOpenHandler {
    private static final String TAG = "ExpressPush";
    private static final long POLL_INTERVAL_MS = 250;
    private static final long TAB_WAIT_TIMEOUT_MS = 12000;
    private static final long POST_SHEET_DELAY_MS = 600;

    private BrowserExpressPushOpenHandler() {}

    /** @return true when the intent came from one of our notifications. */
    public static boolean handle(final BraveActivity activity, final Intent intent) {
        if (activity == null || intent == null) return false;
        final String type = extra(intent, "type");
        if (TextUtils.isEmpty(type)) return false;
        // Never handle the same intent twice (rotation, onNewIntent + getIntent()).
        intent.removeExtra(BrowserExpressPushConfig.EXTRA_PREFIX + "type");

        BrowserExpressPushAnalytics.fire(PostHogEventKeys.PUSH_OPENED, "type", type);
        BrowserExpressPushApi.markReadAsync(extra(intent, "notificationId"));

        final String parentType = extra(intent, "parentType");
        // For a reply, land on *my* comment (the parent) with the replies beneath it.
        final String target =
                BrowserExpressPushConfig.TYPE_COMMENT_REPLY.equals(type)
                        ? extra(intent, "parentCommentId")
                        : extra(intent, "commentId");

        if (BrowserExpressPushConfig.PARENT_TYPE_POST.equals(parentType)) {
            final String postId = extra(intent, "postId");
            final String username = extra(intent, "postUsername");
            final String content = extra(intent, "postContent");
            final String avatar = extra(intent, "postAvatarUrl");
            final String videoUrl = extra(intent, "postVideoUrl");
            if (TextUtils.isEmpty(postId)) return true;
            new Handler(Looper.getMainLooper())
                    .postDelayed(
                            () -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                activity.showCommentsBottomSheetFromPost(
                                        postId, username, content, avatar, false,
                                        emptyToNull(videoUrl), target);
                            },
                            POST_SHEET_DELAY_MS);
            return true;
        }

        final String url = extra(intent, "url");
        if (TextUtils.isEmpty(url)) return true;
        waitForTabAtUrl(activity, url, () -> activity.showCommentsBottomSheet(target));
        return true;
    }

    private static void waitForTabAtUrl(
            final BraveActivity activity, final String url, final Runnable onReady) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final long deadline = System.currentTimeMillis() + TAB_WAIT_TIMEOUT_MS;
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        Tab tab = activity.getActivityTab();
                        boolean timedOut = System.currentTimeMillis() > deadline;
                        if (tab != null && !tab.isLoading() && sameTarget(url, tab.getUrl().getSpec())) {
                            onReady.run();
                            return;
                        }
                        if (timedOut) {
                            // Only open the sheet if we are at least on the right site;
                            // showing another page's comments would be worse than nothing.
                            if (tab != null && sameHost(url, tab.getUrl().getSpec())) {
                                onReady.run();
                            } else {
                                Log.w(TAG, "gave up waiting for tab at " + url);
                            }
                            return;
                        }
                        handler.postDelayed(this, POLL_INTERVAL_MS);
                    }
                });
    }

    /** Same host + path (and same YouTube video id when present); query/fragment differences are ignored. */
    static boolean sameTarget(String expected, String actual) {
        if (TextUtils.isEmpty(expected) || TextUtils.isEmpty(actual)) return false;
        try {
            Uri e = Uri.parse(expected);
            Uri a = Uri.parse(actual);
            if (!sameHost(expected, actual)) return false;
            String ev = e.getQueryParameter("v");
            String av = a.getQueryParameter("v");
            if (!TextUtils.isEmpty(ev) || !TextUtils.isEmpty(av)) {
                return TextUtils.equals(ev, av);
            }
            return TextUtils.equals(trimSlash(e.getPath()), trimSlash(a.getPath()));
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean sameHost(String expected, String actual) {
        if (TextUtils.isEmpty(expected) || TextUtils.isEmpty(actual)) return false;
        try {
            return TextUtils.equals(
                    normalizeHost(Uri.parse(expected).getHost()),
                    normalizeHost(Uri.parse(actual).getHost()));
        } catch (Exception ex) {
            return false;
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        // Hostnames are ASCII: ROOT keeps this stable on Turkish-locale devices,
        // where default-locale lower-casing of "I" would break host matches.
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }

    private static String trimSlash(String path) {
        if (path == null) return "";
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static String extra(Intent intent, String key) {
        return intent.getStringExtra(BrowserExpressPushConfig.EXTRA_PREFIX + key);
    }

    private static String emptyToNull(String s) {
        return TextUtils.isEmpty(s) ? null : s;
    }
}
