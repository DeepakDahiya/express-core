/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.media;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.res.Configuration;
import android.os.PowerManager;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.Log;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.init.AsyncInitializationActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabUtils;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.MediaSessionObserver;
import org.chromium.content_public.browser.WebContents;

public abstract class BravePictureInPictureActivity extends AsyncInitializationActivity {
    private static final String TAG = "BravePictureInPicture";
    private static final String PIP_DEBUG_TAG = "BravePiPDebug";

    // True once this overlay activity has actually been in Android PiP mode.
    private boolean mEnteredPip;
    // True once an exit (expand or close) has been handled, so the
    // onResume-based expand hook and onPictureInPictureModeChanged(false)
    // never both run the restore.
    private boolean mExitHandled;

    // Holds the display awake while the PiP video is actually playing.
    //
    // BraveActivity keeps the screen on only while IT is resumed; entering PiP
    // pauses it and clears that flag, and this overlay activity is the only
    // window left on screen. With nothing holding the display, the phone hits
    // its screen timeout and auto-locks, Android stops this activity, and
    // OverlayWindowAndroid::OnActivityStopped tears the window down through
    // Close(should_pause_video=true) -- which is exactly the reported
    // "my phone locked and the video stopped" bug.
    //
    // Gated on the media session rather than on "PiP is open" so a PiP window
    // the user left PAUSED still lets the display sleep normally instead of
    // burning the battery until the phone dies.
    private MediaSessionObserver mKeepScreenOnObserver;
    private boolean mKeepScreenOnFlagSet;

    @Override
    public void onResume() {
        super.onResume();
        boolean inPip = isInPictureInPictureMode();
        Log.e(
                PIP_DEBUG_TAG,
                "BravePictureInPictureActivity.onResume inPip="
                        + inPip
                        + " enteredPip="
                        + mEnteredPip
                        + " exitHandled="
                        + mExitHandled
                        + " finishing="
                        + isFinishing());
        // A pipped activity sits in the paused state; being RESUMED again while
        // no longer in PiP mode means the user tapped EXPAND and the system
        // brought this activity fullscreen. On Android 16 this is the ONLY
        // reliable expand signal: onPictureInPictureModeChanged(false) is not
        // delivered there, so without this hook the teardown falls through to
        // OverlayWindowAndroid::OnActivityStopped -> Close(should_pause_video
        // =true) and the expand becomes indistinguishable from a close (video
        // paused, no tab restore). Restore the watch tab here and finish; the
        // normal stop-path teardown then cleans up the native side.
        //
        // Deliberately no isFinishing() guard: when the initiator activity is
        // already foreground, Android 16 can deliver this resume as part of a
        // return-to-app transition with the activity already finishing. A
        // genuine close never resumes the activity at all, so acting here is
        // safe even mid-finish.
        if (!mEnteredPip || inPip || mExitHandled) return;
        try {
            Log.e(PIP_DEBUG_TAG, "onResume expand detected; restoring origin tab");
            mExitHandled = true;
            stopKeepScreenOnWhilePlaying();
            restoreInitiatorTabBeforeExit();
            finish();
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "onResume expand restore error: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Belt-and-suspenders in case onPictureInPictureModeChanged(true) is
        // ever skipped: while pipped the activity is paused-but-visible.
        if (isInPictureInPictureMode()) {
            mEnteredPip = true;
            startKeepScreenOnWhilePlaying();
        }
        Log.e(
                PIP_DEBUG_TAG,
                "BravePictureInPictureActivity.onPause inPip="
                        + isInPictureInPictureMode()
                        + " finishing="
                        + isFinishing());
    }

    @Override
    public void onStop() {
        final boolean screenOn = isScreenInteractive();
        Log.e(
                PIP_DEBUG_TAG,
                "BravePictureInPictureActivity.onStop inPip="
                        + isInPictureInPictureMode()
                        + " enteredPip="
                        + mEnteredPip
                        + " exitHandled="
                        + mExitHandled
                        + " finishing="
                        + isFinishing()
                        + " screenOn="
                        + screenOn);
        // Last-resort exit hook, for EXPAND while the browser is already in the
        // foreground. In that case the system neither delivers
        // onPictureInPictureModeChanged(false) nor RESUMES this activity (it is
        // android:noHistory, so it is simply stopped and finished), so both of
        // the hooks above are skipped and the exit falls all the way through to
        // OverlayWindowAndroid::OnActivityStopped -- which tears the overlay
        // down without ever restoring the watch tab. That is the "expand while
        // the app is open just closes PiP and leaves me on the wrong tab" bug;
        // expanding from the background works only because the activity really
        // does resume there.
        //
        // Restoring here matches what every other exit path already does
        // (onPictureInPictureModeChanged -> restoreInitiatorTabBeforeExit,
        // upstream's onBackToTab -> FocusInitiator), and restoreToTab still
        // declines to yank the user off a DIFFERENT YouTube watch page.
        //
        // Skipped while the screen is off: a lock stops this activity too, and
        // there the user has asked for nothing -- playback simply continues in
        // the background (see OnActivityStopped, which no longer pauses it).
        if (mEnteredPip && !mExitHandled && screenOn) {
            mExitHandled = true;
            stopKeepScreenOnWhilePlaying();
            Log.e(PIP_DEBUG_TAG, "onStop fallback exit; restoring origin tab");
            restoreInitiatorTabBeforeExit();
        }
        super.onStop();
    }

    /**
     * False when the display is off, i.e. this stop came from the phone locking
     * rather than from the user dismissing or expanding the PiP window.
     */
    private boolean isScreenInteractive() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager == null || powerManager.isInteractive();
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "isScreenInteractive error: " + e.getMessage());
            return true;
        }
    }

    @Override
    public void onDestroy() {
        Log.e(
                PIP_DEBUG_TAG,
                "BravePictureInPictureActivity.onDestroy exitHandled="
                        + mExitHandled
                        + " finishing="
                        + isFinishing());
        stopKeepScreenOnWhilePlaying();
        super.onDestroy();
    }

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
                mEnteredPip = true;
                startKeepScreenOnWhilePlaying();
                notifyInitiatorTabEnteredPip();
            } else if (!mExitHandled) {
                mExitHandled = true;
                stopKeepScreenOnWhilePlaying();
                restoreInitiatorTabBeforeExit();
            } else {
                stopKeepScreenOnWhilePlaying();
                Log.e(PIP_DEBUG_TAG, "exit already handled by onResume hook; skipping restore");
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

    /**
     * Starts holding the display awake for as long as the PiP video is playing.
     * Safe to call repeatedly; the observer is attached only once.
     */
    private void startKeepScreenOnWhilePlaying() {
        if (mKeepScreenOnObserver != null) return;
        try {
            Tab initiatorTab = getInitiatorTab();
            WebContents webContents =
                    initiatorTab != null ? initiatorTab.getWebContents() : null;
            if (webContents == null) {
                Log.e(PIP_DEBUG_TAG, "startKeepScreenOnWhilePlaying no WebContents");
                return;
            }
            MediaSession mediaSession = MediaSession.fromWebContents(webContents);
            if (mediaSession == null) {
                Log.e(PIP_DEBUG_TAG, "startKeepScreenOnWhilePlaying no MediaSession");
                return;
            }
            // PiP is always entered from a playing video, so assume playing and
            // let the session correct us. MediaSessionImpl#addObserver replays
            // the current state synchronously from the constructor below when it
            // has one, so this is only the fallback for "state not published
            // yet".
            applyKeepScreenOn(true);
            mKeepScreenOnObserver =
                    new MediaSessionObserver(mediaSession) {
                        @Override
                        public void mediaSessionStateChanged(
                                boolean isControllable, boolean isPaused) {
                            applyKeepScreenOn(isControllable && !isPaused);
                        }

                        @Override
                        public void mediaSessionDestroyed() {
                            applyKeepScreenOn(false);
                        }
                    };
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "startKeepScreenOnWhilePlaying error: " + e.getMessage());
        }
    }

    /** Detaches the media-session observer and lets the display sleep again. */
    private void stopKeepScreenOnWhilePlaying() {
        if (mKeepScreenOnObserver != null) {
            try {
                mKeepScreenOnObserver.stopObserving();
            } catch (Exception e) {
                Log.e(PIP_DEBUG_TAG, "stopKeepScreenOnWhilePlaying error: " + e.getMessage());
            }
            mKeepScreenOnObserver = null;
        }
        applyKeepScreenOn(false);
    }

    private void applyKeepScreenOn(boolean keepOn) {
        if (mKeepScreenOnFlagSet == keepOn) return;
        mKeepScreenOnFlagSet = keepOn;
        try {
            if (keepOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            Log.e(PIP_DEBUG_TAG, "PiP keepScreenOn=" + keepOn);
        } catch (Exception e) {
            Log.e(PIP_DEBUG_TAG, "applyKeepScreenOn error: " + e.getMessage());
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
