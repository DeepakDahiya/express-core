/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;

import androidx.annotation.Nullable;

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
 * Pulls the opening frame out of a comment video and keeps it around, so a still of a video can
 * be drawn without standing a player up for it.
 *
 * <p>The one caller today is the reel pager's sneak peek: the page under a reel shows the top of
 * the video waiting below, and a frame is all that takes. Extraction goes through
 * {@link MediaMetadataRetriever}, which reads the first sync sample over range requests rather
 * than streaming the file — cheap enough to run a page ahead of the reader.
 *
 * <p>Frames are held in a small byte-bounded cache and never persisted: they are a scroll-time
 * nicety, and a video whose frame is missing simply shows no preview. A URL whose extraction
 * fails is remembered and not retried.
 *
 * <p>All entry points must be called on the UI thread; extraction runs in the background.
 */
public class CommentVideoFrames {
    private static final String TAG = "CommentVideoFrames";

    /**
     * The peek strip is a fraction of one screen, so a frame this size is already more detail
     * than it can show, and it keeps the cache small enough to hold several videos at once.
     */
    private static final int MAX_FRAME_DIMENSION_PX = 720;

    /** Roughly three 720x1280 frames. */
    private static final int CACHE_BYTES = 12 * 1024 * 1024;

    /** Notified once, on the UI thread, when a requested frame becomes available. */
    public interface FrameListener {
        void onFrameReady(String url, Bitmap frame);
    }

    private static final LruCache<String, Bitmap> sFrames =
            new LruCache<String, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** URLs with an extraction in flight, so one is not started twice. */
    private static final Set<String> sInFlight = new HashSet<>();

    /** URLs whose extraction failed; not retried. */
    private static final Set<String> sFailed = new HashSet<>();

    /** Listeners per URL, run (and cleared) when its frame lands. */
    private static final Map<String, List<FrameListener>> sListenersByUrl = new HashMap<>();

    private CommentVideoFrames() {}

    /**
     * Hands back the video's opening frame, extracting it in the background if this is the first
     * ask.
     *
     * @param listener notified on the UI thread when the frame lands. Not called when the frame
     *     is already cached — the return value is the answer then — and not called at all if
     *     extraction fails.
     * @return the cached frame, or null while it is still being fetched (or if it never will be).
     */
    @Nullable
    public static Bitmap requestFrame(Context context, String url, FrameListener listener) {
        ThreadUtils.assertOnUiThread();
        if (url == null || url.isEmpty()) return null;

        Bitmap cached = sFrames.get(url);
        if (cached != null) return cached;
        if (sFailed.contains(url)) return null;

        if (listener != null) {
            List<FrameListener> listeners = sListenersByUrl.get(url);
            if (listeners == null) {
                listeners = new ArrayList<>();
                sListenersByUrl.put(url, listeners);
            }
            listeners.add(listener);
        }

        if (sInFlight.contains(url)) return null;
        sInFlight.add(url);

        final Context appContext = context.getApplicationContext();
        new AsyncTask<Bitmap>() {
            @Override
            protected Bitmap doInBackground() {
                return extractFrame(appContext, url);
            }

            @Override
            protected void onPostExecute(Bitmap frame) {
                onFrameExtracted(url, frame);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        return null;
    }

    /** Drops a listener that is no longer interested — a recycled view holder, in practice. */
    public static void cancel(String url, FrameListener listener) {
        ThreadUtils.assertOnUiThread();
        if (url == null || listener == null) return;
        List<FrameListener> listeners = sListenersByUrl.get(url);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) sListenersByUrl.remove(url);
    }

    private static void onFrameExtracted(String url, @Nullable Bitmap frame) {
        ThreadUtils.assertOnUiThread();
        sInFlight.remove(url);
        if (frame == null) {
            sFailed.add(url);
            sListenersByUrl.remove(url);
            return;
        }
        sFrames.put(url, frame);
        List<FrameListener> listeners = sListenersByUrl.remove(url);
        if (listeners == null) return;
        for (FrameListener listener : listeners) {
            listener.onFrameReady(url, frame);
        }
    }

    /**
     * Reads the first sync frame, leaves it upright, and shrinks it to
     * {@link #MAX_FRAME_DIMENSION_PX}. Returns null for anything the retriever cannot open —
     * some servers refuse the range requests it makes, and the caller treats that as "no
     * preview" rather than an error.
     */
    @Nullable
    private static Bitmap extractFrame(Context context, String url) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                retriever.setDataSource(url, new HashMap<String, String>());
            } else {
                retriever.setDataSource(context, Uri.parse(url));
            }
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            frame = uprightOf(frame, rotationOf(retriever));
            return scaleDown(frame);
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "Failed to extract frame from " + url + ": " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int rotationOf(MediaMetadataRetriever retriever) {
        String value =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Phone recordings are stored landscape with a rotation flag. Whether {@code getFrameAtTime}
     * has already applied it varies by device, so this decides from the frame itself: a quarter
     * turn is applied only when the frame is still the wrong way round for its flag.
     */
    private static Bitmap uprightOf(Bitmap frame, int rotationDegrees) {
        if (rotationDegrees != 90 && rotationDegrees != 270) return frame;
        boolean alreadyUpright = frame.getHeight() >= frame.getWidth();
        if (alreadyUpright) return frame;

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(
                frame, 0, 0, frame.getWidth(), frame.getHeight(), matrix, true);
        if (rotated != frame) frame.recycle();
        return rotated;
    }

    private static Bitmap scaleDown(Bitmap frame) {
        int largest = Math.max(frame.getWidth(), frame.getHeight());
        if (largest <= MAX_FRAME_DIMENSION_PX) return frame;

        float scale = (float) MAX_FRAME_DIMENSION_PX / largest;
        int width = Math.max(1, Math.round(frame.getWidth() * scale));
        int height = Math.max(1, Math.round(frame.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }
}
