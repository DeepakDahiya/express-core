/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.app.helpers;

import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Profile pictures, one way everywhere: the picture the person uploaded, else their default
 * face, both from our CDN, round, and kept on disk after the first load.
 *
 * <p>The server gives every account a default face (512px WebP on media.browser.express) and
 * picks it the same way as {@link #resolve}. Accounts it hasn't migrated yet still carry a
 * stand-in: a DiceBear URL (a 128px render from a rate-limited third party) or one of the 128px
 * pixel-art portraits signup used to hand out. Those, and accounts with no URL at all, get their
 * default face here, picked by a hash of the DiceBear seed, else of the account id, so a person
 * keeps one face across the feed, comments and the toolbar. The faces and the script that drew
 * them live in browser-express-node/assets/default-avatars; the server's half is
 * src/utils/default-avatar.ts there.
 */
@SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
public final class ProfileAvatar {
    /** The default faces, {@code <index>.webp}. A new set goes under a new version directory. */
    private static final String DEFAULT_FACES_URL =
            "https://media.browser.express/avatars/default/v1/";
    private static final int DEFAULT_FACE_COUNT = 48;

    private static final String CDN_ORIGIN = "https://media.browser.express/";
    /** The bucket behind the CDN, as S3 spells it; the CDN serves the same keys. */
    private static final String[] S3_ORIGINS = {
        "https://telehealth-public.s3.eu-west-2.amazonaws.com/",
        "https://telehealth-public.s3.amazonaws.com/",
        "https://s3.eu-west-2.amazonaws.com/telehealth-public/",
    };
    private static final String DICEBEAR_HOST = "api.dicebear.com";
    /** The pixel-art portraits signup used to hand out: pfp00001.png … pfp00029.png. */
    private static final Pattern LEGACY_PORTRAIT = Pattern.compile("/pfp0*\\d+\\.png$");

    /** Shown while a picture loads, and when there is none or it fails. */
    @DrawableRes
    public static final int PLACEHOLDER = R.drawable.browser_express_post_avatar_placeholder;

    // The signed-in account's picture as /v1/user/me last reported it, and whose it is: a
    // different account signing in on the device must not inherit it.
    private static final String PREFS = "BE_PROFILE_PREFS";
    private static final String PREF_SELF_URL = "avatar_url";
    private static final String PREF_SELF_OWNER = "avatar_url_owner";

    private ProfileAvatar() {}

    /**
     * The picture to show for an account: its own (an upload or the default face the server
     * assigned, served from the CDN), else its default face. Null when it has neither a picture
     * nor anything to pick a face with. Resolving a resolved URL returns it unchanged.
     *
     * @param avatarUrl what the server says the picture is, if anything.
     * @param seed picks the default face when {@code avatarUrl} carries no seed of its own: the
     *     account id, or a handle for authors without one.
     */
    public static @Nullable String resolve(@Nullable String avatarUrl, @Nullable String seed) {
        // org.json hands back "null" for a JSON null read with optString.
        if (!TextUtils.isEmpty(avatarUrl)
                && !"null".equals(avatarUrl)
                && !LEGACY_PORTRAIT.matcher(avatarUrl).find()) {
            Uri uri = Uri.parse(avatarUrl);
            if (!DICEBEAR_HOST.equals(uri.getHost())) return onCdn(avatarUrl);
            String dicebearSeed = uri.getQueryParameter("seed");
            if (!TextUtils.isEmpty(dicebearSeed)) seed = dicebearSeed;
        }
        return TextUtils.isEmpty(seed) ? null : defaultFace(seed);
    }

    /** One of the default faces; the same one for the same seed, here and on the server. */
    public static String defaultFace(String seed) {
        return DEFAULT_FACES_URL + faceIndex(seed) + ".webp";
    }

    /**
     * Whether {@code url} (resolved, see {@link #resolve}) is a default face rather than a
     * picture the person uploaded.
     */
    public static boolean isDefaultFace(@Nullable String url) {
        return url != null && url.startsWith(DEFAULT_FACES_URL);
    }

    /** FNV-1a (32-bit) of the seed's UTF-8 bytes, unsigned, modulo the number of faces. */
    static int faceIndex(String seed) {
        int hash = 0x811c9dc5;
        for (byte b : seed.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return Integer.remainderUnsigned(hash, DEFAULT_FACE_COUNT);
    }

    /** {@code url} through the CDN when it points straight at the bucket behind it. */
    private static String onCdn(String url) {
        for (String origin : S3_ORIGINS) {
            if (url.startsWith(origin)) return CDN_ORIGIN + url.substring(origin.length());
        }
        return url;
    }

    /** Shows an account's picture (see {@link #resolve}) in {@code view}, cropped round. */
    public static void load(ImageView view, @Nullable String avatarUrl, @Nullable String seed) {
        into(view, resolve(avatarUrl, seed));
    }

    /** Shows the signed-in account's picture (see {@link #selfUrl}) in {@code view}. */
    public static void loadSelf(ImageView view, @Nullable String accessToken) {
        into(view, selfUrl(accessToken));
    }

    private static void into(ImageView view, @Nullable String url) {
        RequestManager glide;
        try {
            glide = Glide.with(view);
        } catch (IllegalArgumentException e) {
            return; // Its activity is already destroyed.
        }
        if (url == null) {
            glide.clear(view);
            view.setImageResource(PLACEHOLDER);
            return;
        }
        glide.load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .placeholder(PLACEHOLDER)
                .error(PLACEHOLDER)
                .into(view);
    }

    /** The signed-in account's id, read from its access token; null when signed out. */
    public static @Nullable String selfId(@Nullable String accessToken) {
        if (TextUtils.isEmpty(accessToken)) return null;
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] payload =
                    Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String id =
                    new JSONObject(new String(payload, StandardCharsets.UTF_8)).optString("_id");
            return id.isEmpty() ? null : id;
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }

    /**
     * The signed-in account's picture, resolved: the one /v1/user/me last reported for this
     * account, else its default face. Null when signed out.
     */
    public static @Nullable String selfUrl(@Nullable String accessToken) {
        String id = selfId(accessToken);
        if (id == null) return null;
        SharedPreferences prefs = prefs();
        String saved =
                id.equals(prefs.getString(PREF_SELF_OWNER, null))
                        ? prefs.getString(PREF_SELF_URL, null)
                        : null;
        return resolve(saved, id);
    }

    /** Remembers the signed-in account's picture, as /v1/user/me reported it. */
    public static void saveSelf(@Nullable String accessToken, @Nullable String avatarUrl) {
        String id = selfId(accessToken);
        if (id == null || TextUtils.isEmpty(avatarUrl) || "null".equals(avatarUrl)) return;
        prefs().edit().putString(PREF_SELF_URL, avatarUrl).putString(PREF_SELF_OWNER, id).apply();
    }

    /** Forgets the signed-in account's picture; on sign-out. */
    public static void clearSelf() {
        prefs().edit().remove(PREF_SELF_URL).remove(PREF_SELF_OWNER).apply();
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(PREFS, 0);
    }
}
