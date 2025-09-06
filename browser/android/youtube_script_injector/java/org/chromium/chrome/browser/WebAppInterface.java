package org.chromium.chrome.browser;

import android.os.Handler;
import android.os.Looper;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;

import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.content_public.browser.WebContents;

/**
 * This class is the bridge between the in-page JavaScript and the native browser.
 * An instance of this class is created from the C++ layer (YouTubeScriptInjectorTabHelper).
 */
@JNINamespace("youtube_script_injector")
public class WebAppInterface {
    
    private final WebContents mWebContents;

    private WebAppInterface(WebContents webContents) {
        mWebContents = webContents;
    }

    // This is called from C++ to create an instance of this class.
    @CalledByNative
    private static WebAppInterface create(WebContents webContents) {
        return new WebAppInterface(webContents);
    }

    @android.webkit.JavascriptInterface
    public void enterGlobalPipMode() {
        try{
            final BraveActivity activity = BraveActivity.getBraveActivity();
            if (activity != null) {
                // Post to the UI thread to ensure we are interacting with Views correctly.
                new Handler(Looper.getMainLooper()).post(() -> {
                    activity.showGlobalPip(activity.getActivityTab());
                });
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }
}