/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.media;

import android.app.Activity;

import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.Log;

public class BraveFullscreenVideoPictureInPictureController {
    private static final String PIP_DEBUG_TAG = "BravePiPDebug";
    private static final int METRICS_END_REASON_RESUME = 0;
    private static final int METRICS_END_REASON_LEFT_FULLSCREEN = 6;
    private static final int METRICS_END_REASON_WEB_CONTENTS_LEFT_FULLSCREEN = 7;
    private static final int METRICS_END_REASON_START = 8;

    /**
     * This variable will be used instead of {@link FullscreenVideoPictureInPictureController}'s
     * variable, that will be deleted in bytecode.
     */
    protected boolean mDismissPending;

    void dismissActivityIfNeeded(Activity activity, /*MetricsEndReason*/ int reason) {
        Log.e(
                PIP_DEBUG_TAG,
                "BraveFullscreenVideoPiP.dismissActivityIfNeeded reason="
                        + reason
                        + " activity="
                        + (activity != null ? activity.getClass().getName() : "null"));
        if (shouldKeepPipActivity(activity, reason)) {
            mDismissPending = false;
            Log.e(
                    PIP_DEBUG_TAG,
                    "BraveFullscreenVideoPiP swallowing dismiss reason=" + reason);
            return;
        }
        Log.e(
                PIP_DEBUG_TAG,
                "BraveFullscreenVideoPiP delegating dismiss reason=" + reason);
        BraveReflectionUtil.invokeMethod(
                FullscreenVideoPictureInPictureController.class,
                this,
                "dismissActivityIfNeeded",
                Activity.class,
                activity,
                int.class,
                reason);
        // NOTE: do NOT restore the PiP origin tab here. This callback also
        // fires while PiP is ENTERING (opening the home tab surfaces as
        // reason=NEW_TAB), so restoring here yanked the user back to the watch
        // tab mid-enter. The watch-tab restore is now owned solely by
        // BraveActivity#consumePendingPipRestore on the next foreground.
    }

    private static boolean shouldKeepPipActivity(Activity activity, int reason) {
        if (reason == METRICS_END_REASON_START || reason == METRICS_END_REASON_RESUME) {
            return true;
        }

        if (activity == null || !activity.isInPictureInPictureMode()) {
            return false;
        }

        // Pausing a YouTube video from the Android PiP controls can make the
        // WebContents stop reporting an effectively fullscreen video. Chromium
        // treats that as a reason to dismiss PiP, which races native overlay
        // teardown with the media-session pause action. Keep the Android PiP
        // activity alive; explicit close/crash/reparent/new-tab reasons still
        // go through the original dismissal path.
        return reason == METRICS_END_REASON_LEFT_FULLSCREEN
                || reason == METRICS_END_REASON_WEB_CONTENTS_LEFT_FULLSCREEN;
    }
}
