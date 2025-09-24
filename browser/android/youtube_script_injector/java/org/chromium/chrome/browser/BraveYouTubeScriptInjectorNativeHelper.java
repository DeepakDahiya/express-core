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
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabSelectionType;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

@JNINamespace("youtube_script_injector")
@NullMarked
public class BraveYouTubeScriptInjectorNativeHelper {
    private static final String TAG = "YouTubeNativeHelper";
    private static final String JAVASCRIPT_INTERFACE_NAME = "BravePiPNavigator";

    public static void setFullscreen(WebContents webContents) {
        BraveYouTubeScriptInjectorNativeHelperJni.get().setFullscreen(webContents);
    }
    public static boolean hasFullscreenBeenRequested(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get().hasFullscreenBeenRequested(webContents);
    }
    public static boolean isPictureInPictureAvailable(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get().isPictureInPictureAvailable(webContents);
    }
    @CalledByNative
    public static void enterPictureInPicture(WebContents webContents) {
        // ... (your existing implementation is fine)
        MediaSession mediaSession = MediaSession.fromWebContents(webContents);
        if (mediaSession != null) {
            mediaSession.resume();
        }
        final WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
        if (windowAndroid != null) {
            final Activity activity = windowAndroid.getActivity().get();
            if (activity != null) {
                try {
                    activity.enterPictureInPictureMode(
                            new PictureInPictureParams.Builder().build());
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Log.e(TAG, "Error entering picture in picture mode.", e);
                }
            }
        }
    }
    // --- End of unchanged methods ---

    @CalledByNative
    public static void setupJavaScriptInterface(WebContents webContents) {
        if (webContents == null) return;
        
        WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
        if (windowAndroid == null) return;

        Activity activity = windowAndroid.getActivity().get();
        // We need the activity to get the TabModelSelector.
        if (!(activity instanceof BraveActivity)) {
            return;
        }

        BraveActivity chromeActivity = (BraveActivity) activity;
        TabModelSelector selector = chromeActivity.getTabModelSelector();
        if (selector == null) return;
        
        // This is the correct, classic way to add a JS bridge.
        webContents.addJavascriptInterface(
            new PiPTabRestorer(selector), JAVASCRIPT_INTERFACE_NAME);
    }

    private static class PiPTabRestorer {
        private final WeakReference<TabModelSelector> mTabModelSelectorRef;
        
        PiPTabRestorer(TabModelSelector selector) {
            mTabModelSelectorRef = new WeakReference<>(selector);
        }
        
        @JavascriptInterface
        public void restoreTabWithUrl(String url) {
            final TabModelSelector selector = mTabModelSelectorRef.get();
            if (url == null || url.isEmpty() || selector == null) {
                return;
            }

            ThreadUtils.runOnUiThread(() -> {
                Log.d(TAG, "Request to focus tab with URL: " + url);

                // Search both regular and incognito tabs.
                for (int i = 0; i < 2; i++) {
                    TabModel model = selector.getModel(i == 1); // i=0 normal, i=1 incognito
                    if (model == null) continue;

                    for (int j = 0; j < model.getCount(); j++) {
                        Tab tab = model.getTabAt(j);
                        if (tab != null && tab.getUrl().getSpec().equals(url)) {
                            Log.d(TAG, "Found matching tab at index " + j + ". Switching now.");
                            model.setIndex(j, TabSelectionType.FROM_USER);
                            
                            // Also request focus on the container view.
                            WebContents wc = tab.getWebContents();
                            if (wc != null && wc.getContainerView() != null) {
                                wc.getContainerView().requestFocus();
                            }
                            return; // Success
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
        boolean hasFullscreenBeenRequested(WebContents webContents);
        boolean isPictureInPictureAvailable(WebContents webContents);
        void setupJavaScriptInterface(WebContents webContents);
    }
}