/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Build.VERSION_CODES.R)
public class InstalledAppsUnitTest {
    private static final String USER_A = "64a000000000000000000001";
    private static final String USER_B = "64a000000000000000000002";
    private Context mContext;
    private PackageManager mPackages;

    @Before
    public void setUp() throws Exception {
        mPackages = mock(PackageManager.class);
        mContext =
                new ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override
                    public PackageManager getPackageManager() {
                        return mPackages;
                    }

                    @Override
                    public int checkSelfPermission(String permission) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                };
        ContextUtils.initApplicationContextForTests(mContext);
        InstalledAppsApi.tokenPrefs(mContext).edit().clear().commit();
        mContext.getSharedPreferences("express_installed_apps", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        when(mPackages.getApplicationLabel(any())).thenReturn("News");
        when(mPackages.getInstallSourceInfo(anyString()))
                .thenThrow(new PackageManager.NameNotFoundException());
    }

    @Test
    public void signedOutOrUnconsentedNeverReadsPackages() {
        InstalledAppsManager.syncIfNeeded();
        signIn(USER_A);
        InstalledAppsManager.syncIfNeeded();
        assertFalse(InstalledAppsManager.isSharingEnabled());
        verify(mPackages, never()).getInstalledPackages(anyInt());
    }

    @Test
    public void consentDoesNotTransferBetweenAccounts() {
        mContext.getSharedPreferences("express_installed_apps", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(USER_A + ".enabled", true)
                .commit();
        signIn(USER_A);
        assertTrue(InstalledAppsManager.isSharingEnabled());
        signIn(USER_B);
        assertFalse(InstalledAppsManager.isSharingEnabled());
    }

    @Test
    public void capturesSystemAppsAndMetadataWithoutNeedingLauncherActivities() throws Exception {
        PackageInfo news = pkg("com.example.news", 0);
        news.applicationInfo.category = ApplicationInfo.CATEGORY_NEWS;
        news.firstInstallTime = 123456;
        news.lastUpdateTime = 234567;
        news.versionName = "2.1";
        news.setLongVersionCode(42);
        when(mPackages.getInstalledPackages(0))
                .thenReturn(Arrays.asList(news, pkg("android", ApplicationInfo.FLAG_SYSTEM), news));

        JSONArray apps = InstalledAppsCollector.collect(mContext);
        assertEquals(2, apps.length());
        assertEquals("android", apps.getJSONObject(0).getString("packageName"));
        assertTrue(apps.getJSONObject(0).getBoolean("isSystem"));
        assertEquals("news", apps.getJSONObject(1).getString("category"));
        assertEquals(42, apps.getJSONObject(1).getLong("versionCode"));
        assertEquals(123456, apps.getJSONObject(1).getLong("firstInstallTime"));
        // Uninstall/install-source restriction during the scan doesn't lose the other apps.
        assertFalse(apps.getJSONObject(1).has("installer"));
    }

    @Test
    public void refusesToPretendAFilteredInventoryIsComplete() {
        Context restricted =
                new ContextWrapper(mContext) {
                    @Override
                    public int checkSelfPermission(String permission) {
                        return PackageManager.PERMISSION_DENIED;
                    }
                };
        assertThrows(IllegalStateException.class, () -> InstalledAppsCollector.collect(restricted));
        verify(mPackages, never()).getInstalledPackages(anyInt());
    }

    @Test
    public void refusesOversizedSnapshotsInsteadOfSilentlyTruncating() throws Exception {
        when(mPackages.getInstalledPackages(0))
                .thenReturn(Collections.nCopies(2001, pkg("com.example.news", 0)));
        assertThrows(IllegalStateException.class, () -> InstalledAppsCollector.collect(mContext));
    }

    @Test
    public void malformedSessionsCannotBeUsedAsInventoryAccounts() {
        assertNull(InstalledAppsApi.userId(null));
        assertNull(InstalledAppsApi.userId("not-a-jwt"));
        assertNull(InstalledAppsApi.userId(token("arbitrary-value")));
        assertEquals(USER_A, InstalledAppsApi.userId(token(USER_A)));
    }

    @Test
    public void deletionNeedsNoCollectionPermissionOrInventoryRead() {
        // Disabled by default, so requesting opt-out with no existing consent does no work.
        signIn(USER_A);
        InstalledAppsManager.turnOff();
        assertFalse(InstalledAppsManager.isSharingEnabled());
        verify(mPackages, never()).getInstalledPackages(anyInt());
    }

    private void signIn(String id) {
        SharedPreferences prefs = InstalledAppsApi.tokenPrefs(mContext);
        prefs.edit().putString("AccessToken", token(id)).commit();
    }

    private static String token(String id) {
        String payload = "{\"_id\":\"" + id + "\"}";
        return "header."
                + Base64.encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)
                + ".signature";
    }

    private static PackageInfo pkg(String name, int flags) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        info.applicationInfo.flags = flags;
        return info;
    }
}
