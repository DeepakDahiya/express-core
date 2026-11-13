
/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.InputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class BrowserExpressEditAvatarPreferencesUtil {
    private static final String TAG = "Edit_Avatar_Browser_Express";
    private static final String EDIT_AVATAR_URL = "https://api.browser.express/v1/user/avatar";
    /** Avatars show at 120dp at most: 512px is sharp on any screen. */
    private static final int AVATAR_SIZE_PX = 512;
    private static final int AVATAR_JPEG_QUALITY = 90;

    public interface EditAvatarCallback {
        void editAvatarSuccessful();
        void editAvatarFailed(String error);
    }

    public static class EditAvatarWorkerTask extends AsyncTask<Void> {
        private final InputStream mImagePath;
        private final EditAvatarCallback mCallback;
        private static Boolean editAvatarStatus;
        private static String mErrorMessage;
        private static String mAccessToken;

        public EditAvatarWorkerTask(InputStream imagePath, String accessToken, EditAvatarCallback callback) {
            mImagePath = imagePath;
            mCallback = callback;
            editAvatarStatus = false;
            mErrorMessage = "";
            mAccessToken = accessToken;
        }

        public static void setAuthTokens(){
        }

        public static void setEditAvatarSuccessStatus(Boolean status){
            editAvatarStatus = status;
        }

        public static void setErrorMessage(String error){
            mErrorMessage = error;
        }

        @Override
        protected Void doInBackground() {
            sendEditAvatarRequest(mImagePath, mAccessToken);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            assert ThreadUtils.runningOnUiThread();
            if (isCancelled()) return;
            if(editAvatarStatus){
                mCallback.editAvatarSuccessful();
            }else{
                mCallback.editAvatarFailed(mErrorMessage);
            }
        }
    }

    private static void sendEditAvatarRequest(InputStream imageStream, String accessToken) {
        Log.e("ImagePicker 2", "Inside sendEditAvatarRequest");
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";
        HttpURLConnection urlConnection = null;
        StringBuilder sb = new StringBuilder();
        try {
            InputStream avatarInputStream = new ByteArrayInputStream(toAvatarJpeg(readAll(imageStream)));

            URL url = new URL(EDIT_AVATAR_URL);
            urlConnection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                    url, NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("PATCH");
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            if (accessToken != null && !accessToken.isEmpty()) {
                urlConnection.setRequestProperty("Authorization", accessToken);
            }

            OutputStream outputStream = urlConnection.getOutputStream();

            // Add text fields
            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"email\"" + LINE_FEED + LINE_FEED + email + LINE_FEED).getBytes());

            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"name\"" + LINE_FEED + LINE_FEED + name + LINE_FEED).getBytes());

            // outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            // outputStream.write(("Content-Disposition: form-data; name=\"username\"" + LINE_FEED + LINE_FEED + username + LINE_FEED).getBytes());

            long epochTime = System.currentTimeMillis();
            int randomNum = (int) (Math.random() * 1000); // Random number between 0 and 999
            String name = "avatar_" + epochTime + "_" + randomNum + ".jpg";

            outputStream.write(("--" + boundary + LINE_FEED).getBytes());
            outputStream.write(("Content-Disposition: form-data; name=\"reports\"; filename=\"" + name + "\"" + LINE_FEED).getBytes());
            outputStream.write(("Content-Type: image/jpeg" + LINE_FEED + LINE_FEED).getBytes());
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = avatarInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            avatarInputStream.close();
            outputStream.write(LINE_FEED.getBytes());

            // End of multipart
            outputStream.write(("--" + boundary + "--" + LINE_FEED).getBytes());
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
                    EditAvatarWorkerTask.setEditAvatarSuccessStatus(true);
                    // String accessToken1 = responseObject.getString("accessToken");
                    // String refreshToken = responseObject.getString("refreshToken");
                    // EditAvatarWorkerTask.setAuthTokens(accessToken1, refreshToken);
                }else{
                    EditAvatarWorkerTask.setEditAvatarSuccessStatus(false);
                    EditAvatarWorkerTask.setErrorMessage(responseObject.getString("error"));
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

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /**
     * The picked photo as the avatar the server keeps: upright, cropped to its centre square,
     * at most 512px, JPEG. It used to upload the full-size photo as a lossless PNG, megabytes
     * that every comment row showing the avatar then downloaded.
     */
    private static byte[] toAvatarJpeg(byte[] photo) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(photo, 0, photo.length, bounds);
        int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
        if (shortSide <= 0) throw new IOException("Not an image");

        // Decode no larger than needed: a 50MP photo would not fit in memory otherwise.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (shortSide / (options.inSampleSize * 2) >= AVATAR_SIZE_PX) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(photo, 0, photo.length, options);
        if (decoded == null) throw new IOException("Not an image");

        int crop = Math.min(decoded.getWidth(), decoded.getHeight());
        int size = Math.min(crop, AVATAR_SIZE_PX);
        float centre = size / 2f;
        Matrix matrix = new Matrix();
        matrix.postTranslate(-(decoded.getWidth() - crop) / 2f, -(decoded.getHeight() - crop) / 2f);
        matrix.postScale((float) size / crop, (float) size / crop);
        // Cameras store pixels as the sensor saw them and say in EXIF how to turn them upright.
        int orientation = new ExifInterface(new ByteArrayInputStream(photo))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1, centre, centre);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180, centre, centre);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postRotate(180, centre, centre);
                matrix.postScale(-1, 1, centre, centre);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90, centre, centre);
                matrix.postScale(-1, 1, centre, centre);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90, centre, centre);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(-90, centre, centre);
                matrix.postScale(-1, 1, centre, centre);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(-90, centre, centre);
                break;
            default:
                break;
        }

        Bitmap avatar = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(avatar);
        canvas.drawColor(Color.WHITE); // JPEG has no transparency.
        canvas.drawBitmap(decoded, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        decoded.recycle();

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        avatar.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, jpeg);
        avatar.recycle();
        return jpeg.toByteArray();
    }
}
