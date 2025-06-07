package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.chromium.base.Log;

public class UploadWorker extends Worker {
    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String tempId = getInputData().getString("EXTRA_TEMP_ID");
        String content = getInputData().getString("EXTRA_COMMENT_CONTENT");
        Uri mediaUri = Uri.parse(getInputData().getString("EXTRA_MEDIA_URI"));
        String pType = getInputData().getString("EXTRA_COMMENT_TYPE");
        String pId = getInputData().getString("EXTRA_POST_ID");
        String accessToken = getInputData().getString("EXTRA_ACCESS_TOKEN");
        String mediaType = getInputData().getString("EXTRA_MEDIA_TYPE");
        String url = getInputData().getString("EXTRA_URL");

        Log.d("UploadWorker", "Retrying upload for tempId: " + tempId);
        try {
            BrowserExpressAddCommentUtil.uploadSynchronously(content, pType, url, pId, mediaUri, mediaType, accessToken);
            Log.d("UploadWorker", "Retry succeeded for: " + tempId);
            return Result.success();
        } catch (Exception e) {
            Log.e("UploadWorker", "Retry failed for tempId: " + tempId, e);
            return Result.retry();
        }
    }
}