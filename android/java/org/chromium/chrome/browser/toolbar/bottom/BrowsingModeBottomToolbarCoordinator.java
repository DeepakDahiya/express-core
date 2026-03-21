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
import android.widget.Button;
import android.widget.ImageButton;
import android.view.HapticFeedbackConstants;
import org.chromium.chrome.browser.BraveYouTubeScriptInjectorNativeHelper;
import org.chromium.chrome.browser.browser_express_comments.YouTubeCommentsUtil;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.ViewGroup;
import org.chromium.chrome.browser.media.PictureInPicture;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.url.GURL;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;
import java.io.UnsupportedEncodingException;
import org.json.JSONException;
import android.util.Base64;
import org.json.JSONObject;

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
    private int w;
    private int h;

    private static final String PREF_PIP_COACH_MARK_SHOWN = "pip_coach_mark_shown";

    private ImageButton mYouTubePipButton;
    private View mYouTubePipContainer;
    private View mYouTubePipSpace;
    private View mStatsOverlay;
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

    BrowsingModeBottomToolbarCoordinator(
            View root,
            ActivityTabProvider tabProvider,
            OnClickListener homeButtonListener,
            OnClickListener searchAcceleratorListener,
            ObservableSupplier<OnClickListener> shareButtonListenerSupplier,
            OnLongClickListener tabSwitcherLongClickListener) {
        mModel = new BrowsingModeBottomToolbarModel();
        mToolbarRoot = root.findViewById(R.id.bottom_toolbar_browsing);
        mTabProvider = tabProvider;

        PropertyModelChangeProcessor.create(
                mModel, mToolbarRoot, new BrowsingModeBottomToolbarViewBinder());

        mMediator = new BrowsingModeBottomToolbarMediator(mModel);

        mBraveHomeButton = mToolbarRoot.findViewById(R.id.bottom_home_button);
        mBraveHomeText = mToolbarRoot.findViewById(R.id.bottom_home_text);
        mCommentsButton = mToolbarRoot.findViewById(R.id.comments_button);
        mCommentsText = mToolbarRoot.findViewById(R.id.comments_button1);
        mBeHomeButton = mToolbarRoot.findViewById(R.id.be_home_button);
        int commentCount = 0;
        mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
        mCommentsText.setTextSize(10);
        mCommentsText.setTextColor(android.graphics.Color.WHITE);
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
        mYouTubePipSpace = mToolbarRoot.findViewById(R.id.youtube_pip_space);

        if (mYouTubePipButton != null) {
            OnClickListener pipClickHandler = v -> {
                Tab tab = mTabProvider.get();
                if (tab == null || tab.getWebContents() == null) return;
                BraveYouTubeScriptInjectorNativeHelper.triggerYouTubePiP(tab.getWebContents());
                try {
                    BraveActivity.getBraveActivity()
                            .openNewOrSelectExistingTab("https://m.youtube.com/");
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "openYouTubeHome: " + e.getMessage());
                }
            };
            mYouTubePipButton.setOnClickListener(pipClickHandler);
            mToolbarRoot.findViewById(R.id.bottom_youtube_pip_text).setOnClickListener(
                    pipClickHandler);
        }

        mPipTabObserver = new EmptyTabObserver() {
            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                if (mYouTubePipContainer != null) mYouTubePipContainer.setVisibility(View.GONE);
                if (mYouTubePipSpace != null) mYouTubePipSpace.setVisibility(View.GONE);
            }

            @Override
            public void onPageLoadFinished(Tab tab, GURL url) {
                updateYouTubePipButtonVisibility(tab);
                updateCommentCountForUrl(url.getSpec());
            }

            @Override
            public void onLoadStopped(Tab tab, boolean toDifferentDocument) {
                updateYouTubePipButtonVisibility(tab);
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
                // Only fetch when the tab is already done loading; if it's still loading,
                // onPageLoadFinished will fire with the correct final URL.
                if (!tab.isLoading() && tab.getUrl() != null && !tab.getUrl().isEmpty()) {
                    updateCommentCountForUrl(tab.getUrl().getSpec());
                }
            } else {
                if (mYouTubePipContainer != null) mYouTubePipContainer.setVisibility(View.GONE);
                if (mYouTubePipSpace != null) mYouTubePipSpace.setVisibility(View.GONE);
            }
        };
        mTabProvider.addObserver(mTabProviderObserver);

        Tab initialTab = mTabProvider.get();
        if (initialTab != null) {
            mCurrentObservedTab = initialTab;
            initialTab.addObserver(mPipTabObserver);
            updateYouTubePipButtonVisibility(initialTab);
            if (!initialTab.isLoading() && initialTab.getUrl() != null
                    && !initialTab.getUrl().isEmpty()) {
                updateCommentCountForUrl(initialTab.getUrl().getSpec());
            }
        }
    }

    private void updateYouTubePipButtonVisibility(Tab tab) {
        if (mYouTubePipContainer == null) return;
        if (tab == null || tab.getWebContents() == null) {
            mYouTubePipContainer.setVisibility(View.GONE);
            if (mYouTubePipSpace != null) mYouTubePipSpace.setVisibility(View.GONE);
            return;
        }
        boolean available =
                PictureInPicture.isEnabled(mYouTubePipContainer.getContext())
                && BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                        tab.getWebContents());
        int visibility = available ? View.VISIBLE : View.GONE;
        mYouTubePipContainer.setVisibility(visibility);
        if (mYouTubePipSpace != null) mYouTubePipSpace.setVisibility(visibility);
        if (available) maybeShowPipCoachMark();
    }

    private void maybeShowPipCoachMark() {
        if (mYouTubePipButton == null) return;
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        if (prefs.getBoolean(PREF_PIP_COACH_MARK_SHOWN, false)) return;
        prefs.edit().putBoolean(PREF_PIP_COACH_MARK_SHOWN, true).apply();

        // Wait for the button to be laid out before reading its screen coordinates.
        mYouTubePipButton.post(() -> {
            if (mYouTubePipButton == null || mYouTubePipButton.getWidth() == 0) return;
            PipCoachMarkView coachMark = new PipCoachMarkView(mYouTubePipButton.getContext());
            coachMark.show(mYouTubePipButton, null);
        });
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
     * Shows a floating stats card (views, likes, comments) that rises from just above the bottom
     * toolbar and travels upward ~20 % of the screen height, then auto-dismisses.
     */
    private void showYouTubeStatsOverlay(long commentCount, long viewCount, long likeCount) {
        // Cancel and remove any existing overlay first
        if (mStatsOverlay != null) {
            mStatsOverlay.animate().cancel();
            ViewGroup parent = (ViewGroup) mStatsOverlay.getParent();
            if (parent != null) parent.removeView(mStatsOverlay);
            mStatsOverlay = null;
        }

        ViewGroup contentView;
        try {
            contentView = BraveActivity.getBraveActivity().findViewById(android.R.id.content);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }

        mStatsOverlay = LayoutInflater.from(mToolbarRoot.getContext())
                .inflate(R.layout.youtube_stats_overlay, contentView, false);

        ((TextView) mStatsOverlay.findViewById(R.id.stat_view_count))
                .setText(formatStatCount(viewCount));
        ((TextView) mStatsOverlay.findViewById(R.id.stat_like_count))
                .setText(formatStatCount(likeCount));
        ((TextView) mStatsOverlay.findViewById(R.id.stat_comment_count))
                .setText(formatStatCount(commentCount));

        // Position the card just above the bottom toolbar
        int bottomToolbarHeight = mToolbarRoot.getContext().getResources()
                .getDimensionPixelSize(R.dimen.bottom_controls_height);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.bottomMargin = bottomToolbarHeight;
        mStatsOverlay.setLayoutParams(params);

        // The card starts translated down by 20 % of the screen height (off-screen direction)
        // and rises to its natural resting position (translationY = 0).
        DisplayMetrics metrics = mToolbarRoot.getContext().getResources().getDisplayMetrics();
        float travelDistance = metrics.heightPixels * 0.20f;

        mStatsOverlay.setAlpha(0f);
        mStatsOverlay.setTranslationY(travelDistance);
        contentView.addView(mStatsOverlay);

        final View overlay = mStatsOverlay;
        overlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> overlay.postDelayed(() -> overlay.animate()
                        .translationY(travelDistance)
                        .alpha(0f)
                        .setDuration(400)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            ViewGroup p = (ViewGroup) overlay.getParent();
                            if (p != null) p.removeView(overlay);
                            if (mStatsOverlay == overlay) mStatsOverlay = null;
                        })
                        .start(), 2500))
                .start();
    }

    /** Formats a large number as a compact string, e.g. 185149 → "185.1K". */
    private String formatStatCount(long count) {
        if (count >= 1_000_000_000L) {
            return String.format(Locale.getDefault(), "%.1fB", count / 1_000_000_000.0);
        } else if (count >= 1_000_000L) {
            return String.format(Locale.getDefault(), "%.1fM", count / 1_000_000.0);
        } else if (count >= 1_000L) {
            return String.format(Locale.getDefault(), "%.1fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    private void updateCommentCountForUrl(String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String videoId = uri.getQueryParameter("v");
            boolean isYouTube = host != null && host.contains("youtube.com")
                    && videoId != null && !videoId.isEmpty();

            if (isYouTube) {
                final String finalVideoId = videoId;
                new YouTubeCommentsUtil.GetYouTubeVideoStatsTask(
                        finalVideoId,
                        new YouTubeCommentsUtil.VideoStatsCallback() {
                            @Override
                            public void onSuccess(long commentCount, long viewCount, long likeCount) {
                                mCommentsText.setText(String.format(
                                        Locale.getDefault(), "%d comments", commentCount));
                                showYouTubeStatsOverlay(commentCount, viewCount, likeCount);
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.e(TAG, "[Stats] Failed to load YouTube stats: " + error);
                            }
                        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask workerTask =
                        new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask(
                                url, getFirstCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (Exception e) {
            Log.e(TAG, "[updateCommentCount] Error: " + e.getMessage());
        }
    }
}
