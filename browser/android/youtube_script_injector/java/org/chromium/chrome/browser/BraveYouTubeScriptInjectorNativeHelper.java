/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser;

import android.app.Activity;
import android.app.PictureInPictureParams;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;
import android.content.Intent;
import android.webkit.JavascriptInterface; 
import java.lang.ref.WeakReference;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

/**
 * Helper to interact with native methods. Check brave_youtube_script_injector_native_helper.{h|cc}.
 */
@JNINamespace("youtube_script_injector")
@NullMarked
public class BraveYouTubeScriptInjectorNativeHelper {
    private static final String TAG = "YouTubeNativeHelper";
    private static final String JAVASCRIPT_INTERFACE_NAME = "Android";

    public static void setFullscreen(WebContents webContents) {
        BraveYouTubeScriptInjectorNativeHelperJni.get().setFullscreen(webContents);
    }

    public static boolean hasFullscreenBeenRequested(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get()
                .hasFullscreenBeenRequested(webContents);
    }

    public static boolean isPictureInPictureAvailable(WebContents webContents) {
        return BraveYouTubeScriptInjectorNativeHelperJni.get()
                .isPictureInPictureAvailable(webContents);
    }

    public static void setupJavaScriptInterface(WebContents webContents) {
        if (webContents != null && webContents.getTopLevelNativeWindow() != null) {
            // Add JavaScript interface to handle tab restoration
            webContents.addJavaScriptInterface(new PiPTabRestorer(webContents), JAVASCRIPT_INTERFACE_NAME);
        }
    }

    private static class PiPTabRestorer {
        private final WeakReference<WebContents> mWebContentsRef;
        
        public PiPTabRestorer(WebContents webContents) {
            mWebContentsRef = new WeakReference<>(webContents);
        }
        
        @android.webkit.JavascriptInterface
        public void restoreOriginalTab() {
            WebContents webContents = mWebContentsRef.get();
            if (webContents != null) {
                WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
                if (windowAndroid != null) {
                    Activity activity = windowAndroid.getActivity().get();
                    if (activity != null) {
                        // For Android 15+, we need to ensure the task is brought to foreground
                        activity.runOnUiThread(() -> {
                            try {
                                // Move task to front
                                activity.moveTaskToBack(false);
                                
                                // Then bring it back to ensure proper focus
                                Intent intent = new Intent(activity, activity.getClass());
                                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                activity.startActivity(intent);
                                
                                // Focus the web contents
                                if (webContents.getTopLevelNativeWindow() != null) {
                                    webContents.getTopLevelNativeWindow().requestFeature(WindowAndroid.FEATURE_REQUEST_FOCUS);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error restoring original tab", e);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * @noinspection unused
     */
    @CalledByNative
    public static void enterPictureInPicture(WebContents webContents) {
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

    /**
     * @noinspection unused
     */
    @NativeMethods
    interface Natives {
        void setFullscreen(WebContents webContents);

        boolean hasFullscreenBeenRequested(WebContents webContents);

        boolean isPictureInPictureAvailable(WebContents webContents);

        void setupJavaScriptInterface(WebContents webContents);
    }
}
