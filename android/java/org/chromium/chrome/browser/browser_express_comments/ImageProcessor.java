/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

public class ImageProcessor {

    private static final String TAG = "ImageProcessor";
    public static final int DEFAULT_MAX_IMAGE_DIMENSION = 1920;
    public static final int DEFAULT_IMAGE_COMPRESSION_QUALITY = 80;

    public interface ProcessImageCallback {
        void onImageProcessed(@Nullable Uri processedImageUri, @Nullable String finalMimeType);
    }

    public static class ProcessImageTask extends AsyncTask<Void, Void, Pair<Uri, String>> {
        private WeakReference<Context> contextRef;
        private Uri originalImageUri;
        private ProcessImageCallback callback;
        private int maxDimension;
        private int compressionQuality;

        public ProcessImageTask(Context context, Uri imageUri,
                                int maxDimension, int compressionQuality,
                                ProcessImageCallback callback) {
            this.contextRef = new WeakReference<>(context.getApplicationContext());
            this.originalImageUri = imageUri;
            this.maxDimension = maxDimension;
            this.compressionQuality = compressionQuality;
            this.callback = callback;
        }

        @Override
        protected Pair<Uri, String> doInBackground(Void... voids) {
            Context context = contextRef.get();
            if (context == null) return null;

            InputStream inputStream = null;
            OutputStream outputStream = null;
            File tempFile = null;

            try {
                inputStream = context.getContentResolver().openInputStream(originalImageUri);
                if (inputStream == null) return null;

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(inputStream, null, options);
                try { inputStream.close(); } catch (IOException ignored) {}

                int originalWidth = options.outWidth;
                int originalHeight = options.outHeight;

                if (originalWidth <= 0 || originalHeight <= 0) return null;

                options.inSampleSize = 1;
                if (originalHeight > this.maxDimension || originalWidth > this.maxDimension) {
                    final int halfHeight = originalHeight / 2;
                    final int halfWidth = originalWidth / 2;
                    while ((halfHeight / options.inSampleSize) >= this.maxDimension
                            && (halfWidth / options.inSampleSize) >= this.maxDimension) {
                        options.inSampleSize *= 2;
                    }
                }

                options.inJustDecodeBounds = false;
                inputStream = context.getContentResolver().openInputStream(originalImageUri);
                if (inputStream == null) return null;
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
                try { inputStream.close(); } catch (IOException ignored) {}

                if (bitmap == null) return null;

                inputStream = context.getContentResolver().openInputStream(originalImageUri);
                if (inputStream != null) {
                     ExifInterface exifInterface = new ExifInterface(inputStream);
                     int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                     Matrix matrix = new Matrix();
                     switch (orientation) {
                         case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                         case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                         case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                     }
                     bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                     try { inputStream.close(); } catch (IOException ignored) {}
                }

                String extension = ".jpg";
                Bitmap.CompressFormat format = Bitmap.CompressFormat.JPEG;
                String outputMimeType = "image/jpeg";

                tempFile = File.createTempFile("processed_img_", extension, context.getCacheDir());
                outputStream = new FileOutputStream(tempFile);

                bitmap.compress(format, this.compressionQuality, outputStream);
                bitmap.recycle();

                return new Pair<>(Uri.fromFile(tempFile), outputMimeType);

            } catch (IOException | OutOfMemoryError e) {
                Log.e(TAG, "Error during image processing", e);
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                return null;
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                    if (outputStream != null) outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams", e);
                }
            }
        }

        @Override
        protected void onPostExecute(Pair<Uri, String> result) {
            if (callback != null) {
                callback.onImageProcessed(result != null ? result.first : null, result != null ? result.second : null);
            }
        }
    }

    public static void processImage(Context context, Uri imageUri,
                                    int maxDimension, int compressionQuality,
                                    ProcessImageCallback callback) {
        new ProcessImageTask(context, imageUri, maxDimension, compressionQuality, callback).execute();
    }

     public static void processImage(Context context, Uri imageUri, ProcessImageCallback callback) {
        new ProcessImageTask(context, imageUri,
                DEFAULT_MAX_IMAGE_DIMENSION, DEFAULT_IMAGE_COMPRESSION_QUALITY,
                callback).execute();
    }
}