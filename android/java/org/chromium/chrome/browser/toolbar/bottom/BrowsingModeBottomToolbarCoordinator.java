/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import org.chromium.base.task.AsyncTask;
import java.util.Locale;
import org.json.JSONArray;
import android.widget.TextView;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.Intent;
import android.net.Uri;
import org.chromium.content_public.browser.JavaScriptCallback;
import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.OneShotCallback;
import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ActivityTabProvider;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.omaha.UpdateMenuItemHelper;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.IncognitoStateProvider;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.theme.ThemeColorProvider;
import org.chromium.chrome.browser.toolbar.BraveHomeButton;
import org.chromium.chrome.browser.toolbar.TabSwitcherButtonCoordinator;
import org.chromium.chrome.browser.toolbar.TabSwitcherButtonView;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButton;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonState;
import org.chromium.chrome.browser.ui.appmenu.AppMenuButtonHelper;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.components.browser_ui.styles.ChromeColors;
import org.chromium.ui.modelutil.PropertyModelChangeProcessor;
import android.content.SharedPreferences;
import com.google.android.material.snackbar.Snackbar;
import android.widget.Button;
import android.widget.ImageButton;
import android.view.HapticFeedbackConstants;
import org.chromium.chrome.browser.BraveYouTubeScriptInjectorNativeHelper;
import org.chromium.chrome.browser.browser_express_comments.YouTubeCommentsUtil;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import android.util.DisplayMetrics;
import android.util.Base64;
import java.util.List;
import org.json.JSONObject;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
import org.chromium.chrome.browser.browser_controls.BrowserStateBrowserControlsVisibilityDelegate;
import org.chromium.chrome.browser.media.PictureInPicture;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.ui.util.TokenHolder;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.url.GURL;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;
import java.io.UnsupportedEncodingException;
import org.json.JSONException;
import android.util.Base64;
import org.json.JSONObject;
import java.util.ArrayList;

/**
 * The coordinator for the browsing mode bottom toolbar. This class has two primary components, an
 * Android view that handles user actions and a composited texture that draws when the controls are
 * being scrolled off-screen. The Android version does not draw unless the controls offset is 0.
 */
public class BrowsingModeBottomToolbarCoordinator {
    private static final String TAG = "BrowsingMode";

    private ImageButton mCommentsButton;
    private ImageButton mBeHomeButton;
    private TextView mBraveHomeText;
    private TextView mCommentsText;
    private View mCommentsCountArrow;
    private int w;
    private int h;

    private static final String PREF_PIP_INTRO_SHOWN_COUNT = "pip_intro_shown_count";
    private static final int MAX_PIP_INTRO_SHOW_COUNT = 2;
    private static final String PREF_PIP_BG_PLAY_NUDGE_COUNT = "pip_bg_play_nudge_count";
    private static final int MAX_BG_PLAY_NUDGE_COUNT = 3;
    private boolean mPipIntroShownThisRun;

    private ImageButton mYouTubePipButton;
    private View mYouTubePipContainer;
    private View mPipTrailingSpace;

    private View[] mStatsOverlays;
    private String mCurrentPreviewVideoId;
    private Tab mCurrentObservedTab;
    private TabObserver mPipTabObserver;
    private Callback<Tab> mTabProviderObserver;

    /** The mediator that handles events from outside the browsing mode bottom toolbar. */
    private final BrowsingModeBottomToolbarMediator mMediator;

    /** The home button that lives in the bottom toolbar. */
    private final BraveHomeButton mBraveHomeButton;

    /** The new tab button that lives in the bottom toolbar. */
    private final BottomToolbarNewTabButton mNewTabButton;

    /** The search accelerator that lives in the bottom toolbar. */
    private final SearchAccelerator mSearchAccelerator;

    /** The tab switcher button component that lives in the bottom toolbar. */
    private final TabSwitcherButtonCoordinator mTabSwitcherButtonCoordinator;

    /** The tab switcher button view that lives in the bottom toolbar. */
    private final TabSwitcherButtonView mTabSwitcherButtonView;

    /** The view group that includes all views shown on browsing mode */
    private final BrowsingModeBottomToolbarLinearLayout mToolbarRoot;

    /** The model for the browsing mode bottom toolbar that holds all of its state. */
    private final BrowsingModeBottomToolbarModel mModel;

    /** The callback to be exectured when the share button on click listener is available. */
    private Callback<OnClickListener> mShareButtonListenerSupplierCallback;

    /** The supplier for the share button on click listener. */
    private ObservableSupplier<OnClickListener> mShareButtonListenerSupplier;

    /** The activity tab provider that used for making the IPH. */
    private final ActivityTabProvider mTabProvider;

    private final BookmarksButton mBookmarkButton;
    private final MenuButton mMenuButton;
    private ThemeColorProvider mThemeColorProvider;
    private final BrowserStateBrowserControlsVisibilityDelegate mControlsVisibilityDelegate;
    private int mYouTubePersistentToken = TokenHolder.INVALID_TOKEN;

    BrowsingModeBottomToolbarCoordinator(
            View root,
            ActivityTabProvider tabProvider,
            OnClickListener homeButtonListener,
            OnClickListener searchAcceleratorListener,
            ObservableSupplier<OnClickListener> shareButtonListenerSupplier,
            OnLongClickListener tabSwitcherLongClickListener,
            BrowserStateBrowserControlsVisibilityDelegate controlsVisibilityDelegate) {
        mModel = new BrowsingModeBottomToolbarModel();
        mToolbarRoot = root.findViewById(R.id.bottom_toolbar_browsing);
        mTabProvider = tabProvider;
        mControlsVisibilityDelegate = controlsVisibilityDelegate;

        PropertyModelChangeProcessor.create(
                mModel, mToolbarRoot, new BrowsingModeBottomToolbarViewBinder());

        mMediator = new BrowsingModeBottomToolbarMediator(mModel);

        mBraveHomeButton = mToolbarRoot.findViewById(R.id.bottom_home_button);
        mBraveHomeText = mToolbarRoot.findViewById(R.id.bottom_home_text);
        mCommentsButton = mToolbarRoot.findViewById(R.id.comments_button);
        mCommentsText = mToolbarRoot.findViewById(R.id.comments_button1);
        mCommentsCountArrow = mToolbarRoot.findViewById(R.id.comments_count_arrow);
        mBeHomeButton = mToolbarRoot.findViewById(R.id.be_home_button);
        int commentCount = 0;
        mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
        mCommentsText.setTextSize(10);
        mCommentsText.setTextColor(ContextUtils.getApplicationContext().getColor(R.color.upvote_stroke_color));
        mBraveHomeText.setTextSize(10);
        mBraveHomeText.setTextColor(android.graphics.Color.WHITE);
        mBraveHomeButton.setOnClickListener(homeButtonListener);
        mBraveHomeText.setOnClickListener(homeButtonListener);
        mBeHomeButton.setOnClickListener(homeButtonListener);


        if (mCommentsButton != null) {
            mCommentsButton.setClickable(true);
            OnClickListener commentsClickHandler = v -> {
                mCommentsButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    String t = activity.getActivityTab().getUrl().getSpec();

                    String accessToken = activity.getAccessToken();
                    if (accessToken == null || accessToken.isEmpty()) {
                        activity.showCommentsBottomSheet();
                        return;
                    }
                    String[] split_string = accessToken.split("\\.");
                    if (split_string.length < 2) {
                        Log.e(TAG, "Invalid JWT format");
                        activity.showCommentsBottomSheet();
                        return;
                    }
                    String base64EncodedBody = split_string[1];

                    byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
                    String decodedString = new String(data, "UTF-8");
                    JSONObject decodedAccessTokenObj = new JSONObject(decodedString.toString());

                    String pInfo = activity.getCurrentAppVersion();
                    JSONObject payload = new JSONObject();
                    payload.put("app_version", pInfo);
                    payload.put("type", "page");
                    payload.put("url", t);

                    PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                        new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.BOTTOM_SHEET_CLICKED, decodedAccessTokenObj.getString("_id"), payload);
                    postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

                    activity.showCommentsBottomSheet();
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                } catch(JSONException e){
                }catch(UnsupportedEncodingException e){
                }catch(Exception e){
                    Log.e(TAG, "Error in commentsClickHandler: " + e.getMessage());
                }
            };
            mCommentsButton.setOnClickListener(commentsClickHandler);
            mCommentsText.setOnClickListener(commentsClickHandler);
            BraveTouchUtils.ensureMinTouchTarget(mCommentsButton);
             // SETTING HEIGHT AND WIDTH MATCHING COMMENT BUTTON
           
            mCommentsButton.post(new Runnable() {
                @Override
                public void run() {
                    w = mCommentsButton.getWidth();
                    h = mCommentsButton.getHeight();
                    mBraveHomeButton.getLayoutParams().height = h;
                    mBraveHomeButton.getLayoutParams().width = w;
                    mBraveHomeButton.requestLayout();
                }
            });
        }

        mNewTabButton = mToolbarRoot.findViewById(R.id.bottom_new_tab_button);

        mSearchAccelerator = mToolbarRoot.findViewById(R.id.search_accelerator);
        mSearchAccelerator.setOnClickListener(searchAcceleratorListener);
        BraveTouchUtils.ensureMinTouchTarget(mSearchAccelerator);

        // TODO(amaralp): Make this adhere to MVC framework.
        mTabSwitcherButtonView = mToolbarRoot.findViewById(R.id.bottom_tab_switcher_button);
        mTabSwitcherButtonCoordinator = new TabSwitcherButtonCoordinator(mTabSwitcherButtonView);

        mTabSwitcherButtonView.setOnLongClickListener(tabSwitcherLongClickListener);
        if (BottomToolbarVariationManager.isNewTabButtonOnBottomControls()) {
            mNewTabButton.setVisibility(View.VISIBLE);
        }
        if (BottomToolbarVariationManager.isHomeButtonOnBottomControls()) {
            // mBraveHomeButton.setVisibility(View.VISIBLE);
            mBraveHomeText.setVisibility(View.VISIBLE);
            mBeHomeButton.setVisibility(View.VISIBLE);
        }

        if (BottomToolbarVariationManager.isTabSwitcherOnBottomControls()) {
            // mTabSwitcherButtonView.setVisibility(View.VISIBLE);
        }

        mBookmarkButton = mToolbarRoot.findViewById(R.id.bottom_bookmark_button);
        if (BottomToolbarVariationManager.isBookmarkButtonOnBottomControls()) {
            // mBookmarkButton.setVisibility(View.VISIBLE);
            getNewTabButtonParent().setVisibility(View.GONE);
            OnClickListener bookmarkClickHandler =
                    v -> {
                        Tab tab = mTabProvider.get();
                        try {
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            if (tab == null || activity == null) {
                                assert false;
                                return;
                            }
                            activity.addOrEditBookmark(tab);
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e(TAG, "BookmarkButton click " + e);
                        }
                    };
            mBookmarkButton.setOnClickListener(bookmarkClickHandler);
        }

        mMenuButton = mToolbarRoot.findViewById(R.id.menu_button_wrapper);

        mYouTubePipButton = mToolbarRoot.findViewById(R.id.bottom_youtube_pip_button);
        mYouTubePipContainer = mToolbarRoot.findViewById(R.id.youtube_pip_button_container);
        mPipTrailingSpace = mToolbarRoot.findViewById(R.id.pip_trailing_space);


        if (mYouTubePipButton != null) {
            OnClickListener pipClickHandler = v -> {
                Tab tab = mTabProvider.get();
                if (tab == null || tab.getWebContents() == null) return;
                BraveYouTubeScriptInjectorNativeHelper.triggerYouTubePiP(tab.getWebContents());
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    activity.openNewOrSelectExistingTab("https://m.youtube.com/");

                    // Track PIP feature explored
                    firePostHogEvent(PostHogEventKeys.YT_FEATURE_EXPLORED_PIP, null);
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "openYouTubeHome: " + e.getMessage());
                }
                maybeShowBgPlayNudge();
            };
            mYouTubePipButton.setOnClickListener(pipClickHandler);
            mToolbarRoot.findViewById(R.id.bottom_youtube_pip_text).setOnClickListener(
                    pipClickHandler);
        }

        mPipTabObserver = new EmptyTabObserver() {
            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                if (mYouTubePipContainer != null) mYouTubePipContainer.setVisibility(View.GONE);
                if (mPipTrailingSpace != null) mPipTrailingSpace.setVisibility(View.GONE);
                updateYouTubeControlsLock(url != null ? url.getSpec() : "");
            }

            @Override
            public void onPageLoadFinished(Tab tab, GURL url) {
                updateYouTubePipButtonVisibility(tab);
                updateCommentCountForUrl(url.getSpec());
                updateYouTubeControlsLock(url.getSpec());
            }

            @Override
            public void onLoadStopped(Tab tab, boolean toDifferentDocument) {
                updateYouTubePipButtonVisibility(tab);
                if (tab.getUrl() != null && !tab.getUrl().isEmpty()) {
                    updateCommentCountForUrl(tab.getUrl().getSpec());
                    updateYouTubeControlsLock(tab.getUrl().getSpec());
                }
            }

            @Override
            public void onUrlUpdated(Tab tab) {
                updateYouTubePipButtonVisibility(tab);
                if (tab.getUrl() != null && !tab.getUrl().isEmpty()) {
                    updateCommentCountForUrl(tab.getUrl().getSpec());
                    updateYouTubeControlsLock(tab.getUrl().getSpec());
                }
            }
        };

        mTabProviderObserver = tab -> {
            if (mCurrentObservedTab != null) {
                mCurrentObservedTab.removeObserver(mPipTabObserver);
            }
            mCurrentObservedTab = tab;
            if (tab != null) {
                tab.addObserver(mPipTabObserver);
                updateYouTubePipButtonVisibility(tab);
                if (tab.getUrl() != null && !tab.getUrl().isEmpty()) {
                    updateCommentCountForUrl(tab.getUrl().getSpec());
                    updateYouTubeControlsLock(tab.getUrl().getSpec());
                } else {
                    updateYouTubeControlsLock("");
                }
            } else {
                if (mYouTubePipContainer != null) mYouTubePipContainer.setVisibility(View.GONE);
                if (mPipTrailingSpace != null) mPipTrailingSpace.setVisibility(View.GONE);
                updateYouTubeControlsLock("");
            }
        };
        mTabProvider.addObserver(mTabProviderObserver);

        Tab initialTab = mTabProvider.get();
        if (initialTab != null) {
            mCurrentObservedTab = initialTab;
            initialTab.addObserver(mPipTabObserver);
            updateYouTubePipButtonVisibility(initialTab);
            if (initialTab.getUrl() != null && !initialTab.getUrl().isEmpty()) {
                updateCommentCountForUrl(initialTab.getUrl().getSpec());
            }
        }
    }

    private void updateYouTubePipButtonVisibility(Tab tab) {
        if (mYouTubePipContainer == null) return;
        if (tab == null || tab.getWebContents() == null) {
            mYouTubePipContainer.setVisibility(View.GONE);
            if (mPipTrailingSpace != null) mPipTrailingSpace.setVisibility(View.GONE);
            return;
        }
        boolean available =
                PictureInPicture.isEnabled(mYouTubePipContainer.getContext())
                && BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                        tab.getWebContents());
        int visibility = available ? View.VISIBLE : View.GONE;
        mYouTubePipContainer.setVisibility(visibility);
        if (mPipTrailingSpace != null) mPipTrailingSpace.setVisibility(visibility);

        if (available) maybeShowPipCoachMark();
    }

    private void maybeShowPipCoachMark() {
        if (mYouTubePipButton == null) return;
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        int shownCount = prefs.getInt(PREF_PIP_INTRO_SHOWN_COUNT, 0);
        if (shownCount >= MAX_PIP_INTRO_SHOW_COUNT) return;
        prefs.edit().putInt(PREF_PIP_INTRO_SHOWN_COUNT, shownCount + 1).apply();
        mPipIntroShownThisRun = true;

        // Wait for the button to be laid out before reading its screen coordinates.
        mYouTubePipButton.post(() -> {
            if (mYouTubePipButton == null || mYouTubePipButton.getWidth() == 0) return;
            PipCoachMarkView coachMark = new PipCoachMarkView(mYouTubePipButton.getContext());
            coachMark.show(mYouTubePipButton, null);
        });
    }

    /**
     * Shows a snackbar nudging the user to minimize the app for background playback.
     * Only shown the first {@link #MAX_BG_PLAY_NUDGE_COUNT} times PIP is triggered.
     */
    private void maybeShowBgPlayNudge() {
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        int count = prefs.getInt(PREF_PIP_BG_PLAY_NUDGE_COUNT, 0);
        if (count >= MAX_BG_PLAY_NUDGE_COUNT) return;
        prefs.edit().putInt(PREF_PIP_BG_PLAY_NUDGE_COUNT, count + 1).apply();

        mToolbarRoot.postDelayed(() -> {
            try {
                View rootView = BraveActivity.getBraveActivity()
                        .findViewById(android.R.id.content);
                Snackbar snackbar = Snackbar.make(rootView,
                        "Minimize app to watch in background \uD83D\uDE0A",
                        Snackbar.LENGTH_LONG);
                // Move snackbar to the top so it doesn't hide behind PIP window
                View snackView = snackbar.getView();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) snackView.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = (int) (48 * rootView.getResources()
                        .getDisplayMetrics().density);
                snackView.setLayoutParams(lp);
                snackbar.show();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                // Ignore
            }
        }, 1500);
    }

    /**
     * Forces the bottom toolbar to be visible by resetting the translationY of the
     * ScrollingBottomViewResourceFrameLayout ancestor. This bypasses
     * Chromium's scroll-to-hide behavior for YouTube pages.
     */
    private void forceShowBottomToolbar() {
        // Walk up the view hierarchy from mToolbarRoot to find the scrolling container
        android.view.View view = mToolbarRoot;
        while (view != null) {
            if (view instanceof ScrollingBottomViewResourceFrameLayout) {
                view.animate().translationY(0).setDuration(250).start();
                return;
            }
            android.view.ViewParent parent = view.getParent();
            if (parent instanceof android.view.View) {
                view = (android.view.View) parent;
            } else {
                break;
            }
        }
    }

    /**
     * Acquires or releases a persistent browser-controls-shown token based on whether
     * the current URL is a YouTube page. While a token is held, both the top and bottom
     * toolbars stay visible regardless of scroll.
     */
    private void updateYouTubeControlsLock(String url) {
        boolean isYouTube = url != null && url.contains("youtube.com");
        if (isYouTube && mYouTubePersistentToken == TokenHolder.INVALID_TOKEN) {
            mYouTubePersistentToken =
                    mControlsVisibilityDelegate.showControlsPersistent();
        } else if (!isYouTube && mYouTubePersistentToken != TokenHolder.INVALID_TOKEN) {
            mControlsVisibilityDelegate.releasePersistentShowingToken(
                    mYouTubePersistentToken);
            mYouTubePersistentToken = TokenHolder.INVALID_TOKEN;
        }
    }

    /**
     * @param isVisible Whether the bottom toolbar is visible.
     */
    void onVisibilityChanged(boolean isVisible) {}

    /**
     * Initialize the bottom toolbar with the components that had native initialization
     * dependencies.
     *
     * <p>Calling this must occur after the native library have completely loaded.
     *
     * @param tabSwitcherListener An {@link OnClickListener} that is triggered when the tab switcher
     *     button is clicked.
     * @param menuButtonHelper An {@link AppMenuButtonHelper} that is triggered when the menu button
     *     is clicked.
     * @param tabModelSelector Updates the tab count number in the tab switcher button.
     * @param themeColorProvider Notifies components when theme color changes.
     * @param incognitoStateProvider Notifies components when incognito state changes.
     */
    void initializeWithNative(
            OnClickListener newTabListener,
            OnClickListener tabSwitcherListener,
            ObservableSupplier<AppMenuButtonHelper> menuButtonHelperSupplier,
            TabModelSelector tabModelSelector,
            ThemeColorProvider themeColorProvider,
            IncognitoStateProvider incognitoStateProvider) {
        if (mMenuButton != null) {
            Supplier<MenuButtonState> menuButtonStateSupplier =
                    () ->
                            UpdateMenuItemHelper.getInstance(
                                            tabModelSelector.getModel(false).getProfile())
                                    .getUiState()
                                    .buttonState;
            BraveMenuButtonCoordinator.setupPropertyModel(mMenuButton, menuButtonStateSupplier);
            if (!BottomToolbarVariationManager.isMenuButtonOnBottomControls()) {
                mMenuButton.setVisibility(View.GONE);
            }
        }
        mThemeColorProvider = themeColorProvider;
        mMediator.setThemeColorProvider(themeColorProvider);
        if (incognitoStateProvider.isIncognitoSelected()) {
            mMediator.onThemeColorChanged(
                    ChromeColors.getDefaultThemeColor(ContextUtils.getApplicationContext(), true),
                    false);
        }
        if (BottomToolbarVariationManager.isNewTabButtonOnBottomControls()) {
            mNewTabButton.setOnClickListener(newTabListener);
            mNewTabButton.setThemeColorProvider(themeColorProvider);
            mNewTabButton.setIncognitoStateProvider(incognitoStateProvider);
            mNewTabButton.onTintChanged(
                    mThemeColorProvider.getTint(),
                    mThemeColorProvider.getTint(),
                    mThemeColorProvider.getBrandedColorScheme());
        }

        if (BottomToolbarVariationManager.isHomeButtonOnBottomControls()) {
            mBraveHomeButton.setThemeColorProvider(themeColorProvider);
            mBraveHomeButton.onTintChanged(
                    mThemeColorProvider.getTint(),
                    mThemeColorProvider.getTint(),
                    mThemeColorProvider.getBrandedColorScheme());
        }

        mSearchAccelerator.setThemeColorProvider(themeColorProvider);
        mSearchAccelerator.setIncognitoStateProvider(incognitoStateProvider);
        mSearchAccelerator.onTintChanged(
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getBrandedColorScheme());

        if (BottomToolbarVariationManager.isTabSwitcherOnBottomControls()) {
            mTabSwitcherButtonCoordinator.setTabSwitcherListener(tabSwitcherListener);
            mTabSwitcherButtonCoordinator.setThemeColorProvider(themeColorProvider);
            mTabSwitcherButtonCoordinator.setTabCountSupplier(
                    tabModelSelector.getCurrentModelTabCountSupplier());
        }

        mBookmarkButton.setThemeColorProvider(themeColorProvider);
        mBookmarkButton.onTintChanged(
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getBrandedColorScheme());

        mThemeColorProvider.addTintObserver(mMenuButton);
        mMenuButton.onTintChanged(
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getTint(),
                mThemeColorProvider.getBrandedColorScheme());

        new OneShotCallback<>(
                menuButtonHelperSupplier,
                (menuButtonHelper) -> {
                    assert menuButtonHelper != null;
                    mMenuButton.setAppMenuButtonHelper(menuButtonHelper);
                });
    }

    /**
     * @param enabled Whether to disable click events on the bottom toolbar. Setting true can also
     *                prevent from all click events on toolbar and all children views on toolbar.
     */
    void setTouchEnabled(boolean enabled) {
        mToolbarRoot.setTouchEnabled(enabled);
    }

    /**
     * @param visible Whether to hide the tab switcher bottom toolbar
     */
    void setVisible(boolean visible) {
        mModel.set(BrowsingModeBottomToolbarModel.IS_VISIBLE, visible);
    }

    /**
     * @return The browsing mode bottom toolbar's tab switcher button.
     */
    TabSwitcherButtonView getTabSwitcherButtonView() {
        return mTabSwitcherButtonView;
    }

    /**
     * @return The browsing mode bottom toolbar's search button.
     */
    SearchAccelerator getSearchAccelerator() {
        return mSearchAccelerator;
    }

    /**
     * @return The browsing mode bottom toolbar's home button.
     */
    BraveHomeButton getHomeButton() {
        return mBraveHomeButton;
    }

    /**
     * Clean up any state when the browsing mode bottom toolbar is destroyed.
     */
    public void destroy() {
        // Release the YouTube persistent controls token if held.
        if (mYouTubePersistentToken != TokenHolder.INVALID_TOKEN) {
            mControlsVisibilityDelegate.releasePersistentShowingToken(mYouTubePersistentToken);
            mYouTubePersistentToken = TokenHolder.INVALID_TOKEN;
        }
        if (mShareButtonListenerSupplier != null) {
            mShareButtonListenerSupplier.removeObserver(mShareButtonListenerSupplierCallback);
        }
        if (mCurrentObservedTab != null && mPipTabObserver != null) {
            mCurrentObservedTab.removeObserver(mPipTabObserver);
        }
        if (mTabProviderObserver != null) {
            mTabProvider.removeObserver(mTabProviderObserver);
        }
        mMediator.destroy();
        mBraveHomeButton.destroy();
        mSearchAccelerator.destroy();
        mTabSwitcherButtonCoordinator.destroy();
        mBookmarkButton.destroy();
        if (mThemeColorProvider != null) {
            mThemeColorProvider.removeTintObserver(mMenuButton);
        }
    }

    public void updateBookmarkButton(boolean isBookmarked, boolean editingAllowed) {
        if (mBookmarkButton != null) {
            mBookmarkButton.updateBookmarkButton(isBookmarked, editingAllowed);
        }
    }

    View getNewTabButtonParent() {
        return (View) mNewTabButton.getParent();
    }

    BookmarksButton getBookmarkButton() {
        return mBookmarkButton;
    }

    private final BrowserExpressGetFirstCommentsUtil.GetFirstCommentsCallback getFirstCommentsCallback=
            new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsCallback() {
                @Override
                public void getFirstCommentsSuccessful(JSONArray comments, int commentCount) {
                    mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
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

    /**
     * Shows up to 3 comment preview cards that cycle up from the bottom one at a time,
     * each rising with a spring animation, staying for 3 seconds, then sliding back down
     * before the next one appears.
     */
    private void showYouTubeCommentsPreview(List<Comment> comments) {
        // Cancel and remove any existing overlays first
        if (mStatsOverlays != null) {
            for (View old : mStatsOverlays) {
                if (old != null) {
                    old.animate().cancel();
                    ViewGroup parent = (ViewGroup) old.getParent();
                    if (parent != null) parent.removeView(old);
                }
            }
            mStatsOverlays = null;
        }

        if (comments == null || comments.isEmpty()) return;

        ViewGroup contentView;
        try {
            contentView = BraveActivity.getBraveActivity().findViewById(android.R.id.content);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }

        DisplayMetrics metrics = mToolbarRoot.getContext().getResources().getDisplayMetrics();
        float density = metrics.density;
        int bottomToolbarHeight = mToolbarRoot.getContext().getResources()
                .getDimensionPixelSize(R.dimen.bottom_controls_height);
        float offScreen = metrics.heightPixels * 0.6f;

        // Build all card views up front so Glide can start loading avatars immediately
        LayoutInflater inflater = LayoutInflater.from(mToolbarRoot.getContext());
        int limit = Math.min(comments.size(), 3);
        List<View> cards = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            Comment comment = comments.get(i);
            View card = inflater.inflate(R.layout.youtube_comment_preview, contentView, false);

            if (comment.getUser() != null) {
                ((TextView) card.findViewById(R.id.preview_username))
                        .setText(comment.getUser().getUsername());
                String avatarUrl = comment.getUser().getAvatar();
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    try {
                        Glide.with(mToolbarRoot.getContext())
                                .load(avatarUrl)
                                .circleCrop()
                                .placeholder(R.drawable.btn_toolbar_profile)
                                .into((ImageView) card.findViewById(R.id.preview_avatar));
                    } catch (Exception ignored) {}
                }
            }
            ((TextView) card.findViewById(R.id.preview_content)).setText(comment.getContent());

            // Position each card just above the bottom toolbar, off-screen below to start
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.BOTTOM;
            params.bottomMargin = bottomToolbarHeight + (int) (8 * density);
            params.setMarginStart((int) (12 * density));
            params.setMarginEnd((int) (12 * density));
            card.setLayoutParams(params);
            card.setTranslationY(offScreen);
            card.setAlpha(0f);

            cards.add(card);
        }

        mStatsOverlays = new View[limit];
        cycleCommentCard(cards, 0, contentView, offScreen, mStatsOverlays);
    }

    /**
     * Recursively cycles through comment cards: slides the card at {@code index} up from
     * the bottom, waits 3 seconds, slides it back down, then starts the next card.
     * The {@code overlayRef} identity check ensures a stale cycle stops if a new preview
     * call has already reset {@code mStatsOverlays}.
     */
    private void cycleCommentCard(
            List<View> cards, int index, ViewGroup contentView, float offScreen, View[] overlayRef) {
        if (index >= cards.size() || mStatsOverlays != overlayRef) return;

        View card = cards.get(index);
        mStatsOverlays[index] = card;
        contentView.addView(card);

        // Slide up from below with a slight spring overshoot
        card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(new OvershootInterpolator(0.8f))
                .withEndAction(() -> card.postDelayed(() -> {
                    // Slide current card back down
                    card.animate()
                            .translationY(offScreen)
                            .alpha(0f)
                            .setDuration(350)
                            .setInterpolator(new AccelerateInterpolator())
                            .withEndAction(() -> {
                                ViewGroup p = (ViewGroup) card.getParent();
                                if (p != null) p.removeView(card);
                                if (index == cards.size() - 1 && mStatsOverlays == overlayRef) {
                                    mStatsOverlays = null;
                                }
                            })
                            .start();
                    // Start next card as this one exits
                    cycleCommentCard(cards, index + 1, contentView, offScreen, overlayRef);
                }, 3000))
                .start();
    }


    /** Animates mCommentsText counting up from 0 to {@code targetCount} over 5 seconds,
     *  with a bouncing up-arrow visible while the count is running. */
    private void animateCommentCount(long targetCount) {
        if (targetCount <= 0) {
            mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", 0));
            return;
        }

        // Show the arrow and start its bounce loop
        if (mCommentsCountArrow != null) {
            mCommentsCountArrow.setVisibility(View.VISIBLE);
            startArrowBounce();
        }

        ValueAnimator animator = ValueAnimator.ofInt(0, (int) Math.min(targetCount, Integer.MAX_VALUE));
        animator.setDuration(5000);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> mCommentsText.setText(
                String.format(Locale.getDefault(), "%d comments", (int) a.getAnimatedValue())));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                stopArrowBounce();
            }
        });
        animator.start();
    }

    private android.animation.ObjectAnimator mArrowBounceAnimator;

    private void startArrowBounce() {
        if (mCommentsCountArrow == null) return;
        mCommentsCountArrow.setTranslationY(0f);
        float jumpDist = -mCommentsCountArrow.getContext().getResources()
                .getDisplayMetrics().density * 4f; // 4dp jump
        mArrowBounceAnimator = android.animation.ObjectAnimator.ofFloat(
                mCommentsCountArrow, "translationY", 0f, jumpDist, 0f);
        mArrowBounceAnimator.setDuration(600);
        mArrowBounceAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mArrowBounceAnimator.setInterpolator(new DecelerateInterpolator());
        mArrowBounceAnimator.start();
    }

    private void stopArrowBounce() {
        if (mArrowBounceAnimator != null) {
            mArrowBounceAnimator.cancel();
            mArrowBounceAnimator = null;
        }
        if (mCommentsCountArrow != null) {
            mCommentsCountArrow.setTranslationY(0f);
            mCommentsCountArrow.setVisibility(View.GONE);
        }
    }

    private void updateCommentCountForUrl(String url) {
        if (url == null || url.isEmpty()) {
            mCurrentPreviewVideoId = null;
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            mCurrentPreviewVideoId = null;
            return;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String videoId = uri.getQueryParameter("v");
            boolean isYouTube = host != null && host.contains("youtube.com")
                    && videoId != null && !videoId.isEmpty();

            if (isYouTube) {
                final String finalVideoId = videoId;
                mCurrentPreviewVideoId = finalVideoId;
                // Fetch top 3 comments for the preview animation
                new YouTubeCommentsUtil.GetYouTubeFirstCommentsTask(
                        finalVideoId,
                        new YouTubeCommentsUtil.GetYouTubeFirstCommentsCallback() {
                            @Override
                            public void onSuccess(List<Comment> comments, long ignored) {
                                if (!finalVideoId.equals(mCurrentPreviewVideoId)) return;
                                if (mPipIntroShownThisRun) return;
                                showYouTubeCommentsPreview(comments);
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.e(TAG, "[FirstComments] Failed to load YouTube comments: " + error);
                            }
                        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                // Fetch accurate comment count from the video stats API for the toolbar label
                new YouTubeCommentsUtil.GetYouTubeVideoStatsTask(
                        finalVideoId,
                        new YouTubeCommentsUtil.VideoStatsCallback() {
                            @Override
                            public void onSuccess(long commentCount, long viewCount, long likeCount) {
                                if (!finalVideoId.equals(mCurrentPreviewVideoId)) return;
                                animateCommentCount(commentCount);
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.e(TAG, "[Stats] Failed to load YouTube stats: " + error);
                            }
                        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                mCurrentPreviewVideoId = null;
                BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask workerTask =
                        new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask(
                                url, getFirstCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (Exception e) {
            Log.e(TAG, "[updateCommentCount] Error: " + e.getMessage());
        }
    }

    /**
     * Fires a PostHog analytics event, extracting the userId from the JWT access token.
     * @param eventKey Event name constant from {@link PostHogEventKeys}.
     * @param extraProps Optional extra properties to include (may be null).
     */
    private void firePostHogEvent(String eventKey, JSONObject extraProps) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) return;

            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return;
            byte[] decoded = Base64.decode(parts[1], Base64.DEFAULT);
            JSONObject jwt = new JSONObject(new String(decoded, "UTF-8"));
            String userId = jwt.getString("_id");

            JSONObject payload = extraProps != null ? extraProps : new JSONObject();
            payload.put("app_version", activity.getCurrentAppVersion());

            PostHogUtil.PostHogWorkerTask task =
                    new PostHogUtil.PostHogWorkerTask(eventKey, userId, payload);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (Exception e) {
            Log.e(TAG, "firePostHogEvent error: " + e.getMessage());
        }
    }
}
