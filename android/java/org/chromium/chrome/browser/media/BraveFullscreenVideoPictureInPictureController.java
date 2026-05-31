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
        if (reason == 8 /*MetricsEndReason.START*/ || reason == 0 /*MetricsEndReason.RESUME*/) {
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
    }
}
