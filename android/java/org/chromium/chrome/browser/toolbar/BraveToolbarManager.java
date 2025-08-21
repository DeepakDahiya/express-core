/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar;

import android.content.res.Configuration;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewStub;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.chromium.base.Callback;
import org.chromium.base.CallbackController;
import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ActivityTabProvider;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.ChromeActivity;
import org.chromium.chrome.browser.app.tab_activity_glue.TabReparentingController;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.browser_controls.BrowserControlsSizer;
import org.chromium.chrome.browser.compositor.CompositorViewHolder;
import org.chromium.chrome.browser.compositor.bottombar.ephemeraltab.EphemeralTabCoordinator;
import org.chromium.chrome.browser.compositor.layouts.LayoutManagerImpl;
import org.chromium.chrome.browser.compositor.layouts.content.TabContentManager;
import org.chromium.chrome.browser.findinpage.FindToolbarManager;
import org.chromium.chrome.browser.fullscreen.FullscreenManager;
import org.chromium.chrome.browser.homepage.HomepageManager;
import org.chromium.chrome.browser.identity_disc.IdentityDiscController;
import org.chromium.chrome.browser.layouts.LayoutStateProvider;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.merchant_viewer.MerchantTrustSignalsCoordinator;
import org.chromium.chrome.browser.omnibox.LocationBar;
import org.chromium.chrome.browser.omnibox.suggestions.history_clusters.HistoryClustersProcessor.OpenHistoryClustersDelegate;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.share.ShareDelegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObscuringHandler;
import org.chromium.chrome.browser.tabmodel.IncognitoStateProvider;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tasks.tab_management.TabGroupUi;
import org.chromium.chrome.browser.tasks.tab_management.TabManagementDelegateProvider;
import org.chromium.chrome.browser.theme.TopUiThemeColorProvider;
import org.chromium.chrome.browser.toolbar.bottom.BottomControlsCoordinator;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarConfiguration;
import org.chromium.chrome.browser.toolbar.bottom.BraveBottomControlsCoordinator;
import org.chromium.chrome.browser.toolbar.bottom.BraveScrollingBottomViewResourceFrameLayout;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.top.ActionModeController;
import org.chromium.chrome.browser.toolbar.top.BottomTabSwitcherActionMenuCoordinator;
import org.chromium.chrome.browser.toolbar.top.BraveTopToolbarCoordinator;
import org.chromium.chrome.browser.toolbar.top.ToolbarActionModeCallback;
import org.chromium.chrome.browser.toolbar.top.ToolbarControlContainer;
import org.chromium.chrome.browser.toolbar.top.TopToolbarCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuDelegate;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
import org.chromium.chrome.browser.ui.system.StatusBarColorController;
import org.chromium.chrome.features.start_surface.StartSurface;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.components.browser_ui.widget.scrim.ScrimCoordinator;
import org.chromium.components.omnibox.action.OmniboxActionDelegate;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.url.GURL;
import android.content.SharedPreferences;
import org.chromium.chrome.browser.settings.BrowserExpressGetProfilePreferencesUtil;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import org.json.JSONException;
import org.json.JSONObject;
import android.widget.ImageButton;
import android.content.Context;
import org.chromium.base.ContextUtils;
import com.bumptech.glide.Glide;
import org.chromium.base.task.AsyncTask;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import java.util.List;

public class BraveToolbarManager extends ToolbarManager {
    private static final String TAG = "BraveToolbarManager";
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";

    private ObservableSupplierImpl<BottomControlsCoordinator> mBottomControlsCoordinatorSupplier;
    private CallbackController mCallbackController;
    private BrowserControlsSizer mBrowserControlsSizer;
    private FullscreenManager mFullscreenManager;
    private ActivityTabProvider mActivityTabProvider;
    private AppThemeColorProvider mAppThemeColorProvider;
    private ScrimCoordinator mScrimCoordinator;
    private Supplier<Boolean> mShowStartSurfaceSupplier;
    private MenuButtonCoordinator mMenuButtonCoordinator;
    private ToolbarTabControllerImpl mToolbarTabController;
    private LocationBar mLocationBar;
    private ActionModeController mActionModeController;
    private LocationBarModel mLocationBarModel;
    private TopToolbarCoordinator mToolbar;
    private ObservableSupplier<BookmarkModel> mBookmarkModelSupplier;
    private LayoutManagerImpl mLayoutManager;
    private ObservableSupplierImpl<Boolean> mOverlayPanelVisibilitySupplier;
    private IncognitoStateProvider mIncognitoStateProvider;
    private TabCountProvider mTabCountProvider;
    private TabGroupUi mTabGroupUi;
    private BottomSheetController mBottomSheetController;
    private ActivityLifecycleDispatcher mActivityLifecycleDispatcher;
    private Supplier<Boolean> mIsWarmOnResumeSupplier;
    private TabContentManager mTabContentManager;
    private TabCreatorManager mTabCreatorManager;
    private SnackbarManager mSnackbarManager;
    private TabObscuringHandler mTabObscuringHandler;
    private LayoutStateProvider.LayoutStateObserver mLayoutStateObserver;
    private LayoutStateProvider mLayoutStateProvider;

    private boolean mIsBottomToolbarVisible;
    private ObservableSupplier<Boolean> mOmniboxFocusStateSupplier;
    private OneshotSupplier<LayoutStateProvider> mLayoutStateProviderSupplier;
    private HomepageManager.HomepageStateListener mBraveHomepageStateListener;
    private AppCompatActivity mActivity;
    private WindowAndroid mWindowAndroid;
    private CompositorViewHolder mCompositorViewHolder;
    private final Object mLock = new Object();
    private boolean mBottomControlsEnabled;
    private BraveScrollingBottomViewResourceFrameLayout mBottomControls;

    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;
    private boolean mIsCurrentPageNtpOrHome = false;
    private final ObservableSupplier<TabModelSelector> mPassedTabModelSelectorSupplier;
    private Callback<TabModelSelector> mTabModelSelectorSupplierObserver;
    private TabModelSelector mLocalTabModelSelector;

    private ImageButton mProfileButton;

    public BraveToolbarManager(AppCompatActivity activity, BrowserControlsSizer controlsSizer,
            FullscreenManager fullscreenManager, ToolbarControlContainer controlContainer,
            CompositorViewHolder compositorViewHolder, Callback<Boolean> urlFocusChangedCallback,
            TopUiThemeColorProvider topUiThemeColorProvider,
            TabObscuringHandler tabObscuringHandler,
            ObservableSupplier<ShareDelegate> shareDelegateSupplier,
            IdentityDiscController identityDiscController,
            List<ButtonDataProvider> buttonDataProviders, ActivityTabProvider tabProvider,
            ScrimCoordinator scrimCoordinator, ToolbarActionModeCallback toolbarActionModeCallback,
            FindToolbarManager findToolbarManager, ObservableSupplier<Profile> profileSupplier,
            ObservableSupplier<BookmarkModel> bookmarkModelSupplier,
            @Nullable Supplier<Boolean> canAnimateNativeBrowserControls,
            OneshotSupplier<LayoutStateProvider> layoutStateProviderSupplier,
            OneshotSupplier<AppMenuCoordinator> appMenuCoordinatorSupplier,
            boolean shouldShowUpdateBadge,
            ObservableSupplier<TabModelSelector> tabModelSelectorSupplier,
            OneshotSupplier<StartSurface> startSurfaceSupplier,
            ObservableSupplier<Boolean> omniboxFocusStateSupplier,
            OneshotSupplier<Boolean> promoShownOneshotSupplier, WindowAndroid windowAndroid,
            Supplier<Boolean> isInOverviewModeSupplier,
            Supplier<ModalDialogManager> modalDialogManagerSupplier,
            StatusBarColorController statusBarColorController, AppMenuDelegate appMenuDelegate,
            ActivityLifecycleDispatcher activityLifecycleDispatcher,
            @NonNull Supplier<Tab> startSurfaceParentTabSupplier,
            @NonNull BottomSheetController bottomSheetController,
            @NonNull Supplier<Boolean> isWarmOnResumeSupplier,
            @NonNull TabContentManager tabContentManager,
            @NonNull TabCreatorManager tabCreatorManager, @NonNull SnackbarManager snackbarManager,
            @NonNull Supplier<MerchantTrustSignalsCoordinator>
                    merchantTrustSignalsCoordinatorSupplier,
            OneshotSupplier<TabReparentingController> tabReparentingControllerSupplier,
            @NonNull OmniboxActionDelegate omniboxActionDelegate,
            Supplier<EphemeralTabCoordinator> ephemeralTabCoordinatorSupplier,
            boolean initializeWithIncognitoColors, @Nullable BackPressManager backPressManager,
            @NonNull OpenHistoryClustersDelegate openHistoryClustersDelegate) {
        super(activity, controlsSizer, fullscreenManager, controlContainer, compositorViewHolder,
                urlFocusChangedCallback, topUiThemeColorProvider, tabObscuringHandler,
                shareDelegateSupplier, identityDiscController, buttonDataProviders, tabProvider,
                scrimCoordinator, toolbarActionModeCallback, findToolbarManager, profileSupplier,
                bookmarkModelSupplier, canAnimateNativeBrowserControls, layoutStateProviderSupplier,
                appMenuCoordinatorSupplier, shouldShowUpdateBadge, tabModelSelectorSupplier,
                startSurfaceSupplier, omniboxFocusStateSupplier, promoShownOneshotSupplier,
                windowAndroid, isInOverviewModeSupplier, modalDialogManagerSupplier,
                statusBarColorController, appMenuDelegate, activityLifecycleDispatcher,
                startSurfaceParentTabSupplier, bottomSheetController, isWarmOnResumeSupplier,
                tabContentManager, tabCreatorManager, snackbarManager,
                merchantTrustSignalsCoordinatorSupplier, tabReparentingControllerSupplier,
                omniboxActionDelegate, ephemeralTabCoordinatorSupplier,
                initializeWithIncognitoColors, backPressManager, openHistoryClustersDelegate);

        mPassedTabModelSelectorSupplier = tabModelSelectorSupplier;
        mOmniboxFocusStateSupplier = omniboxFocusStateSupplier;
        mLayoutStateProviderSupplier = layoutStateProviderSupplier;
        mActivity = activity;
        mWindowAndroid = windowAndroid;
        mCompositorViewHolder = compositorViewHolder;

        mLocalTabModelSelector = mPassedTabModelSelectorSupplier.get();
        if (mLocalTabModelSelector != null) {
            initializeTabObserver(mLocalTabModelSelector);
        } else {
            mTabModelSelectorSupplierObserver = (selector) -> {
                if (selector != null) {
                    mLocalTabModelSelector = selector;
                    initializeTabObserver(selector);
                    if (mPassedTabModelSelectorSupplier != null && mTabModelSelectorSupplierObserver != null) {
                        mPassedTabModelSelectorSupplier.removeObserver(mTabModelSelectorSupplierObserver);
                         mTabModelSelectorSupplierObserver = null;
                    }
                }
            };
            mPassedTabModelSelectorSupplier.addObserver(mTabModelSelectorSupplierObserver);
        }


        if (isToolbarPhone()) {
            updateBottomToolbarVisibility();
        }

        mBraveHomepageStateListener = () -> {
            if (mBottomControlsCoordinatorSupplier != null
                    && mBottomControlsCoordinatorSupplier.get()
                                    instanceof BraveBottomControlsCoordinator) {
                ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                        .updateHomeButtonState();
            }
        };
        HomepageManager.getInstance().addListener(mBraveHomepageStateListener);

        Tab currentTab = ((BraveActivity) mActivity).getActivityTab();
        Log.d(TAG, "BraveToolbarManager: currentTab = " + currentTab);
        if (currentTab != null) {
            Log.d(TAG, "BraveToolbarManager: currentTab URL = " + currentTab.getUrl());
            if (currentTab == null) {
                mIsCurrentPageNtpOrHome = false;
                setBottomToolbarVisible(true);
                return;
            }
            GURL currentGurl = currentTab.getUrl();
            boolean isNtp = UrlUtilities.isNTPUrl(currentGurl) || currentTab.getUrl().getSpec().contains("youtube.com");
            Log.d(TAG, "BraveToolbarManager: isNtp = " + isNtp);

            mIsCurrentPageNtpOrHome = isNtp;
            setBottomToolbarVisible(!isNtp);
        }else{
            setBottomToolbarVisible(false);
        }
    }

    private void initializeTabObserver(TabModelSelector selector) {
        if (mTabModelSelectorTabObserver != null) {
            mTabModelSelectorTabObserver.destroy();
        }
        mTabModelSelectorTabObserver = new TabModelSelectorTabObserver(selector) {
            private void updateToolbarForTab(Tab tab) {
                if (tab == null) {
                    mIsCurrentPageNtpOrHome = false;
                    setBottomToolbarVisible(true);
                    return;
                }
                GURL currentGurl = tab.getUrl();
                boolean isNtp = UrlUtilities.isNTPUrl(currentGurl) || tab.getUrl().getSpec().contains("youtube.com");

                mIsCurrentPageNtpOrHome = isNtp;
                setBottomToolbarVisible(!isNtp);
            }

            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                super.onPageLoadStarted(tab, url);
                // Prefer using GURL overload if UrlUtilities.isNTPUrl supports it
                boolean isNtp = UrlUtilities.isNTPUrl(url) || url.getSpec().contains("youtube.com");
                mIsCurrentPageNtpOrHome = isNtp;
                setBottomToolbarVisible(!isNtp);
            }

            @Override
            public void onUrlUpdated(Tab tab) {
                super.onUrlUpdated(tab);
                updateToolbarForTab(tab);
            }
        };

        if (selector != null) {
            Tab currentTab = selector.getCurrentTab();
            if (currentTab != null) {
                if (currentTab == null) {
                    mIsCurrentPageNtpOrHome = false;
                    setBottomToolbarVisible(true);
                    return;
                }
                GURL currentGurl = currentTab.getUrl();
                boolean isNtp = UrlUtilities.isNTPUrl(currentGurl) || currentTab.getUrl().getSpec().contains("youtube.com");

                mIsCurrentPageNtpOrHome = isNtp;
                setBottomToolbarVisible(!isNtp);
            }
        }
    }


    @Override
    public void enableBottomControls() {
        assert (mActivity instanceof ChromeActivity);
        synchronized (mLock) {
            if (mBottomControlsEnabled) {
                return;
            }
            mBottomControlsEnabled = true;
            if (!BottomToolbarConfiguration.isBottomToolbarEnabled()) {
                super.enableBottomControls();
                return;
            }
            ViewStub bottomControlsStub =
                    (ViewStub) mActivity.findViewById(R.id.bottom_controls_stub);
            mBottomControls =
                    (BraveScrollingBottomViewResourceFrameLayout) bottomControlsStub.inflate();

            TabModelSelector currentSelector = mLocalTabModelSelector != null ? mLocalTabModelSelector : mPassedTabModelSelectorSupplier.get();

            mTabGroupUi = TabManagementDelegateProvider.getDelegate().createTabGroupUi(mActivity,
                    mBottomControls.findViewById(R.id.bottom_container_slot), mBrowserControlsSizer,
                    mIncognitoStateProvider, mScrimCoordinator, mOmniboxFocusStateSupplier,
                    mBottomSheetController, mActivityLifecycleDispatcher, mIsWarmOnResumeSupplier,
                    currentSelector, mTabContentManager, mCompositorViewHolder,
                    mCompositorViewHolder::getDynamicResourceLoader, mTabCreatorManager,
                    mLayoutStateProviderSupplier, mSnackbarManager);
            mBottomControlsCoordinatorSupplier.set(new BraveBottomControlsCoordinator(
                    mLayoutStateProviderSupplier,
                    BottomTabSwitcherActionMenuCoordinator.createOnLongClickListener(
                            id -> ((ChromeActivity) mActivity).onOptionsItemSelected(id, null)),
                    mActivityTabProvider, mToolbarTabController::openHomepage,
                    mCallbackController.makeCancelable((reason) -> setUrlBarFocus(true, reason)),
                    mMenuButtonCoordinator.getMenuButtonHelperSupplier(), mAppThemeColorProvider,
                    mActivity, mWindowAndroid, mLayoutManager,
                    mCompositorViewHolder.getResourceManager(), mBrowserControlsSizer,
                    mFullscreenManager, mBottomControls, mTabGroupUi, mTabObscuringHandler,
                    mOverlayPanelVisibilitySupplier, getConstraintsProxy(), mBookmarkModelSupplier,
                    mLocationBarModel));
            mBottomControls.setBottomControlsCoordinatorSupplier(
                    mBottomControlsCoordinatorSupplier);
            updateBottomToolbarVisibility();
            if (mIsBottomToolbarVisible) {
                mBottomControls.setVisibility(View.VISIBLE);
            }
        }
    }


    @Override
    public void initializeWithNative(LayoutManagerImpl layoutManager,
            OnClickListener tabSwitcherClickHandler, OnClickListener newTabClickHandler,
            OnClickListener bookmarkClickHandler, OnClickListener customTabsBackClickHandler,
            Supplier<Boolean> showStartSurfaceSupplier) {
        super.initializeWithNative(layoutManager, tabSwitcherClickHandler, newTabClickHandler,
                bookmarkClickHandler, customTabsBackClickHandler, showStartSurfaceSupplier);

        TabModelSelector currentSelector = mLocalTabModelSelector != null ? mLocalTabModelSelector : mPassedTabModelSelectorSupplier.get();

        if (isToolbarPhone() && BottomToolbarConfiguration.isBottomToolbarEnabled()) {
            enableBottomControls();
            Runnable closeAllTabsAction = () -> {
                if (currentSelector != null) {
                    currentSelector.getModel(mIncognitoStateProvider.isIncognitoSelected())
                            .closeAllTabs();
                }
            };
            assert (mBottomControlsCoordinatorSupplier.get()
                            instanceof BraveBottomControlsCoordinator);
            ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                    .initializeWithNative(mActivity, mCompositorViewHolder.getResourceManager(),
                            mCompositorViewHolder.getLayoutManager(), tabSwitcherClickHandler,
                            newTabClickHandler, mWindowAndroid, mTabCountProvider,
                            mIncognitoStateProvider, mActivity.findViewById(R.id.control_container),
                            closeAllTabsAction);
            if (mLocationBar != null && mLocationBar.getContainerView() != null) {
                 mLocationBar.getContainerView().setAccessibilityTraversalBefore(R.id.bottom_toolbar);
            }
        }
    }

    @Override
    public @Nullable View getMenuButtonView() {
        if (mMenuButtonCoordinator != null && mMenuButtonCoordinator.getMenuButton() == null) {
            return new View(mActivity);
        }
        return super.getMenuButtonView();
    }

    @Override
    public void destroy() {
        if (mBraveHomepageStateListener != null && HomepageManager.getInstance() != null) {
            HomepageManager.getInstance().removeListener(mBraveHomepageStateListener);
            mBraveHomepageStateListener = null;
        }
        if (mLayoutStateProvider != null && mLayoutStateObserver != null) {
            mLayoutStateProvider.removeObserver(mLayoutStateObserver);
            mLayoutStateObserver = null;
        }
        if (mTabModelSelectorTabObserver != null) {
            mTabModelSelectorTabObserver.destroy();
            mTabModelSelectorTabObserver = null;
        }
        if (mPassedTabModelSelectorSupplier != null && mTabModelSelectorSupplierObserver != null) {
            mPassedTabModelSelectorSupplier.removeObserver(mTabModelSelectorSupplierObserver);
            mTabModelSelectorSupplierObserver = null;
        }
        super.destroy();
    }

    protected void onOrientationChange(int newOrientation) {
        if (mActionModeController != null) mActionModeController.showControlsOnOrientationChange();

        if (mBottomControlsCoordinatorSupplier != null && mBottomControlsCoordinatorSupplier.get() != null
                && BottomToolbarConfiguration.isBottomToolbarEnabled()) {
            boolean isBottomToolbarVisible = newOrientation != Configuration.ORIENTATION_LANDSCAPE;
            setBottomToolbarVisible(isBottomToolbarVisible);
        }

        if (mActivity instanceof BraveActivity) {
            ((BraveActivity) mActivity).updateBottomSheetPosition(newOrientation);
        }
    }

    protected void updateBookmarkButtonStatus() {
        if (mBookmarkModelSupplier == null || mLocationBarModel == null || mToolbar == null) return;
        Tab currentTab = mLocationBarModel.getTab();
        BookmarkModel bridge = mBookmarkModelSupplier.get();
        boolean isBookmarked =
                currentTab != null && bridge != null && bridge.hasBookmarkIdForTab(currentTab);
        boolean editingAllowed =
                currentTab == null || bridge == null || !currentTab.isNativePage() && bridge.isEditBookmarksEnabled();
        mToolbar.updateBookmarkButton(isBookmarked, editingAllowed);

        if (mBottomControlsCoordinatorSupplier != null && mBottomControlsCoordinatorSupplier.get() instanceof BraveBottomControlsCoordinator) {
            ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                    .updateBookmarkButton(isBookmarked, editingAllowed);
        }
    }

    protected void updateReloadState(boolean tabCrashed) {
    }

    private void setBottomToolbarVisible(boolean visible) {
        mIsBottomToolbarVisible = visible;

        Tab currentTab = mLocationBarModel.getTab();
        if (currentTab != null) {
            Log.d(TAG, "BraveToolbarManager: currentTab URL = " + currentTab.getUrl());
            if (currentTab == null) {
                mIsCurrentPageNtpOrHome = false;
                setBottomToolbarVisible(true);
                return;
            }
            GURL currentGurl = currentTab.getUrl();
            boolean isNtp = UrlUtilities.isNTPUrl(currentGurl) || currentTab.getUrl().getSpec().contains("youtube.com");
            Log.d(TAG, "BraveToolbarManager: isNtp = " + isNtp);

            mIsCurrentPageNtpOrHome = isNtp;
            mIsBottomToolbarVisible = !isNtp;
            visible = !isNtp;
        }else{
            mIsBottomToolbarVisible = false;
            visible = false;
        }

        if (visible) {
            if (mBottomControlsCoordinatorSupplier != null
                    && mBottomControlsCoordinatorSupplier.get()
                                    instanceof BraveBottomControlsCoordinator) {
                ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                        .updateHomeButtonState();
            }
        }

        if (mToolbar instanceof BraveTopToolbarCoordinator) {
            ((BraveTopToolbarCoordinator) mToolbar).onBottomToolbarVisibilityChanged(visible);
        }
        if (mBottomControls != null) {
            mBottomControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (mBottomControlsCoordinatorSupplier != null && mBottomControlsCoordinatorSupplier.get() instanceof BraveBottomControlsCoordinator) {
            ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                    .setBottomToolbarVisible(visible);
        }
    }

    private void fetchAndUpdateProfileImage() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null){
                return;
            }
            String accessToken = activity.getAccessToken();

            mProfileButton = activity.getProfileButton();

            if (accessToken != null && mProfileButton != null) {
                Context context = ContextUtils.getApplicationContext();
                SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                String avatar = prefs.getString("avatar_url", null);
                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                if (avatar != null) {
                    ImageLoader.downloadImage(avatar, Glide.with(activity), true, 5, mProfileButton, null);
                }else{
                    ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), true, 5, mProfileButton, null);
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

    private BrowserExpressGetProfilePreferencesUtil.GetProfileCallback getProfileCallback =
            new BrowserExpressGetProfilePreferencesUtil.GetProfileCallback() {
                @Override
                public void getProfileSuccessful(String avatar, String xp, String lg, String lr) {
                    Context context = ContextUtils.getApplicationContext();
                    SharedPreferences sharedPref = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                    SharedPreferences.Editor editor = sharedPref.edit();

                    if(avatar != null && avatar.length() > 0){
                        editor.putString("avatar_url", avatar);
                        ImageLoader.downloadImage(avatar, Glide.with(context), true, 5, mProfileButton, null);
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
            };

    private void updateBottomToolbarVisibility() {
        if (mActivity == null || mActivity.getResources() == null) return;
        boolean isBottomToolbarVisible = BottomToolbarConfiguration.isBottomToolbarEnabled()
                && mActivity.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE;
        setBottomToolbarVisible(isBottomToolbarVisible);
    }

    private boolean isToolbarPhone() {
        if (mToolbar == null) return false; // Guard against mToolbar being null
        return mToolbar instanceof BraveTopToolbarCoordinator
                && ((BraveTopToolbarCoordinator) mToolbar).isToolbarPhone();
    }

    private ObservableSupplier<Integer> getConstraintsProxy() {
        if (mToolbar instanceof BraveTopToolbarCoordinator) {
            return ((BraveTopToolbarCoordinator) mToolbar).getConstraintsProxy();
        }
        Log.w(TAG, "getConstraintsProxy called with unexpected toolbar type.");
        return null;
    }
}