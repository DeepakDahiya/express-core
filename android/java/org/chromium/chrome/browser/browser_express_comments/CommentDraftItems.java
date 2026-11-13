/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.InputStream;

/**
 * Turns a picked or recorded file into a {@link CommentUploadManager.Item}: its kind, type, name,
 * size, and the picture's or recording's dimensions and length. Reads the file, so call it off the
 * UI thread.
 */
final class CommentDraftItems {
    private static final String TAG = "CommentDraftItems";

    private CommentDraftItems() {}

    /**
     * @param kindHint the attachment kind the picker was asked for; a photo that turns out to be a
     *     GIF becomes one, and anything visual is told apart by its type.
     * @param durationMs a recording's length when the recorder already knows it, else 0.
     * @return null when the file can't be read.
     */
    @WorkerThread
    @Nullable
    static CommentUploadManager.Item describe(
            Context context, Uri uri, String kindHint, int durationMs) {
        String mime = context.getContentResolver().getType(uri);
        if (mime == null && CommentAttachment.KIND_AUDIO.equals(kindHint)) mime = "audio/mp4";
        if (mime == null) return null;
        mime = mime.toLowerCase(java.util.Locale.ROOT);

        String kind = kindHint;
        if (mime.equals("image/gif")) {
            kind = CommentAttachment.KIND_GIF;
        } else if (mime.startsWith("image/")) {
            kind = CommentAttachment.KIND_IMAGE;
        } else if (mime.startsWith("video/")) {
            kind = CommentAttachment.KIND_VIDEO;
        }

        String name = displayName(context, uri);
        long size = CommentUploadManager.lengthOf(context, uri);

        int width = 0;
        int height = 0;
        int duration = durationMs;
        if (CommentAttachment.KIND_IMAGE.equals(kind) || CommentAttachment.KIND_GIF.equals(kind)) {
            int[] bounds = imageBounds(context, uri);
            width = bounds[0];
            height = bounds[1];
        } else if (CommentAttachment.KIND_VIDEO.equals(kind)
                || (CommentAttachment.KIND_AUDIO.equals(kind) && duration <= 0)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                duration = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (CommentAttachment.KIND_VIDEO.equals(kind)) {
                    width = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    height = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    int rotation =
                            intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (rotation == 90 || rotation == 270) {
                        int swap = width;
                        width = height;
                        height = swap;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't read the recording's details", e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // Nothing left to clean up.
                }
            }
        }
        return new CommentUploadManager.Item(
                uri, kind, mime, Math.max(size, 0), name, width, height, duration);
    }

    @Nullable
    private static String displayName(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (Cursor cursor = context.getContentResolver().query(
                     uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't read the file name", e);
        }
        return uri.getLastPathSegment();
    }

    private static int[] imageBounds(Context context, Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) BitmapFactory.decodeStream(in, null, options);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read the picture's size", e);
        }
        return new int[] {Math.max(options.outWidth, 0), Math.max(options.outHeight, 0)};
    }

    private static int intOf(MediaMetadataRetriever retriever, int key) {
        String value = retriever.extractMetadata(key);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
