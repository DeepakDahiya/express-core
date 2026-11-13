/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.bottom;

import android.icu.text.CompactDecimalFormat;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import org.chromium.base.task.AsyncTask;
import org.json.JSONArray;
import android.widget.TextView;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.Intent;
import android.net.Uri;
import org.chromium.content_public.browser.JavaScriptCallback;
import org.chromium.base.Callback;
import android.app.Activity;

import androidx.annotation.Nullable;
import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
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
import org.chromium.chrome.browser.browser_express_comments.Comment;
import org.chromium.chrome.browser.crash.BrowserExpressCrashBreadcrumbs;
import org.chromium.chrome.browser.crash.BrowserExpressPendingCrashReportStore;
import android.util.DisplayMetrics;
import android.util.Base64;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
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
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.url.GURL;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import android.content.pm.PackageInfo;
import java.io.UnsupportedEncodingException;
import org.json.JSONException;
import android.util.Base64;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;

/**
 * The coordinator for the browsing mode bottom toolbar. This class has two primary components, an
 * Android view that handles user actions and a composited texture that draws when the controls are
 * being scrolled off-screen. The Android version does not draw unless the controls offset is 0.
 */
public class BrowsingModeBottomToolbarCoordinator {
    private static final String TAG = "BrowsingMode";

    // The floating bar's comments button. Its icon carries the page's comment count as a badge,
    // or a "+" badge while there are none, and wiggles once there are comments.
    private final View mCommentsButton;
    private ImageButton mBeHomeButton;
    private TextView mBraveHomeText;
    private final TextView mCommentCountBadge;
    private final View mCommentsJoin;
    private final CommentsBarIconMotion mCommentsIconMotion;
    private int mCommentCount;
    private @Nullable ValueAnimator mCommentCountAnimator;

    private static final String PREF_PIP_INTRO_SHOWN_COUNT = "pip_intro_shown_count";
    // Strictly one background-play tutorial per install. The counter is
    // bumped only when the overlay is actually put on screen (see
    // showLockPhoneTutorialDialog), so a bail-out cannot burn the single
    // show, and a second /watch page can never re-trigger it.
    private static final int MAX_PIP_INTRO_SHOW_COUNT = 1;
    private static final String PREF_PIP_BG_PLAY_NUDGE_COUNT = "pip_bg_play_nudge_count";
    private static final int MAX_BG_PLAY_NUDGE_COUNT = 3;
    // Kill switch: paused per product request. Flip back to true to re-enable
    // the "Minimize the app" background-play nudge shown after a golden-button
    // PiP click. The toast, prefs and strings are left intact.
    private static final boolean ENABLE_BG_PLAY_NUDGE = false;
    private boolean mPipIntroActiveOnCurrentPage;
    private View mCurrentLockPhoneDialog;

    // Two-step "background play" tutorial:
    //   Step 1 ("Go to home"): counts down, then sends the app to the
    //           background so the video keeps playing.
    //   Step 2 ("Congratulations"): shown when the user returns to the app.
    // NOTE: the countdown seconds ALSO drive when the app is backgrounded (the
    // app goes home when the count reaches 0). Set to 3 to match the "3, 2, 1"
    // spec; bump to 5 for a five-second countdown.
    private static final int LOCK_TUTORIAL_COUNTDOWN_SECONDS = 5;
    private static final String PREF_PIP_AWAITING_CONGRATS = "pip_awaiting_congrats";
    private View mCurrentCongratsDialog;
    private ApplicationStatus.ActivityStateListener mCongratsReturnListener;
    private boolean mCongratsSawStop;

    // Whether the current page can enter the golden-button PiP. The button itself lives in the
    // top toolbar; this only drives the background-play tutorial.
    private boolean mYouTubePipAvailable;

    private View[] mStatsOverlays;
    private Handler mPreviewHandler;
    private String mCurrentPreviewVideoId;
    /** The video ID for which we already kicked off a preview-comments fetch.
     *  Prevents duplicate fetches from multiple tab-observer callbacks firing
     *  for the same page load (onPageLoadFinished, onLoadStopped, onUrlUpdated). */
    private String mLastFetchedPreviewVideoId;
    private Tab mCurrentObservedTab;
    private TabObserver mPipTabObserver;
    private Callback<Tab> mTabProviderObserver;
    private String mLastPostHogPageUrl;

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
        // Backstop for the 2-step tutorial: if the process was killed while
        // backgrounded during step 1, the in-memory return listener is gone;
        // recover step 2 ("Congratulations") from the persisted flag once the
        // view tree is ready. No-ops on a normal launch (flag unset).
        if (mToolbarRoot != null) {
            mToolbarRoot.postDelayed(this::showCongratsDialogIfAwaiting, 800);
        }
        mTabProvider = tabProvider;
        mControlsVisibilityDelegate = controlsVisibilityDelegate;

        PropertyModelChangeProcessor.create(
                mModel, mToolbarRoot, new BrowsingModeBottomToolbarViewBinder());

        mMediator = new BrowsingModeBottomToolbarMediator(mModel);

        mBraveHomeButton = mToolbarRoot.findViewById(R.id.bottom_home_button);
        mBraveHomeText = mToolbarRoot.findViewById(R.id.bottom_home_text);
        mCommentsButton = mToolbarRoot.findViewById(R.id.comments_button_section);
        mCommentCountBadge = mToolbarRoot.findViewById(R.id.comments_button1);
        mCommentsJoin = mToolbarRoot.findViewById(R.id.comments_bar_join);
        mCommentsIconMotion =
                new CommentsBarIconMotion(
                        mToolbarRoot.findViewById(R.id.comments_bar_icon_motion),
                        mToolbarRoot.findViewById(R.id.comments_bar_icon_play));
        mBeHomeButton = mToolbarRoot.findViewById(R.id.be_home_button);
        setCommentCount(0);
        // The bar's Home is be_home_button. Chromium's home button and Brave's label stay GONE
        // and keep their listeners so nothing downstream of BottomToolbarCoordinator changes.
        mBraveHomeButton.setOnClickListener(homeButtonListener);
        mBraveHomeText.setOnClickListener(homeButtonListener);
        mBeHomeButton.setOnClickListener(homeButtonListener);

        if (mCommentsButton != null) {
            OnClickListener commentsClickHandler = v -> {
                mCommentsButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    // Which site's comments, by its domain only; nothing about incognito tabs.
                    Tab tab = activity.getActivityTab();
                    if (tab != null && !tab.isIncognito()) {
                        JSONObject payload = PostHogUtil.pageProperties(tab.getUrl());
                        payload.put("type", "page");
                        PostHogUtil.capture(PostHogEventKeys.BOTTOM_SHEET_CLICKED, payload);
                    }
                    activity.showCommentsBottomSheet();
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                } catch (Exception e) {
                    Log.e(TAG, "Error in commentsClickHandler: " + e.getMessage());
                }
            };
            mCommentsButton.setOnClickListener(commentsClickHandler);
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

        BraveActivity ownerActivity = braveActivityOf(mToolbarRoot);
        if (ownerActivity != null) {
            // The golden PiP button is in the top toolbar now; it runs this coordinator's flow.
            ownerActivity.setGoldenPipHandler(this::onGoldenPipRequested);
            // Other screens (the top toolbar's fetch, a newly posted comment) report the
            // page's comment count through the activity; the bar owns showing it.
            ownerActivity.setPageCommentCountSink(
                    new BraveActivity.PageCommentCountSink() {
                        @Override
                        public void setCount(int count) {
                            setCommentCount(count);
                        }

                        @Override
                        public int getCount() {
                            return mCommentCount;
                        }

                        @Override
                        public void setReactions(@Nullable JSONObject pageReactions) {
                            // The bar shows only the comment count since the 2026-10 design.
                        }

                        @Override
                        public void refresh() {
                            refreshPageEngagement();
                        }
                    });
        }

        mPipTabObserver = new EmptyTabObserver() {
            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                mYouTubePipAvailable = false;
                // Reset per-page intro flag so preview comments can show on the next page.
                mPipIntroActiveOnCurrentPage = false;
                // Allow a fresh comment fetch for the new page load.
                mLastFetchedPreviewVideoId = null;
                dismissLockPhoneTutorialDialog();
                String urlSpec = url != null ? url.getSpec() : "";
                updateYouTubeControlsLock(urlSpec);
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
                firePageVisitedPostHog(tab);
            }
        };

        mTabProviderObserver = tab -> {
            if (mCurrentObservedTab != null) {
                mCurrentObservedTab.removeObserver(mPipTabObserver);
            }
            mCurrentObservedTab = tab;
            // Switching tabs is a new page context — reset flags.
            mPipIntroActiveOnCurrentPage = false;
            mLastFetchedPreviewVideoId = null;
            dismissLockPhoneTutorialDialog();
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
                mYouTubePipAvailable = false;
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
                updateYouTubeControlsLock(initialTab.getUrl().getSpec());
            } else {
                updateYouTubeControlsLock("");
            }
        }
    }

    private void updateYouTubePipButtonVisibility(Tab tab) {
        if (tab == null || tab.getWebContents() == null) {
            mYouTubePipAvailable = false;
            return;
        }
        mYouTubePipAvailable =
                PictureInPicture.isEnabled(mToolbarRoot.getContext())
                && BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                        tab.getWebContents());
        if (mYouTubePipAvailable) maybeShowLockPhoneTutorialDialog();
    }

    /**
     * The golden-button PiP flow: PiP on the current watch page, then YouTube home once PiP is
     * confirmed. Runs from the top toolbar's PiP button via {@link
     * BraveActivity#requestGoldenPip()}.
     */
    private void onGoldenPipRequested() {
        BrowserExpressCrashBreadcrumbs.recordEvent("yt_pip_bottom");

        dismissLockPhoneTutorialDialog();
        // User discovered PIP — never show the intro again.
        ContextUtils.getAppSharedPreferences().edit()
                .putInt(PREF_PIP_INTRO_SHOWN_COUNT, MAX_PIP_INTRO_SHOW_COUNT).apply();

        Tab tab = mTabProvider.get();
        if (tab == null || tab.getWebContents() == null) {
            BrowserExpressCrashBreadcrumbs.recordEvent("yt_pip_no_tab");
            BrowserExpressPendingCrashReportStore.recordNonFatal(
                    "youtube", "pip_bottom_no_tab", "warning");
            return;
        }
        if (!BraveYouTubeScriptInjectorNativeHelper.isPictureInPictureAvailable(
                    tab.getWebContents())) {
            BrowserExpressCrashBreadcrumbs.recordEvent("yt_pip_unavailable");
            BrowserExpressPendingCrashReportStore.recordNonFatal(
                    "youtube", "pip_bottom_unavailable", "warning");
        }
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            Log.e(
                    "BravePiPDebug",
                    "golden PiP click tabId=" + tab.getId() + " url=" + tab.getUrl().getSpec());
            // Record the watch tab as the PiP origin before requesting
            // PiP, and pass true so BraveActivity switches to YouTube
            // home once the framework confirms PiP entry (golden-button
            // flow). Without the second arg the home tab never opens.
            activity.notePipOriginTab(tab, true);
            Log.e("BravePiPDebug", "golden PiP triggerYouTubePiP tabId=" + tab.getId());
            BrowserExpressCrashBreadcrumbs.recordEvent("yt_pip_trigger");
            BraveYouTubeScriptInjectorNativeHelper.triggerYouTubePiP(tab.getWebContents());

            // Track PIP feature explored
            firePostHogEvent(PostHogEventKeys.YT_FEATURE_EXPLORED_PIP, null);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            BrowserExpressCrashBreadcrumbs.recordEvent("yt_pip_no_activity");
            BrowserExpressPendingCrashReportStore.recordNonFatal(
                    "youtube", "pip_no_activity", "warning");
            Log.e(TAG, "golden PiP: " + e.getMessage());
        }
        maybeShowBgPlayNudge();
    }

    private static @Nullable BraveActivity braveActivityOf(View view) {
        Activity activity = ContextUtils.activityFromContext(view.getContext());
        return activity instanceof BraveActivity ? (BraveActivity) activity : null;
    }

    private void maybeShowLockPhoneTutorialDialog() {
        if (mToolbarRoot == null) return;
        // mPipIntroActiveOnCurrentPage is set synchronously, so even rapid
        // re-entrant calls from multiple tab observer callbacks are blocked.
        if (mPipIntroActiveOnCurrentPage) return;
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        if (prefs.getInt(PREF_PIP_INTRO_SHOWN_COUNT, 0) >= MAX_PIP_INTRO_SHOW_COUNT) return;
        // NOTE: the counter is NOT bumped here. showLockPhoneTutorialDialog can
        // still bail (dialog already up, PiP button gone), and consuming the
        // one-and-only show for a tutorial the user never saw would silently
        // disable the onboarding.
        mPipIntroActiveOnCurrentPage = true;

        mToolbarRoot.postDelayed(this::showLockPhoneTutorialDialog, 300);
    }

    private void showLockPhoneTutorialDialog() {
        if (mCurrentLockPhoneDialog != null) return;
        if (!mYouTubePipAvailable) return;
        // Re-check the persisted budget here, not just in the caller: two
        // posts can be in flight across a fast navigation (the per-page flag is
        // reset on page start), and the pref is the only cross-page guard.
        final SharedPreferences introPrefs = ContextUtils.getAppSharedPreferences();
        final int shownCount = introPrefs.getInt(PREF_PIP_INTRO_SHOWN_COUNT, 0);
        if (shownCount >= MAX_PIP_INTRO_SHOW_COUNT) return;

        final ViewGroup root;
        try {
            root = (ViewGroup) BraveActivity.getBraveActivity()
                    .findViewById(android.R.id.content);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (root == null) return;

        final float density = root.getResources().getDisplayMetrics().density;
        final int dp16 = (int) (16 * density);
        final int dp20 = (int) (20 * density);
        final int dp32 = (int) (32 * density);

        FrameLayout overlay = new FrameLayout(root.getContext());
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        FrameLayout card = new FrameLayout(root.getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(root.getContext().getColor(R.color.browser_express_coach_mark_background));
        bg.setCornerRadius(16f * density);
        bg.setStroke((int) Math.max(1, density), 0x55D4AF37);
        card.setBackground(bg);
        card.setElevation(10f * density);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout content = new LinearLayout(root.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp20, dp16, dp20, dp16);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(root.getContext());
        titleView.setText(root.getContext().getString(R.string.pip_lock_phone_tutorial_title));
        titleView.setTextColor(root.getContext().getColor(R.color.browser_express_gold));
        titleView.setTextSize(17f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);

        TextView bodyView = new TextView(root.getContext());
        // Countdown body: "Enabling background play in N…" ticking N -> 1, then
        // the app is sent to the background (step 1 of the tutorial).
        bodyView.setText(root.getContext().getString(
                R.string.pip_lock_phone_tutorial_body, LOCK_TUTORIAL_COUNTDOWN_SECONDS));
        bodyView.setTextColor(root.getContext().getColor(R.color.browser_express_text_primary));
        bodyView.setTextSize(14f);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setLineSpacing(2f * density, 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) (8 * density);

        content.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(bodyView, bodyLp);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Card fills the width between the margins so the text wraps inside it
        // rather than being measured wider than the screen.
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp32;
        cardLp.rightMargin = dp32;
        overlay.addView(card, cardLp);

        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(overlay, overlayLp);
        mCurrentLockPhoneDialog = overlay;
        // The overlay is on screen: this is the point the show is spent.
        introPrefs.edit().putInt(PREF_PIP_INTRO_SHOWN_COUNT, shownCount + 1).apply();

        // Tutorial step 1 is on screen — start of the background-play funnel.
        JSONObject shownProps = new JSONObject();
        try {
            shownProps.put("shown_count", shownCount + 1);
        } catch (Exception ignored) {
        }
        firePostHogEvent(PostHogEventKeys.YT_BG_PLAY_TUTORIAL_SHOWN, shownProps);

        // Non-dismissible by touch: the overlay and card stay clickable (set
        // above) purely to absorb taps so nothing falls through to the page and
        // the countdown can't be cancelled by a stray tap. Step 1 auto-advances.

        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .start();

        // Start the countdown; when it hits 0 we send the app to the background
        // so the video keeps playing, then show "Congratulations" on return.
        startLockTutorialCountdown(overlay, bodyView, LOCK_TUTORIAL_COUNTDOWN_SECONDS);
    }

    private void dismissLockPhoneTutorialDialog() {
        if (mCurrentLockPhoneDialog == null) return;
        View dialog = mCurrentLockPhoneDialog;
        mCurrentLockPhoneDialog = null;
        dialog.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) dialog.getParent();
                    if (parent != null) parent.removeView(dialog);
                })
                .start();
    }

    // Ticks the step-1 countdown once per second on the toolbar handler. Each
    // tick updates the body text; at zero it advances to backgrounding the app.
    // Bails safely if the dialog was dismissed/replaced meanwhile (e.g. the user
    // navigated away), so we never background the app after a cancel.
    private void startLockTutorialCountdown(View overlay, TextView bodyView, int secondsLeft) {
        if (mToolbarRoot == null || mCurrentLockPhoneDialog != overlay) return;
        if (secondsLeft <= 0) {
            goHomeForBackgroundPlay();
            return;
        }
        try {
            bodyView.setText(bodyView.getContext().getString(
                    R.string.pip_lock_phone_tutorial_body, secondsLeft));
        } catch (Exception ignored) {
        }
        mToolbarRoot.postDelayed(
                () -> startLockTutorialCountdown(overlay, bodyView, secondsLeft - 1), 1000);
    }

    // Step 1 finale: dismiss the dialog and send the app to the background so
    // the video keeps playing. Persist a flag + arm a lifecycle listener so
    // step 2 ("Congratulations") is shown when the user returns to the app.
    // moveTaskToBack (not finish) keeps the activity + renderer alive so the
    // media session keeps playing and the return can be detected.
    private void goHomeForBackgroundPlay() {
        dismissLockPhoneTutorialDialog();

        final Activity activity;
        try {
            activity = BraveActivity.getBraveActivity();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (activity == null) return;

        ContextUtils.getAppSharedPreferences()
                .edit()
                .putBoolean(PREF_PIP_AWAITING_CONGRATS, true)
                .apply();
        armCongratsReturnListener(activity);
        // Countdown reached zero and we're about to background the app — the
        // user followed the tutorial through step 1. Fire before moveTaskToBack
        // (PostHogWorkerTask persists to disk first, so it survives the freeze).
        firePostHogEvent(PostHogEventKeys.YT_BG_PLAY_TUTORIAL_BACKGROUNDED, null);
        activity.moveTaskToBack(true);
    }

    // Watches the activity: once it has been stopped (backgrounded) and then
    // resumes, show the congratulations dialog. Registered only during the
    // brief tutorial-background window and unregistered as soon as it fires.
    private void armCongratsReturnListener(Activity activity) {
        if (mCongratsReturnListener != null) return;
        mCongratsSawStop = false;
        mCongratsReturnListener =
                (act, newState) -> {
                    if (newState == ActivityState.PAUSED
                            || newState == ActivityState.STOPPED) {
                        mCongratsSawStop = true;
                    } else if (newState == ActivityState.RESUMED && mCongratsSawStop) {
                        disarmCongratsReturnListener();
                        showCongratsDialogIfAwaiting();
                    } else if (newState == ActivityState.DESTROYED) {
                        disarmCongratsReturnListener();
                    }
                };
        ApplicationStatus.registerStateListenerForActivity(mCongratsReturnListener, activity);
    }

    private void disarmCongratsReturnListener() {
        if (mCongratsReturnListener != null) {
            ApplicationStatus.unregisterActivityStateListener(mCongratsReturnListener);
            mCongratsReturnListener = null;
        }
        mCongratsSawStop = false;
    }

    // Shows step 2 iff we are still awaiting it. The pref is the single source
    // of truth so the in-memory listener and the process-death backstop (posted
    // from the constructor) can't double-show — whichever runs first clears it.
    private void showCongratsDialogIfAwaiting() {
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        if (!prefs.getBoolean(PREF_PIP_AWAITING_CONGRATS, false)) return;
        prefs.edit().putBoolean(PREF_PIP_AWAITING_CONGRATS, false).apply();
        if (mToolbarRoot != null) {
            mToolbarRoot.postDelayed(this::showCongratsDialog, 300);
        } else {
            showCongratsDialog();
        }
    }

    // Step 2: centered "Congratulations" card with the body text + a gold play
    // icon, dismissible only via the Close button (outside/card taps absorbed).
    private void showCongratsDialog() {
        if (mCurrentCongratsDialog != null) return;

        final ViewGroup root;
        try {
            root = (ViewGroup) BraveActivity.getBraveActivity()
                    .findViewById(android.R.id.content);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (root == null) return;

        final float density = root.getResources().getDisplayMetrics().density;
        final int dp16 = (int) (16 * density);
        final int dp20 = (int) (20 * density);
        final int dp32 = (int) (32 * density);

        FrameLayout overlay = new FrameLayout(root.getContext());
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        FrameLayout card = new FrameLayout(root.getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(root.getContext().getColor(R.color.browser_express_coach_mark_background));
        bg.setCornerRadius(16f * density);
        bg.setStroke((int) Math.max(1, density), 0x55D4AF37);
        card.setBackground(bg);
        card.setElevation(10f * density);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout content = new LinearLayout(root.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp20, dp16, dp20, dp16);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(root.getContext());
        titleView.setText(root.getContext().getString(R.string.pip_congrats_title));
        titleView.setTextColor(root.getContext().getColor(R.color.browser_express_gold));
        titleView.setTextSize(18f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);

        // Gold play icon sits on its own centered row above the title. Keeping it
        // out of the body row lets the body text take the card's full width and
        // wrap instead of being squeezed/clipped by the icon on narrow screens.
        ImageView playIcon = new ImageView(root.getContext());
        playIcon.setImageResource(R.drawable.ic_play_arrow_white_24dp);
        playIcon.setColorFilter(root.getContext().getColor(R.color.browser_express_gold));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (28 * density), (int) (28 * density));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.bottomMargin = (int) (6 * density);

        TextView bodyView = new TextView(root.getContext());
        bodyView.setText(root.getContext().getString(R.string.pip_congrats_body));
        bodyView.setTextColor(root.getContext().getColor(R.color.browser_express_text_primary));
        bodyView.setTextSize(14f);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setLineSpacing(2f * density, 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) (10 * density);

        TextView closeButton = new TextView(root.getContext());
        closeButton.setText(
                root.getContext().getString(R.string.pip_lock_phone_tutorial_close));
        closeButton.setAllCaps(true);
        closeButton.setTextColor(root.getContext().getColor(R.color.browser_express_gold));
        closeButton.setTextSize(13f);
        closeButton.setTypeface(null, android.graphics.Typeface.BOLD);
        closeButton.setGravity(Gravity.CENTER);
        int closePadV = (int) (8 * density);
        int closePadH = (int) (22 * density);
        closeButton.setPadding(closePadH, closePadV, closePadH, closePadV);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0x22D4AF37);
        closeBg.setCornerRadius(999f * density);
        closeBg.setStroke((int) Math.max(1, density), 0x55D4AF37);
        closeButton.setBackground(closeBg);
        closeButton.setClickable(true);
        closeButton.setFocusable(true);
        closeButton.setOnClickListener(v -> dismissCongratsDialog());

        content.addView(playIcon, iconLp);
        content.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(bodyView, bodyLp);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.CENTER_HORIZONTAL;
        closeLp.topMargin = (int) (18 * density);
        content.addView(closeButton, closeLp);

        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Card fills the width between the margins so the body text wraps
        // inside it rather than being measured wider than the screen.
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp32;
        cardLp.rightMargin = dp32;
        overlay.addView(card, cardLp);

        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(overlay, overlayLp);
        mCurrentCongratsDialog = overlay;

        // Step 2 is on screen — the user backgrounded and came back, so
        // background play worked for them.
        firePostHogEvent(PostHogEventKeys.YT_BG_PLAY_TUTORIAL_CONGRATS_SHOWN, null);

        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private void dismissCongratsDialog() {
        if (mCurrentCongratsDialog == null) return;
        // Only the Close button reaches here (outside/card taps are absorbed), so
        // this is the terminal "user finished the tutorial" step.
        firePostHogEvent(PostHogEventKeys.YT_BG_PLAY_TUTORIAL_COMPLETED, null);
        View dialog = mCurrentCongratsDialog;
        mCurrentCongratsDialog = null;
        dialog.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) dialog.getParent();
                    if (parent != null) parent.removeView(dialog);
                })
                .start();
    }

    /**
     * Shows a centered "minimize the app" nudge styled to match the tutorial
     * card (dark navy card with a gold border, gold title, white
     * body). Only shown the first {@link #MAX_BG_PLAY_NUDGE_COUNT} times PIP
     * is triggered.
     */
    private void maybeShowBgPlayNudge() {
        if (!ENABLE_BG_PLAY_NUDGE) return;
        SharedPreferences prefs = ContextUtils.getAppSharedPreferences();
        int count = prefs.getInt(PREF_PIP_BG_PLAY_NUDGE_COUNT, 0);
        if (count >= MAX_BG_PLAY_NUDGE_COUNT) return;
        prefs.edit().putInt(PREF_PIP_BG_PLAY_NUDGE_COUNT, count + 1).apply();

        mToolbarRoot.postDelayed(this::showBgPlayBrandedToast, 1500);
    }

    /**
     * Builds and shows the branded center-screen toast. Card matches the
     * lock-phone tutorial style so the user perceives one continuous tutorial.
     */
    private void showBgPlayBrandedToast() {
        final ViewGroup root;
        try {
            root = (ViewGroup) BraveActivity.getBraveActivity()
                    .findViewById(android.R.id.content);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (root == null) return;

        final float density = root.getResources().getDisplayMetrics().density;
        final int dp16 = (int) (16 * density);
        final int dp20 = (int) (20 * density);
        final int dp32 = (int) (32 * density);

        // Outer card: themed surface with gold border, rounded.
        FrameLayout card = new FrameLayout(root.getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(root.getContext().getColor(R.color.browser_express_coach_mark_background));
        bg.setCornerRadius(16f * density);
        bg.setStroke((int) Math.max(1, density), 0x55D4AF37);
        card.setBackground(bg);
        card.setElevation(8f * density);

        LinearLayout content = new LinearLayout(root.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp20, dp16, dp20, dp16);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(root.getContext());
        titleView.setText(root.getContext().getString(R.string.pip_bg_play_nudge_title));
        titleView.setTextColor(root.getContext().getColor(R.color.browser_express_gold));
        titleView.setTextSize(16f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);

        TextView bodyView = new TextView(root.getContext());
        bodyView.setText(root.getContext().getString(R.string.pip_bg_play_nudge_body));
        bodyView.setTextColor(root.getContext().getColor(R.color.browser_express_text_primary));
        bodyView.setTextSize(13f);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setLineSpacing(2f * density, 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = (int) (6 * density);

        content.addView(titleView);
        content.addView(bodyView, bodyLp);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = dp32;
        lp.rightMargin = dp32;
        card.setLayoutParams(lp);
        // Decorative \u2014 should never block the user from interacting with PiP.
        card.setClickable(false);
        card.setFocusable(false);

        root.addView(card);

        // Animate in: fade + slight scale-up. Auto-dismiss after a beat.
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .start();

        card.postDelayed(() -> {
            card.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        ViewGroup parent = (ViewGroup) card.getParent();
                        if (parent != null) parent.removeView(card);
                    })
                    .start();
        }, 2800);
    }

    /**
     * Acquires or releases a persistent browser-controls-shown token based on whether
     * the current URL is a YouTube page. While the token is held, both the top and
     * bottom toolbars stay visible regardless of scroll — this is what makes the
     * bottom toolbar "stick" on YouTube. The actual show/hide of the bottom toolbar
     * on non-YouTube pages is handled upstream by BraveToolbarManager via
     * setBottomToolbarVisible(), so there's nothing to animate here.
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
        BraveActivity activity = braveActivityOf(mToolbarRoot);
        if (activity != null) {
            activity.setGoldenPipHandler(null);
            activity.setPageCommentCountSink(null);
        }
        disarmCongratsReturnListener();
        // Cancel any in-flight step-1 countdown by clearing its target so the
        // next pending tick bails instead of backgrounding a torn-down activity.
        mCurrentLockPhoneDialog = null;
        mCurrentCongratsDialog = null;
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
                public void getFirstCommentsSuccessful(
                        JSONArray comments, int commentCount, @Nullable JSONObject pageReactions) {
                    setCommentCount(commentCount);
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
     * Shows up to 2 comment preview cards that stack from bottom to top.
     * Cards enter one after another with equal spacing, hold together, then
     * vanish in the same order: first card disappears, then second.
     */
    private void showYouTubeCommentsPreview(List<Comment> comments) {
        // Cancel pending handler callbacks from a previous invocation so stale
        // runnables don't fire and cause the first card to "pop" multiple times.
        if (mPreviewHandler != null) {
            mPreviewHandler.removeCallbacksAndMessages(null);
        }

        // Cancel and remove any existing overlays
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
        int screenHeight = metrics.heightPixels;
        float offScreen = screenHeight * 0.4f;

        // On newer phones with gesture navigation / edge-to-edge display the
        // content view extends behind the navigation bar, so using only the
        // toolbar dimen underestimates the margin.  Measure the real toolbar
        // position to compute the correct gap.
        int cardBottomMargin = bottomToolbarHeight + (int) (8 * density);
        if (mToolbarRoot.isLaidOut() && contentView.isLaidOut()) {
            int[] toolbarLoc = new int[2];
            mToolbarRoot.getLocationOnScreen(toolbarLoc);
            int[] contentLoc = new int[2];
            contentView.getLocationOnScreen(contentLoc);
            int contentViewBottom = contentLoc[1] + contentView.getHeight();
            cardBottomMargin = contentViewBottom - toolbarLoc[1] + (int) (8 * density);
        }

        // Build all card views up front so Glide can start loading avatars immediately
        LayoutInflater inflater = LayoutInflater.from(mToolbarRoot.getContext());
        int limit = Math.min(comments.size(), 2);
        List<View> cards = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            Comment comment = comments.get(i);
            View card = inflater.inflate(R.layout.youtube_comment_preview, contentView, false);

            if (comment.getUser() != null) {
                ((TextView) card.findViewById(R.id.preview_username))
                        .setText(comment.getUser().getUsername());
                ProfileAvatar.load(card.findViewById(R.id.preview_avatar),
                        comment.getUser().getAvatar(), comment.getUser().getId());
            }
            ((TextView) card.findViewById(R.id.preview_content)).setText(comment.getContent());

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.BOTTOM;
            params.bottomMargin = cardBottomMargin;
            params.setMarginStart((int) (12 * density));
            params.setMarginEnd((int) (12 * density));
            card.setLayoutParams(params);
            card.setTranslationY(offScreen);
            card.setAlpha(0f);

            cards.add(card);
            contentView.addView(card);
        }

        mStatsOverlays = new View[limit];
        for (int i = 0; i < limit; i++) {
            mStatsOverlays[i] = cards.get(i);
        }

        View[] overlayRef = mStatsOverlays;
        Handler handler = new Handler(Looper.getMainLooper());
        mPreviewHandler = handler;

        int gap = (int) (8 * density);

        long entranceInterval = 400;
        long holdDuration = 3000;
        long exitInterval = 400;

        // Wait for cards to be measured so we know their actual heights, then
        // compute positions dynamically. Each card sits above the previous one
        // with a uniform gap — the same gap used between the toolbar and the
        // bottom card — so spacing looks consistent on every screen size.
        cards.get(0).post(() -> {
            if (mStatsOverlays != overlayRef) return;

            // Compute translationY targets based on measured card heights.
            // Cards are initially positioned with bottomMargin = toolbarHeight + gap
            // (i.e. the bottom card's resting spot). Upper cards shift up by
            // (cardHeight + gap) for each position above.
            float[] targetY = new float[limit];
            targetY[limit - 1] = 0f;  // bottom card stays at its natural position
            for (int i = limit - 2; i >= 0; i--) {
                int cardBelow = i + 1;
                targetY[i] = targetY[cardBelow]
                        - cards.get(cardBelow).getMeasuredHeight() - gap;
            }

            // Stagger entrance: card 0 (top) rises first, then card 1 (bottom)
            for (int i = 0; i < limit; i++) {
                final View card = cards.get(i);
                final float dest = targetY[i];
                long delay = (long) i * entranceInterval;

                handler.postDelayed(() -> {
                    if (mStatsOverlays != overlayRef) return;
                    card.animate()
                            .translationY(dest)
                            .alpha(1f)
                            .setDuration(500)
                            .setInterpolator(new OvershootInterpolator(0.8f))
                            .start();
                }, delay);
            }

            // Time when the last card finishes its entrance animation
            long allVisibleAt = (long) (limit - 1) * entranceInterval + 500;

            // Stagger exit: bottom card goes down first, then top.
            for (int i = limit - 1; i >= 0; i--) {
                final View card = cards.get(i);
                final int cardIndex = i;
                long exitDelay = allVisibleAt + holdDuration
                        + (long) (limit - 1 - i) * exitInterval;

                handler.postDelayed(() -> {
                    if (mStatsOverlays != overlayRef) return;
                    card.animate()
                            .translationY(offScreen)
                            .alpha(0f)
                            .setDuration(350)
                            .setInterpolator(new AccelerateInterpolator())
                            .withEndAction(() -> {
                                ViewGroup p = (ViewGroup) card.getParent();
                                if (p != null) p.removeView(card);
                                if (cardIndex == 0 && mStatsOverlays == overlayRef) {
                                    mStatsOverlays = null;
                                    mPreviewHandler = null;
                                }
                            })
                            .start();
                }, exitDelay);
            }
        });
    }


    /**
     * Shows {@code count} on the floating bar: a count badge and the icon's wiggle once the page
     * has comments, a "+" badge while it has none.
     */
    private void setCommentCount(int count) {
        if (mCommentCountAnimator != null) {
            mCommentCountAnimator.cancel();
            mCommentCountAnimator = null;
        }
        mCommentCount = count;
        boolean hasComments = count > 0;
        mCommentCountBadge.setVisibility(hasComments ? View.VISIBLE : View.GONE);
        mCommentsJoin.setVisibility(hasComments ? View.GONE : View.VISIBLE);
        mCommentsIconMotion.setEnabled(hasComments);
        mCommentsButton.setContentDescription(
                hasComments
                        ? mCommentsButton
                                .getResources()
                                .getQuantityString(
                                        R.plurals.browser_express_comments_bar_count, count, count)
                        : mCommentsButton
                                .getResources()
                                .getString(R.string.browser_express_comments_bar_join_description));
        showCountOnBadge(count);
    }

    /** The badge's text: the count, shortened past 999 ("1.2K") to fit the 16dp badge. */
    private void showCountOnBadge(int count) {
        mCommentCountBadge.setText(
                count < 1000
                        ? String.valueOf(count)
                        : CompactDecimalFormat.getInstance(
                                        Locale.getDefault(),
                                        CompactDecimalFormat.CompactStyle.SHORT)
                                .format(count));
    }

    /**
     * Refetches the bar's count for the current page, e.g. after the comments sheet closes.
     * YouTube watch pages are skipped: their count comes from YouTube.
     */
    private void refreshPageEngagement() {
        Tab tab = mTabProvider.get();
        if (tab == null || tab.getUrl() == null || mCurrentPreviewVideoId != null) return;
        String url = tab.getUrl().getSpec();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        new BrowserExpressGetFirstCommentsUtil.GetFirstCommentsWorkerTask(
                        url, getFirstCommentsCallback)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Animates the bar's count up from 0 to {@code targetCount}. */
    private void animateCommentCount(long targetCount) {
        int target = (int) Math.min(targetCount, Integer.MAX_VALUE);
        setCommentCount(target);
        if (target <= 0) return;

        ValueAnimator animator = ValueAnimator.ofInt(0, target);
        mCommentCountAnimator = animator;
        animator.setDuration(7000);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        // Only the badge's number counts up; the description already reads the final count.
        animator.addUpdateListener(a -> showCountOnBadge((int) a.getAnimatedValue()));
        animator.start();
    }

    private void updateCommentCountForUrl(String url) {
        if (url == null || url.isEmpty()) {
            mCurrentPreviewVideoId = null;
            mLastFetchedPreviewVideoId = null;
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            mCurrentPreviewVideoId = null;
            mLastFetchedPreviewVideoId = null;
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

                // Skip if we already started a fetch for this exact video — prevents
                // duplicate preview animations when multiple tab callbacks
                // (onPageLoadFinished, onLoadStopped, onUrlUpdated) fire for the
                // same page load or when in-page popups trigger onUrlUpdated.
                if (finalVideoId.equals(mLastFetchedPreviewVideoId)) return;
                mLastFetchedPreviewVideoId = finalVideoId;

                // Fetch top comments for the preview animation
                new YouTubeCommentsUtil.GetYouTubeFirstCommentsTask(
                        finalVideoId,
                        new YouTubeCommentsUtil.GetYouTubeFirstCommentsCallback() {
                            @Override
                            public void onSuccess(List<Comment> comments, long ignored) {
                                if (!finalVideoId.equals(mCurrentPreviewVideoId)) return;
                                if (mPipIntroActiveOnCurrentPage) return;
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
     * Fires a PostHog analytics event for the signed-in user, else this install.
     * @param eventKey Event name constant from {@link PostHogEventKeys}.
     * @param extraProps Optional extra properties to include (may be null).
     */
    private void firePostHogEvent(String eventKey, JSONObject extraProps) {
        PostHogUtil.capture(eventKey, extraProps);
    }

    /** A web page shown in a regular tab, by its domain (and search terms on a results page). */
    private void firePageVisitedPostHog(Tab tab) {
        if (tab == null || tab.getUrl() == null || tab.isIncognito()) return;
        String spec = tab.getUrl().getSpec();
        if (spec == null || spec.isEmpty()) return;
        if (tab.isNativePage()) return;
        String scheme = tab.getUrl().getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return;
        if (spec.equals(mLastPostHogPageUrl)) return;
        mLastPostHogPageUrl = spec;
        PostHogUtil.capture(PostHogEventKeys.PAGE_VISITED, PostHogUtil.pageProperties(tab.getUrl()));
    }
}
