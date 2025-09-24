/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import java.lang.ref.WeakReference;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;
import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.content_public.browser.InterfaceRegistrar; // Import this

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

    @CalledByNative
    public static void setupJavaScriptInterface(WebContents webContents) {
        if (webContents != null) {
            InterfaceRegistrar.getRegistry(webContents.getMainFrame())
                .addInterface(new PiPTabRestorer(webContents), JAVASCRIPT_INTERFACE_NAME);
        }
    }

    private static class PiPTabRestorer {
        private final WeakReference<WebContents> mWebContentsRef;
        
        PiPTabRestorer(WebContents webContents) {
            mWebContentsRef = new WeakReference<>(webContents);
        }
        
        @JavascriptInterface
        public void restoreOriginalTab() {
            WebContents webContents = mWebContentsRef.get();
            if (webContents == null) return;

            WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
            if (windowAndroid == null) return;

            Activity activity = windowAndroid.getActivity().get();
            if (activity == null) return;
            
            activity.runOnUiThread(() -> {
                try {
                    // This logic to bring the activity to the front is good.
                    Intent intent = new Intent(activity, activity.getClass());
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    activity.startActivity(intent);
                    
                    // FIX 2: Request focus on the WebContents view itself.
                    webContents.getView().requestFocus();
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error restoring original tab", e);
                }
            });
        }
    }

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

    @NativeMethods
    interface Natives {
        void setFullscreen(WebContents webContents);
        boolean hasFullscreenBeenRequested(WebContents webContents);
        boolean isPictureInPictureAvailable(WebContents webContents);
        void setupJavaScriptInterface(WebContents webContents);
    }
}