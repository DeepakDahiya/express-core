/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_apps;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.TreeMap;

/** Reads the current Android profile only. Call off the UI thread, after explicit consent. */
final class InstalledAppsCollector {
    private static final int MAX_APPS = 2000;

    private InstalledAppsCollector() {}

    static boolean hasFullVisibility(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || context.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES")
                        == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    static JSONArray collect(Context context) throws JSONException {
        if (!hasFullVisibility(context)) {
            throw new IllegalStateException("Full package visibility is unavailable");
        }
        PackageManager pm = context.getPackageManager();
        // Flags=0 excludes uninstalled packages. Do not cross work/private profile boundaries.
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        if (packages.size() > MAX_APPS) {
            throw new IllegalStateException("Inventory exceeds the supported size");
        }
        TreeMap<String, JSONObject> apps = new TreeMap<>();
        for (PackageInfo info : packages) {
            if (info.applicationInfo == null || info.packageName == null) continue;
            ApplicationInfo app = info.applicationInfo;
            JSONObject item = new JSONObject();
            item.put("packageName", info.packageName);
            item.put("label", bounded(pm.getApplicationLabel(app).toString()));
            item.put("versionName", bounded(info.versionName));
            long versionCode =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            ? info.getLongVersionCode()
                            : info.versionCode;
            if (versionCode >= 0 && versionCode <= 9007199254740991L) {
                item.put("versionCode", versionCode);
            }
            item.put("firstInstallTime", info.firstInstallTime);
            item.put("lastUpdateTime", info.lastUpdateTime);
            item.put(
                    "isSystem",
                    (app.flags
                                    & (ApplicationInfo.FLAG_SYSTEM
                                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                            != 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.put("category", category(app.category));
            }
            try {
                String installer =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? pm.getInstallSourceInfo(info.packageName)
                                        .getInstallingPackageName()
                                : pm.getInstallerPackageName(info.packageName);
                item.put("installer", bounded(installer));
            } catch (PackageManager.NameNotFoundException
                    | IllegalArgumentException
                    | SecurityException ignored) {
                // The package may be removed during the scan, or its source may be restricted.
            }
            apps.put(info.packageName, item);
        }
        JSONArray result = new JSONArray();
        for (JSONObject app : apps.values()) result.put(app);
        return result;
    }

    private static String bounded(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 200));
    }

    private static String category(int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME:
                return "game";
            case ApplicationInfo.CATEGORY_AUDIO:
                return "audio";
            case ApplicationInfo.CATEGORY_VIDEO:
                return "video";
            case ApplicationInfo.CATEGORY_IMAGE:
                return "image";
            case ApplicationInfo.CATEGORY_SOCIAL:
                return "social";
            case ApplicationInfo.CATEGORY_NEWS:
                return "news";
            case ApplicationInfo.CATEGORY_MAPS:
                return "maps";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return "productivity";
            default:
                return null;
        }
    }
}
