/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_splash;

import android.app.Activity;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.chromium.base.Log;
import org.chromium.base.ObserverList;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The UGC splash (ugc-splash-handoff): shown each time the app starts, over the browser while it
 * starts up underneath.
 *
 * <p>It covers the whole window from the activity's creation. When it ends depends on the app
 * being ready: the Home (new tab page) on screen ({@link #onHomeShown}), or the tab the app
 * reopened to when that isn't the Home ({@link #onTabShown}). The splash plays to its finished
 * phrase and holds there until then, then fades into it. Whatever would otherwise come up over
 * the Home at launch (the walkthrough, the sign-in screen, the Home's prompts) waits for it to go.
 *
 * <p>Coming back to the app while it is still open (from the background or recents) is no launch,
 * and neither is the activity being recreated in a running app (rotation, a theme switch).
 */
public final class UgcSplash {
    private static final String TAG = "UgcSplash";

    /** The splash stops waiting for the app after this long and fades anyway. */
    private static final long READY_TIMEOUT_MS = 8000;

    /** Held weakly: a static view would hold its activity (StaticFieldLeak). */
    private static @Nullable WeakReference<UgcSplashView> sView;

    /** A browser activity was already created in this process. */
    private static boolean sLaunchedInProcess;

    private static final List<Runnable> sWhenGone = new ArrayList<>();
    private static final ObserverList<Runnable> sGoneObservers = new ObserverList<>();

    private UgcSplash() {}

    /**
     * Shows the splash over {@code activity} if it is being created for an app launch. Called as
     * the browser activity is created, so it covers the very first frame.
     *
     * @param restored The activity is being recreated from saved state. Within a running app that
     *     is a rotation or a theme switch, not a launch; in a new process it is the app reopening
     *     after the system closed it, which is.
     * @param plainLaunch False when the launch opens something (a link, a shared take): that goes
     *     straight through, without the splash.
     */
    public static void maybeShowOnLaunch(Activity activity, boolean restored, boolean plainLaunch) {
        boolean newProcess = !sLaunchedInProcess;
        sLaunchedInProcess = true;
        if (current() != null) return;
        if (restored && !newProcess) return;
        if (!plainLaunch) {
            Log.i(TAG, "Launch opens content; no splash");
            return;
        }
        Log.i(TAG, "App launch: showing");
        show(activity, /* preview= */ false);
    }

    /**
     * QA only (Settings › Developer options › QA Preferences): plays the splash now over
     * {@code activity}, as if the app were already ready.
     */
    public static void playForQa(Activity activity) {
        if (current() != null) return;
        Log.i(TAG, "Replay requested from QA Preferences");
        show(activity, /* preview= */ true);
    }

    /** The splash is on screen (playing, holding or fading). */
    public static boolean isShowing() {
        return current() != null;
    }

    /** The Home is on screen under the splash, so it can fade into it. */
    public static void onHomeShown() {
        setReady();
    }

    /**
     * The browser is up on a tab that isn't the Home (the app reopened where the reader left it),
     * so the splash can fade into it.
     */
    public static void onTabShown() {
        setReady();
    }

    /**
     * Runs {@code action} once the splash has gone, if it is showing.
     *
     * @return Whether it was held for later. False means the splash isn't showing: run it now.
     */
    public static boolean runWhenGone(Runnable action) {
        if (current() == null) return false;
        sWhenGone.add(action);
        return true;
    }

    /** Called each time a splash goes, while added. */
    public static void addGoneObserver(Runnable observer) {
        sGoneObservers.addObserver(observer);
    }

    public static void removeGoneObserver(Runnable observer) {
        sGoneObservers.removeObserver(observer);
    }

    private static @Nullable UgcSplashView current() {
        return sView == null ? null : sView.get();
    }

    private static void setReady() {
        UgcSplashView view = current();
        if (view != null) view.setReady();
    }

    private static void show(Activity activity, boolean preview) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        UgcSplashView view =
                new UgcSplashView(activity, windowGone -> onGone(decor, windowGone));
        sView = new WeakReference<>(view);
        decor.addView(
                view,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (preview) {
            view.setReady();
        } else {
            view.postDelayed(view::setReady, READY_TIMEOUT_MS);
        }
    }

    private static void onGone(ViewGroup decor, boolean windowGone) {
        UgcSplashView view = current();
        if (view == null) return;
        sView = null;
        if (!windowGone && view.getParent() == decor) decor.removeView(view);

        List<Runnable> held = new ArrayList<>(sWhenGone);
        sWhenGone.clear();
        for (Runnable action : held) action.run();
        for (Runnable observer : sGoneObservers) observer.run();
    }
}
