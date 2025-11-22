/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import org.chromium.base.Log;
import org.chromium.chrome.R;

public class UploadServiceImpl extends UploadService.Impl {
    private static final String TAG = "UploadServiceImpl";
    private static final String CHANNEL_ID = "UploadServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            getService().stopSelf();
            return Service.START_NOT_STICKY;
        }

        // 1. Show notification immediately (Required for Foreground Service)
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getService(), CHANNEL_ID)
                .setContentTitle("Posting Comment")
                .setContentText("Uploading your media...")
                .setSmallIcon(R.drawable.ic_chrome)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setProgress(100, 0, true);

        // Use getService() to call startForeground
        getService().startForeground(NOTIFICATION_ID, builder.build());

        // 2. Run upload logic on a background thread (replacing JobIntentService behavior)
        new Thread(() -> handleUpload(intent)).start();

        return Service.START_NOT_STICKY;
    }

    /**
     * Actual background logic.
     * NOTE: We reference constants from UploadService (the shell).
     */
    private void handleUpload(Intent intent) {
        String tempId = intent.getStringExtra(UploadService.EXTRA_TEMP_ID);
        
        if (tempId == null) {
            getService().stopSelf();
            return;
        }

        try {
            String content = intent.getStringExtra(UploadService.EXTRA_COMMENT_CONTENT);
            String pType = intent.getStringExtra(UploadService.EXTRA_COMMENT_TYPE);
            String url = intent.getStringExtra(UploadService.EXTRA_URL);
            String pId = intent.getStringExtra(UploadService.EXTRA_POST_ID);
            Uri mediaUri = intent.getParcelableExtra(UploadService.EXTRA_MEDIA_URI);
            String mediaType = intent.getStringExtra(UploadService.EXTRA_MEDIA_TYPE);
            String accessToken = intent.getStringExtra(UploadService.EXTRA_ACCESS_TOKEN);

            BrowserExpressAddCommentUtil.CommentResult result = 
                BrowserExpressAddCommentUtil.uploadSynchronously(
                    content, pType, url, pId, mediaUri, mediaType, accessToken);
            
            Log.d(TAG, "Upload succeeded: " + tempId);

            // Remove foreground state
            getService().stopForeground(true); 
            
            sendSuccessBroadcast(result.getComment(), tempId, result.getNewAccessToken(), result.getNewRefreshToken());

        } catch (Exception e) {
            Log.e(TAG, "Upload failed for tempId: " + tempId, e);

            NotificationCompat.Builder failureBuilder = new NotificationCompat.Builder(getService(), CHANNEL_ID)
                    .setContentTitle("Upload Failed")
                    .setContentText("Couldn't post your comment. Tap to retry.")
                    .setSmallIcon(R.drawable.ic_chrome); 

            // Use getService() for Context
            NotificationManagerCompat.from(getService()).notify(NOTIFICATION_ID, failureBuilder.build());

            // Stop foreground but keep service alive briefly to finish cleanup if needed
            getService().stopForeground(false);
            
            sendFailureBroadcast(tempId, e.getMessage());
        } finally {
            getService().stopSelf();
        }
    }

    private void sendSuccessBroadcast(Comment realComment, String tempId, String newAccessToken, String newRefreshToken) {
        Intent successIntent = new Intent(UploadService.BROADCAST_UPLOAD_COMPLETE);
        successIntent.putExtra(UploadService.EXTRA_TEMP_ID, tempId);
        successIntent.putExtra(UploadService.EXTRA_REAL_COMMENT_JSON, new Gson().toJson(realComment));
        successIntent.putExtra(UploadService.EXTRA_NEW_ACCESS_TOKEN, newAccessToken);
        successIntent.putExtra(UploadService.EXTRA_NEW_REFRESH_TOKEN, newRefreshToken);
        
        LocalBroadcastManager.getInstance(getService()).sendBroadcast(successIntent);
    }

    private void sendFailureBroadcast(String tempId, String errorMessage) {
        Intent failureIntent = new Intent(UploadService.BROADCAST_UPLOAD_FAILED);
        failureIntent.putExtra(UploadService.EXTRA_TEMP_ID, tempId);
        failureIntent.putExtra("error_message", errorMessage);
        
        LocalBroadcastManager.getInstance(getService()).sendBroadcast(failureIntent);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Comment Uploads", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifications for comment upload status");
            
            NotificationManager manager = getService().getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}