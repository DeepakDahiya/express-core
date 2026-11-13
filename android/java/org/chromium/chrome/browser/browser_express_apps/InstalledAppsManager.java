/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_apps;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveConfig;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Explicit, account-scoped sharing; no inventory is read or saved before consent. */
public final class InstalledAppsManager {
    private static final String TAG = "ExpressApps";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 15 * 60 * 1000L;
    private static final Object LOCK = new Object();
    private static final Set<String> QUEUED = new HashSet<>();
    private static final Set<String> RERUN = new HashSet<>();
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static WeakReference<AlertDialog> sDialog = new WeakReference<>(null);

    private InstalledAppsManager() {}

    private static Context context() {
        return ContextUtils.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        return context().getSharedPreferences("express_installed_apps", Context.MODE_PRIVATE);
    }

    private static String key(String userId, String field) {
        return userId + "." + field;
    }

    /** Build-time release gate; browser package visibility alone isn't Play approval. */
    public static boolean isAvailable() {
        return BraveConfig.INSTALLED_APPS_ENABLED
                && InstalledAppsCollector.hasFullVisibility(context());
    }

    public static boolean isSharingEnabled() {
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        return session != null && prefs().getBoolean(key(session.userId, "enabled"), false);
    }

    /** Called only when a regular feed is visible. Dismissal/"Not now" never grants consent. */
    public static void maybePrompt(Activity activity) {
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        if (session == null
                || !isAvailable()
                || prefs().getBoolean(key(session.userId, "prompted"), false)) return;
        showConsent(activity, null);
    }

    /** Settings uses the same disclosure as the feed; toggling a switch isn't consent. */
    public static void showConsent(Activity activity, Runnable onChanged) {
        if (activity == null
                || activity.isFinishing()
                || activity.isDestroyed()
                || !isAvailable()) {
            if (onChanged != null) onChanged.run();
            return;
        }
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        if (session == null) {
            Toast.makeText(
                            activity,
                            R.string.browser_express_installed_apps_sign_in,
                            Toast.LENGTH_SHORT)
                    .show();
            if (onChanged != null) onChanged.run();
            return;
        }
        AlertDialog existing = sDialog.get();
        if (existing != null && existing.isShowing()) return;
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.browser_express_installed_apps_consent_title)
                        .setMessage(R.string.browser_express_installed_apps_consent_message)
                        .setPositiveButton(
                                R.string.browser_express_installed_apps_consent_allow,
                                (unused, which) -> {
                                    InstalledAppsApi.Session current =
                                            InstalledAppsApi.Session.read(context());
                                    if (current == null
                                            || !session.userId.equals(current.userId)
                                            || !isAvailable()) return;
                                    synchronized (LOCK) {
                                        SharedPreferences p = prefs();
                                        p.edit()
                                                .putBoolean(key(session.userId, "enabled"), true)
                                                .putLong(
                                                        key(session.userId, "consented_at"),
                                                        System.currentTimeMillis())
                                                .putLong(
                                                        key(session.userId, "revision"),
                                                        nextRevision(p, session.userId))
                                                .putBoolean(
                                                        key(session.userId, "delete_pending"),
                                                        false)
                                                .putLong(key(session.userId, "last_success"), 0)
                                                .putLong(key(session.userId, "next_attempt"), 0)
                                                .apply();
                                    }
                                    syncIfNeeded();
                                })
                        .setNegativeButton(
                                R.string.browser_express_installed_apps_consent_deny, null)
                        .create();
        dialog.setOnDismissListener(
                unused -> {
                    sDialog.clear();
                    if (onChanged != null) onChanged.run();
                });
        dialog.show();
        sDialog = new WeakReference<>(dialog);
        prefs().edit().putBoolean(key(session.userId, "prompted"), true).apply();
    }

    /**
     * Stop immediately and queue deletion. Offline deletion is retried at a later sign-in/resume.
     */
    public static void turnOff() {
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        if (session == null) return;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            if (p.getBoolean(key(session.userId, "enabled"), false)) {
                p.edit()
                        .putBoolean(key(session.userId, "enabled"), false)
                        .putBoolean(key(session.userId, "delete_pending"), true)
                        .putLong(key(session.userId, "revision"), nextRevision(p, session.userId))
                        .putLong(key(session.userId, "consented_at"), 0)
                        .putLong(key(session.userId, "last_success"), 0)
                        .putLong(key(session.userId, "next_attempt"), 0)
                        .apply();
            }
        }
        enqueue(session);
    }

    /** Called before replacing/clearing session credentials. */
    public static void beforeAccountChange(String nextAccessToken) {
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        if (session == null
                || Objects.equals(session.userId, InstalledAppsApi.userId(nextAccessToken))) {
            return;
        }
        turnOff();
        AlertDialog dialog = sDialog.get();
        if (dialog != null) dialog.dismiss();
    }

    /** No periodic service: refresh at most daily while the app is used, after explicit opt-in. */
    public static void syncIfNeeded() {
        InstalledAppsApi.Session session = InstalledAppsApi.Session.read(context());
        if (session == null) return;
        if (!isAvailable() && isSharingEnabled()) {
            turnOff();
            return;
        }
        enqueue(session);
    }

    private static boolean isDue(SharedPreferences p, String userId) {
        long now = System.currentTimeMillis();
        long next = p.getLong(key(userId, "next_attempt"), 0);
        if (next > now && next - now <= RETRY_MS) return false;
        if (p.getBoolean(key(userId, "delete_pending"), false)) return true;
        if (!isAvailable() || !p.getBoolean(key(userId, "enabled"), false)) return false;
        long last = p.getLong(key(userId, "last_success"), 0);
        return last == 0 || now < last || now - last >= DAY_MS;
    }

    private static void enqueue(InstalledAppsApi.Session session) {
        synchronized (LOCK) {
            if (!isDue(prefs(), session.userId)) return;
            if (QUEUED.contains(session.userId)) {
                RERUN.add(session.userId);
                return;
            }
            if (!prefs().getBoolean(key(session.userId, "delete_pending"), false)) {
                InstalledAppsApi.Session current = InstalledAppsApi.Session.read(context());
                if (current == null || !session.userId.equals(current.userId)) return;
            }
            QUEUED.add(session.userId);
        }
        EXECUTOR.execute(
                () -> {
                    try {
                        syncBlocking(session);
                    } catch (Exception e) {
                        // Exception messages from Android/network code can include package
                        // names/tokens.
                        Log.w(TAG, "Inventory sync failed (%s)", e.getClass().getSimpleName());
                    } finally {
                        boolean rerun;
                        synchronized (LOCK) {
                            QUEUED.remove(session.userId);
                            rerun = RERUN.remove(session.userId);
                        }
                        // A choice may have changed while the request was running. Process its
                        // newer
                        // revision immediately. A failed request keeps its persisted retry delay.
                        if (rerun) enqueue(session);
                    }
                });
    }

    private static long nextRevision(SharedPreferences p, String userId) {
        long revision = p.getLong(key(userId, "revision"), 0);
        if (revision >= 9007199254740991L) throw new IllegalStateException("Revision exhausted");
        return revision + 1;
    }

    private static void syncBlocking(InstalledAppsApi.Session captured) throws Exception {
        String userId = captured.userId;
        final long revision;
        final boolean deleting;
        final JSONObject body = new JSONObject();
        InstalledAppsApi.Session session;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            if (!isDue(p, userId)) return;
            deleting = p.getBoolean(key(userId, "delete_pending"), false);
            InstalledAppsApi.Session current = InstalledAppsApi.Session.read(context());
            session = current != null && userId.equals(current.userId) ? current : captured;
            if (!deleting && (current == null || !userId.equals(current.userId))) return;
            String installationId = p.getString("installation_id", null);
            if (installationId == null) installationId = UUID.randomUUID().toString();
            revision = deleting ? p.getLong(key(userId, "revision"), 0) : nextRevision(p, userId);
            // Persist ordering before a request can leave the device, including process death.
            boolean saved =
                    p.edit()
                            .putString("installation_id", installationId)
                            .putLong(key(userId, "revision"), revision)
                            .putLong(
                                    key(userId, "next_attempt"),
                                    System.currentTimeMillis() + RETRY_MS)
                            .commit();
            if (!saved) return;
            body.put("installationId", installationId);
            body.put("revision", revision);
            if (!deleting) {
                body.put("consentedAt", p.getLong(key(userId, "consented_at"), 0));
                body.put("consentVersion", 1);
                body.put("scope", "android_profile");
            }
        }
        if (!deleting) {
            body.put("apps", InstalledAppsCollector.collect(context()));
            body.put(
                    "appVersion",
                    context()
                            .getPackageManager()
                            .getPackageInfo(context().getPackageName(), 0)
                            .versionName);
        }
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            if (revision != p.getLong(key(userId, "revision"), 0)) return;
            if (!deleting) {
                InstalledAppsApi.Session current = InstalledAppsApi.Session.read(context());
                if (!isAvailable()
                        || !p.getBoolean(key(userId, "enabled"), false)
                        || current == null
                        || !userId.equals(current.userId)) return;
                session = current;
            }
        }
        boolean success =
                InstalledAppsApi.send(context(), session, deleting ? "DELETE" : "PUT", body);
        if (!success) return;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            if (revision != p.getLong(key(userId, "revision"), 0)) return;
            SharedPreferences.Editor editor = p.edit().putLong(key(userId, "next_attempt"), 0);
            if (deleting) {
                editor.putBoolean(key(userId, "delete_pending"), false);
            } else {
                editor.putLong(key(userId, "last_success"), System.currentTimeMillis());
            }
            editor.apply();
        }
    }
}
