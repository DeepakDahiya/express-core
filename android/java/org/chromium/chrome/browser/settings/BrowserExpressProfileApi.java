/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.chrome.browser.browser_express_comments.KarmaEarned;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The Profile page's calls: GET /v1/user/me for who the reader is and their three points (Karma,
 * Taste, Cred), and PATCH /v1/user/me/profile-state for what the page and the "+1 Karma" label
 * last showed, so both stay right across devices. Edit profile's too: whether a username is free,
 * saving the name and username, and removing the photo (browser-express-node
 * docs/edit-profile.md).
 */
public final class BrowserExpressProfileApi {
    private static final String TAG = "ExpressProfile";
    private static final String API_BASE_URL = "https://api.browser.express/v1";
    private static final int TIMEOUT_MS = 15000;

    /** The order of the three points everywhere here: Karma, Taste, Cred. */
    public static final String[] SCORE_KEYS = {"karma", "taste", "cred"};

    private BrowserExpressProfileApi() {}

    /** Who the reader is and where their points stand. */
    public static final class Profile {
        public final @Nullable String name;
        public final @Nullable String username;
        public final @Nullable String avatarUrl;
        public final int[] scores;
        /** What the page last showed; null on the first visit. */
        public final @Nullable int[] scoresSeen;
        public final boolean hasSeenKarmaLabel;

        Profile(@Nullable String name, @Nullable String username, @Nullable String avatarUrl,
                int[] scores, @Nullable int[] scoresSeen, boolean hasSeenKarmaLabel) {
            this.name = name;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.scores = scores;
            this.scoresSeen = scoresSeen;
            this.hasSeenKarmaLabel = hasSeenKarmaLabel;
        }
    }

    public interface Callback {
        /** On the UI thread; null when the profile could not be loaded. */
        void onProfile(@Nullable Profile profile);
    }

    /** Loads the reader's profile off the UI thread. */
    public static void fetch(String accessToken, Callback callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Profile profile = null;
            try {
                JSONObject response = send("GET", "/user/me", accessToken, null);
                JSONObject user = response != null && response.optBoolean("success")
                        ? response.optJSONObject("user")
                        : null;
                if (user != null) profile = parse(accessToken, user);
            } catch (Exception e) {
                Log.w(TAG, "Profile fetch failed: " + e.getMessage());
            }
            final Profile result = profile;
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> callback.onProfile(result));
        });
    }

    /** What saving the name and username came to. */
    public static final class SaveResult {
        public final boolean saved;
        /** The session's fresh tokens; they carry the new name and username. */
        public final @Nullable String accessToken;
        public final @Nullable String refreshToken;
        /** Why not, when the server said: "username_taken" or "username_invalid". */
        public final @Nullable String code;
        public final @Nullable String error;

        SaveResult(boolean saved, @Nullable String accessToken, @Nullable String refreshToken,
                @Nullable String code, @Nullable String error) {
            this.saved = saved;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.code = code;
            this.error = error;
        }
    }

    public interface SaveCallback {
        /** On the UI thread. */
        void onSaved(SaveResult result);
    }

    public interface UsernameCallback {
        /**
         * On the UI thread: whether {@code username} is free for the reader, or null when the
         * server could not say (offline, or a server without the check).
         */
        void onUsername(String username, @Nullable Boolean available);
    }

    public interface DoneCallback {
        /** On the UI thread. */
        void onDone(boolean done);
    }

    /** Asks whether {@code username} is free; the reader's own counts as free. */
    public static void checkUsername(String accessToken, String username,
            UsernameCallback callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Boolean available = null;
            try {
                JSONObject response = send("GET",
                        "/user/username-available?username="
                                + URLEncoder.encode(username, "UTF-8"),
                        accessToken, null);
                if (response != null && response.optBoolean("success")
                        && response.has("available")) {
                    available = response.optBoolean("available");
                }
            } catch (Exception e) {
                Log.w(TAG, "Username check failed: " + e.getMessage());
            }
            final Boolean result = available;
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> callback.onUsername(username, result));
        });
    }

    /** Saves the reader's name and username (PATCH /v1/user/me). */
    public static void saveIdentity(String accessToken, String name, String username,
            SaveCallback callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            SaveResult result = new SaveResult(false, null, null, null, null);
            try {
                JSONObject body = new JSONObject().put("name", name).put("username", username);
                JSONObject response = send("PATCH", "/user/me", accessToken, body);
                if (response != null && response.optBoolean("success")) {
                    result = new SaveResult(true, textOf(response, "accessToken"),
                            textOf(response, "refreshToken"), null, null);
                } else if (response != null) {
                    result = new SaveResult(false, null, null, textOf(response, "code"),
                            textOf(response, "error"));
                }
            } catch (Exception e) {
                Log.w(TAG, "Profile save failed: " + e.getMessage());
            }
            final SaveResult saved = result;
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> callback.onSaved(saved));
        });
    }

    /** Removes the reader's uploaded photo; their default face shows again. */
    public static void removeAvatar(String accessToken, DoneCallback callback) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            boolean done = false;
            try {
                JSONObject response = send("DELETE", "/user/avatar", accessToken, null);
                done = response != null && response.optBoolean("success");
            } catch (Exception e) {
                Log.w(TAG, "Photo removal failed: " + e.getMessage());
            }
            final boolean result = done;
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> callback.onDone(result));
        });
    }

    /** Records that the page has shown {@code scores}; a return visit counts up from them. */
    public static void markScoresSeen(String accessToken, int[] scores) {
        try {
            JSONObject seen = new JSONObject();
            for (int i = 0; i < SCORE_KEYS.length; i++) seen.put(SCORE_KEYS[i], scores[i]);
            patchState(accessToken, new JSONObject().put("scoresSeen", seen));
        } catch (Exception e) {
            Log.w(TAG, "scoresSeen not sent: " + e.getMessage());
        }
    }

    /** Records that the reader has seen their first "+1 Karma". */
    public static void markKarmaLabelSeen(String accessToken) {
        try {
            patchState(accessToken, new JSONObject().put("hasSeenKarmaLabel", true));
        } catch (Exception e) {
            Log.w(TAG, "hasSeenKarmaLabel not sent: " + e.getMessage());
        }
    }

    private static void patchState(String accessToken, JSONObject body) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                send("PATCH", "/user/me/profile-state", accessToken, body);
            } catch (Exception e) {
                Log.w(TAG, "profile-state failed: " + e.getMessage());
            }
        });
    }

    private static Profile parse(String accessToken, JSONObject user) {
        // Remembered for this account, then handed back resolved: the upload, else its face.
        ProfileAvatar.saveSelf(accessToken, user.isNull("avatar") ? null : user.optString("avatar"));
        KarmaEarned.syncLabelSeen(accessToken, user.optBoolean("hasSeenKarmaLabel", false));
        int[] scores = scoresOf(user);
        JSONObject seenJson = user.optJSONObject("scoresSeen");
        int[] seen = seenJson != null ? scoresOf(seenJson) : null;
        return new Profile(textOf(user, "name"), textOf(user, "username"),
                ProfileAvatar.selfUrl(accessToken), scores, seen,
                user.optBoolean("hasSeenKarmaLabel", false));
    }

    private static int[] scoresOf(JSONObject json) {
        int[] scores = new int[SCORE_KEYS.length];
        for (int i = 0; i < SCORE_KEYS.length; i++) {
            scores[i] = Math.max(0, json.optInt(SCORE_KEYS[i], 0));
        }
        return scores;
    }

    private static @Nullable String textOf(JSONObject json, String key) {
        String value = json.isNull(key) ? null : json.optString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static @Nullable JSONObject send(String method, String path, String accessToken,
            @Nullable JSONObject body) throws Exception {
        URL url = new URL(API_BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, method + " " + path + " → HTTP " + code);
                return null;
            }
            return new JSONObject(text.toString());
        } finally {
            conn.disconnect();
        }
    }
}
