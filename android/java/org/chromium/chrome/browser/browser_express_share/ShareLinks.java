/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_share;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.chromium.chrome.browser.referral.ReferralHelper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The links a shared take or post travels as (browser-express-node docs/share-links-android.md).
 * {@code https://www.browser.express/t/<postId>?take=<commentId>&by=<userId>} opens an app-like
 * web page for people without the app, and the post in the app for people with it. That page's
 * install buttons carry a Play install referrer, {@code source=share&post=…&take=…&by=…}, so a
 * fresh install opens on the same take.
 */
public final class ShareLinks {
    /**
     * The host links are made on. browser.express redirects here (308), and Android doesn't follow
     * a redirect when it verifies App Links, so links on the bare host would never open the app.
     */
    static final String HOST = "www.browser.express";

    private static final String APEX_HOST = "browser.express";
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");

    /** What a share link points at. */
    public static final class Target {
        /** The post. Null only for an old {@code /view?id=} link, which names just the take. */
        public final @Nullable String postId;

        /** The shared take, a reply at any depth included; null when a post was shared. */
        public final @Nullable String takeId;

        /** Who shared it, when they were signed in. */
        public final @Nullable String byUserId;

        Target(@Nullable String postId, @Nullable String takeId, @Nullable String byUserId) {
            this.postId = postId;
            this.takeId = takeId;
            this.byUserId = byUserId;
        }
    }

    private ShareLinks() {}

    /** Whether {@code value} is a Mongo id, the only kind of id the links carry. */
    public static boolean isId(@Nullable String value) {
        return value != null && ID.matcher(value).matches();
    }

    /** The link to a take (replies included) under {@code postId}; null unless both are ids. */
    public static @Nullable String forTake(@Nullable String postId, @Nullable String takeId) {
        if (!isId(postId) || !isId(takeId)) return null;
        return build(postId, takeId);
    }

    /** The link to a post; null unless {@code postId} is an id. */
    public static @Nullable String forPost(@Nullable String postId) {
        if (!isId(postId)) return null;
        return build(postId, null);
    }

    private static String build(String postId, @Nullable String takeId) {
        Uri.Builder link =
                new Uri.Builder().scheme("https").authority(HOST).appendPath("t").appendPath(postId);
        if (takeId != null) link.appendQueryParameter("take", takeId);
        // The page greets the reader with the sharer's name and picture.
        String me = ReferralHelper.signedInUserId();
        if (isId(me)) link.appendQueryParameter("by", me);
        return link.build().toString();
    }

    /**
     * The take or post a browser.express {@code /t/<postId>} or old {@code /view?id=<takeId>} link
     * points at, on either host; null for any other URL.
     */
    public static @Nullable Target parse(@Nullable Uri uri) {
        if (uri == null || !uri.isHierarchical()) return null;
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (!HOST.equals(host) && !APEX_HOST.equals(host)) return null;

        try {
            List<String> path = uri.getPathSegments();
            String by = idOrNull(uri.getQueryParameter("by"));
            if (path.size() == 2 && "t".equals(path.get(0)) && isId(path.get(1))) {
                return new Target(path.get(1), idOrNull(uri.getQueryParameter("take")), by);
            }
            if (path.size() == 1 && "view".equals(path.get(0))) {
                String take = idOrNull(uri.getQueryParameter("id"));
                return take != null ? new Target(null, take, by) : null;
            }
        } catch (RuntimeException e) {
            // A malformed query; not a link this app makes.
        }
        return null;
    }

    /** As {@link #parse(Uri)}, for a URL string. */
    public static @Nullable Target parse(@Nullable String url) {
        return TextUtils.isEmpty(url) ? null : parse(Uri.parse(url));
    }

    /** Whether a Play install referrer came from a share page's install button. */
    public static boolean isShareReferrer(@Nullable String referrer) {
        return "share".equals(referrerParams(referrer).get("source"));
    }

    /** The take a share page's install button sent this install to; null for any other referrer. */
    public static @Nullable Target parseInstallReferrer(@Nullable String referrer) {
        Map<String, String> params = referrerParams(referrer);
        if (!"share".equals(params.get("source"))) return null;
        String post = params.get("post");
        if (!isId(post)) return null;
        return new Target(post, idOrNull(params.get("take")), idOrNull(params.get("by")));
    }

    /** The referrer's key=value pairs. Play may hand it over still URL-encoded as a whole. */
    private static Map<String, String> referrerParams(@Nullable String referrer) {
        Map<String, String> params = new HashMap<>();
        if (TextUtils.isEmpty(referrer)) return params;
        String query = referrer.contains("=") ? referrer : decode(referrer);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static @Nullable String idOrNull(@Nullable String value) {
        return isId(value) ? value : null;
    }
}
