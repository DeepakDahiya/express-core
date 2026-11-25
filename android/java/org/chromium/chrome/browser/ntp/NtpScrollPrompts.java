/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.browser_express_push.BrowserExpressPushAnalytics;
import org.chromium.chrome.browser.notifications.BravePermissionUtils;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.set_default_browser.BraveSetDefaultBrowserUtils;
import org.chromium.chrome.browser.settings.PostHogEventKeys;

/**
 * The two asks the Home feed makes, spaced out by scrolling: the notification permission once the
 * reader has scrolled past 5 posts, the default-browser sheet once they are past 10. Posts are
 * counted over all sessions.
 *
 * <p>Every ask waits until 5 more posts have gone by since the one before, so two never come back
 * to back. That includes the default-browser ask the app makes at launch
 * (BraveSetDefaultBrowserUtils.shouldShowDefaultBrowserDialog).
 */
public final class NtpScrollPrompts {
    public static final int NOTIFICATION_PROMPT_POSTS = 5;
    public static final int DEFAULT_BROWSER_PROMPT_POSTS = 10;
    /** Posts to scroll past between one ask and the next. */
    private static final int POSTS_BETWEEN_PROMPTS = 5;

    /** The default-browser sheet shows at most once per launch, as when it showed at startup. */
    private static boolean sDefaultBrowserPromptShown;

    private NtpScrollPrompts() {}

    /** {@code posts} more posts have gone up past the top of a Home feed. */
    public static void recordPostsPassed(int posts) {
        if (posts <= 0) return;
        ChromeSharedPreferences.getInstance()
                .writeInt(BravePreferenceKeys.BROWSER_EXPRESS_NTP_POSTS_PASSED,
                        getPostsPassed() + posts);
    }

    public static int getPostsPassed() {
        return ChromeSharedPreferences.getInstance()
                .readInt(BravePreferenceKeys.BROWSER_EXPRESS_NTP_POSTS_PASSED, 0);
    }

    /** Whether the default-browser ask may come now, on Home or at launch. */
    public static boolean isDefaultBrowserPromptDue() {
        int posts = getPostsPassed();
        return posts >= DEFAULT_BROWSER_PROMPT_POSTS && isSpacedFromLastPrompt(posts);
    }

    /** An ask was just made: the next waits for another {@link #POSTS_BETWEEN_PROMPTS} posts. */
    public static void markPromptShown() {
        ChromeSharedPreferences.getInstance()
                .writeInt(BravePreferenceKeys.BROWSER_EXPRESS_NTP_LAST_PROMPT_AT_POSTS,
                        getPostsPassed());
    }

    private static boolean isSpacedFromLastPrompt(int posts) {
        int last = ChromeSharedPreferences.getInstance().readInt(
                BravePreferenceKeys.BROWSER_EXPRESS_NTP_LAST_PROMPT_AT_POSTS, -1);
        return last < 0 || posts >= last + POSTS_BETWEEN_PROMPTS;
    }

    /**
     * Makes whichever ask is due, notifications first. The caller has checked that Home is in front
     * and the window has focus.
     *
     * @return whether an ask was made.
     */
    public static boolean maybeShowOnNtp(AppCompatActivity activity) {
        int posts = getPostsPassed();
        if (!isSpacedFromLastPrompt(posts)) return false;

        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        if (posts >= NOTIFICATION_PROMPT_POSTS
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !BravePermissionUtils.hasNotificationPermission(activity)
                && !prefs.readBoolean(
                        BravePreferenceKeys.BROWSER_EXPRESS_NTP_NOTIFICATION_PROMPTED, false)) {
            // Asked once; the result lands in BraveActivity.onRequestPermissionsResult.
            prefs.writeBoolean(BravePreferenceKeys.BROWSER_EXPRESS_NTP_NOTIFICATION_PROMPTED, true);
            markPromptShown();
            BrowserExpressPushAnalytics.fire(
                    PostHogEventKeys.PUSH_PERMISSION_PROMPTED, "reason", "ntp_feed_scroll");
            BravePermissionUtils.showNotificationPermissionDialog(activity);
            return true;
        }
        if (posts >= DEFAULT_BROWSER_PROMPT_POSTS
                && !sDefaultBrowserPromptShown
                && !BraveSetDefaultBrowserUtils.hasReachedForcedPromptLimit()
                && !BraveSetDefaultBrowserUtils.isCurrentAppDefaultBrowser(activity)) {
            sDefaultBrowserPromptShown = true;
            markPromptShown();
            BraveSetDefaultBrowserUtils.showBraveSetDefaultBrowserDialog(activity);
            return true;
        }
        return false;
    }
}
