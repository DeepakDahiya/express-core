/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.fragment.app.FragmentManager;

import org.chromium.base.Log;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ShortcutHelper;
import org.chromium.chrome.browser.browser_express_config.BrowserExpressConfigUtil;
import org.chromium.chrome.browser.local_database.DatabaseHelper;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.set_default_browser.BraveSetDefaultBrowserUtils;
import org.chromium.components.webapps.ShortcutSource;
import org.chromium.components.webapps.WebappsUtils;

/** Utilities for pinning the Golden YouTube shortcut to the Android home screen. */
public final class GoldenYouTubeHomeScreenShortcutUtil {
    private static final String TAG = "GoldenYouTubeShortcut";
    private static final String SHORTCUT_ID = "browser_express_golden_youtube";
    private static final String PREF_LAST_SHOWN =
            "golden_youtube_home_screen_shortcut_prompt_last_shown";
    private static final String PREF_SHOWN_COUNT =
            "golden_youtube_home_screen_shortcut_prompt_shown_count";
    private static final String PREF_USED_PIP_BACKGROUND_PLAY =
            "golden_youtube_home_screen_shortcut_used_pip_background_play";
    private static final String PREF_BACKGROUND_PLAY_USE_COUNT =
            "golden_youtube_home_screen_shortcut_background_play_use_count";
    private static final String PREF_BACKGROUND_PLAY_RATING_PROMPT_HANDLED =
            "golden_youtube_background_play_rating_prompt_handled";
    private static final String PREF_BACKGROUND_PLAY_RATING_PROMPT_LAST_SHOWN =
            "golden_youtube_background_play_rating_prompt_last_shown";
    private static final int REQUIRED_BACKGROUND_PLAY_USE_COUNT = 2;
    private static final int REQUIRED_BACKGROUND_PLAY_RATING_USE_COUNT = 3;
    private static final int MAX_PROMPT_COUNT = 3;
    private static final long PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    private GoldenYouTubeHomeScreenShortcutUtil() {}

    public static boolean showBackgroundPlaybackPromptsIfEligible(
            FragmentManager fragmentManager, Activity activity) {
        if (fragmentManager == null || activity == null) return false;

        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        Log.e(
                TAG,
                "checking prompts backgroundPlayUseCount="
                        + getBackgroundPlayUseCount(prefs)
                        + " ratingHandled="
                        + prefs.readBoolean(PREF_BACKGROUND_PLAY_RATING_PROMPT_HANDLED, false)
                        + " ratingLastShown="
                        + prefs.readLong(PREF_BACKGROUND_PLAY_RATING_PROMPT_LAST_SHOWN, 0L));
        if (GoldenYouTubeBackgroundPlaybackRatingDialogFragment.showIfNeeded(
                fragmentManager, activity)) {
            return true;
        }
        return GoldenYouTubeHomeScreenShortcutDialogFragment.showIfNeeded(fragmentManager, activity);
    }

    public static boolean shouldShowPrompt(Activity activity) {
        if (activity == null) return blockPrompt("shortcut", "activity_null");
        if (!BrowserExpressConfigUtil.isYouTubePremiumEnabled()) {
            return blockPrompt("shortcut", "youtube_premium_disabled");
        }
        if (BraveSetDefaultBrowserUtils.isBottomSheetVisible) {
            return blockPrompt("shortcut", "bottom_sheet_visible");
        }
        if (BraveSetDefaultBrowserUtils.shouldShowDefaultBrowserDialog(activity)) {
            return blockPrompt("shortcut", "default_browser_dialog_eligible");
        }
        if (!WebappsUtils.isAddToHomeIntentSupported()) {
            return blockPrompt("shortcut", "add_to_home_not_supported");
        }
        if (isShortcutPinned(activity)) return blockPrompt("shortcut", "shortcut_pinned");

        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int useCount = getBackgroundPlayUseCount(prefs);
        if (useCount < REQUIRED_BACKGROUND_PLAY_USE_COUNT) {
            return blockPrompt("shortcut", "use_count_" + useCount);
        }

        int shownCount = prefs.readInt(PREF_SHOWN_COUNT, 0);
        if (shownCount >= MAX_PROMPT_COUNT) return blockPrompt("shortcut", "max_prompt_count");

        long lastShown = prefs.readLong(PREF_LAST_SHOWN, 0L);
        if (System.currentTimeMillis() - lastShown <= PROMPT_COOLDOWN_MS) {
            return blockPrompt("shortcut", "cooldown");
        }
        return true;
    }

    public static boolean shouldShowBackgroundPlaybackRatingPrompt(Activity activity) {
        if (activity == null) return blockPrompt("rating", "activity_null");
        if (!BrowserExpressConfigUtil.isYouTubePremiumEnabled()) {
            return blockPrompt("rating", "youtube_premium_disabled");
        }
        if (BraveSetDefaultBrowserUtils.isBottomSheetVisible) {
            return blockPrompt("rating", "bottom_sheet_visible");
        }
        if (BraveSetDefaultBrowserUtils.shouldShowDefaultBrowserDialog(activity)) {
            return blockPrompt("rating", "default_browser_dialog_eligible");
        }

        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        if (prefs.readBoolean(PREF_BACKGROUND_PLAY_RATING_PROMPT_HANDLED, false)) {
            return blockPrompt("rating", "handled");
        }
        int useCount = getBackgroundPlayUseCount(prefs);
        if (useCount < REQUIRED_BACKGROUND_PLAY_RATING_USE_COUNT) {
            return blockPrompt("rating", "use_count_" + useCount);
        }
        long lastShown = prefs.readLong(PREF_BACKGROUND_PLAY_RATING_PROMPT_LAST_SHOWN, 0L);
        if (System.currentTimeMillis() - lastShown <= PROMPT_COOLDOWN_MS) {
            return blockPrompt("rating", "cooldown");
        }
        return true;
    }

    public static void recordPromptShown() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        prefs.writeLong(PREF_LAST_SHOWN, System.currentTimeMillis());
        prefs.writeInt(PREF_SHOWN_COUNT, prefs.readInt(PREF_SHOWN_COUNT, 0) + 1);
    }

    public static void recordBackgroundPlaybackRatingPromptHandled() {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(PREF_BACKGROUND_PLAY_RATING_PROMPT_HANDLED, true);
    }

    public static void recordBackgroundPlaybackRatingPromptShown() {
        ChromeSharedPreferences.getInstance()
                .writeLong(PREF_BACKGROUND_PLAY_RATING_PROMPT_LAST_SHOWN, System.currentTimeMillis());
    }

    public static int recordPipBackgroundPlayUsed() {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        int useCount = getBackgroundPlayUseCount(prefs) + 1;
        prefs.writeInt(PREF_BACKGROUND_PLAY_USE_COUNT, useCount);
        prefs.writeBoolean(PREF_USED_PIP_BACKGROUND_PLAY, true);
        Log.e(TAG, "recorded background playback use count=" + useCount);
        return useCount;
    }

    private static int getBackgroundPlayUseCount(SharedPreferencesManager prefs) {
        int useCount = prefs.readInt(PREF_BACKGROUND_PLAY_USE_COUNT, 0);
        if (useCount > 0) return useCount;
        return prefs.readBoolean(PREF_USED_PIP_BACKGROUND_PLAY, false) ? 1 : 0;
    }

    private static boolean blockPrompt(String prompt, String reason) {
        Log.e(TAG, prompt + " prompt blocked: " + reason);
        return false;
    }

    public static boolean requestPinShortcut(Context context) {
        if (context == null || !WebappsUtils.isAddToHomeIntentSupported()) return false;
        Bitmap icon =
                BitmapFactory.decodeResource(
                        context.getResources(), R.drawable.golden_youtube_icon);
        if (icon == null) return false;

        WebappsUtils.addShortcutToHomescreen(
                SHORTCUT_ID,
                context.getString(R.string.golden_youtube_shortcut_label),
                icon,
                false,
                createLaunchIntent());
        return true;
    }

    private static boolean isShortcutPinned(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null) return false;

        for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
            if (SHORTCUT_ID.equals(shortcutInfo.getId())) {
                return true;
            }
        }
        return false;
    }

    private static Intent createLaunchIntent() {
        return ShortcutHelper.createShortcutIntent(
                DatabaseHelper.DEFAULT_YOUTUBE_URL,
                SHORTCUT_ID,
                ShortcutSource.ADD_TO_HOMESCREEN_STANDALONE);
    }
}
