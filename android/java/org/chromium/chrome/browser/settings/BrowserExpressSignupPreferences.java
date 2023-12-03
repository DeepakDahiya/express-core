/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
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
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.components.browser_ui.settings.FragmentSettingsLauncher;
import org.chromium.components.browser_ui.settings.SettingsLauncher;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;

import java.util.List;

public class BrowserExpressSignupPreferences extends BravePreferenceFragment
        implements BraveNewsPreferencesDataListener, ConnectionErrorHandler,
                   FragmentSettingsLauncher {
    public static final String PREF_SHOW_OPTIN = "show_optin";

    private LinearLayout mParentLayout;
    private Button mBtnSignIn;
    private Button mBtnSignUp;

    private boolean mIsSuggestionAvailable;
    private boolean mIsChannelAvailable;
    private boolean mIsPublisherAvailable;
    private BraveNewsController mBraveNewsController;

    // SettingsLauncher injected from main Settings Activity.
    private SettingsLauncher mSettingsLauncher;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.browser_express_signup_settings, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (getActivity() != null) {
            getActivity().setTitle("");
        }

        super.onActivityCreated(savedInstanceState);

        initBraveNewsController();

        View view = getView();
        if (view != null) {
            mParentLayout = (LinearLayout) view.findViewById(R.id.layout_parent);
            mBtnSignIn = (Button) view.findViewById(R.id.btn_sign_in);
            mBtnSignUp = (Button) view.findViewById(R.id.btn_sign_up);

            setData();
            onClickViews();
        }
    }

    private void setData() {
        if (!GlobalNightModeStateProviderHolder.getInstance().isInNightMode()
                && getView() != null) {
            LottieAnimationView lottieAnimationVIew =
                    (LottieAnimationView) getView().findViewById(R.id.animation_view);

            try {
                lottieAnimationVIew.addValueCallback(new KeyPath("newspaper", "**"),
                        LottieProperty.COLOR_FILTER,
                        frameInfo
                        -> new PorterDuffColorFilter(ContextCompat.getColor(getActivity(),
                                                             R.color.news_settings_optin_color),
                                PorterDuff.Mode.SRC_ATOP));
            } catch (Exception exception) {
                // if newspaper keypath changed in animation json
            }
        }

        if (BraveNewsUtils.getLocale() != null
                && BraveNewsUtils.getSuggestionsPublisherList().size() > 0) {
            mIsSuggestionAvailable = true;
        }

        boolean isNewsEnable = BraveNewsUtils.shouldDisplayNewsFeed();
        onShowNewsToggle(isNewsEnable);
    }

    private void onClickViews() {
        mBtnSignIn.setOnClickListener(view -> {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                activity.openBrowserExpressLoginSettings();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }
        });

        mBtnSignUp.setOnClickListener(view -> {
            openBrowserExpressVerify
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                activity.openBrowserExpressVerify();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }
        });
    }

    private void onShowNewsToggle(boolean isEnable) {
        BravePrefServiceBridge.getInstance().setShowNews(isEnable);

        SharedPreferencesManager.getInstance().writeBoolean(
                BravePreferenceKeys.BRAVE_NEWS_PREF_SHOW_NEWS, isEnable);

        FrameLayout.LayoutParams parentLayoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);

        parentLayoutParams.gravity = Gravity.NO_GRAVITY;
        mParentLayout.setLayoutParams(parentLayoutParams);
    }

    private void openBraveNewsPreferencesDetails(
            BraveNewsPreferencesType braveNewsPreferencesType) {
        Bundle fragmentArgs = new Bundle();
        fragmentArgs.putString(
                BraveConstants.BRAVE_NEWS_PREFERENCES_TYPE, braveNewsPreferencesType.toString());
        mSettingsLauncher.launchSettingsActivity(
                getActivity(), BraveNewsPreferencesDetails.class, fragmentArgs);
    }

    private void initBraveNewsController() {
        if (mBraveNewsController != null) {
            return;
        }

        mBraveNewsController =
                BraveNewsControllerFactory.getInstance().getBraveNewsController(this);
    }

    private void updateFollowerCount() {
        List<Publisher> followingPublisherList = BraveNewsUtils.getFollowingPublisherList();
        List<Channel> followingChannelList = BraveNewsUtils.getFollowingChannelList();
        int followingCount = followingChannelList.size() + followingPublisherList.size();
    }

    @Override
    public void onChannelReceived() {
    }

    @Override
    public void onPublisherReceived() {
    }

    @Override
    public void onSuggestionsReceived() {
    }

    @Override
    public void setSettingsLauncher(SettingsLauncher settingsLauncher) {
        mSettingsLauncher = settingsLauncher;
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
        mBraveNewsController = null;
        initBraveNewsController();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
    }
}
