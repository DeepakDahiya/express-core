/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;
import org.chromium.build.annotations.IdentifierNameString;
import org.chromium.chrome.browser.base.SplitCompatService;

/**
 * This is the "Shell" service. It resides in the base module and provides
 * the static constants and entry points that other classes (like Fragments) use.
 * It delegates the actual execution to {@link UploadServiceImpl}.
 */
public class UploadService extends SplitCompatService {
    @SuppressWarnings("FieldCanBeFinal") // @IdentifierNameString requires non-final
    private static @IdentifierNameString String sImplClassName =
            "org.chromium.chrome.browser.browser_express_comments.UploadServiceImpl";

    // ============================================================================================
    // PUBLIC CONSTANTS (Restored here so Fragments can access them)
    // ============================================================================================
    public static final String ACTION_UPLOAD_COMMENT = "org.chromium.chrome.browser.browser_express_comments.action.UPLOAD_COMMENT";
    
    // Input Extras
    public static final String EXTRA_TEMP_ID = "EXTRA_TEMP_ID";
    public static final String EXTRA_COMMENT_CONTENT = "EXTRA_COMMENT_CONTENT";
    public static final String EXTRA_COMMENT_TYPE = "EXTRA_COMMENT_TYPE";
    public static final String EXTRA_URL = "EXTRA_URL";
    public static final String EXTRA_POST_ID = "EXTRA_POST_ID";
    public static final String EXTRA_MEDIA_URI = "EXTRA_MEDIA_URI";
    public static final String EXTRA_MEDIA_TYPE = "EXTRA_MEDIA_TYPE";
    public static final String EXTRA_ACCESS_TOKEN = "EXTRA_ACCESS_TOKEN";
    
    // Broadcast Actions & Extras
    public static final String BROADCAST_UPLOAD_COMPLETE = "broadcast_upload_complete";
    public static final String BROADCAST_UPLOAD_FAILED = "broadcast_upload_failed";
    public static final String EXTRA_REAL_COMMENT_JSON = "extra_real_comment_json";
    public static final String EXTRA_NEW_ACCESS_TOKEN = "extra_new_access_token";
    public static final String EXTRA_NEW_REFRESH_TOKEN = "extra_new_refresh_token";

    public UploadService() {
        super(sImplClassName);
    }

    /**
     * Compatibility method.
     * Previously, this used JobIntentService.enqueueWork.
     * Now, we simply start the service as a Foreground Service.
     */
    public static void enqueueWork(Context context, Intent work) {
        work.setClass(context, UploadService.class);
        // Start as foreground service directly to ensure it runs reliably
        ContextCompat.startForegroundService(context, work);
    }
}