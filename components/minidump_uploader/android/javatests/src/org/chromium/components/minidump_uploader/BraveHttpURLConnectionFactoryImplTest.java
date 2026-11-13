/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.components.minidump_uploader;

import android.net.Uri;

import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.base.version_info.VersionInfo;
import org.chromium.components.minidump_uploader.BraveMinidumpUploaderConfig;
import org.chromium.components.minidump_uploader.util.BraveHttpURLConnectionFactoryImpl;
import org.chromium.components.minidump_uploader.util.HttpURLConnectionFactory;

import java.net.HttpURLConnection;

/** Unittests for {@link BraveHttpURLConnectionFactoryImpl}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BraveHttpURLConnectionFactoryImplTest {
    @Test
    @SmallTest
    public void testUploadUrlHasProductVersionGuid() {
        HttpURLConnectionFactory httpURLConnectionFactory =
                new BraveHttpURLConnectionFactoryImpl("https://crash.example.test/report");
        HttpURLConnection connection = httpURLConnectionFactory.createHttpURLConnection("");
        Uri uri = Uri.parse(connection.getURL().toString());
        Assert.assertEquals("crash.example.test", uri.getHost());
        Assert.assertEquals("/report", uri.getPath());
        Assert.assertEquals(
                BraveMinidumpUploaderConfig.CRASH_PRODUCT, uri.getQueryParameter("product"));
        Assert.assertEquals(VersionInfo.getProductVersion(), uri.getQueryParameter("version"));
        Assert.assertEquals(
                BraveHttpURLConnectionFactoryImpl.ZERO_GUID, uri.getQueryParameter("guid"));
    }

    @Test
    @SmallTest
    public void testEmptyUploadUrlDisablesUploads() {
        HttpURLConnectionFactory httpURLConnectionFactory =
                new BraveHttpURLConnectionFactoryImpl("");
        Assert.assertNull(httpURLConnectionFactory.createHttpURLConnection(""));
    }
}
