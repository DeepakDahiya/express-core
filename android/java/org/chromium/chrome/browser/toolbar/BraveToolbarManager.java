/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar;

import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewStub;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Callback;
import org.chromium.base.CallbackController;
import org.chromium.base.ContextUtils;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ActivityTabProvider;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.ChromeActivity;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.bookmarks.TabBookmarker;
import org.chromium.chrome.browser.browser_controls.BottomControlsStacker;
import org.chromium.chrome.browser.browser_controls.BrowserControlsSizer;
import org.chromium.chrome.browser.browser_controls.BrowserStateBrowserControlsVisibilityDelegate;
import org.chromium.chrome.browser.browser_controls.TopControlsStacker;
import org.chromium.chrome.browser.compositor.CompositorViewHolder;
import org.chromium.chrome.browser.compositor.layouts.LayoutManagerImpl;
import org.chromium.chrome.browser.compositor.overlays.strip.StripLayoutHelperManager;
import org.chromium.chrome.browser.customtabs.FullScreenCustomTabActivity;
import org.chromium.chrome.browser.data_sharing.DataSharingTabManager;
import org.chromium.chrome.browser.ephemeraltab.EphemeralTabCoordinator;
import org.chromium.chrome.browser.findinpage.FindToolbarManager;
import org.chromium.chrome.browser.fullscreen.FullscreenManager;
import org.chromium.chrome.browser.homepage.HomepageManager;
import org.chromium.chrome.browser.layouts.LayoutStateProvider;
import org.chromium.chrome.browser.layouts.LayoutType;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.merchant_viewer.MerchantTrustSignalsCoordinator;
import org.chromium.chrome.browser.multiwindow.MultiInstanceManager;
import org.chromium.chrome.browser.ntp_customization.edge_to_edge.TopInsetCoordinator;
import org.chromium.chrome.browser.omnibox.LocationBar;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.readaloud.ReadAloudController;
import org.chromium.chrome.browser.share.ShareDelegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObscuringHandler;
import org.chromium.chrome.browser.tab_ui.TabContentManager;
import org.chromium.chrome.browser.tab_ui.TabModelDotInfo;
import org.chromium.chrome.browser.tabmodel.IncognitoStateProvider;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tasks.tab_management.TabGroupUiOneshotSupplier;
import org.chromium.chrome.browser.theme.BottomUiThemeColorProvider;
import org.chromium.chrome.browser.theme.ThemeColorProvider;
import org.chromium.chrome.browser.theme.TopUiThemeColorProvider;
import org.chromium.chrome.browser.toolbar.bottom.BottomControlsContentDelegate;
import org.chromium.chrome.browser.toolbar.bottom.BottomControlsCoordinator;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarConfiguration;
import org.chromium.chrome.browser.toolbar.bottom.BraveBottomControlsCoordinator;
import org.chromium.chrome.browser.toolbar.bottom.BraveScrollingBottomViewResourceFrameLayout;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.optional_button.ButtonDataProvider;
import org.chromium.chrome.browser.toolbar.top.ActionModeController;
import org.chromium.chrome.browser.toolbar.top.BottomTabSwitcherActionMenuCoordinator;
import org.chromium.chrome.browser.toolbar.top.BraveToolbarLayoutImpl;
import org.chromium.chrome.browser.toolbar.top.BraveTopToolbarCoordinator;
import org.chromium.chrome.browser.toolbar.top.ToolbarActionModeCallback;
import org.chromium.chrome.browser.toolbar.top.ToolbarControlContainer;
import org.chromium.chrome.browser.toolbar.top.ToolbarLayout;
import org.chromium.chrome.browser.toolbar.top.TopToolbarCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuDelegate;
import org.chromium.chrome.browser.ui.edge_to_edge.EdgeToEdgeController;
import org.chromium.chrome.browser.ui.system.StatusBarColorController;
import org.chromium.chrome.browser.undo_tab_close_snackbar.UndoBarThrottle;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.components.browser_ui.desktop_windowing.DesktopWindowStateManager;
import org.chromium.components.browser_ui.widget.scrim.ScrimManager;
import org.chromium.components.omnibox.action.OmniboxActionDelegate;
import org.chromium.misc_metrics.mojom.MiscAndroidMetrics;
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
import org.chromium.base.Log;

import java.util.List;

@SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
public class BraveToolbarManager extends ToolbarManager
        implements OnSharedPreferenceChangeListener {
    private static final String TAG = "BraveToolbarManager";
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";

    // To delete in bytecode, members from parent class will be used instead.
    private ObservableSupplierImpl<BottomControlsCoordinator> mBottomControlsCoordinatorSupplier;
    private CallbackController mCallbackController;
    private BottomControlsStacker mBottomControlsStacker;
    private FullscreenManager mFullscreenManager;
    private ActivityTabProvider mActivityTabProvider;
    private AppThemeColorProvider mAppThemeColorProvider;
    private ScrimManager mScrimManager;
    private MenuButtonCoordinator mMenuButtonCoordinator;
    private ToolbarTabControllerImpl mToolbarTabController;
    private LocationBar mLocationBar;
    private ActionModeController mActionModeController;
    private LocationBarModel mLocationBarModel;
    private TopToolbarCoordinator mToolbar;
    private ObservableSupplier<BookmarkModel> mBookmarkModelSupplier;
    private LayoutManagerImpl mLayoutManager;
    private ObservableSupplierImpl<Boolean> mOverlayPanelVisibilitySupplier;
    private TabModelSelector mTabModelSelector;
    private IncognitoStateProvider mIncognitoStateProvider;
    private BottomSheetController mBottomSheetController;
    private TabContentManager mTabContentManager;
    private TabCreatorManager mTabCreatorManager;
    private Supplier<ModalDialogManager> mModalDialogManagerSupplier;
    private TabObscuringHandler mTabObscuringHandler;
    private LayoutStateProvider mLayoutStateProvider;
    private ObservableSupplier<ReadAloudController> mReadAloudControllerSupplier;
    private TopUiThemeColorProvider mTopUiThemeColorProvider;
    private int mCurrentOrientation;
    private boolean mInitializedWithNative;
    private @Nullable TabGroupUiOneshotSupplier mTabGroupUiOneshotSupplier;
    private @Nullable UndoBarThrottle mUndoBarThrottle;

    // Own members.
    private boolean mIsBraveBottomControlsVisible;
    private final ObservableSupplier<Boolean> mOmniboxFocusStateSupplier;
    private final OneshotSupplier<LayoutStateProvider> mLayoutStateProviderSupplier;
    private final HomepageManager.HomepageStateListener mBraveHomepageStateListener;
    private final AppCompatActivity mActivity;
    private final WindowAndroid mWindowAndroid;
    private final CompositorViewHolder mCompositorViewHolder;
    private final Object mLock = new Object();
    private boolean mBottomControlsEnabled;
    private BraveScrollingBottomViewResourceFrameLayout mBottomControls;
    private final ObservableSupplier<EdgeToEdgeController> mEdgeToEdgeControllerSupplier;
    private final ObservableSupplier<Profile> mProfileSupplier;
    private final BrowserControlsSizer mBrowserControlsSizer;
    private final DataSharingTabManager mDataSharingTabManager;
    private final ObservableSupplier<TabModelSelector> mTabModelSelectorSupplier;
    private LayoutStateProvider.LayoutStateObserver mLayoutStateObserver;
    private Runnable mOpenGridTabSwitcherHandler;
    private final ObservableSupplier<TabBookmarker> mTabBookmarkerSupplier;
    private final Supplier<ShareDelegate> mShareDelegateSupplier;

    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;
    private boolean mIsCurrentPageNtpOrHome;
    private final ObservableSupplier<TabModelSelector> mPassedTabModelSelectorSupplier;
    private Callback<TabModelSelector> mTabModelSelectorSupplierObserver;
    private TabModelSelector mLocalTabModelSelector;

    private ImageButton mProfileButton;

    public BraveToolbarManager(
            AppCompatActivity activity,
            BottomControlsStacker bottomControlsStacker,
            BrowserControlsSizer controlsSizer,
            FullscreenManager fullscreenManager,
            ObservableSupplier<EdgeToEdgeController> edgeToEdgeControllerSupplier,
            ToolbarControlContainer controlContainer,
            CompositorViewHolder compositorViewHolder,
            Callback<Boolean> urlFocusChangedCallback,
            TopUiThemeColorProvider topUiThemeColorProvider,
            TabObscuringHandler tabObscuringHandler,
            ObservableSupplier<ShareDelegate> shareDelegateSupplier,
            List<ButtonDataProvider> buttonDataProviders,
            ActivityTabProvider tabProvider,
            ScrimManager scrimManager,
            ToolbarActionModeCallback toolbarActionModeCallback,
            FindToolbarManager findToolbarManager,
            ObservableSupplier<Profile> profileSupplier,
            ObservableSupplier<BookmarkModel> bookmarkModelSupplier,
            OneshotSupplier<LayoutStateProvider> layoutStateProviderSupplier,
            OneshotSupplier<AppMenuCoordinator> appMenuCoordinatorSupplier,
            boolean canShowUpdateBadge,
            @NonNull ObservableSupplier<TabModelSelector> tabModelSelectorSupplier,
            ObservableSupplier<Boolean> omniboxFocusStateSupplier,
            OneshotSupplier<Boolean> promoShownOneshotSupplier,
            WindowAndroid windowAndroid,
            Supplier<Boolean> isInOverviewModeSupplier,
            Supplier<ModalDialogManager> modalDialogManagerSupplier,
            StatusBarColorController statusBarColorController,
            AppMenuDelegate appMenuDelegate,
            ActivityLifecycleDispatcher activityLifecycleDispatcher,
            @NonNull BottomSheetController bottomSheetController,
            @NonNull DataSharingTabManager dataSharingTabManager,
            @NonNull TabContentManager tabContentManager,
            @NonNull TabCreatorManager tabCreatorManager,
            @NonNull
                    Supplier<MerchantTrustSignalsCoordinator>
                            merchantTrustSignalsCoordinatorSupplier,
            @NonNull OmniboxActionDelegate omniboxActionDelegate,
            Supplier<EphemeralTabCoordinator> ephemeralTabCoordinatorSupplier,
            boolean initializeWithIncognitoColors,
            @Nullable BackPressManager backPressManager,
            ObservableSupplier<ReadAloudController> readAloudControllerSupplier,
            @Nullable DesktopWindowStateManager desktopWindowStateManager,
            @Nullable MultiInstanceManager multiInstanceManager,
            @NonNull ObservableSupplier<TabBookmarker> tabBookmarkerSupplier,
            @Nullable MenuButtonCoordinator.VisibilityDelegate menuButtonVisibilityDelegate,
            TopControlsStacker topControlsStacker,
            ObservableSupplier<TopInsetCoordinator> topInsetCoordinatorSupplier,
            @Nullable ObservableSupplier<Boolean> xrSpaceModeObservableSupplier) {
        super(
                activity,
                bottomControlsStacker,
                controlsSizer,
                fullscreenManager,
                edgeToEdgeControllerSupplier,
                controlContainer,
                compositorViewHolder,
                urlFocusChangedCallback,
                topUiThemeColorProvider,
                tabObscuringHandler,
                shareDelegateSupplier,
                buttonDataProviders,
                tabProvider,
                scrimManager,
                toolbarActionModeCallback,
                findToolbarManager,
                profileSupplier,
                bookmarkModelSupplier,
                layoutStateProviderSupplier,
                appMenuCoordinatorSupplier,
                canShowUpdateBadge,
                tabModelSelectorSupplier,
                omniboxFocusStateSupplier,
                promoShownOneshotSupplier,
                windowAndroid,
                isInOverviewModeSupplier,
                modalDialogManagerSupplier,
                statusBarColorController,
                appMenuDelegate,
                activityLifecycleDispatcher,
                bottomSheetController,
                dataSharingTabManager,
                tabContentManager,
                tabCreatorManager,
                merchantTrustSignalsCoordinatorSupplier,
                omniboxActionDelegate,
                ephemeralTabCoordinatorSupplier,
                initializeWithIncognitoColors,
                backPressManager,
                readAloudControllerSupplier,
                desktopWindowStateManager,
                multiInstanceManager,
                tabBookmarkerSupplier,
                menuButtonVisibilityDelegate,
                topControlsStacker,
                topInsetCoordinatorSupplier,
                xrSpaceModeObservableSupplier);

        mPassedTabModelSelectorSupplier = tabModelSelectorSupplier;
        mOmniboxFocusStateSupplier = omniboxFocusStateSupplier;
        mLayoutStateProviderSupplier = layoutStateProviderSupplier;
        mActivity = activity;
        mWindowAndroid = windowAndroid;
        mCompositorViewHolder = compositorViewHolder;
        mEdgeToEdgeControllerSupplier = edgeToEdgeControllerSupplier;
        mProfileSupplier = profileSupplier;
        mBrowserControlsSizer = controlsSizer;
        mDataSharingTabManager = dataSharingTabManager;
        mTabModelSelectorSupplier = tabModelSelectorSupplier;
        mTabBookmarkerSupplier = tabBookmarkerSupplier;
        mShareDelegateSupplier = shareDelegateSupplier;

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
            updateBraveBottomControlsVisibility();
            mLayoutStateProviderSupplier.onAvailable(
                    mCallbackController.makeCancelable(this::setLayoutStateProvider));
        }

        mBraveHomepageStateListener =
                () -> {
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
                setBraveBottomControlsVisible(true);
                return;
            }
            GURL currentGurl = currentTab.getUrl();
            boolean isNtp = UrlUtilities.isNtpUrl(currentGurl.getSpec());
            Log.d(TAG, "BraveToolbarManager: isNtp = " + isNtp);

            mIsCurrentPageNtpOrHome = isNtp;
            setBraveBottomControlsVisible(!isNtp);
        }else{
            setBraveBottomControlsVisible(false);
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
                    setBraveBottomControlsVisible(true);
                    return;
                }
                GURL currentGurl = tab.getUrl();
                boolean isNtp = UrlUtilities.isNtpUrl(currentGurl.getSpec());

                mIsCurrentPageNtpOrHome = isNtp;
                setBraveBottomControlsVisible(!isNtp);
            }

            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                super.onPageLoadStarted(tab, url);
                // Prefer using GURL overload if UrlUtilities.isNtpUrl supports it
                boolean isNtp = UrlUtilities.isNtpUrl(url.getSpec());
                mIsCurrentPageNtpOrHome = isNtp;
                setBraveBottomControlsVisible(!isNtp);
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
                    setBraveBottomControlsVisible(true);
                    return;
                }
                GURL currentGurl = currentTab.getUrl();
                boolean isNtp = UrlUtilities.isNtpUrl(currentGurl.getSpec());

                mIsCurrentPageNtpOrHome = isNtp;
                setBraveBottomControlsVisible(!isNtp);
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
            if (!BottomToolbarConfiguration.isBraveBottomControlsEnabled()) {
                super.enableBottomControls();
                return;
            }
            ViewStub bottomControlsStub =
                    (ViewStub) mActivity.findViewById(R.id.bottom_controls_stub);
            mBottomControls =
                    (BraveScrollingBottomViewResourceFrameLayout) bottomControlsStub.inflate();

            TabModelSelector currentSelector = mLocalTabModelSelector != null ? mLocalTabModelSelector : mPassedTabModelSelectorSupplier.get();

            ThemeColorProvider bottomUiThemeColorProvider =
                    new BottomUiThemeColorProvider(
                            mTopUiThemeColorProvider,
                            mBrowserControlsSizer,
                            mBottomControlsStacker,
                            mIncognitoStateProvider,
                            mActivity);

            mTabGroupUiOneshotSupplier =
                    new TabGroupUiOneshotSupplier(
                            mActivityTabProvider,
                            mTabModelSelector,
                            mActivity,
                            mBottomControls.findViewById(R.id.bottom_container_slot),
                            mBrowserControlsSizer,
                            mScrimManager,
                            mOmniboxFocusStateSupplier,
                            mBottomSheetController,
                            mDataSharingTabManager,
                            mTabContentManager,
                            mTabCreatorManager,
                            mLayoutStateProviderSupplier,
                            mModalDialogManagerSupplier.get(),
                            bottomUiThemeColorProvider,
                            mUndoBarThrottle,
                            mTabBookmarkerSupplier,
                            mShareDelegateSupplier);
            var bottomControlsContentDelegateSupplier =
                    (OneshotSupplier<BottomControlsContentDelegate>)
                            ((OneshotSupplier<? extends BottomControlsContentDelegate>)
                                    mTabGroupUiOneshotSupplier);

            BrowserStateBrowserControlsVisibilityDelegate controlsVisibilityDelegate =
                    mBrowserControlsSizer.getBrowserVisibilityDelegate();
            assert controlsVisibilityDelegate != null;
            var bottomControlsCoordinator =
                    new BraveBottomControlsCoordinator(
                            mLayoutStateProviderSupplier,
                            BottomTabSwitcherActionMenuCoordinator.createOnLongClickListener(
                                    id ->
                                            ((ChromeActivity) mActivity)
                                                    .onOptionsItemSelected(id, null),
                                    mProfileSupplier.get(),
                                    mTabModelSelectorSupplier),
                            mActivityTabProvider,
                            mToolbarTabController::openHomepage,
                            mCallbackController.makeCancelable(
                                    (reason) -> setUrlBarFocus(true, reason)),
                            mMenuButtonCoordinator.getMenuButtonHelperSupplier(),
                            mAppThemeColorProvider,
                            mBookmarkModelSupplier,
                            mLocationBarModel,
                            /* Below are parameters for BottomControlsCoordinator */
                            mWindowAndroid,
                            mLayoutManager,
                            mCompositorViewHolder.getResourceManager(),
                            mBottomControlsStacker,
                            controlsVisibilityDelegate,
                            mFullscreenManager,
                            mEdgeToEdgeControllerSupplier,
                            mBottomControls,
                            bottomControlsContentDelegateSupplier,
                            mTabObscuringHandler,
                            mOverlayPanelVisibilitySupplier,
                            getConstraintsProxy(),
                            /* readAloudRestoringSupplier= */ () -> {
                                final var readAloud = mReadAloudControllerSupplier.get();
                                return readAloud != null && readAloud.isRestoringPlayer();
                            });
            if (mInitializedWithNative) {
                Runnable closeAllTabsAction =
                        () -> {
                            mTabModelSelector
                                    .getModel(mIncognitoStateProvider.isIncognitoSelected())
                                    .getTabRemover()
                                    .closeTabs(TabClosureParams.closeAllTabs().build(), false);
                        };

                assert (mActivity instanceof ChromeActivity);
                OnClickListener wrappedNewTabClickHandler =
                        v -> {
                            recordNewTabClick();
                            ((ChromeActivity) mActivity)
                                    .getMenuOrKeyboardActionController()
                                    .onMenuOrKeyboardAction(
                                            mIncognitoStateProvider.isIncognitoSelected()
                                                    ? R.id.new_incognito_tab_menu_id
                                                    : R.id.new_tab_menu_id,
                                            false);
                        };

                bottomControlsCoordinator.initializeWithNative(
                        mActivity,
                        mCompositorViewHolder.getResourceManager(),
                        mCompositorViewHolder.getLayoutManager(),
                        /*tabSwitcherListener*/ v -> mOpenGridTabSwitcherHandler.run(),
                        /*newTabClickListener*/ wrappedNewTabClickHandler,
                        mWindowAndroid,
                        mTabModelSelector,
                        mIncognitoStateProvider,
                        mActivity.findViewById(R.id.control_container),
                        closeAllTabsAction);
            }
            assert mBottomControlsCoordinatorSupplier != null
                    : "It must not be null at this point! Something has changed in the upstream!";
            mBottomControlsCoordinatorSupplier.set(bottomControlsCoordinator);
            mBottomControls.setBottomControlsCoordinatorSupplier(
                    mBottomControlsCoordinatorSupplier);
            updateBraveBottomControlsVisibility();
            if (mIsBraveBottomControlsVisible) {
                mBottomControls.setVisibility(View.VISIBLE);
            }
        }
    }

    // The 3rd parameter at ToolbarManager.initializeWithNativ is
    // OnClickListener newTabClickHandler, but at
    // ChromeTabbedActivity.initializeToolbarManager
    // `v -> onTabSwitcherClicked()` is passed.
    // Also ToolbarManager.initializeWithNative calls
    // TopToolbarCoordinator.initializeWithNative where 3rd parameter is
    // `OnClickListener tabSwitcherClickHandler`. So it is a tabSwitcherClickHandler.
    //
    // Suppress to observe SharedPreferences, which is discouraged; use another messaging channel
    // instead.
    @SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
    @Override
    public void initializeWithNative(
            @NonNull LayoutManagerImpl layoutManager,
            @Nullable StripLayoutHelperManager stripLayoutHelperManager,
            Runnable openGridTabSwitcherHandler,
            OnClickListener bookmarkClickHandler,
            OnClickListener customTabsBackClickHandler,
            @Nullable ObservableSupplier<Integer> archivedTabCountSupplier,
            ObservableSupplier<TabModelDotInfo> tabModelNotificationDotSupplier,
            @Nullable UndoBarThrottle undoBarThrottle) {

        super.initializeWithNative(
                layoutManager,
                stripLayoutHelperManager,
                openGridTabSwitcherHandler,
                bookmarkClickHandler,
                customTabsBackClickHandler,
                archivedTabCountSupplier,
                tabModelNotificationDotSupplier,
                undoBarThrottle);

        mOpenGridTabSwitcherHandler = openGridTabSwitcherHandler;

        if (isToolbarPhone() && BottomToolbarConfiguration.isBraveBottomControlsEnabled()) {
            mLocationBar.getContainerView().setAccessibilityTraversalBefore(R.id.bottom_toolbar);
            ContextUtils.getAppSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        ToolbarLayout toolbarLayout = mActivity.findViewById(R.id.toolbar);
        assert toolbarLayout instanceof BraveToolbarLayoutImpl
                : "Something has changed in the upstream!";
        if (toolbarLayout instanceof BraveToolbarLayoutImpl) {
            final BraveToolbarLayoutImpl braveToolbarLayout =
                    (BraveToolbarLayoutImpl) toolbarLayout;
            braveToolbarLayout.setTabModelSelector(mTabModelSelectorSupplier.get());
        }
    }

    @Override
    public @Nullable View getMenuButtonView() {
        if (mMenuButtonCoordinator.getMenuButton() == null) {
            // Return fake view instead of null to avoid NullPointerException as some code within
            // Chromium doesn't check for null.
            return new View(mActivity);
        }
        return super.getMenuButtonView();
    }

    @Override
    public void destroy() {
        if (mLayoutStateProvider != null && mLayoutStateObserver != null) {
            mLayoutStateProvider.removeObserver(mLayoutStateObserver);
            mLayoutStateObserver = null;
        }
        super.destroy();
        HomepageManager.getInstance().removeListener(mBraveHomepageStateListener);
    }

    private void recordNewTabClick() {
        if (!mIncognitoStateProvider.isIncognitoSelected()) {
            if (mActivity instanceof BraveActivity) {
                MiscAndroidMetrics miscAndroidMetrics =
                        ((BraveActivity) mActivity).getMiscAndroidMetrics();
                if (miscAndroidMetrics != null) {
                    miscAndroidMetrics.recordTabSwitcherNewTab();
                }
            }
        }
    }

    protected void onOrientationChange() {
        if (mActionModeController != null) mActionModeController.showControlsOnOrientationChange();

        if (mBottomControlsCoordinatorSupplier != null
                && mBottomControlsCoordinatorSupplier.get() != null
                && BottomToolbarConfiguration.isBraveBottomControlsEnabled()) {
            boolean isBraveBottomControlsVisible =
                    mCurrentOrientation != Configuration.ORIENTATION_LANDSCAPE;
            setBraveBottomControlsVisible(isBraveBottomControlsVisible);
        }

        if (mActivity instanceof FullScreenCustomTabActivity) {
            // When rewards page is shown on rotated screen we don't care about
            // the toolbar.
            return;
        }

        if (mActivity instanceof BraveActivity) {
            ((BraveActivity) mActivity).updateBottomSheetPosition(mCurrentOrientation);
        }
    }

    protected void updateBookmarkButtonStatus() {
        if (mBookmarkModelSupplier == null) return;
        Tab currentTab = mLocationBarModel.getTab();
        BookmarkModel bridge = mBookmarkModelSupplier.get();
        boolean isBookmarked =
                currentTab != null && bridge != null && bridge.hasBookmarkIdForTab(currentTab);
        boolean editingAllowed =
                currentTab == null || bridge == null || bridge.isEditBookmarksEnabled();
        mToolbar.updateBookmarkButton(isBookmarked, editingAllowed);

        if (mBottomControlsCoordinatorSupplier != null
                && mBottomControlsCoordinatorSupplier.get()
                        instanceof BraveBottomControlsCoordinator) {
            ((BraveBottomControlsCoordinator) mBottomControlsCoordinatorSupplier.get())
                    .updateBookmarkButton(isBookmarked, editingAllowed);
        }
    }

    protected void updateReloadState(boolean tabCrashed) {
        assert false;
    }

    private void setBraveBottomControlsVisible(boolean visible) {
        mIsBraveBottomControlsVisible = visible;

        Tab currentTab = mLocationBarModel.getTab();
        if (currentTab != null) {
            Log.d(TAG, "BraveToolbarManager: currentTab URL = " + currentTab.getUrl());
            if (currentTab == null) {
                mIsCurrentPageNtpOrHome = false;
                setBraveBottomControlsVisible(true);
                return;
            }
            GURL currentGurl = currentTab.getUrl();
            boolean isNtp = UrlUtilities.isNtpUrl(currentGurl.getSpec());
            Log.d(TAG, "BraveToolbarManager: isNtp = " + isNtp);

            mIsCurrentPageNtpOrHome = isNtp;
            mIsBraveBottomControlsVisible = !isNtp;
            visible = !isNtp;
        }else{
            mIsBraveBottomControlsVisible = false;
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

        boolean isMenuFromBottom =
                (mIsBraveBottomControlsVisible
                                && BottomToolbarConfiguration.isBraveBottomControlsEnabled())
                        || BottomToolbarConfiguration.isToolbarBottomAnchored();
        BraveMenuButtonCoordinator.setMenuFromBottom(isMenuFromBottom);

        if (mToolbar instanceof BraveTopToolbarCoordinator) {
            ((BraveTopToolbarCoordinator) mToolbar).onBottomControlsVisibilityChanged(visible);
        }
        if (mBottomControlsCoordinatorSupplier != null
                && mBottomControlsCoordinatorSupplier.get()
                        instanceof BraveBottomControlsCoordinator) {
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
        if (accessToken == null || accessToken.isEmpty()) return null;
        try{
            String[] split_string = accessToken.split("\\.");
            if (split_string.length < 2) {
                Log.e(TAG, "Invalid JWT format - token does not have header.payload.signature structure");
                return null;
            }
            String base64EncodedBody = split_string[1];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e(TAG, "JSON parsing error: " + e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e(TAG, "Encoding error: " + e.getMessage());
            return null;
        }catch(Exception e){
            Log.e(TAG, "Unexpected error decoding token: " + e.getMessage());
            return null;
        }
        
    }

    private final BrowserExpressGetProfilePreferencesUtil.GetProfileCallback getProfileCallback =
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

    private void updateBraveBottomControlsVisibility() {
        boolean isBraveBottomControlsVisible =
                BottomToolbarConfiguration.isBraveBottomControlsEnabled()
                        && mActivity.getResources().getConfiguration().orientation
                                != Configuration.ORIENTATION_LANDSCAPE;
        setBraveBottomControlsVisible(isBraveBottomControlsVisible);
    }

    private boolean isToolbarPhone() {
        assert (mToolbar instanceof BraveTopToolbarCoordinator);
        return mToolbar instanceof BraveTopToolbarCoordinator
                && ((BraveTopToolbarCoordinator) mToolbar).isToolbarPhone();
    }

    private ObservableSupplier<Integer> getConstraintsProxy() {
        if (mToolbar instanceof BraveTopToolbarCoordinator) {
            return ((BraveTopToolbarCoordinator) mToolbar).getConstraintsProxy();
        }

        assert false : "Wrong top toolbar type!";
        return null;
    }

    @Override
    public LocationBar getLocationBar() {
        return mLocationBar;
    }

    @Override
    public void onSharedPreferenceChanged(
            SharedPreferences sharedPreferences, @Nullable String key) {
        if (ChromePreferenceKeys.TOOLBAR_TOP_ANCHORED.equals(key)) {
            if (sharedPreferences.getBoolean(
                    BravePreferenceKeys.BRAVE_BOTTOM_TOOLBAR_ENABLED_KEY, true)) {
                updateBraveBottomControlsVisibility();
            }
        }
    }

    private void setLayoutStateProvider(LayoutStateProvider layoutStateProvider) {
        assert mLayoutStateObserver == null : "mLayoutStateObserver should be set only once";

        mLayoutStateObserver =
                new LayoutStateProvider.LayoutStateObserver() {
                    @Override
                    public void onStartedShowing(@LayoutType int layoutType) {
                        if (layoutType == LayoutType.TAB_SWITCHER
                                && BottomToolbarConfiguration.isToolbarBottomAnchored()) {
                            BraveMenuButtonCoordinator.setMenuFromBottom(false);
                        }
                    }

                    @Override
                    public void onStartedHiding(@LayoutType int layoutType) {
                        if (layoutType == LayoutType.TAB_SWITCHER
                                && BottomToolbarConfiguration.isToolbarBottomAnchored()) {
                            BraveMenuButtonCoordinator.setMenuFromBottom(true);
                        }
                    }
                };
        layoutStateProvider.addObserver(mLayoutStateObserver);
    }

    public void openHomepage() {
        if (mToolbarTabController == null) return;

        mToolbarTabController.openHomepage();
    }
}
