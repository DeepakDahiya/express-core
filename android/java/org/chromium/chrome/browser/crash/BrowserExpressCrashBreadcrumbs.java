/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.crash.CrashKeyIndex;
import org.chromium.components.crash.CrashKeys;

import java.util.ArrayDeque;
import java.util.List;

/** Privacy-safe crash breadcrumbs owned by Browser Express. */
@NullMarked
public final class BrowserExpressCrashBreadcrumbs {
    private static final String PREF_BREADCRUMBS = "browser_express_crash_breadcrumbs";
    private static final int MAX_EVENTS = 8;
    private static final int MAX_CRASH_KEY_LENGTH = 63;
    private static final int MAX_EXIT_REASONS_TO_SCAN = 5;

    private static boolean sInitialized;
    private static @Nullable String sLastBreadcrumbValue;
    private static @Nullable String sLastExitInfoValue;

    private BrowserExpressCrashBreadcrumbs() {}

    public static void initialize() {
        ThreadUtils.assertOnUiThread();
        if (sInitialized) {
            return;
        }
        sInitialized = true;

        BrowserExpressFeatureJournal.initialize();
        restoreBreadcrumbCrashKey();
        updateLastExitInfoCrashKey();
        recordEvent("app_create");
        recordApplicationState(ApplicationStatus.getStateForApplication());
        ApplicationStatus.registerApplicationStateListener(
                BrowserExpressCrashBreadcrumbs::recordApplicationState);
    }

    public static void recordEvent(String event) {
        ThreadUtils.assertOnUiThread();

        String safeEvent = normalizeEventToken(event);
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        ArrayDeque<String> events = parseBreadcrumbs(prefs.getString(PREF_BREADCRUMBS, ""));
        if (!events.isEmpty() && safeEvent.equals(events.peekLast())) {
            setBreadcrumbCrashKey(serializeBreadcrumbs(events));
            BrowserExpressFeatureJournal.recordEvent(safeEvent);
            return;
        }

        events.addLast(safeEvent);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }

        String value = serializeBreadcrumbs(events);
        while (value.length() > MAX_CRASH_KEY_LENGTH && events.size() > 1) {
            events.removeFirst();
            value = serializeBreadcrumbs(events);
        }

        prefs.edit().putString(PREF_BREADCRUMBS, value).apply();
        setBreadcrumbCrashKey(value);
        BrowserExpressFeatureJournal.recordEvent(safeEvent);
    }

    public static void recordTrimMemory(int level) {
        recordEvent(getTrimMemoryEvent(level));
    }

    public static void recordConfigurationChanged(Configuration newConfig) {
        recordEvent("config");
    }

    private static void restoreBreadcrumbCrashKey() {
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        setBreadcrumbCrashKey(serializeBreadcrumbs(parseBreadcrumbs(prefs.getString(
                PREF_BREADCRUMBS, ""))));
    }

    private static void recordApplicationState(@ApplicationState int newState) {
        if (newState == ApplicationState.HAS_RUNNING_ACTIVITIES
                || newState == ApplicationState.HAS_PAUSED_ACTIVITIES) {
            recordEvent("fg");
        } else {
            recordEvent("bg");
        }
    }

    private static ArrayDeque<String> parseBreadcrumbs(@Nullable String value) {
        ArrayDeque<String> events = new ArrayDeque<>();
        if (value == null || value.isEmpty()) {
            return events;
        }

        for (String event : value.split(">")) {
            if (!event.isEmpty()) {
                events.addLast(normalizeEventToken(event));
            }
        }
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
        return events;
    }

    private static String serializeBreadcrumbs(ArrayDeque<String> events) {
        StringBuilder value = new StringBuilder();
        for (String event : events) {
            if (value.length() > 0) {
                value.append('>');
            }
            value.append(event);
        }
        return value.toString();
    }

    private static String normalizeEventToken(String event) {
        if (event.isEmpty() || event.length() > 24) {
            return "event";
        }
        for (int i = 0; i < event.length(); i++) {
            char c = event.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!valid) {
                return "event";
            }
        }
        return event;
    }

    private static String getTrimMemoryEvent(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                return "mem_run_mod";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "mem_run_low";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "mem_run_crit";
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                return "ui_hidden";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "mem_bg";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "mem_mod";
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "mem_crit";
            default:
                return "mem_other";
        }
    }

    private static void setBreadcrumbCrashKey(String value) {
        if (value.equals(sLastBreadcrumbValue)) {
            return;
        }
        sLastBreadcrumbValue = value;
        CrashKeys.getInstance().set(CrashKeyIndex.BROWSER_EXPRESS_BREADCRUMBS, value);
    }

    private static void updateLastExitInfoCrashKey() {
        String value = "api_pre_r";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                value = getLastExitInfoCrashKey();
            } catch (RuntimeException e) {
                value = "api_error";
            }
        }
        setLastExitInfoCrashKey(value);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String getLastExitInfoCrashKey() {
        Context context = ContextUtils.getApplicationContext();
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return "am_none";
        }

        List<ApplicationExitInfo> exitInfos =
                activityManager.getHistoricalProcessExitReasons(
                        null, 0, MAX_EXIT_REASONS_TO_SCAN);
        if (exitInfos == null || exitInfos.isEmpty()) {
            return "none";
        }

        String browserProcessName = context.getPackageName();
        @Nullable ApplicationExitInfo fallbackExitInfo = null;
        for (ApplicationExitInfo exitInfo : exitInfos) {
            if (exitInfo == null) {
                continue;
            }
            if (browserProcessName.equals(exitInfo.getProcessName())) {
                return formatExitInfo(exitInfo, "b");
            }
            if (fallbackExitInfo == null) {
                fallbackExitInfo = exitInfo;
            }
        }

        if (fallbackExitInfo == null) {
            return "none";
        }
        return formatExitInfo(fallbackExitInfo, getProcessRole(fallbackExitInfo, browserProcessName));
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String formatExitInfo(ApplicationExitInfo exitInfo, String processRole) {
        return "p="
                + processRole
                + "|r="
                + getReasonToken(exitInfo.getReason())
                + "|i="
                + getImportanceToken(exitInfo.getImportance())
                + "|age="
                + getAgeToken(exitInfo.getTimestamp());
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String getProcessRole(ApplicationExitInfo exitInfo, String browserProcessName) {
        @Nullable String processName = exitInfo.getProcessName();
        if (browserProcessName.equals(processName)) {
            return "b";
        }
        if (processName != null && processName.startsWith(browserProcessName + ":")) {
            return "c";
        }
        return "o";
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String getReasonToken(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_CRASH:
                return "java";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "dep";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "resource";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "self";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "init";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "low_mem";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "perm";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "signal";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "user_req";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "user_stop";
            case ApplicationExitInfo.REASON_FREEZER:
                return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return "pkg_state";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return "pkg_update";
            case ApplicationExitInfo.REASON_OTHER:
                return "other";
            case ApplicationExitInfo.REASON_UNKNOWN:
            default:
                return "unknown";
        }
    }

    private static String getImportanceToken(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return "fg";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            return "fgs";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            return "vis";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
            return "per";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
            return "svc";
        }
        if (importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
            return "cache";
        }
        return "bg";
    }

    private static String getAgeToken(long timestampMillis) {
        if (timestampMillis <= 0) {
            return "na";
        }

        long ageMillis = Math.max(0, System.currentTimeMillis() - timestampMillis);
        if (ageMillis < 60_000) {
            return "s";
        }
        if (ageMillis < 3_600_000) {
            return "m";
        }
        if (ageMillis < 86_400_000) {
            return "h";
        }
        return "d";
    }

    private static void setLastExitInfoCrashKey(String value) {
        if (value.equals(sLastExitInfoValue)) {
            return;
        }
        sLastExitInfoValue = value;
        CrashKeys.getInstance().set(CrashKeyIndex.BROWSER_EXPRESS_EXIT_INFO, value);
    }
}
