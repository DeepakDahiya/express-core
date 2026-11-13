/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.components.minidump_uploader.util;

import android.net.Uri;

import org.chromium.base.version_info.VersionInfo;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.minidump_uploader.BraveMinidumpUploaderConfig;

import java.net.HttpURLConnection;

@NullMarked
public class BraveHttpURLConnectionFactoryImpl extends HttpURLConnectionFactoryImpl {
    // Guid is intentionally zeroed so we cannot identify users across reports.
    public static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";

    private final String mCrashUploadUrl;

    public BraveHttpURLConnectionFactoryImpl() {
        this(BraveMinidumpUploaderConfig.CRASH_UPLOAD_URL);
    }

    public BraveHttpURLConnectionFactoryImpl(String crashUploadUrl) {
        mCrashUploadUrl = crashUploadUrl;
    }

    @Override
    public @Nullable HttpURLConnection createHttpURLConnection(String url) {
        if (mCrashUploadUrl.isEmpty()) {
            return null;
        }

        String uploadUrl =
                Uri.parse(mCrashUploadUrl)
                        .buildUpon()
                        .appendQueryParameter(
                                "product", BraveMinidumpUploaderConfig.CRASH_PRODUCT)
                        .appendQueryParameter("version", VersionInfo.getProductVersion())
                        .appendQueryParameter("guid", ZERO_GUID)
                        .build()
                        .toString();
        return super.createHttpURLConnection(uploadUrl);
    }
}
