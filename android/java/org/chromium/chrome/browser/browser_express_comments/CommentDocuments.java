/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.format.Formatter;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Document attachments on comments: what can be picked, and how a document is labelled on the
 * composer's preview and on the comment card. The server accepts the same types and size (see
 * utils/comment-documents.ts in the API).
 */
public final class CommentDocuments {
    /** Largest document a comment may carry. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** What the document picker offers. */
    public static final String[] PICKER_MIME_TYPES = {
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
        "text/plain",
        "text/csv",
        "text/rtf",
    };

    private CommentDocuments() {}

    /** Whether {@code mimeType} is one the server accepts as a document. */
    public static boolean isSupported(@Nullable String mimeType) {
        if (mimeType == null) return false;
        for (String type : PICKER_MIME_TYPES) {
            if (type.equalsIgnoreCase(mimeType)) return true;
        }
        return false;
    }

    /** The file's name as the provider reports it, or null. */
    @Nullable
    public static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                     uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // Providers may refuse the query; the caller falls back to a generic name.
        }
        String path = uri.getLastPathSegment();
        return path != null && !path.isEmpty() ? path : null;
    }

    /** The file's size in bytes, or -1 when the provider does not say. */
    public static long size(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                     uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException e) {
            // Unknown size; the server still enforces the limit.
        }
        return -1;
    }

    /** A name safe to send as the multipart file name (it becomes part of the stored URL). */
    public static String uploadFileName(@Nullable String displayName) {
        String name = displayName == null || displayName.trim().isEmpty()
                ? "document"
                : displayName.trim();
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** "PDF · 1.2 MB": the type (from the extension) and the size, whichever are known. */
    public static String describe(Context context, @Nullable String name, long sizeBytes) {
        String extension = null;
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                extension = name.substring(dot + 1).toUpperCase(Locale.ROOT);
            }
        }
        String size = sizeBytes > 0 ? Formatter.formatShortFileSize(context, sizeBytes) : null;
        if (extension != null && size != null) return extension + " · " + size;
        if (extension != null) return extension;
        return size != null ? size : "";
    }
}
