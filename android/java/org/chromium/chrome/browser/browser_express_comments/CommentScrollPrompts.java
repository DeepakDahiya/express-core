/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

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
 * The NTP's default-browser and notification prompts wait until the reader has scrolled through
 * comments enough times in total (across sessions): one scroll is one drag the reader starts on a
 * comment list or the comments reel.
 */
public final class CommentScrollPrompts {
    public static final int DEFAULT_BROWSER_PROMPT_SCROLLS = 5;
    public static final int NOTIFICATION_PROMPT_SCROLLS = 10;

    /** The default-browser sheet shows at most once per launch, as when it showed at startup. */
    private static boolean sDefaultBrowserPromptShown;

    private CommentScrollPrompts() {}

    public static void recordScroll() {
        ChromeSharedPreferences.getInstance()
                .writeInt(
                        BravePreferenceKeys.BROWSER_EXPRESS_COMMENT_SCROLL_COUNT,
                        getScrollCount() + 1);
    }

    public static int getScrollCount() {
        return ChromeSharedPreferences.getInstance()
                .readInt(BravePreferenceKeys.BROWSER_EXPRESS_COMMENT_SCROLL_COUNT, 0);
    }

    /**
     * Shows whichever of the two prompts is due, never both at once. The caller has checked that
     * the NTP is in front and the window has focus.
     *
     * @return whether a prompt was shown.
     */
    public static boolean maybeShowOnNtp(AppCompatActivity activity) {
        int scrolls = getScrollCount();
        if (scrolls >= DEFAULT_BROWSER_PROMPT_SCROLLS
                && !sDefaultBrowserPromptShown
                && !BraveSetDefaultBrowserUtils.hasReachedForcedPromptLimit()
                && !BraveSetDefaultBrowserUtils.isCurrentAppDefaultBrowser(activity)) {
            sDefaultBrowserPromptShown = true;
            BraveSetDefaultBrowserUtils.showBraveSetDefaultBrowserDialog(activity);
            return true;
        }
        if (scrolls >= NOTIFICATION_PROMPT_SCROLLS
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !BravePermissionUtils.hasNotificationPermission(activity)) {
            SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
            if (prefs.readBoolean(
                    BravePreferenceKeys.BROWSER_EXPRESS_NTP_NOTIFICATION_PROMPTED, false)) {
                return false;
            }
            // Asked once; the result lands in BraveActivity.onRequestPermissionsResult.
            prefs.writeBoolean(BravePreferenceKeys.BROWSER_EXPRESS_NTP_NOTIFICATION_PROMPTED, true);
            BrowserExpressPushAnalytics.fire(
                    PostHogEventKeys.PUSH_PERMISSION_PROMPTED, "reason", "ntp_comment_scrolls");
            BravePermissionUtils.showNotificationPermissionDialog(activity);
            return true;
        }
        return false;
    }
}
