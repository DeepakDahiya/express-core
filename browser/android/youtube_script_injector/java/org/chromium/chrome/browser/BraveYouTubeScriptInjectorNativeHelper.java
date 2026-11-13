/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.webkit.JavascriptInterface;
import java.lang.ref.WeakReference;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.content_public.browser.JavascriptInjector;

@JNINamespace("youtube_script_injector")
@NullMarked
public class BraveYouTubeScriptInjectorNativeHelper {
    private static final String TAG = "YouTubeNativeHelper";
    private static final String PIP_DEBUG_TAG = "BravePiPDebug";
    private static final String JAVASCRIPT_INTERFACE_NAME = "BravePiPNavigator";

    public static void setFullscreen(WebContents webContents) {
        BraveYouTubeScriptInjectorNativeHelperJni.get().setFullscreen(webContents);
    }
    public static void triggerYouTubePiP(WebContents webContents) {
        Log.e(
                PIP_DEBUG_TAG,
                "BraveYouTubeScriptInjectorNativeHelper.triggerYouTubePiP webContents="
                        + webContents);
        BraveYouTubeScriptInjectorNativeHelperJni.get().triggerYouTubePiP(webContents);
    }
    public static boolean hasFullscreenBeenRequested(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get().hasFullscreenBeenRequested(webContents);
    }
    public static boolean isPictureInPictureAvailable(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get().isPictureInPictureAvailable(webContents);
    }
    @CalledByNative
    public static void enterPictureInPicture(WebContents webContents) {
        Log.e(
                PIP_DEBUG_TAG,
                "BraveYouTubeScriptInjectorNativeHelper.enterPictureInPicture webContents="
                        + webContents);
        MediaSession mediaSession = MediaSession.fromWebContents(webContents);
        if (mediaSession != null) {
            Log.e(PIP_DEBUG_TAG, "enterPictureInPicture resume media session");
            mediaSession.resume();
        } else {
            Log.e(PIP_DEBUG_TAG, "enterPictureInPicture no media session");
        }
        final WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
        if (windowAndroid != null) {
            final Activity activity = windowAndroid.getActivity().get();
            Log.e(
                    PIP_DEBUG_TAG,
                    "enterPictureInPicture activity="
                            + (activity != null ? activity.getClass().getName() : "null"));
            if (activity != null) {
                try {
                    boolean entered =
                            activity.enterPictureInPictureMode(
                                    new PictureInPictureParams.Builder().build());
                    Log.e(PIP_DEBUG_TAG, "enterPictureInPicture framework result=" + entered);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Log.e(PIP_DEBUG_TAG, "enterPictureInPicture framework error: " + e.getMessage());
                    Log.e(TAG, "Error entering picture in picture mode.", e);
                }
            }
        } else {
            Log.e(PIP_DEBUG_TAG, "enterPictureInPicture no WindowAndroid");
        }
    }

    public static void setupJavaScriptInterface(WebContents webContents, TabModelSelector selector) {
        Log.e(TAG, "Setting up JavaScript interface for PiP tab restoration");
        if (webContents == null || selector == null) return;

        Log.e(TAG, "WebContents and TabModelSelector are valid");

        JavascriptInjector injector = JavascriptInjector.fromWebContents(webContents);
        if (injector != null) {
            Log.e(TAG, "JavascriptInjector is valid, adding interface");
            injector.addPossiblyUnsafeInterface(
                new PiPTabRestorer(webContents, selector),
                JAVASCRIPT_INTERFACE_NAME,
                JavascriptInterface.class);
        }
    }

    private static class PiPTabRestorer {
        private static final String TAG = "YouTubeNativeHelper";
        // Fallback only. Must match BraveActivity.PIP_ORIGIN_TAB_ID_PREF. We do
        // NOT rely on this for the expand/fullscreen button: that path runs the
        // native PiP-exit code (onPictureInPictureModeChanged → restorePipOriginTab
        // → clearPipRestoreState), which WIPES this pref before our JS bridge
        // reads it, so a pref read would no-op and leave the user on the home
        // tab. We resolve the source tab from the hosting WebContents instead.
        private static final String PIP_ORIGIN_TAB_ID_PREF = "brave_pip_origin_tab_id";
        // Android may resurface the browser on the home tab a beat after PiP is
        // dismissed; a short idempotent retry ladder lets the source tab win.
        private static final long[] RESTORE_RETRY_DELAYS_MS = {0L, 250L, 600L, 1200L};
        private final WeakReference<TabModelSelector> mTabModelSelectorRef;
        // The WebContents this bridge instance was injected into. That document
        // is the one whose leavepictureinpicture fires on PiP exit, i.e. the
        // source/watch tab — independent of the (clearable) origin-tab pref.
        private final WeakReference<WebContents> mSourceWebContentsRef;

        PiPTabRestorer(WebContents sourceWebContents, TabModelSelector selector) {
            mSourceWebContentsRef = new WeakReference<>(sourceWebContents);
            mTabModelSelectorRef = new WeakReference<>(selector);
        }

        // Called from the source tab's leavepictureinpicture handler (see
        // kYoutubePipNavigationFix) when the user dismisses PiP via the expand,
        // fullscreen or close button. Selects the tab that hosted the PiP video,
        // which Android leaves in the background after tearing down the overlay.
        @JavascriptInterface
        public void restorePipOriginTab() {
            final TabModelSelector selector = mTabModelSelectorRef.get();
            if (selector == null) {
                Log.e(TAG, "restorePipOriginTab: no TabModelSelector");
                return;
            }
            final WebContents sourceWebContents = mSourceWebContentsRef.get();
            // Tab-model access must happen on the UI thread.
            ThreadUtils.runOnUiThread(
                    () -> {
                        int resolvedId = findTabIdForWebContents(selector, sourceWebContents);
                        if (resolvedId == Tab.INVALID_TAB_ID) {
                            // Fallback for the close path / if the WebContents
                            // lookup misses; the pref is still set there.
                            resolvedId =
                                    ContextUtils.getAppSharedPreferences()
                                            .getInt(
                                                    PIP_ORIGIN_TAB_ID_PREF,
                                                    Tab.INVALID_TAB_ID);
                        }
                        Log.e(TAG, "restorePipOriginTab bridge resolvedTabId=" + resolvedId);
                        if (resolvedId == Tab.INVALID_TAB_ID) return;

                        final int targetTabId = resolvedId;
                        for (long delayMs : RESTORE_RETRY_DELAYS_MS) {
                            ThreadUtils.postOnUiThreadDelayed(
                                    () -> selectTabById(selector, targetTabId), delayMs);
                        }
                    });
        }

        private static int findTabIdForWebContents(
                TabModelSelector selector, @Nullable WebContents webContents) {
            // The caller reads through a WeakReference, so null is expected once
            // the source tab's WebContents has been collected.
            if (webContents == null) return Tab.INVALID_TAB_ID;
            try {
                for (int i = 0; i < 2; i++) {
                    TabModel model = selector.getModel(i == 1);
                    if (model == null) continue;
                    for (int j = 0; j < model.getCount(); j++) {
                        Tab tab = model.getTabAt(j);
                        if (tab != null && tab.getWebContents() == webContents) {
                            return tab.getId();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "findTabIdForWebContents error: " + e.getMessage());
            }
            return Tab.INVALID_TAB_ID;
        }

        private static void selectTabById(TabModelSelector selector, int tabId) {
            try {
                Tab current = selector.getCurrentTab();
                if (current != null && current.getId() == tabId) return;
                // Don't pull the user back to the PiP origin tab if they have moved
                // on to a DIFFERENT YouTube video (a new video superseding the PiP,
                // e.g. tapped from the home feed). Brave's background playback can
                // keep the old video "playing", so the JS playback-state gate is
                // not enough on its own; gate on the current page being another
                // watch page here too.
                if (current != null && isYouTubeWatchUrl(current.getUrl().getSpec())) {
                    Log.e(TAG, "selectTabById skip; user on a different YouTube watch page");
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    TabModel model = selector.getModel(i == 1);
                    if (model == null) continue;
                    for (int j = 0; j < model.getCount(); j++) {
                        Tab tab = model.getTabAt(j);
                        if (tab != null && tab.getId() == tabId) {
                            Log.e(TAG, "restorePipOriginTab selecting tabId=" + tabId);
                            model.setIndex(j, TabSelectionType.FROM_USER);
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "restorePipOriginTab selectTabById error: " + e.getMessage());
            }
        }

        private static boolean isYouTubeWatchUrl(String url) {
            return url != null
                    && (url.contains("youtube.com/watch")
                            || url.contains("youtube.com/shorts/"));
        }

        @JavascriptInterface
        public void restoreTabWithUrl(String url) {
            Log.e(TAG, "Request to restore tab with URL: " + url);
            final TabModelSelector selector = mTabModelSelectorRef.get();
            Log.e(TAG, "TabModelSelector retrieved: " + (selector != null ? "valid" : "null"));
            if (url == null || url.isEmpty() || selector == null) return;

            ThreadUtils.runOnUiThread(() -> {
                Log.e(TAG, "Request to focus tab with URL: " + url);
                for (int i = 0; i < 2; i++) {
                    Log.e(TAG, "Checking model for tab restoration");
                    TabModel model = selector.getModel(i == 1);
                    Log.e(TAG, "TabModel retrieved: " + (model != null ? "valid" : "null"));
                    if (model == null) continue;
                    Log.e(TAG, "Searching for tab with URL: " + url);
                    for (int j = 0; j < model.getCount(); j++) {
                        Tab tab = model.getTabAt(j);
                        Log.e(TAG, "Checking tab at index " + j + ": " + (tab != null ? tab.getUrl().getSpec() : "null"));
                        if (tab != null && tab.getUrl().getSpec().equals(url)) {
                            Log.e(TAG, "Found matching tab at index " + j + ", selecting it");
                            model.setIndex(j, TabSelectionType.FROM_USER);
                            return;
                        }
                    }
                }
                Log.w(TAG, "No open tab found with URL: " + url);
            });
        }
    }

    @NativeMethods
    interface Natives {
        void setFullscreen(WebContents webContents);
        void triggerYouTubePiP(WebContents webContents);
        boolean hasFullscreenBeenRequested(WebContents webContents);
        boolean isPictureInPictureAvailable(WebContents webContents);
    }
}
