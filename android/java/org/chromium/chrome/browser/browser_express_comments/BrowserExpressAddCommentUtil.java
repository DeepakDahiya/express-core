/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.content.ContentResolver; // Added
import android.net.Uri;
import android.os.Build;
import android.webkit.MimeTypeMap; // Added
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask; // Assuming this is the AsyncTask you must use
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream; // Added
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class BrowserExpressAddCommentUtil {
    private static final String TAG = "Add_Comment_Util";
    private static final String ADD_COMMENT_URL = "https://api.browser.express/v1/comment";
    private static final String ADD_COMMENT_URL_WITH_ATTACHMENT = "https://api.browser.express/v1/comment/with-media";
    private static final String LINE_FEED = "\r\n";

    public interface AddCommentCallback {
        void addCommentSuccessful(Comment comment, String newAccessToken, String newRefreshToken);
        void addCommentFailed(String error);
    }

    public static class AddCommentWorkerTask extends AsyncTask<Void> {
        private AddCommentCallback mCallback;
        // !!! WARNING: Static fields below are problematic for concurrent operations !!!
        private static Boolean addCommentStatus;
        private static String mErrorMessage;
        private static String mContent;
        private static String mParentType;
        private static String mParentId;
        private static String mPageUrl; // Renamed from mUrl
        private static String mAccessToken;
        private static Comment mComment;
        private static Uri mMediaUri;
        private static String mMediaType;
        private static String mNewAccessToken = "";
        private static String mNewRefreshToken = "";

        public AddCommentWorkerTask(String content, String parentType, String pageUrl, String parentId,
                                    Uri mediaUri, String mediaType, String accessToken,
                                    AddCommentCallback callback) {
            // Assign to static fields - this is the problematic pattern
            mCallback = callback; // mCallback should ideally be an instance field too if AsyncTask isn't static
            AddCommentWorkerTask.addCommentStatus = false;
            AddCommentWorkerTask.mErrorMessage = "";
            AddCommentWorkerTask.mContent = content;
            AddCommentWorkerTask.mParentType = parentType;
            AddCommentWorkerTask.mParentId = parentId;
            AddCommentWorkerTask.mPageUrl = pageUrl;
            AddCommentWorkerTask.mAccessToken = accessToken;
            AddCommentWorkerTask.mMediaUri = mediaUri;
            AddCommentWorkerTask.mMediaType = mediaType;
            AddCommentWorkerTask.mNewAccessToken = ""; // Reset for each task
            AddCommentWorkerTask.mNewRefreshToken = ""; // Reset for each task
        }

        // Static setters remain as per your existing structure
        public static void setComment(Comment comment) {
            AddCommentWorkerTask.mComment = comment;
        }

        public static void setAddCommentSuccessStatus(Boolean status) {
            AddCommentWorkerTask.addCommentStatus = status;
        }

        public static void setNewTokens(String accessToken, String refreshToken) {
            AddCommentWorkerTask.mNewAccessToken = accessToken;
            AddCommentWorkerTask.mNewRefreshToken = refreshToken;
        }

        public static void setErrorMessage(String error) {
            AddCommentWorkerTask.mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            // Pass the static fields to the send method
            sendAddCommentRequest(mContent, mParentType, mParentId, mPageUrl, mMediaUri, mMediaType, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread(); // This is fine if using org.chromium.base.task.AsyncTask
            if (isCancelled()) { // isCancelled() is part of org.chromium.base.task.AsyncTask
                if (mCallback != null) mCallback.addCommentFailed("Operation cancelled.");
                return;
            }
            // Access static fields for result
            if (AddCommentWorkerTask.addCommentStatus != null && AddCommentWorkerTask.addCommentStatus) {
                if (mCallback != null) mCallback.addCommentSuccessful(AddCommentWorkerTask.mComment, AddCommentWorkerTask.mNewAccessToken, AddCommentWorkerTask.mNewRefreshToken);
            } else {
                if (mCallback != null) mCallback.addCommentFailed(AddCommentWorkerTask.mErrorMessage);
            }
        }
    }

    private static String getMimeType(Context context, Uri uri) {
        String mimeType = null;
        if (uri == null) return "application/octet-stream";

        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            ContentResolver cr = context.getContentResolver();
            mimeType = cr.getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        fileExtension.toLowerCase(Locale.ROOT));
            }
        }
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    private static void addFormField(OutputStream outputStream, String boundary, String name, String value) throws IOException {
        if (value == null) return;
        outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Disposition: form-data; name=\"" + name + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Type: text/plain; charset=UTF-8" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
        outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendAddCommentRequest(String content, String parentType, String parentId, String pageUrl,
                                               Uri mediaUri, String mediaType, String accessToken,
                                               AddCommentCallback callback) { // callback is passed to use static setters
        Log.e(TAG, "Content: " + content);
        Log.e(TAG, "Parent Type: " + parentType);
        Log.e(TAG, "Parent ID: " + parentId);   
        Log.e(TAG, "Page URL: " + pageUrl);
        Log.e(TAG, "Media URI: " + (mediaUri != null ? mediaUri.toString() : "null"));
        Log.e(TAG, "Media Type: " + mediaType);
        Log.d(TAG, "sendAddCommentRequest called. Media URI: " + (mediaUri != null ? mediaUri.toString() : "null"));

        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        Context context = ContextUtils.getApplicationContext();

        // Initialize status for this specific call via the static setters (as per existing pattern)
        AddCommentWorkerTask.setAddCommentSuccessStatus(false);
        AddCommentWorkerTask.setErrorMessage("Unknown error.");


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
            urlConnection.setConnectTimeout(15000);
            urlConnection.setReadTimeout(15000);

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
                if (parentId != null) addFormField(outputStream, boundary, "parentId", parentId);
                if (pageUrl != null) addFormField(outputStream, boundary, "url", pageUrl);
                if (mediaType != null) addFormField(outputStream, boundary, "mediaType", mediaType);

                String resolvedMimeType = getMimeType(context, mediaUri);
                String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMimeType);
                String fileName = "media_" + System.currentTimeMillis() + (extension != null ? "." + extension : "");
                if (extension == null || fileName.endsWith(".")) {
                    String path = mediaUri.getPath();
                    if (path != null) {
                        int lastSlash = path.lastIndexOf('/');
                        if (lastSlash != -1 && lastSlash < path.length() - 1) {
                           fileName = path.substring(lastSlash + 1);
                        } else {
                            fileName = !path.isEmpty() && !path.endsWith("/") ? path : "media_" + System.currentTimeMillis();
                        }
                    } else {
                         fileName = "media_" + System.currentTimeMillis();
                    }
                    if (!fileName.contains(".")) {
                        fileName += ".dat";
                    }
                }

                outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Disposition: form-data; name=\"reports\"; filename=\"" + fileName + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Type: " + resolvedMimeType + LINE_FEED + LINE_FEED).getBytes(StandardCharsets.UTF_8));

                InputStream fileInputStream = null;
                try {
                    fileInputStream = context.getContentResolver().openInputStream(mediaUri);
                    if (fileInputStream == null) {
                        Log.e(TAG, "Failed to open InputStream for media URI: " + mediaUri);
                        AddCommentWorkerTask.setErrorMessage("Failed to open media file.");
                        // No explicit callback.addCommentFailed here, relies on onPostExecute
                        return; // Exit if file stream fails
                    }
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                } finally {
                    if (fileInputStream != null) {
                        try { fileInputStream.close(); } catch (IOException ignored) {}
                    }
                }
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
            String responseString;

            if (HttpResult >= HttpURLConnection.HTTP_OK && HttpResult < HttpURLConnection.HTTP_MULT_CHOICE) {
                responseStream = urlConnection.getInputStream();
            } else {
                responseStream = urlConnection.getErrorStream();
            }

            if (responseStream != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                responseString = sb.toString();
            } else {
                responseString = "No response body from server. HTTP Code: " + HttpResult;
                if (HttpResult < HttpURLConnection.HTTP_OK || HttpResult >= HttpURLConnection.HTTP_MULT_CHOICE) {
                     AddCommentWorkerTask.setErrorMessage(responseString);
                     Log.e(TAG, responseString);
                     return;
                }
                 // If 2xx but no body, it might be a 204, but we expect JSON for success.
                 // So, treat lack of body in 2xx as an issue if we need to parse a comment.
            }


            if (HttpResult >= HttpURLConnection.HTTP_OK && HttpResult < HttpURLConnection.HTTP_MULT_CHOICE) {
                JSONObject responseObject = new JSONObject(responseString);
                if(responseObject.getBoolean("success")){
                    AddCommentWorkerTask.setAddCommentSuccessStatus(true);

                    JSONObject comment = responseObject.getJSONObject("comment");
                    JSONObject user = comment.getJSONObject("user");
                    User u = new User(user.getString("_id"), user.getString("username"), user.optString("avatar", null));
                    Vote v = null;
                    String pageParent = null;
                    String postParent = null;
                    String commentParent = null;
                    if(comment.has("pageParent")){
                        pageParent = comment.getString("pageParent");
                    }
                    if(comment.has("postParent")){
                        postParent = comment.getString("postParent");
                    }

                    if(comment.has("commentParent")){
                        commentParent = comment.getString("commentParent");
                    }

                    Log.e(TAG, comment.toString());
                    AddCommentWorkerTask.setComment(new Comment(
                        comment.getString("_id"), 
                        comment.getString("content"),
                        comment.getInt("upvoteCount"),
                        comment.getInt("downvoteCount"),
                        comment.getInt("commentCount"),
                        pageParent, 
                        postParent,
                        commentParent,
                        u,
                        v,
                        comment.optString("mediaImageUrl", null),
                        comment.optString("mediaVideoUrl", null),
                        null,
                        null,
                        null,
                    ));

                    AddCommentWorkerTask.setNewTokens(responseObject.getString("accessToken"), responseObject.getString("refreshToken"));
                }else{
                    AddCommentWorkerTask.setAddCommentSuccessStatus(false);
                    AddCommentWorkerTask.setErrorMessage(responseObject.getString("error"));
                }
            } else {
                Log.e(TAG, "HTTP Error: " + HttpResult + " Response: " + responseString);
                try {
                    JSONObject errorJson = new JSONObject(responseString);
                    AddCommentWorkerTask.setErrorMessage(errorJson.optString("error", "Server error: " + HttpResult));
                } catch (JSONException jsonEx) {
                    AddCommentWorkerTask.setErrorMessage("Server error: " + HttpResult + " (Could not parse error body)");
                }
            }
        } catch (MalformedURLException e) {
            Log.e(TAG, "MalformedURLException", e);
            AddCommentWorkerTask.setErrorMessage("Error: Invalid URL format.");
        } catch (IOException e) {
            Log.e(TAG, "IOException (Network I/O or Timeout)", e);
            AddCommentWorkerTask.setErrorMessage("Error: Network problem or request timed out.");
        } catch (JSONException e) {
            Log.e(TAG, "JSONException while parsing response", e);
            AddCommentWorkerTask.setErrorMessage("Error: Could not understand server's response.");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected Exception in sendAddCommentRequest", e);
            AddCommentWorkerTask.setErrorMessage("Error: An unexpected error occurred.");
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }
}