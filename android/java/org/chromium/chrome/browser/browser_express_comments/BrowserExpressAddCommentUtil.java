/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.content.ContentResolver; // Added import
import android.os.Build;
import android.webkit.MimeTypeMap; // Added import

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream; // Added import
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import android.net.Uri;
import android.util.Pair; // Assuming you use android.util.Pair for TaskResult, otherwise import androidx.core.util.Pair


public class BrowserExpressAddCommentUtil {
    private static final String TAG = "Add_Comment_Browser_Express";
    private static final String ADD_COMMENT_URL = "https://api.browser.express/v1/comment";
    private static final String ADD_COMMENT_URL_WITH_ATTACHMENT = "https://api.browser.express/v1/comment/with-media";
    private static final String LINE_FEED = "\r\n"; // Added constant


    public interface AddCommentCallback {
        void addCommentSuccessful(Comment comment, String newAccessToken, String newRefreshToken);
        void addCommentFailed(String error);
    }

    // Helper class for AsyncTask result
    private static class TaskResult {
        private boolean success;
        private Comment comment;
        private String newAccessToken;
        private String newRefreshToken;
        private String errorMessage;

        public TaskResult(Comment comment, String newAccessToken, String newRefreshToken) {
            this.success = true;
            this.comment = comment;
            this.newAccessToken = newAccessToken;
            this.newRefreshToken = newRefreshToken;
        }

        public TaskResult(String errorMessage) {
            this.success = false;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Comment getComment() { return comment; }
        public String getNewAccessToken() { return newAccessToken; }
        public String getNewRefreshToken() { return newRefreshToken; }
        public String getErrorMessage() { return errorMessage; }
    }


    public static class AddCommentWorkerTask extends AsyncTask<Void, Void, TaskResult> {
        private AddCommentCallback mCallback;
        // Removed static from all fields below
        private String mContent;
        private String mParentType;
        private String mParentId;
        private String mUrl;
        private String mAccessToken;
        private Uri mMediaUri;
        private String mMediaType;

        public AddCommentWorkerTask(String content, String parentType, String url, String parentId, Uri mediaUri, String mediaType, String accessToken, AddCommentCallback callback) {
            this.mCallback = callback;
            this.mContent = content;
            this.mParentType = parentType;
            this.mParentId = parentId;
            this.mUrl = url;
            this.mAccessToken = accessToken;
            this.mMediaUri = mediaUri;
            this.mMediaType = mediaType;
        }

        @Override
        protected TaskResult doInBackground(Void... voids) {
             return sendAddCommentRequest(mContent, mParentType, mParentId, mUrl, mMediaUri, mMediaType, mAccessToken);
        }

        @Override
        protected void onPostExecute(TaskResult result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) {
                if (mCallback != null) mCallback.addCommentFailed("Operation cancelled.");
                return;
            }
            if (result == null) {
                 if (mCallback != null) mCallback.addCommentFailed("Unknown error occurred.");
                return;
            }

            if (result.isSuccess()) {
                if (mCallback != null) mCallback.addCommentSuccessful(result.getComment(), result.getNewAccessToken(), result.getNewRefreshToken());
            } else {
                if (mCallback != null) mCallback.addCommentFailed(result.getErrorMessage());
            }
        }
    }

    private static String getMimeType(Context context, Uri uri) {
        String mimeType = null; // Initialize to null
        if (uri == null) return "application/octet-stream";

        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            ContentResolver cr = context.getContentResolver();
            mimeType = cr.getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        fileExtension.toLowerCase());
            }
        }
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    private static void addFormField(OutputStream outputStream, String boundary, String name, String value) throws IOException {
        if (value == null) return; // Don't add field if value is null
        outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Disposition: form-data; name=\"" + name + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Type: text/plain; charset=UTF-8" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
        outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
    }

    private static TaskResult sendAddCommentRequest(String content, String parentType, String parentId, String pageUrl, Uri mediaUri, String mediaType, String accessToken) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        Context context = ContextUtils.getApplicationContext();

        try {
            String countryCode = Locale.getDefault().getCountry();
            String searchQuery = (countryCode != null && !countryCode.isEmpty()) ? "?country=" + countryCode : "";
            URL url;
            if (mediaUri != null) {
                url = new URL(ADD_COMMENT_URL_WITH_ATTACHMENT + searchQuery);
            } else {
                url = new URL(ADD_COMMENT_URL + searchQuery);
            }

            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setUseCaches(false);
            if (accessToken != null && !accessToken.isEmpty()) {
                urlConnection.setRequestProperty("Authorization", accessToken);
            }

            if (mediaUri != null) {
                urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            } else {
                urlConnection.setRequestProperty("Content-Type", "application/json");
            }

            OutputStream outputStream = urlConnection.getOutputStream();

            if (mediaUri != null) {
                addFormField(outputStream, boundary, "content", content);
                addFormField(outputStream, boundary, "platform", "Android");
                addFormField(outputStream, boundary, "parentType", parentType);
                addFormField(outputStream, boundary, "parentId", parentId);
                addFormField(outputStream, boundary, "url", pageUrl);
                addFormField(outputStream, boundary, "mediaType", mediaType);

                String resolvedMimeType = getMimeType(context, mediaUri);
                String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMimeType);
                String fileName = "media_" + System.currentTimeMillis() + (extension != null ? "." + extension : "");

                if (extension == null) { // Fallback for filename if extension couldn't be derived
                    String path = mediaUri.getPath();
                    if (path != null) {
                        int lastSlash = path.lastIndexOf('/');
                        if (lastSlash != -1 && lastSlash < path.length() -1) {
                           fileName = path.substring(lastSlash + 1);
                        } else {
                            fileName = "media_" + System.currentTimeMillis();
                        }
                    } else {
                         fileName = "media_" + System.currentTimeMillis();
                    }
                }


                outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Disposition: form-data; name=\"reports\"; filename=\"" + fileName + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Type: " + resolvedMimeType + LINE_FEED + LINE_FEED).getBytes(StandardCharsets.UTF_8));

                InputStream fileInputStream = context.getContentResolver().openInputStream(mediaUri);
                if (fileInputStream == null) {
                    return new TaskResult("Failed to open media file stream.");
                }
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                fileInputStream.close();
                outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
                outputStream.write(("--" + boundary + "--" + LINE_FEED).getBytes(StandardCharsets.UTF_8));

            } else {
                JSONObject jsonParam = new JSONObject();
                jsonParam.put("content", content);
                jsonParam.put("platform", "Android");
                jsonParam.put("parentType", parentType);
                if (parentId != null) jsonParam.put("parentId", parentId);
                if (pageUrl != null) jsonParam.put("url", pageUrl);
                byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8);
                outputStream.write(input, 0, input.length);
            }

            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            InputStream responseStream;
            if (HttpResult >= HttpURLConnection.HTTP_OK && HttpResult < HttpURLConnection.HTTP_MULT_CHOICE) {
                responseStream = urlConnection.getInputStream();
            } else {
                responseStream = urlConnection.getErrorStream();
            }

            if (responseStream == null) {
                 return new TaskResult("Server error: " + HttpResult + " " + urlConnection.getResponseMessage() + " (No response body)");
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            String responseString = sb.toString();

            if (HttpResult >= HttpURLConnection.HTTP_OK && HttpResult < HttpURLConnection.HTTP_MULT_CHOICE) {
                JSONObject responseObject = new JSONObject(responseString);
                if (responseObject.getBoolean("success")) {
                    JSONObject commentJson = responseObject.getJSONObject("comment");
                    JSONObject userJson = commentJson.getJSONObject("user");
                    User u = new User(userJson.getString("_id"), userJson.getString("username"), userJson.optString("avatar", null));

                    Comment parsedComment = new Comment(
                        commentJson.getString("_id"),
                        commentJson.getString("content"),
                        commentJson.getInt("upvoteCount"),
                        commentJson.getInt("downvoteCount"),
                        commentJson.getInt("commentCount"),
                        commentJson.optString("pageParent", null),
                        commentJson.optString("postParent", null),
                        commentJson.optString("commentParent", null),
                        u,
                        null, // Vote v
                        commentJson.optString("mediaUrl", null),      // Changed from mediaImageUrl
                        commentJson.optString("mediaType", null));   // Changed from mediaVideoUrl
                    String newAccessToken = responseObject.optString("accessToken", "");
                    String newRefreshToken = responseObject.optString("refreshToken", "");
                    return new TaskResult(parsedComment, newAccessToken, newRefreshToken);
                } else {
                    return new TaskResult(responseObject.optString("error", "Unknown server error."));
                }
            } else {
                Log.e(TAG, "HTTP Error: " + HttpResult + " Response: " + responseString);
                 try {
                    JSONObject errorJson = new JSONObject(responseString);
                    return new TaskResult(errorJson.optString("error", "Server error: " + HttpResult));
                } catch (JSONException jsonEx) {
                    return new TaskResult("Server error: " + HttpResult + " (Could not parse error response)");
                }
            }
        } catch (MalformedURLException e) {
            Log.e(TAG, "Malformed URL", e);
            return new TaskResult("Error: Invalid URL.");
        } catch (IOException e) {
            Log.e(TAG, "Network I/O error", e);
            return new TaskResult("Error: Network problem.");
        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing error", e);
            return new TaskResult("Error: Problem parsing server response.");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error", e);
            return new TaskResult("Error: An unexpected error occurred.");
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }
}