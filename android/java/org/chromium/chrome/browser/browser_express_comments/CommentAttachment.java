/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One file on a comment: a photo, GIF, video, voice note or document. Comments from the API carry
 * a list of these in {@code attachments} (browser-express-node docs/comment-attachments.md); a
 * comment still being sent carries the reader's local files, with content:// or file:// URLs.
 */
public class CommentAttachment {
    public static final String KIND_IMAGE = "image";
    public static final String KIND_GIF = "gif";
    public static final String KIND_VIDEO = "video";
    public static final String KIND_AUDIO = "audio";
    public static final String KIND_DOCUMENT = "document";

    private final String kind;
    private final String url;
    private final @Nullable String mime;
    private final long size;
    private final @Nullable String name;
    private final int width;
    private final int height;
    private final int durationMs;

    public CommentAttachment(String kind, String url, @Nullable String mime, long size,
            @Nullable String name, int width, int height, int durationMs) {
        this.kind = kind;
        this.url = url;
        this.mime = mime;
        this.size = size;
        this.name = name;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
    }

    public String getKind() {
        return kind;
    }

    public String getUrl() {
        return url;
    }

    @Nullable
    public String getMime() {
        return mime;
    }

    /** Bytes; 0 when unknown. */
    public long getSize() {
        return size;
    }

    @Nullable
    public String getName() {
        return name;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Length of a video or voice note; 0 when unknown. */
    public int getDurationMs() {
        return durationMs;
    }

    /** A photo, GIF or video: something with a picture to show. */
    public boolean isVisual() {
        return KIND_IMAGE.equals(kind) || KIND_GIF.equals(kind) || KIND_VIDEO.equals(kind);
    }

    public boolean isVideo() {
        return KIND_VIDEO.equals(kind);
    }

    /** Taller than wide, so a reel. */
    public boolean isPortrait() {
        return width > 0 && height > width;
    }

    /** The {@code attachments} array of a comments-API comment; empty when there is none. */
    public static List<CommentAttachment> readList(@Nullable JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<CommentAttachment> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("kind", "");
            String url = item.isNull("url") ? "" : item.optString("url", "");
            if (kind.isEmpty() || url.isEmpty()) continue;
            list.add(new CommentAttachment(kind, url,
                    item.isNull("mime") ? null : item.optString("mime", null),
                    item.optLong("size", 0),
                    item.isNull("name") ? null : item.optString("name", null),
                    item.optInt("width", 0), item.optInt("height", 0),
                    item.optInt("durationMs", 0)));
        }
        return list;
    }
}
