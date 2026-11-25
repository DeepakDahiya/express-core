/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_share;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.chrome.browser.browser_express_comments.SourceChip;
import org.chromium.chrome.browser.settings.BrowserExpressGuestLogin;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import org.chromium.components.embedder_support.util.UrlConstants;
import org.chromium.components.external_intents.ExternalNavigationParams;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;
import org.chromium.ui.base.PageTransition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Opens a shared take or post in the app: the post's takes, on the shared one. One path for all
 * three ways a link arrives (docs/share-links-android.md §4): an App Link (or any VIEW intent, the
 * app being a browser), a link tapped inside one of the app's own tabs, and the Play install
 * referrer of a fresh install. A link that arrives before there is a session waits through
 * onboarding and sign-in, then opens.
 */
public final class ShareLinkOpener {
    private static final String TAG = "ShareLink";

    public static final String VIA_APP_LINK = "app_link";
    public static final String VIA_IN_APP = "in_app";
    public static final String VIA_INSTALL_REFERRER = "install_referrer";

    private static final String SHARE_API = "https://api.browser.express/v1/public/share";

    /** Same tag BraveActivity shows the comments sheet under. */
    private static final String COMMENTS_SHEET_TAG = "BrowserExpressCommentsBottomSheetFragment";

    /** The link waiting for a session, as JSON: post, take, by, via, at. */
    private static final String PREF_PENDING = "browser_express_share_link_pending";

    /** A link kept through sign-up is dropped once it has waited this long. */
    private static final long PENDING_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Lets the activity settle (restored tab, new tab page) before the sheet goes over it. */
    private static final long OPEN_DELAY_MS = 600;

    private static final int TIMEOUT_MS = 10000;

    private static final String RESULT_OPENED = "opened";
    private static final String RESULT_TAKE_GONE = "take_gone";
    private static final String RESULT_POST_GONE = "post_gone";
    private static final String RESULT_FAILED = "failed";

    private static boolean sWaitingForGuestSession;

    private ShareLinkOpener() {}

    /** Whether {@code intent} opens a share link, which the app shows natively, not in a tab. */
    public static boolean isShareLinkIntent(@Nullable Intent intent) {
        return intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && ShareLinks.parse(intent.getData()) != null;
    }

    /**
     * Opens the share link a VIEW intent carries. True when it was one; the caller then must not
     * load it in a tab.
     */
    public static boolean maybeHandleIntent(BraveActivity activity, @Nullable Intent intent) {
        if (!isShareLinkIntent(intent)) return false;
        open(activity, ShareLinks.parse(intent.getData()), VIA_APP_LINK);
        return true;
    }

    /**
     * Opens a share link the reader followed inside a tab. True when it was one and the tab must
     * not load it. Reloads and back/forward are left alone, so is incognito (the post would open
     * with the reader's account) and anything not in the tab they're looking at.
     */
    public static boolean maybeHandleNavigation(ExternalNavigationParams params) {
        if (!params.isMainFrame() || params.isIncognito() || params.isBackgroundTabNavigation()) {
            return false;
        }
        int transition = params.getPageTransition();
        if ((transition & PageTransition.CORE_MASK) == PageTransition.RELOAD
                || (transition & PageTransition.FORWARD_BACK) != 0) {
            return false;
        }
        ShareLinks.Target target = ShareLinks.parse(params.getUrl().getSpec());
        if (target == null) return false;
        BraveActivity activity = currentActivity();
        // Only from the browser in front: in a custom tab over it, the post would open unseen
        // behind, so the custom tab loads the page instead.
        if (activity == null || !isResumed(activity)) return false;
        open(activity, target, VIA_IN_APP);
        return true;
    }

    /**
     * Keeps the take a share page's install button sent this install to, to open once the reader
     * is through onboarding and signed in.
     */
    public static void openAfterInstall(ShareLinks.Target target) {
        savePending(target, VIA_INSTALL_REFERRER);
        PostTask.postTask(TaskTraits.UI_DEFAULT, () -> openPendingIfReady(currentActivity()));
    }

    /**
     * Opens the link waiting for a session, if there is one and the reader is now signed in and
     * looking at the browser. Called on every resume and whenever a session arrives.
     */
    public static void openPendingIfReady(@Nullable BraveActivity activity) {
        if (readPending() == null) return;
        if (!hasSession()) {
            waitForGuestSession();
            return;
        }
        if (activity == null) return;
        PostTask.postDelayedTask(
                TaskTraits.UI_DEFAULT,
                () -> {
                    if (!isResumed(activity) || !hasSession()) return;
                    JSONObject pending = readPending();
                    if (pending == null) return;
                    clearPending();
                    resolve(
                            activity,
                            new ShareLinks.Target(
                                    optId(pending, "post"),
                                    optId(pending, "take"),
                                    optId(pending, "by")),
                            pending.optString("via", VIA_APP_LINK));
                },
                OPEN_DELAY_MS);
    }

    private static void open(BraveActivity activity, ShareLinks.Target target, String via) {
        if (!hasSession()) {
            // Sign-in comes first; the new tab page underneath starts it.
            savePending(target, via);
            waitForGuestSession();
            return;
        }
        resolve(activity, target, via);
    }

    /**
     * Looks the link up (GET /v1/public/share, the same answer the web page renders), then opens
     * the post's takes on the shared one. A take that's gone opens the post; a post that's gone
     * says so over the feed.
     */
    private static void resolve(BraveActivity activity, ShareLinks.Target target, String via) {
        PostTask.postTask(
                TaskTraits.USER_VISIBLE_MAY_BLOCK,
                () -> {
                    Lookup lookup = lookUp(target.postId, target.takeId);
                    String result = RESULT_OPENED;
                    if (lookup.status == HttpURLConnection.HTTP_NOT_FOUND
                            && target.takeId != null
                            && target.postId != null) {
                        // The take is gone; the post it was under may not be.
                        lookup = lookUp(target.postId, null);
                        result = RESULT_TAKE_GONE;
                    }
                    if (lookup.status == HttpURLConnection.HTTP_NOT_FOUND) {
                        result = RESULT_POST_GONE;
                    } else if (lookup.share == null) {
                        result = RESULT_FAILED;
                    }
                    final Lookup found = lookup;
                    final String outcome = result;
                    PostTask.postTask(
                            TaskTraits.UI_DEFAULT,
                            () -> show(activity, target, via, found, outcome));
                });
    }

    private static void show(
            BraveActivity activity,
            ShareLinks.Target target,
            String via,
            Lookup lookup,
            String result) {
        PostHogUtil.capture(
                PostHogEventKeys.SHARE_LINK_OPENED,
                "via", via,
                "post_id", target.postId,
                "take_id", target.takeId,
                "by", target.byUserId,
                "result", result);
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (RESULT_POST_GONE.equals(result)) {
            Toast.makeText(activity, R.string.browser_express_share_post_gone, Toast.LENGTH_LONG)
                    .show();
            activity.openNewOrSelectExistingTab(UrlConstants.NTP_URL);
            return;
        }
        if (RESULT_FAILED.equals(result)) {
            Toast.makeText(activity, R.string.browser_express_share_link_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        JSONObject share = lookup.share;
        JSONObject topic = share.optJSONObject("topic");
        if (topic == null) return;
        String postId = optId(topic, "id");
        if (postId == null) return;
        JSONObject poster = topic.optJSONObject("poster");
        String posterName = poster != null ? optText(poster, "name") : null;
        String avatar =
                ProfileAvatar.resolve(poster != null ? optText(poster, "avatar") : null, posterName);
        Bundle media =
                SourceChip.postMedia(
                        optText(topic, "cover"),
                        null,
                        null,
                        0,
                        isoMillis(optText(topic, "createdAt")),
                        optText(topic, "hashtag"),
                        topic.optInt("takeCount", 0),
                        0);

        // A shared reply opens on the take it sits under; the stream holds top-level takes only.
        String anchor = null;
        if (RESULT_OPENED.equals(result) && share.optJSONObject("sharedTake") != null) {
            anchor = optId(share, "rootTakeId");
            if (anchor == null) anchor = optId(share.optJSONObject("sharedTake"), "id");
        }

        dismissOpenComments(activity);
        activity.showPostComments(
                postId,
                posterName != null ? posterName : "",
                caption(topic),
                avatar,
                media,
                anchor);
    }

    /** A comments sheet already up would make the new one a no-op; this link replaces it. */
    private static void dismissOpenComments(BraveActivity activity) {
        FragmentManager fragments = activity.getSupportFragmentManager();
        Fragment open = fragments.findFragmentByTag(COMMENTS_SHEET_TAG);
        if (!(open instanceof DialogFragment)) return;
        ((DialogFragment) open).dismissAllowingStateLoss();
        try {
            fragments.executePendingTransactions();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Could not close the open comments: " + e.getMessage());
        }
    }

    /** The post's text as the feed card's caption has it: title, then the rest. */
    private static String caption(JSONObject topic) {
        String title = optText(topic, "title");
        String summary = optText(topic, "summary");
        if (TextUtils.isEmpty(title)) return summary != null ? summary : "";
        if (TextUtils.isEmpty(summary)) return title;
        // Untitled, the title is the text's first line and the summary the rest. Titled, the
        // summary is the whole text, which may begin with the title already.
        return summary.startsWith(title) ? summary : title + "\n" + summary;
    }

    /** What GET /v1/public/share answered: its HTTP status (0 for none) and body when 200. */
    private static final class Lookup {
        final int status;
        final @Nullable JSONObject share;

        Lookup(int status, @Nullable JSONObject share) {
            this.status = status;
            this.share = share;
        }
    }

    private static Lookup lookUp(@Nullable String postId, @Nullable String takeId) {
        Uri.Builder url = Uri.parse(SHARE_API).buildUpon();
        if (postId != null) url.appendQueryParameter("post", postId);
        if (takeId != null) url.appendQueryParameter("take", takeId);
        HttpURLConnection connection = null;
        try {
            connection =
                    (HttpURLConnection)
                            ChromiumNetworkAdapter.openConnection(
                                    new URL(url.build().toString()),
                                    NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return new Lookup(status, null);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return new Lookup(status, new JSONObject(body.toString()));
        } catch (Exception e) {
            Log.w(TAG, "Share lookup failed: " + e.getMessage());
            return new Lookup(0, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The link waiting for a session
    // ---------------------------------------------------------------------------------------------

    private static void savePending(ShareLinks.Target target, String via) {
        JSONObject pending = new JSONObject();
        try {
            pending.put("post", target.postId);
            pending.put("take", target.takeId);
            pending.put("by", target.byUserId);
            pending.put("via", via);
            pending.put("at", System.currentTimeMillis());
        } catch (JSONException e) {
            return;
        }
        prefs().edit().putString(PREF_PENDING, pending.toString()).apply();
    }

    private static @Nullable JSONObject readPending() {
        String json = prefs().getString(PREF_PENDING, null);
        if (json == null) return null;
        try {
            JSONObject pending = new JSONObject(json);
            long age = System.currentTimeMillis() - pending.optLong("at", 0);
            boolean usable =
                    (optId(pending, "post") != null || optId(pending, "take") != null)
                            && age >= 0
                            && age < PENDING_TTL_MS;
            if (usable) return pending;
        } catch (JSONException e) {
            // Unreadable; drop it below.
        }
        clearPending();
        return null;
    }

    private static void clearPending() {
        prefs().edit().remove(PREF_PENDING).apply();
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getAppSharedPreferences();
    }

    /**
     * A reader who skipped sign-in gets a guest session in the background, with the browser in
     * front; the waiting link opens when it lands. Phone sign-in returns to the browser instead,
     * whose resume opens it.
     */
    private static void waitForGuestSession() {
        if (sWaitingForGuestSession) return;
        sWaitingForGuestSession = true;
        BrowserExpressGuestLogin.addObserver(token -> openPendingIfReady(currentActivity()));
    }

    /** Whether this device holds a session, guest or signed in. */
    private static boolean hasSession() {
        Context context = ContextUtils.getApplicationContext();
        return !TextUtils.isEmpty(
                context.getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0)
                        .getString(BraveActivity.ACCESS_TOKEN_KEY, null));
    }

    private static boolean isResumed(BraveActivity activity) {
        return !activity.isFinishing()
                && !activity.isDestroyed()
                && ApplicationStatus.getStateForActivity(activity) == ActivityState.RESUMED;
    }

    private static @Nullable BraveActivity currentActivity() {
        try {
            return BraveActivity.getBraveActivity();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------------

    private static @Nullable String optId(@Nullable JSONObject object, String key) {
        String value = optText(object, key);
        return ShareLinks.isId(value) ? value : null;
    }

    /** {@code key}'s string, or null when it is missing, JSON null or empty. */
    private static @Nullable String optText(@Nullable JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static long isoMillis(@Nullable String iso) {
        if (iso == null) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
