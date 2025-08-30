/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.chromium.base.Log;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.Channel;
import org.chromium.brave_news.mojom.Publisher;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_news.BraveNewsControllerFactory;
import org.chromium.chrome.browser.brave_news.BraveNewsUtils;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.night_mode.GlobalNightModeStateProviderHolder;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import androidx.preference.Preference;
import androidx.annotation.NonNull;

import java.util.List;

public class BrowserExpressCommentsPreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private LinearLayout mParentLayout;
    private Button mGenerateUsername;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set("Comments");
        return inflater.inflate(R.layout.browser_express_comments_settings, container, false);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        View view = getView();
        if (view != null) {
            mParentLayout = (LinearLayout) view.findViewById(R.id.layout_parent);
            mGenerateUsername = (Button) view.findViewById(R.id.btn_generate_username);

            onClickViews();
        }
    }

    private void onClickViews() {
        mGenerateUsername.setOnClickListener(view -> {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                String accessToken = activity.getAccessToken();
                activity.showGenerateUsernameBottomSheet();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e("comments", "maybeShowWalletPanel " + e);
            }
        });
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    private BrowserExpressLoginPreferencesUtil.LoginCallback loginCallback =
            new BrowserExpressLoginPreferencesUtil.LoginCallback() {
                @Override
                public void loginSuccessful(String accessToken, String refreshToken) {
                    // dismiss();
                }

                @Override
                public void loginFailed(String error) {
                    // dismiss();
                }
            };
}
