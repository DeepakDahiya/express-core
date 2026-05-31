/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.media;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.Log;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.init.AsyncInitializationActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabUtils;

public abstract class BravePictureInPictureActivity extends AsyncInitializationActivity {
    private static final String TAG = "BravePictureInPicture";
    private static final String PIP_DEBUG_TAG = "BravePiPDebug";

    @Override
    public void onPictureInPictureModeChanged(
            boolean isInPictureInPictureMode, Configuration newConfig) {
        try {
            Log.e(
                    PIP_DEBUG_TAG,
                    "BravePictureInPictureActivity.onPictureInPictureModeChanged inPip="
                            + isInPictureInPictureMode
                            + " finishing="
                            + isFinishing()
                            + " destroyed="
                            + isDestroyed());
            if (isInPictureInPictureMode) {
                notifyInitiatorTabEnteredPip();
            } else {
                restoreInitiatorTabBeforeExit();
            }
            Log.e(
                    PIP_DEBUG_TAG,
                    "BravePictureInPictureActivity.onPictureInPictureModeChanged beforeSuper inPip="
                            + isInPictureInPictureMode);
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
            Log.e(
                    PIP_DEBUG_TAG,
                    "BravePictureInPictureActivity.onPictureInPictureModeChanged afterSuper inPip="
                            + isInPictureInPictureMode
                            + " finishing="
                            + isFinishing()
                            + " destroyed="
                            + isDestroyed());
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "BravePictureInPictureActivity.onPictureInPictureModeChanged error: " + e.getMessage());
            Log.e(TAG, "BravePictureInPictureActivity.onPictureInPictureModeChanged error: " + e.getMessage());
        }
    }

    @Override
    public void setPictureInPictureParams(@NonNull PictureInPictureParams params) {
        try {
            super.setPictureInPictureParams(params);
        } catch (IllegalStateException ignored) {
            // TODO(sergz): It looks like the call has been made when
            // the Java environment or the application is not in an
            // appropriate state for the requested operation.
            // We can just ignore it.
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void restoreInitiatorTabBeforeExit() {
        try {
            Tab initiatorTab = getInitiatorTab();
            Log.e(
                    PIP_DEBUG_TAG,
                    "restoreInitiatorTabBeforeExit initiatorTab="
                            + initiatorTab
                            + " class="
                            + (initiatorTab != null
                                    ? initiatorTab.getClass().getName()
                                    : "null"));
            if (initiatorTab == null) {
                Log.e(PIP_DEBUG_TAG, "restoreInitiatorTabBeforeExit no Tab instance");
                return;
            }

            Activity activity = TabUtils.getActivity(initiatorTab);
            Log.e(
                    PIP_DEBUG_TAG,
                    "restoreInitiatorTabBeforeExit tabId="
                            + initiatorTab.getId()
                            + " url="
                            + initiatorTab.getUrl().getSpec()
                            + " activity="
                            + (activity != null ? activity.getClass().getName() : "null"));

            if (activity instanceof BraveActivity) {
                ((BraveActivity) activity).restorePipOriginTab(initiatorTab);
            } else {
                Log.e(PIP_DEBUG_TAG, "restoreInitiatorTabBeforeExit activity is not BraveActivity");
            }
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "restoreInitiatorTabBeforeExit error: " + e.getMessage());
            Log.e(TAG, "restoreInitiatorTabBeforeExit error: " + e.getMessage());
        }
    }

    private void notifyInitiatorTabEnteredPip() {
        try {
            Tab initiatorTab = getInitiatorTab();
            if (initiatorTab == null) {
                Log.e(PIP_DEBUG_TAG, "notifyInitiatorTabEnteredPip no Tab instance");
                return;
            }
            Activity activity = TabUtils.getActivity(initiatorTab);
            if (activity instanceof BraveActivity) {
                ((BraveActivity) activity).onPipEnteredForOriginTab(initiatorTab);
            }
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "notifyInitiatorTabEnteredPip error: " + e.getMessage());
            Log.e(TAG, "notifyInitiatorTabEnteredPip error: " + e.getMessage());
        }
    }

    private Tab getInitiatorTab() {
        Object initiatorTab =
                BraveReflectionUtil.getField(PictureInPictureActivity.class, "mInitiatorTab", this);
        return initiatorTab instanceof Tab ? (Tab) initiatorTab : null;
    }
}
