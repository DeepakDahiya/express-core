
package org.chromium.chrome.browser.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.about_settings.AboutChromeSettings;
import org.chromium.chrome.browser.about_settings.AboutSettingsBridge;
import org.chromium.chrome.browser.night_mode.ThemeType;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.referral.ReferralHelper;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;
import org.chromium.url.GURL;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.Integer;
import java.util.Locale;
import java.util.UUID;

public class PostHogUtil {
    private static final String TAG = "Post_Hog_Util";
    private static final String GET_PROFILE_URL = "https://posthog.dd-fb2.workers.dev/i/v0/e/";
    private static final String POST_HOG_API_KEY = "phc_Nn8HVrhAeFqo35ymuvUKOKe59RhXnShdushDuyDn3vL";

    // Disk-backed queue for events fired right as the app is being backgrounded
    // (PIP, onUserLeaveHint). The process can be frozen before the HTTP POST
    // finishes — we persist the payload first, drop it on success, and flush
    // anything still pending on the next send / next app start.
    private static final String PENDING_PREFS = "posthog_pending_events";
    private static final String PENDING_KEY = "events";
    private static final int MAX_PENDING = 50;
    private static final Object sQueueLock = new Object();

    private static final String IDENTIFY_EVENT = "$identify";
    /** The id some callers pass for "no one signed in"; replaced by this install's id. */
    private static final String ANONYMOUS = "ANONYMOUS";
    /** Events further apart than this start a new app session. */
    private static final long SESSION_IDLE_MS = 30 * 60 * 1000L;
    private static final int MAX_SEARCH_QUERY_LENGTH = 200;

    @Nullable private static String sSessionId;
    private static long sSessionLastEventMs;

    private static boolean sWatchingVisibility;
    /** {@link SystemClock#elapsedRealtime} when the app came to the front; 0 while away. */
    private static long sForegroundSinceMs;
    /** When the app went to the background; 0 while in front. */
    private static long sBackgroundSinceMs;

    public static class PostHogWorkerTask extends AsyncTask<Void> {
        private final String mEvent;
        private final String mUserId;
        private final JSONObject mProperties;

        /**
         * @param userId who the event is about; null, empty or "ANONYMOUS" means the signed-in
         *     user, else this install ({@link ReferralHelper#resolveDistinctId}), never one
         *     shared anonymous person.
         */
        public PostHogWorkerTask(String event, String userId, JSONObject properties) {
            mEvent = event;
            mUserId = isUnidentified(userId) ? ReferralHelper.resolveDistinctId() : userId;
            mProperties =
                    IDENTIFY_EVENT.equals(event) ? properties : withCommonProperties(properties);
        }

        @Override
        protected Void doInBackground() {
            String pendingId = enqueue(mEvent, mUserId, mProperties);
            boolean ok = sendPostHogRequest(mEvent, mUserId, mProperties);
            if (ok && pendingId != null) {
                removePending(pendingId);
            }
            // Opportunistically retry anything that was stuck from prior runs.
            flushPending();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
        }
    }

    /**
     * Sends {@code event} about the signed-in user, else this install (the id sign-in later
     * merges into the user), with the common properties every event carries. Any thread.
     */
    public static void capture(String event, @Nullable JSONObject properties) {
        try {
            new PostHogWorkerTask(event, null, properties)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (Exception e) {
            Log.e(TAG, "capture error: " + e.getMessage());
        }
    }

    /** {@link #capture(String, JSONObject)} with the properties as name, value, name, value… */
    public static void capture(String event, Object... namesAndValues) {
        JSONObject properties = new JSONObject();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            try {
                Object value = namesAndValues[i + 1];
                properties.put(
                        String.valueOf(namesAndValues[i]), value != null ? value : JSONObject.NULL);
            } catch (JSONException ignored) {
            }
        }
        capture(event, properties);
    }

    private static boolean isUnidentified(@Nullable String userId) {
        return userId == null || userId.isEmpty() || ANONYMOUS.equals(userId);
    }

    /**
     * Adds what every event says about where it came from: the app and OS versions, the device,
     * the app session ({@code $session_id}), whether someone is signed in and the language.
     */
    private static JSONObject withCommonProperties(@Nullable JSONObject properties) {
        JSONObject props = properties != null ? properties : new JSONObject();
        try {
            if (!props.has("app_version")) {
                String version = appVersion();
                if (version != null) props.put("app_version", version);
            }
            props.put("$os", "Android");
            props.put("$os_version", Build.VERSION.RELEASE);
            props.put("$device_manufacturer", Build.MANUFACTURER);
            props.put("$device_model", Build.MODEL);
            props.put("$lib", "browser-express-android");
            props.put("$session_id", sessionId());
            props.put("signed_in", hasSession());
            props.put("locale", Locale.getDefault().toLanguageTag());
        } catch (JSONException e) {
            Log.e(TAG, "common properties error: " + e.getMessage());
        }
        return props;
    }

    /**
     * The app session the event belongs to: a new one after {@link #SESSION_IDLE_MS} without
     * events. A UUIDv7, the form PostHog's session analysis expects.
     */
    private static synchronized String sessionId() {
        long now = System.currentTimeMillis();
        if (sSessionId == null || now - sSessionLastEventMs > SESSION_IDLE_MS) {
            UUID random = UUID.randomUUID();
            long mostSignificant =
                    (now << 16) | 0x7000L | (random.getMostSignificantBits() & 0x0FFFL);
            sSessionId = new UUID(mostSignificant, random.getLeastSignificantBits()).toString();
        }
        sSessionLastEventMs = now;
        return sSessionId;
    }

    private static boolean hasSession() {
        String token =
                ContextUtils.getApplicationContext()
                        .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0)
                        .getString("AccessToken", null);
        return token != null && !token.isEmpty();
    }

    @Nullable
    private static String appVersion() {
        try {
            Context context = ContextUtils.getApplicationContext();
            PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info != null ? info.versionName : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What events say about a web page: its {@code domain} and {@code site} (registrable domain),
     * never the full URL. A results page of the default search engine adds {@code is_search},
     * {@code search_engine} and {@code search_query}; a YouTube page adds {@code youtube_page}
     * (home, watch, shorts, search, channel or other) and, on YouTube search, the terms. UI
     * thread: the search engine lookup is native.
     */
    public static JSONObject pageProperties(GURL url) {
        JSONObject props = new JSONObject();
        if (url == null || !url.isValid()) return props;
        try {
            String host = url.getHost();
            if (host.startsWith("www.")) host = host.substring(4);
            props.put("domain", host);
            String site = UrlUtilities.getDomainAndRegistry(url.getSpec(), false);
            if (site != null && !site.isEmpty()) props.put("site", site);
            boolean youTube = "youtube.com".equals(site) || "youtu.be".equals(site);
            props.put("is_youtube", youTube);

            String query = defaultEngineSearchQuery(url);
            if (youTube) {
                String path = url.getPath();
                String page = youTubePage(path);
                props.put("youtube_page", page);
                if ("search".equals(page)) {
                    query = Uri.parse(url.getSpec()).getQueryParameter("search_query");
                }
            }
            props.put("is_search", query != null && !query.trim().isEmpty());
            if (query != null && !query.trim().isEmpty()) {
                String terms = query.trim();
                if (terms.length() > MAX_SEARCH_QUERY_LENGTH) {
                    terms = terms.substring(0, MAX_SEARCH_QUERY_LENGTH);
                }
                props.put("search_engine", host);
                props.put("search_query", terms);
            }
        } catch (JSONException e) {
            Log.e(TAG, "page properties error: " + e.getMessage());
        }
        return props;
    }

    /** "system", "light" or "dark": the app's theme setting as events and people carry it. */
    public static String themeSettingName(@ThemeType int theme) {
        switch (theme) {
            case ThemeType.LIGHT:
                return "light";
            case ThemeType.DARK:
                return "dark";
            default:
                return "system";
        }
    }

    private static String youTubePage(@Nullable String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "home";
        if (path.startsWith("/watch")) return "watch";
        if (path.startsWith("/shorts")) return "shorts";
        if (path.startsWith("/results")) return "search";
        if (path.startsWith("/@") || path.startsWith("/channel") || path.startsWith("/c/")) {
            return "channel";
        }
        return "other";
    }

    /** The search terms when {@code url} is a results page of the default search engine. */
    @Nullable
    private static String defaultEngineSearchQuery(GURL url) {
        try {
            TemplateUrlService service =
                    TemplateUrlServiceFactory.getForProfile(
                            ProfileManager.getLastUsedRegularProfile());
            if (!service.isSearchResultsPageFromDefaultSearchProvider(url)) return null;
            return service.getSearchQueryForUrl(url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reports how long the app stays in front and away: "App Backgrounded" with the time it was
     * in front, "App Foregrounded" with the time it was away. Call once the app is up (UI thread).
     */
    public static void watchAppVisibility() {
        if (sWatchingVisibility) return;
        sWatchingVisibility = true;
        sForegroundSinceMs = SystemClock.elapsedRealtime();
        ApplicationStatus.registerApplicationStateListener(
                state -> {
                    long now = SystemClock.elapsedRealtime();
                    if (state == ApplicationState.HAS_RUNNING_ACTIVITIES && sBackgroundSinceMs > 0) {
                        capture(
                                PostHogEventKeys.APP_FOREGROUNDED,
                                "background_ms",
                                now - sBackgroundSinceMs);
                        sBackgroundSinceMs = 0;
                        sForegroundSinceMs = now;
                    } else if (state == ApplicationState.HAS_STOPPED_ACTIVITIES
                            && sForegroundSinceMs > 0) {
                        capture(
                                PostHogEventKeys.APP_BACKGROUNDED,
                                "foreground_ms",
                                now - sForegroundSinceMs);
                        sForegroundSinceMs = 0;
                        sBackgroundSinceMs = now;
                    }
                });
    }

    /**
     * Merge an anonymous distinct_id (e.g. the pre-login install id
     * {@code anon-<deviceId>}) into the identified mongo user id, so PostHog
     * stitches the install/referral funnel onto the signed-in person instead of
     * leaving it on an orphan anonymous person. Sends PostHog's special
     * {@code $identify} event with {@code $anon_distinct_id}.
     */
    public static void identify(String userId, String anonymousDistinctId) {
        if (userId == null || userId.isEmpty()
                || anonymousDistinctId == null || anonymousDistinctId.isEmpty()
                || userId.equals(anonymousDistinctId)) {
            return;
        }
        try {
            JSONObject props = new JSONObject();
            props.put("$anon_distinct_id", anonymousDistinctId);
            new PostHogWorkerTask(IDENTIFY_EVENT, userId, props)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (JSONException e) {
            Log.e(TAG, "identify error: " + e.getMessage());
        }
    }

    /** Flush queued events. Safe to call from app startup. */
    public static void flushPendingEvents() {
        new AsyncTask<Void>() {
            @Override
            protected Void doInBackground() {
                flushPending();
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {}
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static SharedPreferences pendingPrefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE);
    }

    // commit() (not apply()) is intentional: apply() writes async and is
    // exactly what gets dropped when the OS freezes the process — the very
    // failure mode this queue exists to work around.
    @SuppressLint("ApplySharedPref")
    private static String enqueue(String event, String userId, JSONObject properties) {
        try {
            synchronized (sQueueLock) {
                SharedPreferences prefs = pendingPrefs();
                JSONArray arr = new JSONArray(prefs.getString(PENDING_KEY, "[]"));
                String id = UUID.randomUUID().toString();
                JSONObject entry = new JSONObject();
                entry.put("id", id);
                entry.put("event", event);
                entry.put("distinct_id", userId);
                entry.put("properties", properties != null ? properties : new JSONObject());
                arr.put(entry);
                // Cap the queue so a long offline stretch can't grow it without bound.
                while (arr.length() > MAX_PENDING) {
                    arr.remove(0);
                }
                prefs.edit().putString(PENDING_KEY, arr.toString()).commit();
                return id;
            }
        } catch (JSONException e) {
            Log.e(TAG, "enqueue error: " + e.getMessage());
            return null;
        }
    }

    @SuppressLint("ApplySharedPref") // See enqueue() — durability over speed.
    private static void removePending(String id) {
        if (id == null) return;
        try {
            synchronized (sQueueLock) {
                SharedPreferences prefs = pendingPrefs();
                JSONArray arr = new JSONArray(prefs.getString(PENDING_KEY, "[]"));
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e != null && !id.equals(e.optString("id"))) out.put(e);
                }
                prefs.edit().putString(PENDING_KEY, out.toString()).commit();
            }
        } catch (JSONException e) {
            Log.e(TAG, "removePending error: " + e.getMessage());
        }
    }

    private static void flushPending() {
        JSONArray snapshot;
        synchronized (sQueueLock) {
            try {
                snapshot = new JSONArray(pendingPrefs().getString(PENDING_KEY, "[]"));
            } catch (JSONException e) {
                return;
            }
        }
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject entry = snapshot.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id", null);
            String event = entry.optString("event", null);
            String userId = entry.optString("distinct_id", "ANONYMOUS");
            JSONObject props = entry.optJSONObject("properties");
            if (event == null) {
                removePending(id);
                continue;
            }
            if (sendPostHogRequest(event, userId, props)) {
                removePending(id);
            } else {
                // Stop on first failure — likely offline; try again next time.
                break;
            }
        }
    }

    private static boolean sendPostHogRequest(String event, String userId, JSONObject properties) {
        Log.e("Express Browser", "GETTING USER PROFILE 2");
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(GET_PROFILE_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            urlConnection.setConnectTimeout(8000);
            urlConnection.setReadTimeout(8000);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + POST_HOG_API_KEY);
            urlConnection.connect();

            JSONObject jsonParam = new JSONObject();
            jsonParam.put("api_key", POST_HOG_API_KEY);
            jsonParam.put("event", event);
            jsonParam.put("distinct_id", userId);
            jsonParam.put("properties", properties);

            Log.e(TAG, "Sending PostHog request: " + jsonParam.toString());

            OutputStream outputStream = urlConnection.getOutputStream();
            byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
            outputStream.write(input, 0, input.length);
            outputStream.flush();
            outputStream.close();

            int httpResult = urlConnection.getResponseCode();
            if (httpResult >= 200 && httpResult < 300) {
                return true;
            }
            Log.e(TAG, urlConnection.getResponseMessage());
            // 4xx is unrecoverable (bad payload / auth) — treat as success so we
            // don't retry forever. Only 5xx / network failure should re-queue.
            return httpResult >= 400 && httpResult < 500;
        } catch (MalformedURLException e) {
            Log.e(TAG, e.getMessage());
            return true; // URL is hard-coded — retrying won't help.
        } catch (IOException e) {
            Log.e(TAG, e.getMessage() != null ? e.getMessage() : "IOException");
            return false;
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            return true;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage() != null ? e.getMessage() : e.toString());
            return false;
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}
