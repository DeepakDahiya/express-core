/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.ObserverList;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends comments from the comments sheet, with or without files (Figma "Comments · Complete
 * Attachment &amp; Upload Flow", 498:2056).
 *
 * <p>Each file goes straight to S3 on its own presigned PUT, so its progress can be shown, the
 * upload can be cancelled, and a retry resends only the files that did not arrive. Then the
 * comment is created with the uploaded keys (browser-express-node docs/comment-attachments.md).
 * Against a server that predates that API, a comment with one file falls back to the old
 * multipart upload, without progress.
 *
 * <p>One instance per process, used on the UI thread: an upload carries on while the reader moves
 * between comment pages or closes the sheet, and listeners hear about every job.
 */
public final class CommentUploadManager {
    private static final String TAG = "CommentUpload";

    private static final String COMMENT_URL = "https://api.browser.express/v1/comment";
    private static final String PRESIGN_URL = COMMENT_URL + "/attachments/presign";

    /** Most files on one comment; the server enforces the same. */
    public static final int MAX_FILES = 10;
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;
    private static final long MAX_OTHER_BYTES = 20L * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** S3 answers only once the whole body is in, so a slow link needs time for the tail. */
    private static final int UPLOAD_READ_TIMEOUT_MS = 120000;
    private static final int BUFFER_BYTES = 64 * 1024;
    /** Progress reaches listeners at most this often. */
    private static final long PROGRESS_INTERVAL_MS = 120;

    /** A file on a comment being sent. Uploaded once: a retry skips files already up. */
    public static final class Item {
        public final Uri uri;
        public final String kind;
        public final String mime;
        public final long size;
        public final @Nullable String name;
        public final int width;
        public final int height;
        public final int durationMs;

        /** What is actually uploaded (a scaled-down photo, say), set by the first attempt. */
        private @Nullable Uri mSendUri;
        private @Nullable String mSendMime;
        private long mSendSize;
        /** Where the file is in S3, once it is there. */
        private @Nullable String mKey;

        public Item(Uri uri, String kind, String mime, long size, @Nullable String name, int width,
                int height, int durationMs) {
            this.uri = uri;
            this.kind = kind;
            this.mime = mime;
            this.size = size;
            this.name = name;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
        }

        /** The file as a comment attachment, pointing at the local file until it is posted. */
        public CommentAttachment toAttachment() {
            return new CommentAttachment(kind, uri.toString(), mime, size, name, width, height,
                    durationMs);
        }

        public boolean isVideo() {
            return CommentAttachment.KIND_VIDEO.equals(kind);
        }

        public boolean isAudio() {
            return CommentAttachment.KIND_AUDIO.equals(kind);
        }

        /** Larger than the server accepts for its kind. */
        public boolean isTooLarge() {
            return size > (isVideo() ? MAX_VIDEO_BYTES : MAX_OTHER_BYTES);
        }
    }

    public enum State { UPLOADING, POSTED, FAILED, CANCELLED }

    /** One comment being sent. Its fields belong to the UI thread unless noted. */
    public static final class Job {
        public final String tempId;
        public final String content;
        public final String parentType;
        public final @Nullable String parentId;
        public final @Nullable String pageUrl;
        public final List<Item> items;
        /** {@link SystemClock#elapsedRealtime} when it was first sent; retries keep it. */
        final long startedAtMs = SystemClock.elapsedRealtime();

        private State mState = State.UPLOADING;
        /** 0 to 1 while files upload; negative while unknown. */
        private float mProgress = -1f;
        private @Nullable String mError;
        /** Read by the worker; set from the UI thread. */
        private volatile boolean mCancelRequested;
        /** The upload in flight, so a cancel can cut it off. Worker thread. */
        private volatile @Nullable HttpURLConnection mConnection;
        /** Worker thread. */
        private @Nullable String mAccessToken;
        private @Nullable String mNewAccessToken;
        private @Nullable String mNewRefreshToken;

        Job(String tempId, String content, String parentType, @Nullable String parentId,
                @Nullable String pageUrl, List<Item> items, @Nullable String accessToken) {
            this.tempId = tempId;
            this.content = content;
            this.parentType = parentType;
            this.parentId = parentId;
            this.pageUrl = pageUrl;
            this.items = items;
            this.mAccessToken = accessToken;
        }

        public State getState() {
            return mState;
        }

        public float getProgress() {
            return mProgress;
        }

        @Nullable
        public String getError() {
            return mError;
        }

        public boolean hasFiles() {
            return !items.isEmpty();
        }

        /** The comment carries a video, whose upload gets its own status toast. */
        public boolean hasVideo() {
            for (Item item : items) {
                if (item.isVideo()) return true;
            }
            return false;
        }

        /** Nothing but a voice note. */
        public boolean isVoiceOnly() {
            return items.size() == 1 && items.get(0).isAudio();
        }

        /** The comment's only file when that is a video, else null. */
        @Nullable
        public Item singleVideo() {
            return items.size() == 1 && items.get(0).isVideo() ? items.get(0) : null;
        }
    }

    public interface Listener {
        /** More of the job is up, or it failed or was cancelled. */
        void onJobUpdated(Job job);

        /** The comment is posted; {@code comment} is the server's copy of it. */
        void onJobPosted(Job job, Comment comment);
    }

    private static CommentUploadManager sInstance;

    private final Map<String, Job> mJobs = new HashMap<>();
    private final ObserverList<Listener> mListeners = new ObserverList<>();

    public static CommentUploadManager getInstance() {
        ThreadUtils.assertOnUiThread();
        if (sInstance == null) sInstance = new CommentUploadManager();
        return sInstance;
    }

    private CommentUploadManager() {}

    public void addListener(Listener listener) {
        mListeners.addObserver(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.removeObserver(listener);
    }

    @Nullable
    public Job getJob(String tempId) {
        return mJobs.get(tempId);
    }

    /**
     * Starts sending a comment.
     *
     * @param tempId the optimistic comment's id, which the job is known by.
     * @param parentType "page", "post" or "comment".
     */
    public Job send(String tempId, String content, String parentType, @Nullable String parentId,
            @Nullable String pageUrl, List<Item> items, @Nullable String accessToken) {
        Job job = new Job(tempId, content, parentType, parentId, pageUrl,
                new ArrayList<>(items), accessToken);
        mJobs.put(tempId, job);
        run(job);
        return job;
    }

    /** Tries a failed job again, resending only the files that are not up yet. */
    public void retry(String tempId) {
        Job job = mJobs.get(tempId);
        if (job == null || job.mState != State.FAILED) return;
        job.mState = State.UPLOADING;
        job.mError = null;
        job.mProgress = -1f;
        job.mCancelRequested = false;
        job.mAccessToken = currentAccessToken();
        notifyUpdated(job);
        run(job);
    }

    /**
     * Stops a job that is still uploading. A comment whose files are all up may already be on
     * its way to the server; if that lands, it is reported as posted after all.
     */
    public void cancel(String tempId) {
        Job job = mJobs.get(tempId);
        if (job == null || job.mState != State.UPLOADING) return;
        job.mCancelRequested = true;
        job.mState = State.CANCELLED;
        HttpURLConnection connection = job.mConnection;
        if (connection != null) {
            // Closing the socket is what makes the blocked write throw; keep it off this thread.
            AsyncTask.THREAD_POOL_EXECUTOR.execute(connection::disconnect);
        }
        notifyUpdated(job);
    }

    /** Forgets a failed or cancelled job, e.g. once its files are back in the comment box. */
    public void dismiss(String tempId) {
        Job job = mJobs.get(tempId);
        if (job != null && job.mState != State.UPLOADING) mJobs.remove(tempId);
    }

    private void run(Job job) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> work(job));
    }

    private void notifyUpdated(Job job) {
        for (Listener listener : mListeners) listener.onJobUpdated(job);
    }

    // -------------------------------------------------------------------------------------------
    // Worker thread
    // -------------------------------------------------------------------------------------------

    private static final class CancelledException extends IOException {}

    private static final class ServerException extends IOException {
        ServerException(String message) {
            super(message);
        }
    }

    /** The server predates presigned attachments (it answered 404). */
    private static final class LegacyServerException extends IOException {}

    private void work(Job job) {
        Context context = ContextUtils.getApplicationContext();
        // Which step a failure happened in, for the log: the banner can't say much more than
        // "couldn't post", so this is what tells a failed upload apart from a refused comment.
        String step = "preparing";
        try {
            long total = 0;
            for (Item item : job.items) {
                if (item.mKey != null) continue;
                step = "preparing " + describe(item);
                prepare(context, item);
                total += item.mSendSize;
            }

            long done = 0;
            for (Item item : job.items) {
                if (item.mKey != null) continue;
                checkCancelled(job);
                step = "presigning " + describe(item);
                JSONObject signed = presign(job, item);
                step = "uploading " + describe(item) + " to storage";
                upload(context, job, item, signed, done, total);
                done += item.mSendSize;
                item.mKey = signed.getString("key");
            }

            checkCancelled(job);
            if (total > 0) postProgress(job, 1f);
            step = "creating the comment";
            Comment comment = create(job);
            ThreadUtils.postOnUiThread(() -> onPosted(job, comment));
        } catch (LegacyServerException e) {
            sendTheOldWay(job);
        } catch (Exception e) {
            if (job.mCancelRequested) {
                Log.i(TAG, "Upload cancelled");
                return;
            }
            Log.e(TAG, "Sending the comment failed while " + step, e);
            String message = e instanceof ServerException ? e.getMessage() : null;
            ThreadUtils.postOnUiThread(() -> onFailed(job, message));
        }
    }

    /** "video/mp4 of 18874368 bytes" (the size once prepared), for the log. */
    private static String describe(Item item) {
        String mime = item.mSendMime != null ? item.mSendMime : item.mime;
        long size = item.mSendSize > 0 ? item.mSendSize : item.size;
        return item.kind + " " + mime + " of " + size + " bytes";
    }

    private static void checkCancelled(Job job) throws CancelledException {
        if (job.mCancelRequested) throw new CancelledException();
    }

    /**
     * Settles what goes up for a file: photos are scaled down (as the old composer did), and
     * anything whose length the provider can't tell is copied to the cache first, because a
     * presigned PUT has to declare it.
     */
    private static void prepare(Context context, Item item) throws IOException {
        if (item.mSendUri != null) return;
        Uri uri = item.uri;
        String mime = item.mime;
        if (CommentAttachment.KIND_IMAGE.equals(item.kind)) {
            Pair<Uri, String> scaled = ImageProcessor.processSync(context, uri,
                    ImageProcessor.DEFAULT_MAX_IMAGE_DIMENSION,
                    ImageProcessor.DEFAULT_IMAGE_COMPRESSION_QUALITY);
            if (scaled != null && scaled.first != null) {
                uri = scaled.first;
                mime = scaled.second;
            }
        }
        long length = lengthOf(context, uri);
        if (length <= 0) {
            uri = copyToCache(context, uri);
            length = lengthOf(context, uri);
        }
        if (length <= 0) throw new IOException("The file is empty");
        item.mSendUri = uri;
        item.mSendMime = mime;
        item.mSendSize = length;
    }

    /** The byte length of a file or content URI, or -1 when unknown. */
    static long lengthOf(Context context, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            return file.exists() ? file.length() : -1;
        }
        try (Cursor cursor = context.getContentResolver().query(
                     uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't read the file size", e);
        }
        return -1;
    }

    private static Uri copyToCache(Context context, Uri uri) throws IOException {
        File dir = new File(context.getCacheDir(), "comment-media");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No cache directory");
        File copy = File.createTempFile("upload_", ".bin", dir);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(copy)) {
            if (in == null) throw new IOException("Can't read the file");
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return Uri.fromFile(copy);
    }

    private JSONObject presign(Job job, Item item) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("kind", item.kind);
        body.put("mime", item.mSendMime);
        body.put("size", item.mSendSize);
        if (item.name != null) body.put("name", item.name);
        HttpURLConnection connection = openApi(PRESIGN_URL, job.mAccessToken);
        try {
            writeJson(connection, body);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw new LegacyServerException();
            JSONObject response = readJson(connection, code);
            rememberTokens(job, response);
            if (!response.optBoolean("success", false)) {
                throw new ServerException(errorOf(response, code));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private void upload(Context context, Job job, Item item, JSONObject signed, long doneBefore,
            long total) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                new URL(signed.getString("uploadUrl")),
                NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
        job.mConnection = connection;
        try {
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
            // S3 needs the length up front (it takes no chunked bodies), and declaring it also
            // keeps HttpURLConnection from buffering the whole file to work it out.
            connection.setFixedLengthStreamingMode(item.mSendSize);
            JSONObject headers = signed.optJSONObject("headers");
            if (headers != null) {
                for (Iterator<String> names = headers.keys(); names.hasNext();) {
                    String name = names.next();
                    connection.setRequestProperty(name, headers.getString(name));
                }
            }
            checkCancelled(job);

            long sent = 0;
            long lastReport = 0;
            try (InputStream in = context.getContentResolver().openInputStream(item.mSendUri);
                    OutputStream out = connection.getOutputStream()) {
                if (in == null) throw new IOException("Can't read the file");
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(job);
                    out.write(buffer, 0, read);
                    sent += read;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now;
                        postProgress(job, total > 0 ? (float) (doneBefore + sent) / total : -1f);
                    }
                }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                // S3 says why in a short XML body (SignatureDoesNotMatch, EntityTooLarge…).
                throw new IOException("Storage answered " + code + ": " + errorBody(connection));
            }
        } finally {
            job.mConnection = null;
            connection.disconnect();
        }
    }

    private Comment create(Job job) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("content", job.content);
        body.put("platform", "Android");
        body.put("parentType", job.parentType);
        if (job.parentId != null) body.put("parentId", job.parentId);
        if (job.pageUrl != null) body.put("url", job.pageUrl);
        if (!job.items.isEmpty()) {
            JSONArray attachments = new JSONArray();
            for (Item item : job.items) {
                JSONObject attachment = new JSONObject();
                attachment.put("key", item.mKey);
                attachment.put("kind", item.kind);
                if (item.name != null) attachment.put("name", item.name);
                if (item.width > 0) attachment.put("width", item.width);
                if (item.height > 0) attachment.put("height", item.height);
                if (item.durationMs > 0) attachment.put("durationMs", item.durationMs);
                attachments.put(attachment);
            }
            body.put("attachments", attachments);
        }

        HttpURLConnection connection = openApi(withCountry(COMMENT_URL), job.mAccessToken);
        try {
            writeJson(connection, body);
            int code = connection.getResponseCode();
            JSONObject response = readJson(connection, code);
            rememberTokens(job, response);
            if (!response.optBoolean("success", false)) {
                throw new ServerException(errorOf(response, code));
            }
            return BrowserExpressAddCommentUtil.parseCreatedComment(
                    response.getJSONObject("comment"));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * A server without presigned attachments can still take one file through the old multipart
     * endpoint. Several files can't be sent there at all.
     */
    private void sendTheOldWay(Job job) {
        if (job.items.size() != 1) {
            Log.e(TAG, "The server can't take several files yet");
            ThreadUtils.postOnUiThread(() -> onFailed(job, null));
            return;
        }
        Item item = job.items.get(0);
        try {
            BrowserExpressAddCommentUtil.CommentResult result =
                    BrowserExpressAddCommentUtil.uploadSynchronously(job.content, job.parentType,
                            job.pageUrl, job.parentId,
                            item.mSendUri != null ? item.mSendUri : item.uri, item.kind,
                            item.isAudio() ? item.durationMs : 0, job.mAccessToken);
            if (job.mCancelRequested) Log.i(TAG, "Posted despite the cancel");
            job.mNewAccessToken = result.getNewAccessToken();
            job.mNewRefreshToken = result.getNewRefreshToken();
            Comment comment = result.getComment();
            ThreadUtils.postOnUiThread(() -> onPosted(job, comment));
        } catch (Exception e) {
            if (job.mCancelRequested) return;
            Log.e(TAG, "Sending the comment the old way failed", e);
            ThreadUtils.postOnUiThread(() -> onFailed(job, null));
        }
    }

    private void postProgress(Job job, float progress) {
        ThreadUtils.postOnUiThread(() -> {
            if (job.mState != State.UPLOADING) return;
            job.mProgress = Math.max(0f, Math.min(1f, progress));
            notifyUpdated(job);
        });
    }

    private static HttpURLConnection openApi(String url, @Nullable String accessToken)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                new URL(url), NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", accessToken);
        }
        return connection;
    }

    /** The comments API reads the reader's country from the query, as the old client sent it. */
    private static String withCountry(String url) {
        String country = Locale.getDefault().getCountry();
        return country == null || country.isEmpty() ? url : url + "?country=" + country;
    }

    private static void writeJson(HttpURLConnection connection, JSONObject body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static JSONObject readJson(HttpURLConnection connection, int code)
            throws IOException {
        InputStream in = code >= 200 && code < 300 ? connection.getInputStream()
                                                   : connection.getErrorStream();
        if (in == null) throw new IOException("Empty response (" + code + ")");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
        }
        try {
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        } catch (JSONException e) {
            throw new IOException("Unreadable response (" + code + ")", e);
        }
    }

    /** The start of an error answer's body, for the log; empty when there is none. */
    private static String errorBody(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) return "";
            byte[] buffer = new byte[1024];
            int length = 0;
            int read;
            while (length < buffer.length
                    && (read = in.read(buffer, length, buffer.length - length)) != -1) {
                length += read;
            }
            return new String(buffer, 0, length, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String errorOf(JSONObject response, int code) {
        String message = response.optString("message", "");
        if (message.isEmpty()) message = response.optString("error", "");
        return message.isEmpty() ? "Server error " + code : message;
    }

    /**
     * A signed-out reader gets a username claimed by the first request that needs one; its token
     * is what every later request of the job (and the app) has to send.
     */
    private static void rememberTokens(Job job, JSONObject response) {
        String access = response.optString("accessToken", "");
        String refresh = response.optString("refreshToken", "");
        if (access.isEmpty() || refresh.isEmpty()) return;
        job.mAccessToken = access;
        job.mNewAccessToken = access;
        job.mNewRefreshToken = refresh;
    }

    // -------------------------------------------------------------------------------------------
    // Back on the UI thread
    // -------------------------------------------------------------------------------------------

    private void onPosted(Job job, Comment comment) {
        job.mState = State.POSTED;
        job.mProgress = 1f;
        mJobs.remove(job.tempId);
        storeTokens(job);
        reportOutcome(job, PostHogEventKeys.COMMENT_UPLOAD_COMPLETED, null);
        for (Listener listener : mListeners) listener.onJobPosted(job, comment);
    }

    private void onFailed(Job job, @Nullable String message) {
        storeTokens(job);
        if (job.mState != State.UPLOADING) return; // Cancelled meanwhile.
        job.mState = State.FAILED;
        job.mError = message;
        reportOutcome(job, PostHogEventKeys.COMMENT_UPLOAD_FAILED, message);
        notifyUpdated(job);
    }

    /** How a send ended, with what it carried and how long it took since it was first sent. */
    private static void reportOutcome(Job job, String event, @Nullable String error) {
        long bytes = 0;
        for (Item item : job.items) bytes += Math.max(0, item.size);
        PostHogUtil.capture(
                event,
                "parent_type",
                job.parentType,
                "files",
                job.items.size(),
                "kinds",
                kindsOf(job.items),
                "bytes",
                bytes,
                "duration_ms",
                SystemClock.elapsedRealtime() - job.startedAtMs,
                "error",
                error);
    }

    /** The distinct kinds of {@code items}, comma separated ("image,video"); "" for none. */
    public static String kindsOf(List<Item> items) {
        StringBuilder kinds = new StringBuilder();
        for (Item item : items) {
            if (kinds.indexOf(item.kind) >= 0) continue;
            if (kinds.length() > 0) kinds.append(',');
            kinds.append(item.kind);
        }
        return kinds.toString();
    }

    /** A username claimed along the way becomes the app's, as when posting a text comment. */
    private static void storeTokens(Job job) {
        String access = job.mNewAccessToken;
        String refresh = job.mNewRefreshToken;
        if (access == null || refresh == null || refresh.isEmpty()) return;
        job.mNewAccessToken = null;
        job.mNewRefreshToken = null;
        try {
            BraveActivity.getBraveActivity().setAccessToken(access);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "No activity to keep the new token", e);
        }
    }

    @Nullable
    private static String currentAccessToken() {
        try {
            return BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return null;
        }
    }
}
