/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import org.chromium.base.Log;

import java.io.File;
import java.io.IOException;

/**
 * Records a voice-note comment: AAC in an m4a container, written to the cache directory.
 *
 * <p>Encoding matches what the upload path already handles — the file goes up through the same
 * multipart {@code reports} field as an image or a video, tagged {@code mediaType=audio}.
 */
public class AudioCommentRecorder {
    private static final String TAG = "AudioCommentRecorder";

    /** Matches the server-side clamp in the comments controller. */
    public static final long MAX_DURATION_MS = 60 * 1000L;

    /**
     * Anything shorter than this is a mis-tap rather than a message. Discarded on release so a
     * stray touch on the mic does not post an empty clip.
     */
    public static final long MIN_DURATION_MS = 500L;

    private static final int MAX_AMPLITUDE = 32767;

    private final Context mContext;
    private MediaRecorder mRecorder;
    private File mOutputFile;
    private long mStartedAtMs;

    public AudioCommentRecorder(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isRecording() {
        return mRecorder != null;
    }

    /**
     * Starts recording into a fresh file.
     *
     * @return true if the microphone opened. False means the caller should drop back to the idle
     *     input bar — the mic may be held by a call or another app.
     */
    public boolean start() {
        if (mRecorder != null) return true;

        try {
            File dir = new File(mContext.getCacheDir(), "voice_comments");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Could not create voice comment cache directory");
                return false;
            }
            mOutputFile = new File(dir, "voice_" + System.currentTimeMillis() + ".m4a");

            mRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(mContext)
                    : new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(64000);
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setOutputFile(mOutputFile.getAbsolutePath());
            mRecorder.prepare();
            mRecorder.start();

            mStartedAtMs = SystemClock.elapsedRealtime();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to start recording", e);
            releaseRecorder();
            deleteOutput();
            return false;
        }
    }

    /** Milliseconds recorded so far, or 0 when idle. */
    public long getElapsedMs() {
        return mRecorder == null ? 0 : SystemClock.elapsedRealtime() - mStartedAtMs;
    }

    /**
     * Current microphone level as 0..1, for the live bars.
     *
     * <p>Square-rooted because {@code getMaxAmplitude()} is linear and speech spends most of its
     * time near the bottom of that range — drawn raw, the strip barely moves.
     */
    public float getAmplitude() {
        if (mRecorder == null) return 0f;
        try {
            float linear = mRecorder.getMaxAmplitude() / (float) MAX_AMPLITUDE;
            return (float) Math.sqrt(Math.max(0f, Math.min(1f, linear)));
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /**
     * Stops and keeps the recording.
     *
     * @return the finished clip, or null if it was too short or the encoder produced nothing — in
     *     which case the file has already been cleaned up and nothing should be posted.
     */
    public Result stop() {
        if (mRecorder == null) return null;

        long elapsed = getElapsedMs();
        boolean stoppedCleanly = true;
        try {
            mRecorder.stop();
        } catch (RuntimeException e) {
            // stop() throws when the encoder captured nothing at all, and the output file is
            // invalid when it does. Treat it exactly like a too-short recording.
            Log.e(TAG, "Recorder stopped with no output", e);
            stoppedCleanly = false;
        } finally {
            releaseRecorder();
        }

        File file = mOutputFile;
        if (!stoppedCleanly || elapsed < MIN_DURATION_MS || file == null || !file.exists()
                || file.length() == 0) {
            deleteOutput();
            return null;
        }

        mOutputFile = null;
        long durationMs = Math.min(elapsed, MAX_DURATION_MS);
        // A plain file:// URI is enough here, and avoids adding a cache path to Chromium's
        // FileProvider config: the clip is ours, in our own cache, and is read back in this same
        // process by the upload service. It never crosses a process boundary.
        return new Result(Uri.fromFile(file), durationMs);
    }

    /** Stops and throws the recording away — the slide-to-cancel path. */
    public void cancel() {
        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (RuntimeException e) {
                // Nothing captured; the file is discarded either way.
            } finally {
                releaseRecorder();
            }
        }
        deleteOutput();
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.release();
            } catch (RuntimeException e) {
                Log.e(TAG, "Recorder release failed", e);
            }
            mRecorder = null;
        }
    }

    private void deleteOutput() {
        if (mOutputFile != null) {
            if (!mOutputFile.delete()) {
                Log.w(TAG, "Could not delete discarded voice comment");
            }
            mOutputFile = null;
        }
    }

    /** A finished recording, ready to hand to the upload path. */
    public static class Result {
        private final Uri mUri;
        private final long mDurationMs;

        Result(Uri uri, long durationMs) {
            mUri = uri;
            mDurationMs = durationMs;
        }

        public Uri getUri() {
            return mUri;
        }

        public int getDurationMs() {
            return (int) mDurationMs;
        }
    }
}
