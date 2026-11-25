/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import org.chromium.chrome.browser.util.PackageUtils;

/**
 * Where the first-launch walkthrough stands (design_handoff_walkthrough README §3–5).
 *
 * <p>The step persists, so a walkthrough the app was killed in resumes at the same step on the
 * next launch: the search and post prompts show again, the scroll runs again and the video step
 * waits for the next stream. Once done it never shows again; there is no replay. Everything lives
 * in this install's preferences, so a reinstall or a new device starts over.
 */
public final class WalkthroughState {
    private static final String TAG = "Walkthrough";

    /** The search bar prompt ("The Internet, Upgraded"). */
    public static final String STEP_SEARCH = "search";

    /** The feed scrolling itself to the featured post. */
    public static final String STEP_SCROLL = "scroll";

    /** The featured post's prompt ("The Best User Reactions"). */
    public static final String STEP_POST = "post";

    /** Waiting for a stream on a vertical video, then the swipe nudge. */
    public static final String STEP_VIDEO = "video";

    public static final String STEP_DONE = "done";

    /**
     * Whether installs that predate the walkthrough get it too. The README asks for it on the
     * first app launch, so an update leaves existing users alone.
     */
    private static final boolean SHOW_TO_EXISTING_INSTALLS = false;

    /** The bonus tip comes on this vertical-video take, counted across all streams. */
    static final int BONUS_TIP_TAKE = 3;

    private static final String KEY_STEP = "browser_express_walkthrough_step";
    private static final String KEY_BONUS_TIP_SHOWN = "browser_express_walkthrough_bonus_tip_shown";
    private static final String KEY_VIDEO_TAKES_SEEN =
            "browser_express_walkthrough_video_takes_seen";

    /**
     * "Pick another post" took the prompt away so the reader can choose for themselves. Held for
     * this process only: the step is still the post step, and a relaunch shows its prompt again.
     */
    private static boolean sPickingPost;

    /** When the reader's first swipe finished the walkthrough, for the bonus tip's delay. */
    private static long sFinishedAtMs = -1;

    private WalkthroughState() {}

    private static SharedPreferences prefs() {
        return ContextUtils.getAppSharedPreferences();
    }

    /**
     * The current step, deciding on the first call whether this install gets a walkthrough: a
     * first launch in India only.
     */
    public static String step() {
        String step = prefs().getString(KEY_STEP, null);
        if (step != null) return step;
        Context context = ContextUtils.getApplicationContext();
        boolean firstLaunch =
                SHOW_TO_EXISTING_INSTALLS || PackageUtils.isFirstInstall(context);
        // The same India test as the sign-in gate (device region IN or Asia/Kolkata time zone).
        boolean inIndia = BrowserExpressClaimUsernameUtil.shouldRequireManualAuth();
        boolean starts = firstLaunch && inIndia;
        step = starts ? STEP_SEARCH : STEP_DONE;
        // In release logs (cr_Walkthrough): an install updated over an older one never gets it,
        // nor one outside India.
        Log.i(TAG, "First read: fresh install %b, India %b, walkthrough %s", firstLaunch, inIndia,
                starts ? "starts" : "skipped");
        SharedPreferences.Editor editor = prefs().edit().putString(KEY_STEP, step);
        // An install that never had the walkthrough doesn't get its bonus tip either.
        if (!starts) editor.putBoolean(KEY_BONUS_TIP_SHOWN, true);
        editor.apply();
        return step;
    }

    static void setStep(String step) {
        if (step.equals(prefs().getString(KEY_STEP, null))) return;
        prefs().edit().putString(KEY_STEP, step).apply();
        sPickingPost = false;
    }

    /** One of the Home steps (search bar, scroll, featured post) is current. */
    public static boolean isOnHome() {
        String step = step();
        return STEP_SEARCH.equals(step) || STEP_SCROLL.equals(step) || STEP_POST.equals(step);
    }

    /**
     * The Home's other prompts (default browser, notifications, installed apps) wait while the
     * walkthrough is on the Home, so they never land on top of it.
     */
    public static boolean blocksNtpPrompts() {
        return isOnHome();
    }

    public static boolean isWaitingForVideo() {
        return STEP_VIDEO.equals(step());
    }

    /** The reader asked to pick a post themselves: the next post they open runs step 4. */
    public static boolean isPickingPost() {
        return sPickingPost && STEP_POST.equals(step());
    }

    static void setPickingPost(boolean picking) {
        sPickingPost = picking;
    }

    /**
     * A post's stream is opening: the featured post from "Dive in", or any post the reader picked
     * after "Pick another post". The video step takes over from here.
     */
    public static void onPostOpened(String how, @Nullable String postId) {
        if (!STEP_POST.equals(step())) return;
        sPickingPost = false;
        setStep(STEP_VIDEO);
        PostHogUtil.capture(
                PostHogEventKeys.WALKTHROUGH_ACTION,
                "step",
                STEP_POST,
                "action",
                how,
                "post_id",
                postId == null ? "" : postId);
    }

    /**
     * QA only (Settings › Developer options › QA Preferences): the walkthrough starts over as on
     * a fresh install, bonus tip included, the next time the Home is on screen. It skips the India
     * check, so testers anywhere can run it. Readers get no replay (README §5).
     */
    public static void replayForQa() {
        prefs().edit()
                .putString(KEY_STEP, STEP_SEARCH)
                .putBoolean(KEY_BONUS_TIP_SHOWN, false)
                .putInt(KEY_VIDEO_TAKES_SEEN, 0)
                .apply();
        sPickingPost = false;
        sFinishedAtMs = -1;
        Log.i(TAG, "Replay requested from QA Preferences");
    }

    static void finish() {
        setStep(STEP_DONE);
        sFinishedAtMs = SystemClock.elapsedRealtime();
    }

    /** {@link SystemClock#elapsedRealtime} of the finishing swipe in this process, else -1. */
    static long finishedAtMs() {
        return sFinishedAtMs;
    }

    static boolean isBonusTipShown() {
        return prefs().getBoolean(KEY_BONUS_TIP_SHOWN, false);
    }

    static void setBonusTipShown() {
        prefs().edit().putBoolean(KEY_BONUS_TIP_SHOWN, true).apply();
    }

    static int videoTakesSeen() {
        return prefs().getInt(KEY_VIDEO_TAKES_SEEN, 0);
    }

    /** Counts one more vertical-video take, up to {@link #BONUS_TIP_TAKE}, and returns the count. */
    static int countVideoTake() {
        int seen = videoTakesSeen();
        if (seen >= BONUS_TIP_TAKE) return seen;
        seen++;
        prefs().edit().putInt(KEY_VIDEO_TAKES_SEEN, seen).apply();
        return seen;
    }
}
