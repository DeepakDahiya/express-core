package org.chromium.chrome.browser;

import android.os.Handler;
import android.os.Looper;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;

import org.chromium.content_public.browser.WebContents;

import java.lang.ref.WeakReference;

/**
 * A "dumb" bridge that receives calls from JavaScript and notifies a listener.
 * It has NO KNOWLEDGE of BraveActivity.
 */
@JNINamespace("youtube_script_injector")
public class WebAppInterface {

    // --- Listener Interface ---
    public interface GlobalPipListener {
        void enterGlobalPipMode(WebContents webContents);
    }
    private static WeakReference<GlobalPipListener> sListener = new WeakReference<>(null);
    public static void setListener(GlobalPipListener listener) {
        sListener = new WeakReference<>(listener);
    }
    // -------------------------

    private final WebContents mWebContents;

    private WebAppInterface(WebContents webContents) {
        mWebContents = webContents;
    }

    @CalledByNative
    private static WebAppInterface create(WebContents webContents) {
        return new WebAppInterface(webContents);
    }

    @android.webkit.JavascriptInterface
    public void enterGlobalPipMode() {
        // Notify the listener that the event occurred.
        final GlobalPipListener listener = sListener.get();
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                // Pass our WebContents so the listener knows which tab triggered the event.
                listener.enterGlobalPipMode(mWebContents);
            });
        }
    }
}