package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.google.gson.Gson;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import org.chromium.chrome.browser.browser_express_comments.UploadWorker;

public class UploadService extends JobIntentService {
    public static final String ACTION_UPLOAD_COMMENT = "org.chromium.chrome.browser.browser_express_comments.action.UPLOAD_COMMENT";
    public static final String EXTRA_TEMP_ID = "EXTRA_TEMP_ID";
    public static final String EXTRA_COMMENT_CONTENT = "EXTRA_COMMENT_CONTENT";
    public static final String EXTRA_COMMENT_TYPE = "EXTRA_COMMENT_TYPE";
    public static final String EXTRA_URL = "EXTRA_URL";
    public static final String EXTRA_POST_ID = "EXTRA_POST_ID";
    public static final String EXTRA_MEDIA_URI = "EXTRA_MEDIA_URI";
    public static final String EXTRA_MEDIA_TYPE = "EXTRA_MEDIA_TYPE";
    public static final String EXTRA_ACCESS_TOKEN = "EXTRA_ACCESS_TOKEN";
    
    public static final String BROADCAST_UPLOAD_COMPLETE = "broadcast_upload_complete";
    public static final String BROADCAST_UPLOAD_FAILED = "broadcast_upload_failed";
    public static final String EXTRA_REAL_COMMENT_JSON = "extra_real_comment_json";
    public static final String EXTRA_NEW_ACCESS_TOKEN = "extra_new_access_token";
    public static final String EXTRA_NEW_REFRESH_TOKEN = "extra_new_refresh_token";

    private static final int JOB_ID = 1001;
    private static final String CHANNEL_ID = "UploadServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static void enqueueWork(Context context, Intent work) {
        enqueueWork(context, UploadService.class, JOB_ID, work);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Posting Comment")
            .setSmallIcon(R.drawable.ic_chrome)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        startForeground(NOTIFICATION_ID, notification);

        String tempId = intent.getStringExtra(EXTRA_TEMP_ID);
        String content = intent.getStringExtra(EXTRA_COMMENT_CONTENT);
        String pType = intent.getStringExtra(EXTRA_COMMENT_TYPE);
        String url = intent.getStringExtra(EXTRA_URL);
        String pId = intent.getStringExtra(EXTRA_POST_ID);
        Uri mediaUri = intent.getParcelableExtra(EXTRA_MEDIA_URI);
        String mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
        String accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN);

        try {
            BrowserExpressAddCommentUtil.CommentResult result = BrowserExpressAddCommentUtil.uploadSynchronously(content, pType, url, pId, mediaUri, mediaType, accessToken);
            Log.d("UploadService", "Upload succeeded: " + tempId);

            Intent successIntent = new Intent(BROADCAST_UPLOAD_COMPLETE);
            successIntent.putExtra(EXTRA_TEMP_ID, tempId);
            successIntent.putExtra(EXTRA_REAL_COMMENT_JSON, new Gson().toJson(result.getComment()));
            successIntent.putExtra(EXTRA_NEW_ACCESS_TOKEN, result.getNewAccessToken());
            successIntent.putExtra(EXTRA_NEW_REFRESH_TOKEN, result.getNewRefreshToken());
            LocalBroadcastManager.getInstance(this).sendBroadcast(successIntent);
        } catch (Exception e) {
            Log.e("UploadService", "Upload failed for tempId: " + tempId + ". Scheduling retry...", e);
            scheduleRetryWithWorkManager(intent);
            
            Intent failureIntent = new Intent(BROADCAST_UPLOAD_FAILED);
            failureIntent.putExtra(EXTRA_TEMP_ID, tempId);
            failureIntent.putExtra("error_message", e.getMessage());
            LocalBroadcastManager.getInstance(this).sendBroadcast(failureIntent);
        } finally {
            stopForeground(true);
        }
    }

    private void scheduleRetryWithWorkManager(Intent originalIntent) {
        Data.Builder dataBuilder = new Data.Builder();
        dataBuilder.putString("EXTRA_TEMP_ID", originalIntent.getStringExtra("EXTRA_TEMP_ID"));
        dataBuilder.putString("EXTRA_COMMENT_CONTENT", originalIntent.getStringExtra("EXTRA_COMMENT_CONTENT"));
        dataBuilder.putString("EXTRA_COMMENT_TYPE", originalIntent.getStringExtra("EXTRA_COMMENT_TYPE"));
        dataBuilder.putString("EXTRA_URL", originalIntent.getStringExtra("EXTRA_URL"));
        dataBuilder.putString("EXTRA_POST_ID", originalIntent.getStringExtra("EXTRA_POST_ID"));
        dataBuilder.putString("EXTRA_MEDIA_TYPE", originalIntent.getStringExtra("EXTRA_MEDIA_TYPE"));
        dataBuilder.putString("EXTRA_ACCESS_TOKEN", originalIntent.getStringExtra("EXTRA_ACCESS_TOKEN"));
        Uri mediaUri = originalIntent.getParcelableExtra("EXTRA_MEDIA_URI");
        if (mediaUri != null) {
            dataBuilder.putString("EXTRA_MEDIA_URI", mediaUri.toString());
        }

        OneTimeWorkRequest retryWork = new OneTimeWorkRequest.Builder(UploadWorker.class)
            .setInputData(dataBuilder.build())
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();

        WorkManager.getInstance(this).enqueue(retryWork);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Comment Uploads", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}