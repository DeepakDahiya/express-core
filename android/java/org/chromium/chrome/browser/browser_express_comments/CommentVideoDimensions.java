/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.MetadataRetriever;
import androidx.media3.exoplayer.source.TrackGroupArray;

import com.google.common.util.concurrent.ListenableFuture;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the pixel dimensions of a video comment, so the reel pager can tell portrait from
 * landscape before handing a whole page to it.
 *
 * <p>Dimensions arrive from three directions and meet here:
 * <ul>
 *   <li>{@link #fetchAsync} probes the URL. Remote videos go through media3's
 *       {@link MetadataRetriever}, which parses just the container over the same networking
 *       stack ExoPlayer itself uses — so it succeeds wherever playback would, and typically
 *       answers within the initial-pack hold. Local files (a just-picked attachment) use
 *       {@link MediaMetadataRetriever}, which reads them directly.
 *   <li>{@link #recordDimensions} accepts what an ExoPlayer instance reported once it prepared
 *       the same stream — the safety net for a probe that lost the race or failed.
 * </ul>
 * Whichever lands first wins: every waiter registered through {@link #fetchAsync} is notified
 * exactly once, and the result is cached — in memory for this process, and in SharedPreferences
 * across restarts, so a video the app has ever seen renders correctly on the first pack of every
 * later session with no probe at all.
 *
 * <p>All entry points must be called on the UI thread; probing runs in the background.
 */
public class CommentVideoDimensions {
    private static final String TAG = "CommentVideoDims";

    private static final String PREFS_NAME = "browser_express_video_dimensions";
    /** Crude growth bound: past this the persisted cache is dropped and rebuilt organically. */
    private static final int MAX_PERSISTED_ENTRIES = 500;
    private static final long REMOTE_PROBE_TIMEOUT_MS = 8000;

    /** url -> {width, height}, upright (rotation already folded in). UI thread only. */
    private static final Map<String, int[]> sDimensionsByUrl = new HashMap<>();

    /** URLs with a probe in flight, so one is not started twice. */
    private static final Set<String> sProbeInFlight = new HashSet<>();

    /** URLs whose probe failed; not re-probed, but a player report can still resolve them. */
    private static final Set<String> sProbeFailed = new HashSet<>();

    /** Waiters per URL, run (and cleared) when its dimensions first become known. */
    private static final Map<String, List<Runnable>> sWaitersByUrl = new HashMap<>();

    private static boolean sPersistedLoaded;

    private CommentVideoDimensions() {}

    /**
     * Copies already-known dimensions onto the comment, without any I/O beyond the one-time
     * SharedPreferences read.
     *
     * @return true when the comment now has dimensions (either it already did, or the cache had
     *     its URL). False means they are still unknown.
     */
    public static boolean applyCached(Comment comment) {
        ThreadUtils.assertOnUiThread();
        if (comment == null || !comment.hasVideoMedia()) return false;
        if (comment.hasCachedDimensions()) return true;
        loadPersistedIfNeeded();
        int[] dims = sDimensionsByUrl.get(comment.getMediaVideoUrl());
        if (dims == null) return false;
        comment.setMediaWidth(dims[0]);
        comment.setMediaHeight(dims[1]);
        return true;
    }

    /**
     * Resolves the comment's video dimensions in the background. When they become known — from
     * this probe, a concurrent one for the same URL, or a player report — the dimensions are
     * written onto the comment and {@code onResolved} runs on the UI thread. If the probe fails
     * and no player ever reports the size, the callback never runs; callers deal with that by
     * treating an unresolved video as landscape.
     *
     * @return true when a resolution is pending and the callback may fire; false when it
     *     certainly will not (no video, already resolved, or the URL is known-unprobeable).
     */
    public static boolean fetchAsync(Context context, Comment comment, Runnable onResolved) {
        ThreadUtils.assertOnUiThread();
        if (comment == null || !comment.hasVideoMedia() || comment.hasCachedDimensions()) {
            return false;
        }
        loadPersistedIfNeeded();
        final String url = comment.getMediaVideoUrl();

        // The waiter both applies the dimensions to this caller's Comment object and forwards
        // the news; registered before the in-flight check so a probe started by an earlier
        // sheet still notifies this one.
        Runnable waiter = () -> {
            applyCached(comment);
            if (onResolved != null) onResolved.run();
        };
        List<Runnable> waiters = sWaitersByUrl.get(url);
        if (waiters == null) {
            waiters = new ArrayList<>();
            sWaitersByUrl.put(url, waiters);
        }
        waiters.add(waiter);

        if (sProbeInFlight.contains(url)) return true;
        if (sProbeFailed.contains(url)) return false; // Only a player report can save it now.
        sProbeInFlight.add(url);

        final Context appContext = context.getApplicationContext();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            startRemoteProbe(appContext, url);
        } else {
            startLocalProbe(appContext, url);
        }
        return true;
    }

    /**
     * Stores upright dimensions learned out-of-band — in practice from an ExoPlayer that has
     * prepared this URL — and notifies every waiter. First writer per URL wins; later reports
     * of the same video are no-ops.
     */
    public static void recordDimensions(String url, int width, int height) {
        ThreadUtils.assertOnUiThread();
        if (url == null || width <= 0 || height <= 0) return;
        loadPersistedIfNeeded();
        if (sDimensionsByUrl.containsKey(url)) return;
        sDimensionsByUrl.put(url, new int[] {width, height});
        sProbeFailed.remove(url);

        // Only remote URLs persist: a content:// or file:// URI names a transient local copy
        // that will never be fetched again once the upload has replaced it.
        if (url.startsWith("http://") || url.startsWith("https://")) {
            prefs().edit().putString(url, width + "x" + height).apply();
        }

        List<Runnable> waiters = sWaitersByUrl.remove(url);
        if (waiters != null) {
            for (Runnable waiter : waiters) {
                waiter.run();
            }
        }
    }

    /**
     * Reads upright {width, height} straight from a local video file or content URI, or null.
     * Pure function, safe from any background thread — used by the upload path to send the
     * dimensions to the API alongside the file.
     */
    public static int[] probeLocalSync(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            int width = extractInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = extractInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int rotation =
                    extractInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (width <= 0 || height <= 0) return null;
            // Phone recordings are stored landscape with a 90°/270° rotation flag; what matters
            // is the orientation as displayed.
            if (rotation == 90 || rotation == 270) {
                return new int[] {height, width};
            }
            return new int[] {width, height};
        } catch (Exception e) {
            Log.e(TAG, "Failed to probe local video " + uri + ": " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Parses the remote container with media3 — no playback, no buffering, and crucially the
     * same HTTP stack as the player, so it works on servers {@link MediaMetadataRetriever}
     * chokes on.
     */
    private static void startRemoteProbe(Context context, String url) {
        final ListenableFuture<TrackGroupArray> future;
        try {
            // Through the shared cache factory: the container headers the probe pulls are
            // exactly the bytes playback needs first, so a successful probe doubles as a
            // warm start for the player (and a played video probes from disk).
            future =
                    MetadataRetriever.retrieveMetadata(
                            CommentMediaCache.mediaSourceFactory(context),
                            MediaItem.fromUri(url));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start remote probe for " + url + ": " + e.getMessage());
            onProbeFinished(url, null);
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> future.cancel(true);
        handler.postDelayed(timeout, REMOTE_PROBE_TIMEOUT_MS);
        future.addListener(() -> {
            handler.removeCallbacks(timeout);
            int[] dims = null;
            try {
                dims = videoDimensionsOf(future.get());
            } catch (Exception e) {
                Log.e(TAG, "Remote probe failed for " + url + ": " + e.getMessage());
            }
            onProbeFinished(url, dims);
        }, command -> handler.post(command));
    }

    private static int[] videoDimensionsOf(TrackGroupArray trackGroups) {
        if (trackGroups == null) return null;
        for (int i = 0; i < trackGroups.length; i++) {
            for (int j = 0; j < trackGroups.get(i).length; j++) {
                Format format = trackGroups.get(i).getFormat(j);
                if (format.width <= 0 || format.height <= 0) continue;
                if (format.rotationDegrees == 90 || format.rotationDegrees == 270) {
                    return new int[] {format.height, format.width};
                }
                return new int[] {format.width, format.height};
            }
        }
        return null;
    }

    private static void startLocalProbe(Context context, String url) {
        new AsyncTask<int[]>() {
            @Override
            protected int[] doInBackground() {
                return probeLocalSync(context, Uri.parse(url));
            }

            @Override
            protected void onPostExecute(int[] dims) {
                onProbeFinished(url, dims);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static void onProbeFinished(String url, int[] dims) {
        ThreadUtils.assertOnUiThread();
        sProbeInFlight.remove(url);
        if (dims == null) {
            sProbeFailed.add(url);
            return;
        }
        recordDimensions(url, dims[0], dims[1]);
    }

    private static void loadPersistedIfNeeded() {
        if (sPersistedLoaded) return;
        sPersistedLoaded = true;
        SharedPreferences prefs = prefs();
        Map<String, ?> persisted = prefs.getAll();
        if (persisted.size() > MAX_PERSISTED_ENTRIES) {
            prefs.edit().clear().apply();
            return;
        }
        for (Map.Entry<String, ?> entry : persisted.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String[] parts = ((String) entry.getValue()).split("x");
            if (parts.length != 2) continue;
            try {
                int width = Integer.parseInt(parts[0]);
                int height = Integer.parseInt(parts[1]);
                if (width > 0 && height > 0) {
                    sDimensionsByUrl.put(entry.getKey(), new int[] {width, height});
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int extractInt(MediaMetadataRetriever retriever, int key) {
        String value = retriever.extractMetadata(key);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
