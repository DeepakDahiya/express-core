package org.chromium.chrome.browser.browser_express_comments;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.gson.Gson;
import org.chromium.chrome.R;

public class UploadService extends IntentService {
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

    private static final String CHANNEL_ID = "UploadServiceChannel";
    private static final int NOTIFICATION_ID = 12345;

    public UploadService() {
        super("UploadService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null || !ACTION_UPLOAD_COMMENT.equals(intent.getAction())) return;

        String tempId = intent.getStringExtra(EXTRA_TEMP_ID);
        String content = intent.getStringExtra(EXTRA_COMMENT_CONTENT);
        String pType = intent.getStringExtra(EXTRA_COMMENT_TYPE);
        String url = intent.getStringExtra(EXTRA_URL);
        String pId = intent.getStringExtra(EXTRA_POST_ID);
        Uri mediaUri = intent.getParcelableExtra(EXTRA_MEDIA_URI);
        String mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
        String accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Posting Comment")
                .setContentText("Your comment is being uploaded...")
                .setSmallIcon(R.drawable.ic_chrome)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        try {
            // This is a blocking call to your existing network utility.
            // Replace with your actual network upload logic. For this example, we assume
            // BrowserExpressAddCommentUtil.uploadSynchronously is a new method you create.
            BrowserExpressAddCommentUtil.CommentResult realComment = BrowserExpressAddCommentUtil.uploadSynchronously(content, pType, url, pId, mediaUri, mediaType, accessToken);
            
            Intent successIntent = new Intent(BROADCAST_UPLOAD_COMPLETE);
            successIntent.putExtra(EXTRA_TEMP_ID, tempId);
            successIntent.putExtra(EXTRA_REAL_COMMENT_JSON, new Gson().toJson(realComment.comment));
            LocalBroadcastManager.getInstance(this).sendBroadcast(successIntent);
        } catch (Exception e) {
            Intent failureIntent = new Intent(BROADCAST_UPLOAD_FAILED);
            failureIntent.putExtra(EXTRA_TEMP_ID, tempId);
            LocalBroadcastManager.getInstance(this).sendBroadcast(failureIntent);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Upload Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}