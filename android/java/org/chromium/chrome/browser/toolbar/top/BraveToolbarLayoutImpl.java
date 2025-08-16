/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.top;

import static org.chromium.ui.base.ViewUtils.dpToPx;
import org.chromium.ui.base.ViewUtils;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.bumptech.glide.Glide;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Base64;
import java.io.UnsupportedEncodingException;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.FragmentActivity;

import com.brave.playlist.enums.PlaylistOptions;
import com.brave.playlist.listener.PlaylistOnboardingActionClickListener;
import com.brave.playlist.listener.PlaylistOptionsListener;
import com.brave.playlist.model.PlaylistOptionsModel;
import com.brave.playlist.model.SnackBarActionModel;
import com.brave.playlist.util.ConnectionUtils;
import com.brave.playlist.util.ConstantUtils;
import com.brave.playlist.util.PlaylistPreferenceUtils;
import com.brave.playlist.util.PlaylistViewUtils;
import com.brave.playlist.view.PlaylistOnboardingPanel;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;

import org.json.JSONArray;

import org.chromium.base.ApiCompatibilityUtils;
import org.chromium.base.BraveFeatureList;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.MathUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveRelaunchUtils;
import org.chromium.chrome.browser.BraveRewardsHelper;
import org.chromium.chrome.browser.BraveRewardsNativeWorker;
import org.chromium.chrome.browser.BraveRewardsObserver;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_stats.BraveStatsUtil;
import org.chromium.chrome.browser.crypto_wallet.controller.DAppsWalletController;
import org.chromium.chrome.browser.custom_layout.popup_window_tooltip.PopupWindowTooltip;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.customtabs.features.toolbar.CustomTabToolbar;
import org.chromium.chrome.browser.dialogs.BraveAdsSignupDialog;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.local_database.BraveStatsTable;
import org.chromium.chrome.browser.local_database.DatabaseHelper;
import org.chromium.chrome.browser.local_database.SavedBandwidthTable;
import org.chromium.chrome.browser.notifications.BraveNotificationWarningDialog;
import org.chromium.chrome.browser.notifications.BravePermissionUtils;
import org.chromium.chrome.browser.notifications.RewardsYouAreNotEarningDialog;
import org.chromium.chrome.browser.omnibox.LocationBarCoordinator;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.onboarding.SearchActivity;
import org.chromium.chrome.browser.onboarding.v2.HighlightItem;
import org.chromium.chrome.browser.onboarding.v2.HighlightView;
import org.chromium.chrome.browser.playlist.PlaylistServiceFactoryAndroid;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl.PlaylistServiceObserverImplDelegate;
import org.chromium.chrome.browser.playlist.PlaylistWarningDialogFragment.PlaylistWarningDialogListener;
import org.chromium.chrome.browser.playlist.settings.BravePlaylistPreferences;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettingsObserver;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.rewards.BraveRewardsPanel;
import org.chromium.chrome.browser.rewards.onboarding.RewardsOnboarding;
import org.chromium.chrome.browser.settings.AppearancePreferences;
import org.chromium.chrome.browser.shields.BraveShieldsHandler;
import org.chromium.chrome.browser.shields.BraveShieldsMenuObserver;
import org.chromium.chrome.browser.shields.BraveShieldsUtils;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabImpl;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.chrome.browser.theme.ThemeUtils;
import org.chromium.chrome.browser.toolbar.HomeButton;
import org.chromium.chrome.browser.toolbar.ToolbarDataProvider;
import org.chromium.chrome.browser.toolbar.ToolbarTabController;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarVariationManager;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.top.NavigationPopup.HistoryDelegate;
import org.chromium.chrome.browser.toolbar.top.ToolbarTablet.OfflineDownloader;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.chrome.browser.util.ConfigurationUtils;
import org.chromium.chrome.browser.util.PackageUtils;
import org.chromium.chrome.browser.widget.quickactionsearchandbookmark.promo.SearchWidgetPromoPanel;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.playlist.mojom.PlaylistEvent;
import org.chromium.playlist.mojom.PlaylistItem;
import org.chromium.playlist.mojom.PlaylistService;
import org.chromium.ui.UiUtils;
import org.chromium.ui.base.DeviceFormFactor;
import org.chromium.ui.base.ViewUtils;
import org.chromium.ui.interpolators.Interpolators;
import org.chromium.ui.util.ColorUtils;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;
import org.chromium.url.mojom.Url;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.chromium.chrome.browser.toolbar.bottom.BrowserExpressGetFirstCommentsUtil;
import java.net.MalformedURLException;
import org.chromium.chrome.browser.ntp_background_images.model.TopSite;
import org.chromium.chrome.browser.local_database.TopSiteTable;
import java.io.File;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.chromium.chrome.browser.settings.BrowserExpressGetProfilePreferencesUtil;
import org.chromium.chrome.browser.toolbar.BraveHomeButton;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

public abstract class BraveToolbarLayoutImpl extends ToolbarLayout
        implements BraveToolbarLayout, OnClickListener, View.OnLongClickListener,
                   BraveRewardsObserver, BraveRewardsNativeWorker.PublisherObserver,
                   ConnectionErrorHandler, PlaylistServiceObserverImplDelegate {
    private static final String TAG = "BraveToolbar";
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";

    private static final int CONNECTION_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;

    private static final String YOUTUBE_DOMAIN = "youtube.com";
    private static final List<String> BRAVE_SEARCH_ENGINE_DEFAULT_REGIONS =
            Arrays.asList("CA", "DE", "FR", "GB", "US", "AT", "ES", "MX", "BR", "AR", "IN");
    private static final long MB_10 = 10000000;
    private static final long MINUTES_10 = 10 * 60 * 1000;
    private static final int URL_FOCUS_TOOLBAR_BUTTONS_TRANSLATION_X_DP = 10;

    private long lastProfileFetchTimestamp = 0;
    private static final long PROFILE_FETCH_COOLDOWN_MS = 30 * 60 * 1000;

    private static final int PLAYLIST_MEDIA_COUNT_LIMIT = 3;

    private PlaylistServiceObserverImpl mPlaylistServiceObserver;

    private DatabaseHelper mDatabaseHelper = DatabaseHelper.getInstance();

    private ImageButton mProfileButton;
    private HomeButton mHomeButton;
    private FrameLayout mProfileLayout;
    private BraveShieldsHandler mBraveShieldsHandler;
    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;
    private TabModelSelectorTabModelObserver mTabModelSelectorTabModelObserver;
    private BraveRewardsNativeWorker mBraveRewardsNativeWorker;
    private BraveRewardsPanel mRewardsPopup;
    private DAppsWalletController mDAppsWalletController;
    private BraveShieldsContentSettings mBraveShieldsContentSettings;
    private BraveShieldsContentSettingsObserver mBraveShieldsContentSettingsObserver;
    private ImageView mBraveRewardsOnboardingIcon;
    private ImageView mWalletIcon;
    private int mCurrentToolbarColor;

    private TextView mCommentsText;
    private BraveHomeButton mBottomHomeButton;
    private ImageButton mBeHomeButton;

    private boolean mIsPublisherVerified;
    private boolean mIsNotificationPosted;
    private boolean mIsInitialNotificationPosted; // initial red circle notification

    private PopupWindowTooltip mShieldsPopupWindowTooltip;

    private boolean mIsBottomToolbarVisible;

    private ColorStateList mDarkModeTint;
    private ColorStateList mLightModeTint;

    private SearchWidgetPromoPanel mSearchWidgetPromoPanel;

    private final Set<Integer> mTabsWithWalletIcon =
            Collections.synchronizedSet(new HashSet<Integer>());

    private PlaylistService mPlaylistService;

    private enum BigtechCompany { Google, Facebook, Amazon }

    public BraveToolbarLayoutImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void destroy() {
        if (mBraveShieldsContentSettings != null) {
            mBraveShieldsContentSettings.removeObserver(mBraveShieldsContentSettingsObserver);
        }
        if (mPlaylistService != null) {
            mPlaylistService.close();
        }
        if (mPlaylistServiceObserver != null) {
            mPlaylistServiceObserver.close();
            mPlaylistServiceObserver.destroy();
            mPlaylistServiceObserver = null;
        }
        super.destroy();
        if (mBraveRewardsNativeWorker != null) {
            mBraveRewardsNativeWorker.RemoveObserver(this);
            mBraveRewardsNativeWorker.RemovePublisherObserver(this);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (BraveReflectionUtil.EqualTypes(this.getClass(), ToolbarTablet.class)) {
            ImageButton forwardButton = findViewById(R.id.forward_button);
            if (forwardButton != null) {
                final Drawable forwardButtonDrawable = UiUtils.getTintedDrawable(getContext(),
                        R.drawable.btn_right_tablet, R.color.default_icon_color_tint_list);
                forwardButton.setImageDrawable(forwardButtonDrawable);
            }
        }

        mCommentsText = findViewById(R.id.comments_button1);
        mBeHomeButton = findViewById(R.id.be_home_button);
        mBottomHomeButton = findViewById(R.id.bottom_home_button);

        mProfileLayout = (FrameLayout) findViewById(R.id.profile_button_layout);
        mBraveRewardsOnboardingIcon = findViewById(R.id.br_rewards_onboarding_icon);
        mProfileButton = (ImageButton) findViewById(R.id.profile_button);
        mHomeButton = (HomeButton) findViewById(R.id.home_button);

        mDarkModeTint = ThemeUtils.getThemedToolbarIconTint(getContext(), false);
        mLightModeTint =
                ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.brave_white));
        mSearchWidgetPromoPanel = new SearchWidgetPromoPanel(getContext());
        if (mHomeButton != null) {
            mHomeButton.setOnLongClickListener(this);
        }

        if (mProfileButton != null) {
            mProfileButton.setClickable(true);
            mProfileButton.setOnClickListener(this);
            mProfileButton.setOnLongClickListener(this);
            BraveTouchUtils.ensureMinTouchTarget(mProfileButton);

            mProfileButton.post(this::fetchAndUpdateProfileImage);
        }

        mBraveShieldsHandler = new BraveShieldsHandler(getContext());
        if (!mBraveShieldsHandler.isDisconnectEntityLoaded
                && !BraveShieldsUtils.hasShieldsTooltipShown(
                        BraveShieldsUtils.PREF_SHIELDS_TOOLTIP)) {
            mBraveShieldsHandler.loadDisconnectEntityList(getContext());
        }
        mBraveShieldsHandler.addObserver(new BraveShieldsMenuObserver() {
            @Override
            public void onMenuTopShieldsChanged(boolean isOn, boolean isTopShield) {
                Tab currentTab = getToolbarDataProvider().getTab();
                if (currentTab == null) {
                    return;
                }
                if (isTopShield) {
                    updateBraveShieldsButtonState(currentTab);
                }
                if (currentTab.isLoading()) {
                    currentTab.stopLoading();
                }
                currentTab.reloadIgnoringCache();
                if (null != mBraveShieldsHandler) {
                    // Clean the Bravery Panel
                    mBraveShieldsHandler.updateValues(0, 0, 0, 0);
                }
            }
        });
        mBraveShieldsContentSettingsObserver = new BraveShieldsContentSettingsObserver() {
            @Override
            public void blockEvent(int tabId, String blockType, String subresource) {
                mBraveShieldsHandler.addStat(tabId, blockType, subresource);
                Tab currentTab = getToolbarDataProvider().getTab();
                if (currentTab == null || currentTab.getId() != tabId) {
                    return;
                }
                mBraveShieldsHandler.updateValues(tabId);
                if (!isIncognito() && OnboardingPrefManager.getInstance().isBraveStatsEnabled()
                        && (blockType.equals(BraveShieldsContentSettings.RESOURCE_IDENTIFIER_ADS)
                                || blockType.equals(BraveShieldsContentSettings
                                                            .RESOURCE_IDENTIFIER_TRACKERS))) {
                    addStatsToDb(blockType, subresource, currentTab.getUrl().getSpec());
                }
            }

            @Override
            public void savedBandwidth(long savings) {
                if (!isIncognito() && OnboardingPrefManager.getInstance().isBraveStatsEnabled()) {
                    addSavedBandwidthToDb(savings);
                }
            }
        };
        // Initially show shields off image. Shields button state will be updated when tab is
        // shown and loading state is changed.
        updateBraveShieldsButtonState(null);
        if (BraveReflectionUtil.EqualTypes(this.getClass(), ToolbarPhone.class)) {
            // if (getMenuButtonCoordinator() != null && false) {
            //     getMenuButtonCoordinator().setVisibility(false);
            // }
        }

        if (BraveReflectionUtil.EqualTypes(this.getClass(), CustomTabToolbar.class)) {
            LinearLayout customActionButtons = findViewById(R.id.action_buttons);
            assert customActionButtons != null : "Something has changed in the upstream!";
        }
        updateShieldsLayoutBackground(isIncognito()
                || !ContextUtils.getAppSharedPreferences().getBoolean(
                        AppearancePreferences.PREF_SHOW_BRAVE_REWARDS_ICON, true));
    }

    private void fetchAndUpdateProfileImage() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null){
                return;
            }
            String accessToken = activity.getAccessToken();

            if (accessToken != null) {
                Context context = ContextUtils.getApplicationContext();
                SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                String avatar = prefs.getString("avatar_url", null);
                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                if (avatar != null) {
                    ImageLoader.downloadImage(avatar, Glide.with(getContext()), true, 5, mProfileButton, null);
                }else{
                    ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(getContext()), true, 5, mProfileButton, null);
                }

                BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask workerTask1 =
                    new BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask(accessToken, getProfileCallback);
                workerTask1.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "maybeShowWalletPanel " + e);
        } catch (JSONException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (isPlaylistEnabledByPrefsAndFlags()) {
            mPlaylistService = null;
            initPlaylistService();
        }
    }

    private void initPlaylistService() {
        if (mPlaylistService != null) {
            return;
        }

        mPlaylistService = PlaylistServiceFactoryAndroid.getInstance().getPlaylistService(this);
    }

    @Override
    protected void onNativeLibraryReady() {
        super.onNativeLibraryReady();
        if (isPlaylistEnabledByPrefsAndFlags()) {
            initPlaylistService();
            mPlaylistServiceObserver = new PlaylistServiceObserverImpl(this);
            mPlaylistService.addObserver(mPlaylistServiceObserver);
        }
        mBraveShieldsContentSettings = BraveShieldsContentSettings.getInstance();
        mBraveShieldsContentSettings.addObserver(mBraveShieldsContentSettingsObserver);

        SharedPreferences sharedPreferences = ContextUtils.getAppSharedPreferences();
        mBraveRewardsNativeWorker = BraveRewardsNativeWorker.getInstance();
        if (mBraveRewardsNativeWorker != null && mBraveRewardsNativeWorker.IsSupported()
                && !BravePrefServiceBridge.getInstance().getSafetynetCheckFailed()
                && sharedPreferences.getBoolean(
                        AppearancePreferences.PREF_SHOW_BRAVE_REWARDS_ICON, true)) {
        }
        if (mBraveRewardsNativeWorker != null) {
            mBraveRewardsNativeWorker.AddObserver(this);
            mBraveRewardsNativeWorker.AddPublisherObserver(this);
            mBraveRewardsNativeWorker.TriggerOnNotifyFrontTabUrlChanged();
            mBraveRewardsNativeWorker.GetAllNotifications();
        }
    }

    @Override
    public void setTabModelSelector(TabModelSelector selector) {
        // We might miss events before calling setTabModelSelector, so we need
        // to proactively update the shields button state here, otherwise shields
        // might sometimes show as disabled while it is actually enabled.
        updateBraveShieldsButtonState(getToolbarDataProvider().getTab());
        mTabModelSelectorTabObserver = new TabModelSelectorTabObserver(selector) {
            @Override
            protected void onTabRegistered(Tab tab) {
                super.onTabRegistered(tab);
                if (tab.isIncognito()) {
                    showWalletIcon(false);
                }
            }

            @Override
            public void onShown(Tab tab, @TabSelectionType int type) {
                // Update shields button state when visible tab is changed.
                updateBraveShieldsButtonState(tab);
                // case when window.open is triggered from dapps site and new tab is in focus
                if (type != TabSelectionType.FROM_USER) {
                    dismissWalletPanelOrDialog();
                }
                findMediaFiles(tab);
            }

            @Override
            public void onHidden(Tab tab, @TabHidingType int reason) {
                hidePlaylistButton();
            }

            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                showWalletIcon(false, tab);
                if (getToolbarDataProvider().getTab() == tab) {
                    updateBraveShieldsButtonState(tab);
                }
                mBraveShieldsHandler.clearBraveShieldsCount(tab.getId());
                dismissShieldsTooltip();
                hidePlaylistButton();

                String mUrl = url.getSpec();

                if(isValidUrl(mUrl) && !tab.isIncognito()) {
                    new AsyncTask<TopSite>() {
                        @Override
                        protected TopSite doInBackground() {
                            try {
                                URL tempUrl = new URL(mUrl);
                                String protocol = tempUrl.getProtocol();
                                String host = tempUrl.getHost();

                                if (mDatabaseHelper.isTopSiteAlreadyAdded(protocol + "://" + host)) {
                                    return null;
                                }

                                // Download favicon in background
                                String faviconPath = saveFavicon(ContextUtils.getApplicationContext(), mUrl);

                                // Create TopSite object
                                return new TopSite(
                                    getWebsiteName(mUrl), 
                                    protocol + "://" + host, 
                                    "#323639", 
                                    faviconPath
                                );
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing top site", e);
                                return null;
                            }
                        }

                        @Override
                        protected void onPostExecute(TopSite topSite) {
                            Log.e(TAG, "TopSiteAsyncTask onPostExecute");
                            if (topSite != null) {
                                try {
                                    Log.e(TAG, "Inserting TopSite: " + topSite.getName());
                                    mDatabaseHelper.insertTopSite(topSite);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error inserting top site", e);
                                }
                            }
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }

                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    String accessToken = activity.getAccessToken();
                
                    if (accessToken != null) {
                        Context context = ContextUtils.getApplicationContext();
                        SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                        String avatar = prefs.getString("avatar_url", null);
                        JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);

                        if(mUrl.contains(YOUTUBE_DOMAIN)){
                            String pInfo = activity.getCurrentAppVersion();
                            JSONObject payload = new JSONObject();
                            payload.put("app_version", pInfo);
                            payload.put("url", mUrl);
                            PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                                new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.YOUTUBE_VISITED, decodedAccessTokenObj.getString("_id"), payload);
                            postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }
                        if (avatar != null) {
                            ImageLoader.downloadImage(avatar, Glide.with(getContext()), true, 5, mProfileButton, null);
                        }else{
                            ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(getContext()), true, 5, mProfileButton, null);
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastProfileFetchTimestamp > PROFILE_FETCH_COOLDOWN_MS) {
                            lastProfileFetchTimestamp = now;
                            BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask workerTask1 =
                                new BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask(accessToken, getProfileCallback);
                            workerTask1.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }
                    }

                    int commentCount = 0;
                    mCommentsText = activity.getCommentCountText();
                    mBottomHomeButton = activity.getBottomHomeButton();
                    mBeHomeButton = activity.getBeHomeButton();
                    mBeHomeButton.setVisibility(View.VISIBLE);

                    // if(mBottomHomeButton != null) {
                    //     if (mBottomHomeButton.getVisibility() == View.VISIBLE) {
                    //         mBeHomeButton.setVisibility(View.GONE);
                    //     } else {
                    //         mBeHomeButton.setVisibility(View.VISIBLE);
                    //     }
                    // } else {
                    //     mBeHomeButton.setVisibility(View.VISIBLE);
                    // }

                    mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
                    
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "BookmarkButton click " + e);
                } catch (JSONException e) {
                    Log.e("Express Browser Access Token", e.getMessage());
                }

                BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask workerTask =
                    new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask(
                            mUrl, getFirstCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }

            @Override
            public void onUrlUpdated(Tab tab) {
                String mUrl = tab.getUrl().getSpec();

                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    String accessToken = activity.getAccessToken();

                    int commentCount = 0;
                    mCommentsText = activity.getCommentCountText();
                    mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));

                    if (accessToken != null) {
                        Context context = ContextUtils.getApplicationContext();
                        SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                        String avatar = prefs.getString("avatar_url", null);
                        JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                        if (avatar != null) {
                            ImageLoader.downloadImage(avatar, Glide.with(getContext()), true, 5, mProfileButton, null);
                        }else{
                            ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(getContext()), true, 5, mProfileButton, null);
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastProfileFetchTimestamp > PROFILE_FETCH_COOLDOWN_MS) {
                            lastProfileFetchTimestamp = now;
                            BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask workerTask1 =
                                new BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask(accessToken, getProfileCallback);
                            workerTask1.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }
                    }
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "BookmarkButton click " + e);
                } catch (JSONException e) {
                    Log.e("Express Browser Access Token", e.getMessage());
                }

                BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask workerTask =
                    new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask(
                            mUrl, getFirstCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

                super.onUrlUpdated(tab);
            }

            @Override
            public void onPageLoadFinished(final Tab tab, GURL url) {
                if (getToolbarDataProvider().getTab() == tab) {
                    mBraveShieldsHandler.updateUrlSpec(url.getSpec());
                    updateBraveShieldsButtonState(tab);

                }

                String countryCode = Locale.getDefault().getCountry();
                // if (countryCode.equals(BraveConstants.INDIA_COUNTRY_CODE)
                //         && url.domainIs(YOUTUBE_DOMAIN)
                //         && SharedPreferencesManager.getInstance().readBoolean(
                //                 BravePreferenceKeys.BRAVE_AD_FREE_CALLOUT_DIALOG, true)) {
                //     SharedPreferencesManager.getInstance().writeBoolean(
                //             BravePreferenceKeys.BRAVE_OPENED_YOUTUBE, true);
                // }

                if (url.getSpec().contains("youtube.com/watch")) {
                    SharedPreferencesManager.getInstance().writeBoolean(BravePreferenceKeys.BRAVE_OPENED_YOUTUBE, true);
                }else{
                    SharedPreferencesManager.getInstance().writeBoolean(BravePreferenceKeys.BRAVE_OPENED_YOUTUBE, false);
                }
            }

            private void showNotificationNotEarningDialog() {
                try {
                    RewardsYouAreNotEarningDialog rewardsYouAreNotEarningDialog =
                            RewardsYouAreNotEarningDialog.newInstance();
                    rewardsYouAreNotEarningDialog.setCancelable(false);
                    rewardsYouAreNotEarningDialog.show(
                            BraveActivity.getBraveActivity().getSupportFragmentManager(),
                            RewardsYouAreNotEarningDialog.RewardsYouAreNotEarningDialogTAG);

                } catch (BraveActivity.BraveActivityNotFoundException | IllegalStateException e) {
                    Log.e(TAG, "showNotificationNotEarningDialog " + e);
                }
            }

            @Override
            public void onDidFinishNavigationInPrimaryMainFrame(
                    Tab tab, NavigationHandle navigation) {
                if (getToolbarDataProvider().getTab() == tab && mBraveRewardsNativeWorker != null
                        && !tab.isIncognito()) {
                    mBraveRewardsNativeWorker.OnNotifyFrontTabUrlChanged(
                            tab.getId(), tab.getUrl().getSpec());
                }
                if (PackageUtils.isFirstInstall(getContext()) && tab.getUrl().getSpec() != null
                        && (tab.getUrl().getSpec().equals(BraveActivity.BRAVE_REWARDS_SETTINGS_URL))
                        && BraveRewardsHelper.shouldShowBraveRewardsOnboardingModal()
                        && mBraveRewardsNativeWorker != null
                        && !mBraveRewardsNativeWorker.isRewardsEnabled()
                        && mBraveRewardsNativeWorker.IsSupported()) {
                    showOnBoarding();
                }
                findMediaFiles(tab);
            }

            @Override
            public void onDestroyed(Tab tab) {
                // Remove references for the ads from the Database. Tab is destroyed, they are not
                // needed anymore.
                new Thread() {
                    @Override
                    protected void onTabRegistered(Tab tab) {
                        super.onTabRegistered(tab);
                        if (tab.isIncognito()) {
                            showWalletIcon(false);
                        }
                    }

                    @Override
                    public void onShown(Tab tab, @TabSelectionType int type) {
                        // Update shields button state when visible tab is changed.
                        updateBraveShieldsButtonState(tab);
                        // case when window.open is triggered from dapps site and new tab is in
                        // focus
                        if (type != TabSelectionType.FROM_USER) {
                            dismissWalletPanelOrDialog();
                        }
                        findMediaFiles(tab);
                    }

                    @Override
                    public void onHidden(Tab tab, @TabHidingType int reason) {
                        hidePlaylistButton();
                    }

                    @Override
                    public void onPageLoadStarted(Tab tab, GURL url) {
                        showWalletIcon(false, tab);
                        if (getToolbarDataProvider().getTab() == tab) {
                            updateBraveShieldsButtonState(tab);
                        }
                        mBraveShieldsHandler.clearBraveShieldsCount(tab.getId());
                        dismissShieldsTooltip();
                        hidePlaylistButton();
                    }

                    @Override
                    public void onPageLoadFinished(final Tab tab, GURL url) {
                        if (getToolbarDataProvider().getTab() == tab) {
                            mBraveShieldsHandler.updateUrlSpec(url.getSpec());
                            updateBraveShieldsButtonState(tab);

                            if (mBraveShieldsButton != null
                                    && mBraveShieldsButton.isShown()
                                    && mBraveShieldsHandler != null
                                    && !mBraveShieldsHandler.isShowing()) {
                                checkForTooltip(tab);
                            }
                        }

                        if (mBraveShieldsButton != null
                                && mBraveShieldsButton.isShown()
                                && mBraveShieldsHandler != null
                                && !mBraveShieldsHandler.isShowing()
                                && url.getSpec().contains("rewards")
                                && ((!BravePermissionUtils.hasNotificationPermission(getContext()))
                                        || BraveNotificationWarningDialog
                                                .shouldShowRewardWarningDialog(getContext()))) {
                            showNotificationNotEarningDialog();
                        }

                        String countryCode = Locale.getDefault().getCountry();
                        if (countryCode.equals(BraveConstants.INDIA_COUNTRY_CODE)
                                && url.domainIs(YOUTUBE_DOMAIN)
                                && SharedPreferencesManager.getInstance()
                                        .readBoolean(
                                                BravePreferenceKeys.BRAVE_AD_FREE_CALLOUT_DIALOG,
                                                true)) {
                            SharedPreferencesManager.getInstance()
                                    .writeBoolean(BravePreferenceKeys.BRAVE_OPENED_YOUTUBE, true);
                        }
                    }

                    private void showNotificationNotEarningDialog() {
                        try {
                            RewardsYouAreNotEarningDialog rewardsYouAreNotEarningDialog =
                                    RewardsYouAreNotEarningDialog.newInstance();
                            rewardsYouAreNotEarningDialog.setCancelable(false);
                            rewardsYouAreNotEarningDialog.show(
                                    BraveActivity.getBraveActivity().getSupportFragmentManager(),
                                    RewardsYouAreNotEarningDialog.RewardsYouAreNotEarningDialogTAG);

                        } catch (BraveActivity.BraveActivityNotFoundException
                                | IllegalStateException e) {
                            Log.e(TAG, "showNotificationNotEarningDialog " + e);
                        }
                    }

                    @Override
                    public void onDidFinishNavigationInPrimaryMainFrame(
                            Tab tab, NavigationHandle navigation) {
                        if (getToolbarDataProvider().getTab() == tab
                                && mBraveRewardsNativeWorker != null
                                && !tab.isIncognito()) {
                            mBraveRewardsNativeWorker.OnNotifyFrontTabUrlChanged(
                                    tab.getId(), tab.getUrl().getSpec());
                        }
                        if (PackageUtils.isFirstInstall(getContext())
                                && tab.getUrl().getSpec() != null
                                && (tab.getUrl()
                                        .getSpec()
                                        .equals(BraveActivity.BRAVE_REWARDS_SETTINGS_URL))
                                && BraveRewardsHelper.shouldShowBraveRewardsOnboardingModal()
                                && mBraveRewardsNativeWorker != null
                                && !mBraveRewardsNativeWorker.isRewardsEnabled()
                                && mBraveRewardsNativeWorker.IsSupported()) {
                            showOnBoarding();
                        }
                        findMediaFiles(tab);
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        // Remove references for the ads from the Database. Tab is destroyed, they
                        // are not
                        // needed anymore.
                        new Thread() {
                            @Override
                            public void run() {
                                mDatabaseHelper.deleteDisplayAdsFromTab(tab.getId());
                            }
                        }.start();
                        mBraveShieldsHandler.removeStat(tab.getId());
                        mTabsWithWalletIcon.remove(tab.getId());
                    }
                };

        mTabModelSelectorTabModelObserver = new TabModelSelectorTabModelObserver(selector) {
            @Override
            public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
                if (mBraveRewardsNativeWorker != null && !tab.isIncognito()) {
                    mBraveRewardsNativeWorker.OnNotifyFrontTabUrlChanged(
                            tab.getId(), tab.getUrl().getSpec());
                    Tab providerTab = getToolbarDataProvider().getTab();
                    if (providerTab != null && providerTab.getId() == tab.getId()) {
                        showWalletIcon(mTabsWithWalletIcon.contains(tab.getId()));
                    }
                }
            }
        };
    }



    private void showOnBoarding() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            int deviceWidth = ConfigurationUtils.getDisplayMetrics(activity).get("width");
            boolean isTablet = DeviceFormFactor.isNonMultiDisplayContextOnTablet(activity);
            deviceWidth = (int) (isTablet ? (deviceWidth * 0.6) : (deviceWidth * 0.95));
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "RewardsOnboarding failed " + e);
        }
    }

    private static boolean isPlaylistEnabledByPrefsAndFlags() {
        return ChromeFeatureList.isEnabled(BraveFeatureList.BRAVE_PLAYLIST)
                && SharedPreferencesManager.getInstance().readBoolean(
                        BravePlaylistPreferences.PREF_ENABLE_PLAYLIST, true);
    }

    private void hidePlaylistButton() {
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            View playlistButton = viewGroup.findViewById(R.id.playlist_button_id);
            if (playlistButton != null && playlistButton.getVisibility() == View.VISIBLE) {
                playlistButton.setVisibility(View.GONE);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "hidePlaylistButton " + e);
        }
    }

    private boolean isPlaylistButtonVisible() {
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            View playlistButton = viewGroup.findViewById(R.id.playlist_button_id);
            return playlistButton != null && playlistButton.getVisibility() == View.VISIBLE;
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "isPlaylistButtonVisible " + e);
            return false;
        }
    }

    private void findMediaFiles(Tab tab) {
        if (isPlaylistEnabledByPrefsAndFlags() && mPlaylistService != null) {
            hidePlaylistButton();
            mPlaylistService.findMediaFilesFromActiveTab((url, playlistItems) -> {});
        }
    }

    private void showPlaylistButton(PlaylistItem[] items) {
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);

            PlaylistOptionsListener playlistOptionsListener = new PlaylistOptionsListener() {
                @Override
                public void onOptionClicked(PlaylistOptionsModel playlistOptionsModel) {
                    try {
                        if (playlistOptionsModel.getOptionType() == PlaylistOptions.ADD_MEDIA) {
                            int mediaCount = SharedPreferencesManager.getInstance().readInt(
                                    PlaylistPreferenceUtils.ADD_MEDIA_COUNT);
                            if (mediaCount == 2) {
                                PlaylistWarningDialogListener playlistWarningDialogListener =
                                        new PlaylistWarningDialogListener() {
                                            @Override
                                            public void onActionClicked() {
                                                addMediaToPlaylist(items);
                                            }

                                            @Override
                                            public void onSettingsClicked() {
                                                try {
                                                    BraveActivity.getBraveActivity()
                                                            .openBravePlaylistSettings();
                                                } catch (
                                                        BraveActivity
                                                                .BraveActivityNotFoundException e) {
                                                    Log.e(TAG,
                                                            "showPlaylistButton"
                                                                    + " onOptionClicked"
                                                                    + " onSettingsClicked" + e);
                                                }
                                            }
                                        };
                                BraveActivity.getBraveActivity().showPlaylistWarningDialog(
                                        playlistWarningDialogListener);

                            } else {
                                addMediaToPlaylist(items);
                            }
                        } else if (playlistOptionsModel.getOptionType()
                                == PlaylistOptions.OPEN_PLAYLIST) {
                            BraveActivity.getBraveActivity().openPlaylistActivity(
                                    getContext(), ConstantUtils.DEFAULT_PLAYLIST);
                        } else if (playlistOptionsModel.getOptionType()
                                == PlaylistOptions.PLAYLIST_SETTINGS) {
                            BraveActivity.getBraveActivity().openBravePlaylistSettings();
                        }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e(TAG, "showPlaylistButton onOptionClicked " + e);
                    }
                }
            };
            if (!isPlaylistButtonVisible()) {
                PlaylistViewUtils.showPlaylistButton(
                        BraveActivity.getBraveActivity(), viewGroup, playlistOptionsListener);
                if (SharedPreferencesManager.getInstance().readBoolean(
                            PlaylistPreferenceUtils.SHOULD_SHOW_PLAYLIST_ONBOARDING, true)) {
                    View playlistButton = viewGroup.findViewById(R.id.playlist_button_id);
                    if (playlistButton != null) {
                        playlistButton.post(new Runnable() {
                            @Override
                            public void run() {
                                PlaylistOnboardingActionClickListener
                                        playlistOnboardingActionClickListener =
                                                new PlaylistOnboardingActionClickListener() {
                                                    @Override
                                                    public void onOnboardingActionClick() {
                                                        addMediaToPlaylist(items);
                                                    }
                                                };
                                try {
                                    new PlaylistOnboardingPanel(
                                            (FragmentActivity) BraveActivity.getBraveActivity(),
                                            playlistButton, playlistOnboardingActionClickListener);
                                } catch (BraveActivity.BraveActivityNotFoundException e) {
                                    Log.e(TAG, "showPlaylistButton " + e);
                                }
                            }
                        });
                    }
                    SharedPreferencesManager.getInstance().writeBoolean(
                            PlaylistPreferenceUtils.SHOULD_SHOW_PLAYLIST_ONBOARDING, false);
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showPlaylistButton " + e);
        }
    }

    private void addMediaToPlaylist(PlaylistItem[] items) {
        if (mPlaylistService == null) {
            return;
        }
        mPlaylistService.addMediaFiles(items, ConstantUtils.DEFAULT_PLAYLIST,
                shouldCacheMediaFilesForPlaylist(), addedItems -> {});
        int mediaCount = SharedPreferencesManager.getInstance().readInt(
                PlaylistPreferenceUtils.ADD_MEDIA_COUNT);
        if (mediaCount < PLAYLIST_MEDIA_COUNT_LIMIT) {
            SharedPreferencesManager.getInstance().writeInt(
                    PlaylistPreferenceUtils.ADD_MEDIA_COUNT, mediaCount + 1);
        }
    }

    private void addMediaToPlaylist() {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (mPlaylistService == null || currentTab == null) {
            return;
        }
        org.chromium.url.mojom.Url contentUrl = new org.chromium.url.mojom.Url();
        contentUrl.url = currentTab.getUrl().getSpec();
        mPlaylistService.addMediaFilesFromPageToPlaylist(
                ConstantUtils.DEFAULT_PLAYLIST, contentUrl, shouldCacheMediaFilesForPlaylist());
    }

    private void showAddedToPlaylistSnackBar() {
        SnackBarActionModel snackBarActionModel =
                new SnackBarActionModel(getContext().getResources().getString(R.string.view_action),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                try {
                                    BraveActivity.getBraveActivity().openPlaylistActivity(
                                            getContext(), ConstantUtils.DEFAULT_PLAYLIST);
                                } catch (BraveActivity.BraveActivityNotFoundException e) {
                                    Log.e(TAG, "showAddedToPlaylistSnackBar onClick " + e);
                                }
                            }
                        });
        try {
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            PlaylistViewUtils.showSnackBarWithActions(viewGroup,
                    String.format(getContext().getResources().getString(R.string.added_to_playlist),
                            getContext().getResources().getString(R.string.playlist_play_later)),
                    snackBarActionModel);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showAddedToPlaylistSnackBar " + e);
        }
    }

    private boolean shouldCacheMediaFilesForPlaylist() {
        boolean shouldCacheOnlyOnWifi =
                (SharedPreferencesManager.getInstance().readInt(
                         BravePlaylistPreferences.PREF_AUTO_SAVE_MEDIA_FOR_OFFLINE, 0)
                                == 2
                        && ConnectionUtils.isWifiAvailable(getContext()));

        boolean shouldCache = SharedPreferencesManager.getInstance().readInt(
                                      BravePlaylistPreferences.PREF_AUTO_SAVE_MEDIA_FOR_OFFLINE, 0)
                        == 0
                || shouldCacheOnlyOnWifi;
        return shouldCache;
    }

    private void checkForTooltip(Tab tab) {
        try {
            if (!BraveShieldsUtils.isTooltipShown
                    && !BraveActivity.getBraveActivity().mIsDeepLink) {
                if (!BraveShieldsUtils.hasShieldsTooltipShown(
                            BraveShieldsUtils.PREF_SHIELDS_TOOLTIP)
                        && mBraveShieldsHandler.getTrackersBlockedCount(tab.getId())
                                        + mBraveShieldsHandler.getAdsBlockedCount(tab.getId())
                                > 0) {
                    showTooltip(BraveShieldsUtils.PREF_SHIELDS_TOOLTIP, tab.getId());
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "checkForTooltip " + e);
        }
    }

    private void showTooltip(String tooltipPref, int tabId) {
        try {
            HighlightView highlightView = new HighlightView(getContext(), null);
            highlightView.setColor(ContextCompat.getColor(
                    getContext(), R.color.onboarding_search_highlight_color));
            ViewGroup viewGroup =
                    BraveActivity.getBraveActivity().getWindow().getDecorView().findViewById(
                            android.R.id.content);
            float padding = (float) dpToPx(getContext(), 20);
            mShieldsPopupWindowTooltip =
                    new PopupWindowTooltip.Builder(getContext())
                            .arrowColor(ContextCompat.getColor(
                                    getContext(), R.color.onboarding_arrow_color))
                            .gravity(Gravity.BOTTOM)
                            .dismissOnOutsideTouch(true)
                            .dismissOnInsideTouch(false)
                            .backgroundDimDisabled(true)
                            .padding(padding)
                            .parentPaddingHorizontal(dpToPx(getContext(), 10))
                            .modal(true)
                            .onDismissListener(tooltip -> {
                                if (viewGroup != null && highlightView != null) {
                                    highlightView.stopAnimation();
                                    viewGroup.removeView(highlightView);
                                }
                            })
                            .contentView(R.layout.brave_shields_tooltip_layout)
                            .build();

            ArrayList<String> blockerNamesList = mBraveShieldsHandler.getBlockerNamesList(tabId);

            int adsTrackersCount = mBraveShieldsHandler.getTrackersBlockedCount(tabId)
                    + mBraveShieldsHandler.getAdsBlockedCount(tabId);

            String displayTrackerName = "";
            if (blockerNamesList.contains(BigtechCompany.Google.name())) {
                displayTrackerName = BigtechCompany.Google.name();
            } else if (blockerNamesList.contains(BigtechCompany.Facebook.name())) {
                displayTrackerName = BigtechCompany.Facebook.name();
            } else if (blockerNamesList.contains(BigtechCompany.Amazon.name())) {
                displayTrackerName = BigtechCompany.Amazon.name();
            }

            String trackerText = "";
            if (!displayTrackerName.isEmpty()) {
                if (adsTrackersCount - 1 == 0) {
                    trackerText =
                            String.format(getContext().getResources().getString(
                                                  R.string.shield_bigtech_tracker_only_blocked),
                                    displayTrackerName);

                } else {
                    trackerText = String.format(getContext().getResources().getString(
                                                        R.string.shield_bigtech_tracker_blocked),
                            displayTrackerName, String.valueOf(adsTrackersCount - 1));
                }
            } else {
                trackerText = String.format(
                        getContext().getResources().getString(R.string.shield_tracker_blocked),
                        String.valueOf(adsTrackersCount));
            }

            TextView tvBlocked = mShieldsPopupWindowTooltip.findViewById(R.id.tv_blocked);
            tvBlocked.setText(trackerText);

        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "showTooltip " + e);
        }
    }

    public void dismissShieldsTooltip() {
        if (mShieldsPopupWindowTooltip != null && mShieldsPopupWindowTooltip.isShowing()) {
            mShieldsPopupWindowTooltip.dismiss();
            mShieldsPopupWindowTooltip = null;
        }
    }

    public void reopenShieldsPanel() {
        if (mBraveShieldsHandler != null && mBraveShieldsHandler.isShowing()) {
            mBraveShieldsHandler.hideBraveShieldsMenu();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dismissShieldsTooltip();
        reopenShieldsPanel();
        // TODO: show wallet panel
    }

    private void showBraveRewardsOnboardingModal() {
        Context context = getContext();
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setContentView(R.layout.brave_rewards_onboarding_modal);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        View braveRewardsOnboardingModalView =
                dialog.findViewById(R.id.brave_rewards_onboarding_modal_layout);

        String tosText =
                String.format(context.getResources().getString(R.string.brave_rewards_tos_text),
                        context.getResources().getString(R.string.terms_of_service),
                        context.getResources().getString(R.string.privacy_policy));
        int termsOfServiceIndex =
                tosText.indexOf(context.getResources().getString(R.string.terms_of_service));
        Spanned tosTextSpanned = BraveRewardsHelper.spannedFromHtmlString(tosText);
        SpannableString tosTextSS = new SpannableString(tosTextSpanned.toString());

        ClickableSpan tosClickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View textView) {
                CustomTabActivity.showInfoPage(context, BraveActivity.BRAVE_TERMS_PAGE);
            }
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setUnderlineText(false);
            }
        };

        tosTextSS.setSpan(tosClickableSpan, termsOfServiceIndex,
                termsOfServiceIndex
                        + context.getResources().getString(R.string.terms_of_service).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tosTextSS.setSpan(new ForegroundColorSpan(context.getResources().getColor(
                                  R.color.brave_rewards_modal_theme_color)),
                termsOfServiceIndex,
                termsOfServiceIndex
                        + context.getResources().getString(R.string.terms_of_service).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ClickableSpan privacyProtectionClickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View textView) {
                CustomTabActivity.showInfoPage(context, BraveActivity.BRAVE_PRIVACY_POLICY);
            }
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setUnderlineText(false);
            }
        };

        int privacyPolicyIndex =
                tosText.indexOf(context.getResources().getString(R.string.privacy_policy));
        tosTextSS.setSpan(privacyProtectionClickableSpan, privacyPolicyIndex,
                privacyPolicyIndex
                        + context.getResources().getString(R.string.privacy_policy).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tosTextSS.setSpan(new ForegroundColorSpan(context.getResources().getColor(
                                  R.color.brave_rewards_modal_theme_color)),
                privacyPolicyIndex,
                privacyPolicyIndex
                        + context.getResources().getString(R.string.privacy_policy).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView tosAndPpText = braveRewardsOnboardingModalView.findViewById(
                R.id.brave_rewards_onboarding_modal_tos_pp_text);
        tosAndPpText.setMovementMethod(LinkMovementMethod.getInstance());
        tosAndPpText.setText(tosTextSS);
        BraveTouchUtils.ensureMinTouchTarget(tosAndPpText);

        TextView takeQuickTourButton =
                braveRewardsOnboardingModalView.findViewById(R.id.take_quick_tour_button);
        takeQuickTourButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BraveRewardsHelper.setShowBraveRewardsOnboardingOnce(true);
                openRewardsPanel();
                dialog.dismiss();
            }
        }));
        BraveTouchUtils.ensureMinTouchTarget(takeQuickTourButton);
        TextView btnBraveRewards =
                braveRewardsOnboardingModalView.findViewById(R.id.start_using_brave_rewards_text);
        btnBraveRewards.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BraveRewardsHelper.setShowDeclareGeoModal(true);
                openRewardsPanel();
                dialog.dismiss();
            }
        }));

        dialog.show();
    }

    private void addSavedBandwidthToDb(long savings) {
        new AsyncTask<Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    SavedBandwidthTable savedBandwidthTable = new SavedBandwidthTable(
                            savings, BraveStatsUtil.getCalculatedDate("yyyy-MM-dd", 0));
                    long rowId = mDatabaseHelper.insertSavedBandwidth(savedBandwidthTable);
                } catch (Exception e) {
                    // Do nothing if url is invalid.
                    // Just return w/o showing shields popup.
                    return null;
                }
                return null;
            }
            @Override
            protected void onPostExecute(Void result) {
                assert ThreadUtils.runningOnUiThread();
                if (isCancelled()) return;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void addStatsToDb(String statType, String statSite, String url) {
        new AsyncTask<Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    URL urlObject = new URL(url);
                    URL siteObject = new URL(statSite);
                    BraveStatsTable braveStatsTable = new BraveStatsTable(url, urlObject.getHost(),
                            statType, statSite, siteObject.getHost(),
                            BraveStatsUtil.getCalculatedDate("yyyy-MM-dd", 0));
                    long rowId = mDatabaseHelper.insertStats(braveStatsTable);
                } catch (Exception e) {
                    // Do nothing if url is invalid.
                    // Just return w/o showing shields popup.
                    return null;
                }
                return null;
            }
            @Override
            protected void onPostExecute(Void result) {
                assert ThreadUtils.runningOnUiThread();
                if (isCancelled()) return;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public boolean isWalletIconVisible() {
        return false;
    }

    public void showWalletIcon(boolean show, Tab tab) {
        // The layout could be null in Custom Tabs layout
        Tab currentTab = tab;
        if (currentTab == null) {
            currentTab = getToolbarDataProvider().getTab();
            if (currentTab == null) {
                return;
            }
        }
    }

    public void showWalletIcon(boolean show) {
        showWalletIcon(show, null);
    }

    public void hideRewardsOnboardingIcon() {
        if (mBraveRewardsOnboardingIcon != null) {
            mBraveRewardsOnboardingIcon.setVisibility(View.GONE);
        }
        SharedPreferences sharedPref = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putBoolean(BraveRewardsPanel.PREF_WAS_TOOLBAR_BAT_LOGO_BUTTON_PRESSED, true);
        editor.apply();
    }

    @Override
    public void onClickImpl(View v) {
        if (mBraveShieldsHandler == null) {
            assert false;
            return;
        }
        if (mProfileButton == v && mProfileButton != null){
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                String accessToken = activity.getAccessToken();
                if (accessToken == null) {
                    activity.openBrowserExpressLoginSettings();
                } else {
                    activity.openBrowserExpressProfileSettings();
                }
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "maybeShowWalletPanel " + e);
            }
        }
        if (mHomeButton == v) {
            // Helps Brave News know how to behave on home button action
            try {
                BraveActivity.getBraveActivity().setComesFromNewTab(true);
                fetchAndUpdateProfileImage();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "HomeButton click " + e);
            }
        }
    }

    private void maybeShowWalletPanel(View v) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            activity.showWalletPanel(true);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "maybeShowWalletPanel " + e);
        }
    }

    private void showWalletPanelInternal(View v) {
        mDAppsWalletController =
                new DAppsWalletController(getContext(), v, dialog -> mDAppsWalletController = null);
        mDAppsWalletController.showWalletPanel();
    }

    public void showWalletPanel() {
        dismissWalletPanelOrDialog();
        showWalletPanelInternal(this);
    }

    @Override
    public void onClick(View v) {
        onClickImpl(v);
    }

    private boolean checkForRewardsOnboarding() {
        return PackageUtils.isFirstInstall(getContext()) && mBraveRewardsNativeWorker != null
                && !mBraveRewardsNativeWorker.isRewardsEnabled()
                && mBraveRewardsNativeWorker.IsSupported()
                && !OnboardingPrefManager.getInstance().isOnboardingShown();
    }

    private void showShieldsMenu(View mBraveShieldsButton) {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (currentTab == null) {
            return;
        }
        try {
            URL url = new URL(currentTab.getUrl().getSpec());
            // Don't show shields popup if protocol is not valid for shields.
            if (!isValidProtocolForShields(url.getProtocol())) {
                return;
            }
            mBraveShieldsHandler.show(mBraveShieldsButton, currentTab);
        } catch (Exception e) {
            // Do nothing if url is invalid.
            // Just return w/o showing shields popup.
            return;
        }
    }

    @Override
    public boolean onLongClickImpl(View v) {
        // Use null as the default description since Toast.showAnchoredToast
        // will return false if it is null.
        String description = null;
        Context context = getContext();
        Resources resources = context.getResources();

        if (v == mHomeButton) {
            description = resources.getString(R.string.accessibility_toolbar_btn_home);
        }

        return Toast.showAnchoredToast(context, v, description);
    }

    @Override
    public boolean onLongClick(View v) {
        return onLongClickImpl(v);
    }

    @Override
    public void onUrlFocusChange(boolean hasFocus) {
        Context context = getContext();
        String countryCode = Locale.getDefault().getCountry();
        try {
            if (hasFocus && PackageUtils.isFirstInstall(context)
                    && BraveActivity.getBraveActivity().getActivityTab() != null
                    && UrlUtilities.isNTPUrl(
                            BraveActivity.getBraveActivity().getActivityTab().getUrl().getSpec())
                    && !OnboardingPrefManager.getInstance().hasSearchEngineOnboardingShown()
                    && OnboardingPrefManager.getInstance().getUrlFocusCount() == 1
                    && !BRAVE_SEARCH_ENGINE_DEFAULT_REGIONS.contains(countryCode)) {
                Intent searchActivityIntent = new Intent(context, SearchActivity.class);
                searchActivityIntent.setAction(Intent.ACTION_VIEW);
                context.startActivity(searchActivityIntent);
            }

        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "onUrlFocusChange " + e);
        }

        // We need to enable the promo for later release.
        // Delay showing the panel. Otherwise there are ANRs on holding onUrlFocusChange
        /* PostTask.postTask(TaskTraits.UI_DEFAULT, () -> {
            int appOpenCountForWidgetPromo = SharedPreferencesManager.getInstance().readInt(
                    BravePreferenceKeys.BRAVE_APP_OPEN_COUNT_FOR_WIDGET_PROMO);
            if (hasFocus
                    && appOpenCountForWidgetPromo >= BraveActivity.APP_OPEN_COUNT_FOR_WIDGET_PROMO)
                mSearchWidgetPromoPanel.showIfNeeded(this);
        }); */

        if (OnboardingPrefManager.getInstance().getUrlFocusCount() == 0) {
            OnboardingPrefManager.getInstance().updateUrlFocusCount();
        }
        super.onUrlFocusChange(hasFocus);
    }

    @Override
    public void populateUrlAnimatorSetImpl(boolean showExpandedState,
            int urlFocusToolbarButtonsDuration, int urlClearFocusTabStackDelayMs,
            List<Animator> animators) {
    }

    @Override
    public void updateModernLocationBarColorImpl(int color) {
        mCurrentToolbarColor = color;
        if (mProfileLayout != null) {
            // mProfileLayout.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * If |tab| is null, set disabled image to shields button and |urlString| is
     * ignored.
     * If |urlString| is null, url is fetched from |tab|.
     */
    private void updateBraveShieldsButtonState(Tab tab) {
        if (tab == null) {
            return;
        }
        SharedPreferences sharedPreferences = ContextUtils.getAppSharedPreferences();

        if (isIncognito()) {
            updateShieldsLayoutBackground(true);
        } else if (isNativeLibraryReady() && mBraveRewardsNativeWorker != null
                && mBraveRewardsNativeWorker.IsSupported()
                && !BravePrefServiceBridge.getInstance().getSafetynetCheckFailed()
                && sharedPreferences.getBoolean(
                        AppearancePreferences.PREF_SHOW_BRAVE_REWARDS_ICON, true)) {
            updateShieldsLayoutBackground(false);
        }
    }

    private boolean isShieldsOnForTab(Tab tab) {
        if (!isNativeLibraryReady() || tab == null
                || Profile.fromWebContents(((TabImpl) tab).getWebContents()) == null) {
            return false;
        }

        return BraveShieldsContentSettings.getShields(
                Profile.fromWebContents(((TabImpl) tab).getWebContents()), tab.getUrl().getSpec(),
                BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS);
    }

    private boolean isValidProtocolForShields(String protocol) {
        if (protocol.equals("http") || protocol.equals("https")) {
            return true;
        }

        return false;
    }

    public void dismissRewardsPanel() {
        if (mRewardsPopup != null) {
            mRewardsPopup.dismiss();
            mRewardsPopup = null;
        }
    }

    public void dismissWalletPanelOrDialog() {
        if (mDAppsWalletController != null) {
            mDAppsWalletController.dismiss();
            mDAppsWalletController = null;
        }
    }

    public void onRewardsPanelDismiss() {
        mRewardsPopup = null;
    }

    public void openRewardsPanel() {
    }

    public boolean isShieldsTooltipShown() {
        if (mShieldsPopupWindowTooltip != null) {
            return mShieldsPopupWindowTooltip.isShowing();
        }
        return false;
    }

    @Override
    public void onCompleteReset(boolean success) {
        if (success) {
            BraveRewardsHelper.resetRewards();
            try {
                BraveRelaunchUtils.askForRelaunch(BraveActivity.getBraveActivity());
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "onCompleteReset " + e);
            }
        }
    }

    @Override
    public void OnNotificationAdded(String id, int type, long timestamp, String[] args) {
        if (mBraveRewardsNativeWorker == null) {
            return;
        }

        if (type == BraveRewardsNativeWorker.REWARDS_NOTIFICATION_GRANT) {
            // Set flag
            SharedPreferences sharedPreferences = ContextUtils.getAppSharedPreferences();
            SharedPreferences.Editor sharedPreferencesEditor = sharedPreferences.edit();
            sharedPreferencesEditor.putBoolean(
                    BraveRewardsPanel.PREF_GRANTS_NOTIFICATION_RECEIVED, true);
            sharedPreferencesEditor.apply();
        }
        mBraveRewardsNativeWorker.GetAllNotifications();
    }

    private boolean mayShowBraveAdsOnboardingDialog() {
        Context context = getContext();

        if (BraveAdsSignupDialog.shouldShowNewUserDialog(context)) {
            BraveAdsSignupDialog.showNewUserDialog(getContext());
            return true;
        } else if (BraveAdsSignupDialog.shouldShowNewUserDialogIfRewardsIsSwitchedOff(context)) {
            BraveAdsSignupDialog.showNewUserDialog(getContext());
            return true;
        } else if (BraveAdsSignupDialog.shouldShowExistingUserDialog(context)) {
            BraveAdsSignupDialog.showExistingUserDialog(getContext());
            return true;
        }

        return false;
    }

    @Override
    public void OnNotificationsCount(int count) {
        updateNotificationBadgeForNewInstall();
        if (!PackageUtils.isFirstInstall(getContext())
                && !OnboardingPrefManager.getInstance().isAdsAvailable()) {
            mayShowBraveAdsOnboardingDialog();
        }

        if (checkForRewardsOnboarding()) {
            if (mBraveRewardsOnboardingIcon != null) {
                mBraveRewardsOnboardingIcon.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateNotificationBadgeForNewInstall() {
        SharedPreferences sharedPref = ContextUtils.getAppSharedPreferences();
        boolean shownBefore = sharedPref.getBoolean(
                BraveRewardsPanel.PREF_WAS_TOOLBAR_BAT_LOGO_BUTTON_PRESSED, false);
        boolean shouldShow = false;
        mIsInitialNotificationPosted = shouldShow; // initial notification

        if (!shouldShow) return;
    }

    @Override
    public void onThemeColorChanged(int color, boolean shouldAnimate) {
        if (mWalletIcon != null) {
            ImageViewCompat.setImageTintList(mWalletIcon,
                    !ColorUtils.shouldUseLightForegroundOnBackground(color) ? mDarkModeTint
                                                                            : mLightModeTint);
        }

        final int textBoxColor = ThemeUtils.getTextBoxColorForToolbarBackgroundInNonNativePage(
                getContext(), color, isIncognito());
        updateModernLocationBarColorImpl(textBoxColor);
    }

    /**
     * BraveRewardsNativeWorker.PublisherObserver:
     *   Update a 'verified publisher' checkmark on url bar BAT icon only if
     *   no notifications are posted.
     */
    @Override
    public void onFrontTabPublisherChanged(boolean verified) {
        mIsPublisherVerified = verified;
        updateVerifiedPublisherMark();
    }

    private void updateVerifiedPublisherMark() {
        if (mIsInitialNotificationPosted) {
            return;
        } else if (!mIsNotificationPosted) {
            if (mIsPublisherVerified) {
            } else {
            }
        }
    }

    public void onBottomToolbarVisibilityChanged(boolean isVisible) {
        mIsBottomToolbarVisible = isVisible;
        if (BraveReflectionUtil.EqualTypes(this.getClass(), ToolbarPhone.class)
                && getMenuButtonCoordinator() != null) {
            getMenuButtonCoordinator().setVisibility(true);
            ToggleTabStackButton toggleTabStackButton = findViewById(R.id.tab_switcher_button);
            if (toggleTabStackButton != null) {
                toggleTabStackButton.setVisibility(VISIBLE);
                // toggleTabStackButton.setVisibility(isTabSwitcherOnBottom() ? GONE : VISIBLE);
            }
        }
    }

    private void updateShieldsLayoutBackground(boolean rounded) {
    }

    private boolean isTabSwitcherOnBottom() {
        return mIsBottomToolbarVisible && BottomToolbarVariationManager.isTabSwitcherOnBottom();
    }

    private boolean isMenuButtonOnBottom() {
        return BottomToolbarVariationManager.isMenuButtonOnBottom();
    }

    @Override
    public void initialize(ToolbarDataProvider toolbarDataProvider,
            ToolbarTabController tabController, MenuButtonCoordinator menuButtonCoordinator,
            HistoryDelegate historyDelegate, BooleanSupplier partnerHomepageEnabledSupplier,
            OfflineDownloader offlineDownloader) {
        super.initialize(toolbarDataProvider, tabController, menuButtonCoordinator, historyDelegate,
                partnerHomepageEnabledSupplier, offlineDownloader);

        BraveMenuButtonCoordinator.setMenuFromBottom(false);
        // BraveMenuButtonCoordinator.setMenuFromBottom(isMenuButtonOnBottom());
    }

    public void updateWalletBadgeVisibility(boolean visible) {
    }

    public void updateMenuButtonState() {
        BraveMenuButtonCoordinator.setMenuFromBottom(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (BraveReflectionUtil.EqualTypes(this.getClass(), CustomTabToolbar.class)
                || BraveReflectionUtil.EqualTypes(this.getClass(), ToolbarPhone.class)) {
            updateMenuButtonState();
            Tab tab = getToolbarDataProvider() != null ? getToolbarDataProvider().getTab() : null;
            if (tab != null && ((TabImpl) tab).getWebContents() != null) {
                updateBraveShieldsButtonState(tab);
            }
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean isLocationBarValid(LocationBarCoordinator locationBar) {
        return locationBar != null && locationBar.getPhoneCoordinator() != null
                && locationBar.getPhoneCoordinator().getViewForDrawing() != null;
    }

    @Override
    public void drawAnimationOverlay(ViewGroup toolbarButtonsContainer, Canvas canvas) {
    }

    @Override
    public void onEvent(int eventType, String playlistId) {
        if (eventType == PlaylistEvent.ITEM_ADDED) {
            showAddedToPlaylistSnackBar();
        }
    }

    @Override
    public void onMediaFilesUpdated(Url pageUrl, PlaylistItem[] items) {
        Tab currentTab = getToolbarDataProvider().getTab();
        if (currentTab == null || !pageUrl.url.equals(currentTab.getUrl().getSpec())) {
            return;
        }
        showPlaylistButton(items);
    }

    private JSONObject getDecodedToken(String accessToken){
        try{
            String[] split_string = accessToken.split("\\.");
            String base64EncodedHeader = split_string[0];
            String base64EncodedBody = split_string[1];
            String base64EncodedSignature = split_string[2];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
        
    }

    public String saveFavicon(Context context, String urlString) {
        try {
            URL fullUrl = new URL(urlString);
            String host = fullUrl.getHost();
            
            // List of potential favicon retrieval URLs
            List<String> faviconCandidates = generateFaviconCandidateUrls(fullUrl);
            
            for (String faviconUrlStr : faviconCandidates) {
                try {
                    URL faviconUrl = new URL(faviconUrlStr);
                    Bitmap favicon = downloadFavicon(faviconUrl);
                    
                    if (favicon != null) {
                        return saveFaviconBitmap(context, favicon, host);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Failed to download favicon from " + faviconUrlStr, e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Favicon download failed", e);
        }
        
        return null;
    }

    private List<String> generateFaviconCandidateUrls(URL fullUrl) {
        String baseUrl = fullUrl.getProtocol() + "://" + fullUrl.getHost();
        List<String> candidates = new ArrayList<>();
        
        // Standard favicon locations
        candidates.add(baseUrl + "/favicon.ico");
        candidates.add(baseUrl + "/apple-touch-icon.png");
        candidates.add(baseUrl + "/android-chrome-192x192.png");
        
        // More complex favicon URL
        String cleanHost = fullUrl.getHost().replaceAll("^www\\.", "");
        candidates.add(baseUrl + "/" + cleanHost + "-favicon.ico");
        
        return candidates;
    }

    private Bitmap downloadFavicon(URL faviconUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) faviconUrl.openConnection();
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestMethod("GET");
        
        // Check for successful response
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            return null;
        }
        
        // Check content type
        String contentType = connection.getContentType();
        if (contentType == null || 
            (!contentType.startsWith("image/") && 
             !contentType.contains("icon"))) {
            return null;
        }
        
        try (InputStream inputStream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(inputStream);
        }
    }

    private String saveFaviconBitmap(Context context, Bitmap favicon, String host) {
        try {
            // Generate unique filename using MD5 hash
            String fileName = generateUniqueFileName(host);
            
            // Get app-specific external files directory
            File faviconDir = new File(context.getExternalFilesDir(null), "favicons");
            if (!faviconDir.exists()) {
                faviconDir.mkdirs();
            }
            
            File faviconFile = new File(faviconDir, fileName);
            
            try (FileOutputStream out = new FileOutputStream(faviconFile)) {
                // Compress to PNG to ensure compatibility
                favicon.compress(Bitmap.CompressFormat.PNG, 100, out);
                return faviconFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving favicon", e);
            return null;
        }
    }

    private String generateUniqueFileName(String host) {
        try {
            // Use MD5 hash to create a unique, deterministic filename
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(host.getBytes());
            
            // Convert to hex
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString() + ".png";
        } catch (NoSuchAlgorithmException e) {
            // Fallback to timestamp-based filename
            return host.replaceAll("[^a-zA-Z0-9]", "_") + 
                   System.currentTimeMillis() + ".png";
        }
    }

    public boolean isValidUrl(String urlString) {
        try {
            // Attempt to create a URL object. If it fails, it's not a valid URL.
            new URL(urlString);

            // Check if the protocol is one we consider valid (http or https).
            if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
                return false; // Not http or https, invalid for our purpose.
            }
            return true; // If no exceptions and http/https, it's valid.

        } catch (MalformedURLException e) {
            return false; // URL is malformed.
        }
    }

    private String getWebsiteName(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "Unknown Website"; // Handle empty input.
        }

        try {
            URL url = new URL(urlString);
            String host = url.getHost();

            // Handle IP addresses.
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return host;
            }

            // Remove "www." prefix (if present).
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // Split the host by dots.
            String[] parts = host.split("\\.");

            // Return the second-to-last part as the website name.
            if (parts.length >= 2) {
                return parts[parts.length - 2];
            } else {
                return host; // Return the entire host if it's simple.
            }

        } catch (MalformedURLException e) {
            // Default Fallback
            String cleanUrl = urlString;
            if (cleanUrl.startsWith("https://")) {
                cleanUrl = cleanUrl.substring(8);
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl = cleanUrl.substring(7);
            }

            if (cleanUrl.startsWith("www.")) {
                cleanUrl = cleanUrl.substring(4);
            }

            try{
                URL url = new URL("https://" + cleanUrl);
                return url.getHost();
            } catch (MalformedURLException e2){
                return cleanUrl;
            }

        }
    }

    private BrowserExpressGetFirstCommentsUtil.GetFirstCommentsCallback getFirstCommentsCallback=
        new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsCallback() {
            @Override
            public void getFirstCommentsSuccessful(JSONArray comments, int commentCount) {
               
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();

                    mCommentsText = activity.getCommentCountText();
                    mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));

                    activity.setFirstComments(comments.toString());
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "BookmarkButton click " + e);
                }
            }

            @Override
            public void getFirstCommentsFailed(String error) {
                Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
            }
        };

    private BrowserExpressGetProfilePreferencesUtil.GetProfileCallback getProfileCallback =
            new BrowserExpressGetProfilePreferencesUtil.GetProfileCallback() {
                @Override
                public void getProfileSuccessful(String avatar, String xp, String lg, String lr) {
                    if (!isAttachedToWindow()) {
                        return;
                    }

                    if (getContext() == null) return; // Extra safety check

                    Context context = ContextUtils.getApplicationContext();
                    SharedPreferences sharedPref = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                    SharedPreferences.Editor editor = sharedPref.edit();

                    if(avatar != null && avatar.length() > 0){
                        editor.putString("avatar_url", avatar);
                        ImageLoader.downloadImage(avatar, Glide.with(getContext()), true, 5, mProfileButton, null);
                    }

                    if(xp != null && xp.length() > 0){
                        editor.putString("views", xp);
                    }else{
                        editor.putString("views", "-");
                    }

                    if(lg != null && lg.length() > 0){
                        editor.putString("likes_given", lg);
                    }else{
                        editor.putString("likes_given", "-");
                    }

                    if(lr != null && lr.length() > 0){
                        editor.putString("likes_received", lr);
                    }else{
                        editor.putString("likes_received", "-");
                    }

                    editor.apply();
                }

                @Override
                public void getProfileFailed(String error) {
                    Log.e("Express Browser LOGIN", "GET PROFILE FAILED");
                }

                private JSONObject getDecodedToken(String accessToken){
                    try{
                        String[] split_string = accessToken.split("\\.");
                        String base64EncodedHeader = split_string[0];
                        String base64EncodedBody = split_string[1];
                        String base64EncodedSignature = split_string[2];

                        byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
                        String decodedString = new String(data, "UTF-8");
                        JSONObject jsonObj = new JSONObject(decodedString.toString());
                        return jsonObj;
                    }catch(JSONException e){
                        Log.e("Express Browser Access Token", e.getMessage());
                        return null;
                    }catch(UnsupportedEncodingException e){
                        Log.e("Express Browser Access Token", e.getMessage());
                        return null;
                    }
                    
                }
            };
}
