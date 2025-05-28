   
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.about_settings.AboutChromeSettings;
import org.chromium.chrome.browser.about_settings.AboutSettingsBridge;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;

import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressGenerateUsernameBottomSheetFragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import android.net.Uri;

public class BrowserExpressAddCommentUtil {
    private static final String TAG = "Add_Comment_Browser_Express";
    private static final String ADD_COMMENT_URL = "https://api.browser.express/v1/comment";
    private static final String ADD_COMMENT_URL_WITH_ATTACHMENT = "https://api.browser.express/v1/comment/with-media";

    public interface AddCommentCallback {
        void addCommentSuccessful(Comment comment, String newAccessToken, String newRefreshToken);
        void addCommentFailed(String error);
    }

    public static class AddCommentWorkerTask extends AsyncTask<Void> {
        private AddCommentCallback mCallback;
        private Boolean addCommentStatus;
        private String mErrorMessage;
        private String mContent;
        private String mParentType;
        private String mParentId;
        private String mUrl;
        private String mAccessToken;
        private Comment mComment;
        private Uri mMediaUri;
        private String mMediaType;

        private String mNewAccessToken = "";
        private String mNewRefreshToken = "";

        public AddCommentWorkerTask(String content, String parentType, String url, String parentId, Uri mediaUri, String mediaType, String accessToken, AddCommentCallback callback) {
            mCallback = callback;
            addCommentStatus = false;
            mErrorMessage = "";
            mContent = content;
            mParentType = parentType;
            mParentId = parentId;
            mUrl = url;
            mAccessToken = accessToken;
            mMediaUri = mediaUri;
            mMediaType = mediaType;
        }

        public static void setComment(Comment comment){
            mComment = comment;
        }

        public static void setAddCommentSuccessStatus(Boolean status){
            addCommentStatus = status;
        }

        public static void setNewTokens(String accessToken, String refreshToken){
            mNewAccessToken = accessToken;
            mNewRefreshToken = refreshToken;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendAddCommentRequest(mContent, mParentType, mParentId, mUrl, mMediaUri, mMediaType, mAccessToken, mCallback);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(addCommentStatus){
                mCallback.addCommentSuccessful(mComment, mNewAccessToken, mNewRefreshToken);
            }else{
                mCallback.addCommentFailed(mErrorMessage);
            }
        }
    }

    private static String getMimeType(Context context, Uri uri) {
        String mimeType;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            ContentResolver cr = context.getContentResolver();
            mimeType = cr.getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileExtension.toLowerCase());
        }
        return mimeType == null ? "application/octet-stream" : mimeType; // Default MIME type
    }

    private static void addFormField(OutputStream outputStream, String boundary, String name, String value) throws IOException {
        outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Disposition: form-data; name=\"" + name + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Type: text/plain; charset=UTF-8" + LINE_FEED).getBytes(StandardCharsets.UTF_8)); // Specify charset for text
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
        outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        outputStream.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendAddCommentRequest(String content, String parentType, String parentId, String pageUrl, Uri mediaUri, String mediaType, String accessToken, AddCommentCallback callback) {
        StringBuilder sb = new StringBuilder();
        HttpURLConnection urlConnection = null;
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";
        Context context = ContextUtils.getApplicationContext();

        try {
            String countryCode = Locale.getDefault().getCountry();
            String searchQuery = "?country=" + countryCode;
            URL url = null;
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
                // Multipart request
                addFormField(outputStream, boundary, "content", content);
                addFormField(outputStream, boundary, "platform", "Android");
                addFormField(outputStream, boundary, "parentType", parentType);
                addFormField(outputStream, boundary, "parentId", parentId);
                addFormField(outputStream, boundary, "url", pageUrl);
                addFormField(outputStream, boundary, "mediaType", mediaType);

                String mimeType = getMimeType(context, mediaUri);
                String fileName = "media_" + System.currentTimeMillis() + "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if(MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) == null) { // fallback if extension not found
                    String path = mediaUri.getPath();
                    if (path != null) {
                        fileName = path.substring(path.lastIndexOf('/') + 1);
                    } else {
                         fileName = "media_" + System.currentTimeMillis(); // generic filename
                    }
                }

                outputStream.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Disposition: form-data; name=\"reports\"; filename=\"" + fileName + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
                outputStream.write(("Content-Type: " + mimeType + LINE_FEED + LINE_FEED).getBytes(StandardCharsets.UTF_8));

                InputStream fileInputStream = context.getContentResolver().openInputStream(mediaUri);
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
                jsonParam.put("parentId", parentId);
                jsonParam.put("url", pageUrl);
                byte[] input = jsonParam.toString().getBytes(StandardCharsets.UTF_8.name());
                outputStream.write(input, 0, input.length);
            }

            outputStream.flush();
            outputStream.close();

            int HttpResult = urlConnection.getResponseCode();
            if (HttpResult == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        urlConnection.getInputStream(), StandardCharsets.UTF_8.name()));
                String line = null;
                while ((line = br.readLine()) != null) {
                    sb.append(line + "\n");
                }
                JSONObject responseObject = new JSONObject(sb.toString());
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
                        comment.optString("mediaVideoUrl", null)
                    ));

                    AddCommentWorkerTask.setNewTokens(responseObject.getString("accessToken"), responseObject.getString("refreshToken"));
                }else{
                    AddCommentWorkerTask.setAddCommentSuccessStatus(false);
                    AddCommentWorkerTask.setErrorMessage(responseObject.getString("error"));
                }
                br.close();
            } else {
                Log.e(TAG, urlConnection.getResponseMessage());
            }
        } catch (MalformedURLException e) {
            Log.e(TAG, e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}
