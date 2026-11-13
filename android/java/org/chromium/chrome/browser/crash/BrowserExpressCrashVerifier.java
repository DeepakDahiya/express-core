/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import android.os.Process;
import android.util.Log;

import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.build.annotations.NullMarked;

/** Internal-only helper for validating production crash capture routing. */
@JNINamespace("chrome::android")
@NullMarked
public final class BrowserExpressCrashVerifier {
    private static final String TAG = "BECrashVerifier";
    private static final String JAVA_CRASH_MARKER = "qa_java";
    private static final String NATIVE_CRASH_MARKER = "qa_native";
    private static final String APP_CRASH_MARKER = "qa_app";
    private static final int SIGNAL_ABORT = 6;

    private BrowserExpressCrashVerifier() {}

    public static void forceJavaCrash() {
        markCrash(JAVA_CRASH_MARKER);
        RuntimeException exception = new RuntimeException("BrowserExpressCrashVerifierJavaCrash");
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler != null) {
            handler.uncaughtException(Thread.currentThread(), exception);
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
        throw exception;
    }

    public static void forceNativeCrash() {
        markCrash(NATIVE_CRASH_MARKER);
        BrowserExpressCrashVerifierJni.get().forceNativeCrash();
    }

    public static void forceAppCrash() {
        Log.e(TAG, "Force app crash requested");
        markCrash(APP_CRASH_MARKER);
        Process.sendSignal(Process.myPid(), SIGNAL_ABORT);
        Process.killProcess(Process.myPid());
        System.exit(10);
        Runtime.getRuntime().halt(10);
    }

    private static void markCrash(String marker) {
        BrowserExpressCrashBreadcrumbs.recordEvent(marker);
    }

    @NativeMethods
    interface Natives {
        void forceNativeCrash();
    }
}
