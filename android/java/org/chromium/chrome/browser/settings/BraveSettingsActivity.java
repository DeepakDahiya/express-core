/* Copyright (c) 2021 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.app.BraveActivity;

public class BraveSettingsActivity extends SettingsActivity {
    public static final String EXTRA_BROWSER_EXPRESS_AUTH_REQUIRED =
            "browser_express_auth_required";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isBrowserExpressAuthLocked()) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        applyBrowserExpressAuthChromeIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBrowserExpressAuthChromeIfNeeded();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.clear();
        if (!isBrowserExpressAuthLocked()) {
            getMenuInflater().inflate(R.menu.exit_settings_menu, menu);
        }
        applyBrowserExpressAuthChromeIfNeeded();
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.removeItem(R.id.menu_id_general_help);
        if (isBrowserExpressAuthLocked()) {
            menu.removeItem(R.id.close_menu_id);
        }
        applyBrowserExpressAuthChromeIfNeeded();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (isBrowserExpressAuthLocked()
                && (item.getItemId() == R.id.close_menu_id
                        || item.getItemId() == android.R.id.home)) {
            return true;
        }
        if (item.getItemId() == R.id.close_menu_id) {
            Intent intent = new Intent(this, ChromeTabbedActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.setAction(Intent.ACTION_VIEW);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        if (isBrowserExpressAuthLocked()) return;
        super.finish();
    }

    public boolean isBrowserExpressAuthLocked() {
        if (!getIntent().getBooleanExtra(EXTRA_BROWSER_EXPRESS_AUTH_REQUIRED, false)) {
            return false;
        }
        // Closed already: the guest session is on its way (BrowserExpressGuestLogin).
        if (BrowserExpressGuestLogin.isPending()) return false;
        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0);
        return sharedPref.getString(BraveActivity.ACCESS_TOKEN_KEY, null) == null;
    }

    private void applyBrowserExpressAuthChromeIfNeeded() {
        if (isBrowserExpressAuthPage()) {
            BrowserExpressAuthUiUtil.applyAuthPageChrome(this);
        } else if (BrowserExpressProfilePreferences.class.getName().equals(
                           getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT))
                || BrowserExpressEditProfilePreferences.class.getName().equals(
                        getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT))) {
            // The Profile draws its own back / title / overflow bar, Edit profile its own
            // Cancel / title / Save.
            BrowserExpressAuthUiUtil.applyOwnNavigationChrome(
                    this, getColor(R.color.browser_express_profile_page_bg));
        } else {
            BrowserExpressAuthUiUtil.restoreSettingsPageChrome(this);
        }
    }

    private boolean isBrowserExpressAuthPage() {
        String fragmentName = getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT);
        return BrowserExpressLoginPreferences.class.getName().equals(fragmentName)
                || BrowserExpressSignupPreferences.class.getName().equals(fragmentName)
                || BrowserExpressOtpVerifyPreferences.class.getName().equals(fragmentName);
    }
}
