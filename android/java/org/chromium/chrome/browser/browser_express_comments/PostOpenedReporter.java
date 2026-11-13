/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import androidx.annotation.Nullable;

import org.chromium.base.Log;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells the server the reader opened a post's stream: {@code POST /v1/post/:postId/opened}
 * (browser-express-node docs/new-takes.md). From then on the feed counts "new takes" from this
 * open, and the next stream load marks takes NEW against it.
 *
 * <p>Send it after the stream's first load, which is what measured {@code isNew} against the open
 * before. It is fire and forget: a lost report only means the next feed may show a callout for
 * takes the reader has already seen. Signed-out readers have nothing to report.
 */
public final class PostOpenedReporter {
    private static final String TAG = "PostOpened";
    private static final String POST_URL = "https://api.browser.express/v1/post/";
    private static final int TIMEOUT_MS = 15_000;

    private PostOpenedReporter() {}

    public static void report(@Nullable String postId) {
        if (postId == null || postId.isEmpty()) return;
        String token;
        try {
            token = BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (token == null || token.isEmpty()) return;
        PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK, () -> send(postId, token));
    }

    private static void send(String postId, String token) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(POST_URL + postId + "/opened");
            connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", token);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Recording the open failed: HTTP " + status);
                return;
            }
            try (InputStream body = connection.getInputStream()) {
                // Drained so the connection can be reused; the answer is only {success, …}.
                byte[] buffer = new byte[512];
                while (body.read(buffer) != -1) {}
            }
        } catch (IOException e) {
            Log.w(TAG, "Recording the open failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
