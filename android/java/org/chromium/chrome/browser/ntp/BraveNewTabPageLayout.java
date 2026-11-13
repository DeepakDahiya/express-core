/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.base.ThreadUtils.runOnUiThread;
import static org.chromium.ui.base.ViewUtils.dpToPx;
import org.chromium.ui.base.ViewUtils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.base.MathUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.airbnb.lottie.LottieAnimationView;
import com.bumptech.glide.Glide;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.Supplier;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.CardType;
import org.chromium.brave_news.mojom.DisplayAd;
import org.chromium.brave_news.mojom.Feed;
import org.chromium.brave_news.mojom.FeedItem;
import org.chromium.brave_news.mojom.FeedPage;
import org.chromium.brave_news.mojom.FeedPageItem;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_news.BraveNewsControllerFactory;
import org.chromium.chrome.browser.brave_news.BraveNewsUtils;
import org.chromium.chrome.browser.brave_news.CardBuilderFeedCard;
import org.chromium.chrome.browser.brave_news.LinearLayoutManagerWrapper;
import org.chromium.chrome.browser.brave_news.models.FeedItemCard;
import org.chromium.chrome.browser.brave_news.models.FeedItemsCard;
import org.chromium.chrome.browser.brave_stats.BraveStatsUtil;
import org.chromium.chrome.browser.feed.FeedSurfaceScrollDelegate;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.local_database.DatabaseHelper;
import org.chromium.chrome.browser.local_database.TopSiteTable;
import org.chromium.chrome.browser.logo.LogoCoordinator;
import org.chromium.chrome.browser.ntp_background_images.NTPBackgroundImagesBridge;
import org.chromium.chrome.browser.ntp_background_images.model.NTPImage;
import org.chromium.chrome.browser.ntp_background_images.model.SponsoredTab;
import org.chromium.chrome.browser.ntp_background_images.model.TopSite;
import org.chromium.chrome.browser.ntp_background_images.model.Wallpaper;
import org.chromium.chrome.browser.ntp_background_images.util.FetchWallpaperWorkerTask;
import org.chromium.chrome.browser.ntp_background_images.util.NTPImageUtil;
import org.chromium.chrome.browser.ntp_background_images.util.NewTabPageListener;
import org.chromium.chrome.browser.ntp_background_images.util.SponsoredImageUtil;
import org.chromium.chrome.browser.offlinepages.DownloadUiActionFlags;
import org.chromium.chrome.browser.offlinepages.OfflinePageBridge;
import org.chromium.chrome.browser.offlinepages.RequestCoordinatorBridge;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.rate.RateUtils;
import org.chromium.chrome.browser.settings.BackgroundImagesPreferences;
import org.chromium.chrome.browser.settings.BraveNewsPreferencesV2;
import org.chromium.chrome.browser.settings.BrowserExpressGuestLogin;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.chrome.browser.suggestions.tile.BraveMostVisitedTilesLayoutBase;
import org.chromium.chrome.browser.suggestions.tile.TileGroup.Delegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabAttributes;
import org.chromium.chrome.browser.ui.native_page.TouchEnabledDelegate;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.components.browser_ui.settings.SettingsNavigation;
import org.chromium.components.browser_ui.widget.displaystyle.UiConfig;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.url.GURL;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Locale;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.util.TypedValue;
import java.io.File;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.util.Random;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import org.chromium.ui.widget.Toast;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;
import org.chromium.chrome.browser.settings.BrowserExpressGetProfilePreferencesUtil;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.json.JSONException;
import org.json.JSONObject;
import android.widget.ImageButton;
import java.io.UnsupportedEncodingException;
import android.util.Base64;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;

@SuppressWarnings("UseSharedPreferencesManagerFromChromeCheck")
public class BraveNewTabPageLayout extends NewTabPageLayout
        implements ConnectionErrorHandler, OnBraveNtpListener {
    private static final String TAG = "BraveNewTabPage";
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";

    /** Posts asked for per feed page. */
    private static final int FEED_PAGE_SIZE = 20;

    /** The next feed page starts loading once the reader is this many posts from the end. */
    private static final int FEED_PREFETCH_POSTS = 5;

    // private static final int MINIMUM_VISIBLE_HEIGHT_THRESHOLD = 50;
    // private static final int HOUR_MS = 3_600_000;

    // To be removed in bytecode, parent variable will be used instead.
    private ViewGroup mMvTilesContainerLayout;

    @SuppressWarnings("UnusedVariable")
    private LogoCoordinator mLogoCoordinator;

    private Integer mInitialTileNum;

    // Own members.
    // private WindowAndroid mWindowAndroid;

    private ImageView mBgImageView;
    // private SponsoredRichMediaWebView mSponsoredRichMediaWebView;
    // private FrameLayout mBackgroundSponsoredRichMediaView;

    // To be removed in bytecode, parent variable will be used instead.
    private Profile mProfile;
    private SponsoredTab mSponsoredTab;
    private boolean mIsTablet;

    private BitmapDrawable mImageDrawable;

    private FetchWallpaperWorkerTask mWorkerTask;
    private boolean mIsFromBottomSheet;
    private NTPBackgroundImagesBridge mNTPBackgroundImagesBridge;
    private ViewGroup mMainLayout;
    private final DatabaseHelper mDatabaseHelper;

    private LottieAnimationView mBadgeAnimationView;

    private Tab mTab;
    private Activity mActivity;
    private LinearLayout mSuperReferralSitesLayout;

    private BraveNtpAdapter mNtpAdapter;
    private Bitmap mSponsoredLogo;
    private Wallpaper mWallpaper;

    private BraveNewTabTakeoverInfobar mNewTabTakeoverInfobar;

    private CopyOnWriteArrayList<FeedItemsCard> mNewsItemsFeedCard =
            new CopyOnWriteArrayList<FeedItemsCard>();
    private RecyclerView mRecyclerView;

    // The feed header's search box stands in for Chrome's fake search box: the toolbar keeps its
    // location bar out of sight while the box is on screen and grows it out of the box when it is
    // tapped or scrolled under the toolbar (see BraveNewTabPage#isLocationBarShownInNtp).
    private NewTabPage.OnSearchBoxScrollListener mHeaderSearchBoxScrollListener;
    private float mUrlFocusPercent;
    private float mHeaderSearchBoxAlpha = 1f;
    private LinearLayout mNewsSettingsBar;
    private LinearLayout mNewContentLayout;
    private TextView mNewContentText;
    private ProgressBar mNewContentProgressBar;
    private PostListAdapter mPostAdapter;
    /**
     * The feed so far. It outlives the adapter: the page builds a fresh adapter each time it is
     * shown again (see onDetachedFromWindow), over the same posts.
     */
    private List<Post> mPosts;
    /** The ids in {@link #mPosts}. A page can repeat posts an earlier page had. */
    private final Set<String> mPostIds = new HashSet<>();
    private boolean mPostsRequestInFlight;
    /** Whether the feed's first page has loaded. */
    private boolean mPostsLoaded;
    /** Asks for the page after the last one loaded; null before the first page and after the last. */
    @Nullable private String mNextPostsCursor;
    /** Whether the last feed request failed; the feed's last row then offers to try again. */
    private boolean mPostsLoadFailed;
    private boolean mLoadMoreCheckPending;
    private String mPendingPostsRetryAccessToken;
    private static boolean sNtpClaimUsernameInFlight;
    private static boolean sNtpManualAuthOpened;

    private NTPImage mNtpImageGlobal;
    private BraveNewsController mBraveNewsController;

    // private long mStartCardViewTime;
    // private long mEndCardViewTime;
    // private String mCreativeInstanceId;
    // private String mUuid;
    //@TODO alex make an enum
    // private String mCardType;
    // private int mItemPosition;
    private int mPrevVisibleNewsCardPosition = -1;
    private int mNewsSessionCardViews;
    // private FeedItemsCard mVisibleCard;
    private String mFeedHash;
    private SharedPreferences.OnSharedPreferenceChangeListener mPreferenceListener;
    private boolean mIsTopSitesEnabled;
    private boolean mIsBraveStatsEnabled;
    private boolean mIsDisplayNewsFeed;
    private boolean mIsDisplayNewsOptin;
    private long mNewsFeedLastViewTime;
    // private ShimmerFrameLayout mShimmerLoading;
    // private ViewGroup mShimmerItems;

    private Supplier<Tab> mTabProvider;

    private ImageButton mProfileButton;

    private static final int SHOW_BRAVE_RATE_ENTRY_AT = 10; // 10th row

    public BraveNewTabPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        mDatabaseHelper = DatabaseHelper.getInstance();
    }

    protected void updateTileGridPlaceholderVisibility() {
        // This function is kept empty to avoid placeholder implementation
    }

    private boolean shouldShowSuperReferral() {
        return mNTPBackgroundImagesBridge.isSuperReferral()
                && NTPBackgroundImagesBridge.enableSponsoredImages()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    @Override
    public void checkForBraveStats() {
        if (OnboardingPrefManager.getInstance().isBraveStatsEnabled()) {
            BraveStatsUtil.showBraveStats();
        } else {
            ((BraveActivity) mActivity).showOnboardingV2(false);
        }
    }

    protected void initializeSiteSectionView() {
        mMainLayout = findViewById(R.id.ntp_content);
        mMvTilesContainerLayout =
                (ViewGroup)
                        LayoutInflater.from(mMainLayout.getContext())
                                .inflate(R.layout.mv_tiles_layout, mMainLayout, false);
        mMvTilesContainerLayout.setId(R.id.mv_tiles_container);
        mMvTilesContainerLayout.setPadding(0, 0, 0, 0);
        mMvTilesContainerLayout.setVisibility(View.VISIBLE);

        mMvTilesContainerLayout.post(
                new Runnable() {
                    @Override
                    public void run() {
                        mMvTilesContainerLayout.addOnLayoutChangeListener(
                                (View view,
                                        int left,
                                        int top,
                                        int right,
                                        int bottom,
                                        int oldLeft,
                                        int oldTop,
                                        int oldRight,
                                        int oldBottom) -> {
                                    int oldHeight = oldBottom - oldTop;
                                    int newHeight = bottom - top;

                                    if (oldHeight != newHeight
                                            && mIsTopSitesEnabled
                                            && mNtpAdapter != null) {
                                        new Handler(Looper.getMainLooper())
                                                .post(
                                                        () -> {
                                                            mNtpAdapter.notifyItemRangeChanged(
                                                                    mNtpAdapter.getStatsCount(),
                                                                    mNtpAdapter.getNewContentCount()
                                                                            + 2);
                                                        });
                                    }
                                });
                    }
                });

        // The page contents are initially hidden; otherwise they'll be drawn centered on the
        // page before the tiles are available and then jump upwards to make space once the
        // tiles are available.
        if (getVisibility() != View.VISIBLE) setVisibility(View.VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mSponsoredTab == null) {
            initilizeSponsoredTab();
        }
        // checkAndShowNTPImage(false);
        mNTPBackgroundImagesBridge.addObserver(mNTPBackgroundImageServiceObserver);
        BrowserExpressGuestLogin.addObserver(mGuestLoginObserver);

        if (OnboardingPrefManager.getInstance().isFromNotification() ) {
            ((BraveActivity)mActivity).showOnboardingV2(false);
            OnboardingPrefManager.getInstance().setFromNotification(false);
        }
        if (mBadgeAnimationView != null
                && !OnboardingPrefManager.getInstance().shouldShowBadgeAnimation()) {
            mBadgeAnimationView.setVisibility(View.INVISIBLE);
        }

        mIsDisplayNewsOptin = false;
        mIsDisplayNewsFeed = false;

        initPreferenceObserver();
        if (mPreferenceListener != null) {
            ContextUtils.getAppSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(mPreferenceListener);
        }
        setNtpViews();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setNtpViews() {
        try {
            mRecyclerView = findViewById(R.id.recycler_posts);
            
            if (mPosts == null) mPosts = new ArrayList<Post>();
            // Posts hidden last time showed "Post hidden · Undo" in their place; shown again, the
            // page leaves them out (their ids stay in mPostIds, so they don't come back).
            mPosts.removeIf(post -> HiddenFeedPosts.isHidden(post.getId()));

            // YouTube (first when present) and the newest other sites.
            List<TopSiteTable> topSites = mDatabaseHelper.getTopSitesForDisplay(4);
            
            mRecyclerView.setLayoutManager(new LinearLayoutManager(mActivity));

            mRecyclerView.setHasFixedSize(false);
            mRecyclerView.setItemViewCacheSize(10);

            mPostAdapter = new PostListAdapter(mActivity, mPosts, mRecyclerView, topSites, this);
            mPostAdapter.setFeedRetryAction(this::retryPosts);
            // Shown again: the posts loaded before are still here, so no skeleton.
            if (mPostsLoaded) mPostAdapter.setLoading(false);
            applyFeedFooter();
            
            mRecyclerView.setAdapter(mPostAdapter);
            mRecyclerView.addOnScrollListener(
                    new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(
                                @NonNull RecyclerView recyclerView, int dx, int dy) {
                            notifyHeaderSearchBoxScrolled();
                            scheduleLoadMoreCheck();
                        }
                    });
            mRecyclerView.addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            notifyHeaderSearchBoxScrolled());

            mMainLayout = findViewById(R.id.ntp_content);
            
            if (mMainLayout != null) {
                mMainLayout.setBackgroundColor(
                        ContextCompat.getColor(mActivity, R.color.browser_express_feed_background));
            }

            String accessToken = ((BraveActivity)mActivity).getAccessToken();
            loadPostsAndProfile(accessToken);
            if(accessToken == null){
                startClaimUsernameIfNeeded();
            }
            
            // Fetch tutorial video config from remote
            TutorialVideoUtil.fetch((enabled, videoUrl, imageUrl) -> {
                if (enabled && mPostAdapter != null
                        && ((videoUrl != null && !videoUrl.isEmpty())
                                || (imageUrl != null && !imageUrl.isEmpty()))) {
                    mPostAdapter.setTutorialMedia(videoUrl, imageUrl);
                }
            });
        } catch (Exception e) {
            throw e; // Re-throw to see the original crash
        }
    }

    private void loadPostsAndProfile(String accessToken) {
        loadPosts(accessToken);
        fetchAndUpdateProfileImage();
    }

    /** Loads the feed's first page, under the header's skeleton. */
    private void loadPosts(String accessToken) {
        if (mPostsRequestInFlight || mPostsLoaded) return;
        if (mPostAdapter != null) {
            mPostAdapter.setLoading(true);
            mPostAdapter.showShimmer();
        }
        requestPosts(null, accessToken);
    }

    /** Loads the feed page {@code cursor} names, or the first page when it is null. */
    private void requestPosts(@Nullable String cursor, String accessToken) {
        mPostsRequestInFlight = true;
        mPostsLoadFailed = false;
        applyFeedFooter();
        BrowserExpressGetPostsUtil.GetPostsWorkerTask workerTask =
                new BrowserExpressGetPostsUtil.GetPostsWorkerTask(
                        cursor, FEED_PAGE_SIZE, accessToken, getPostsCallback);
        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** The error row's action: asks again for the page that failed. */
    private void retryPosts() {
        if (mPostsRequestInFlight) return;
        if (!mPostsLoaded) {
            loadPosts(getCurrentAccessToken());
        } else if (mNextPostsCursor != null) {
            requestPosts(mNextPostsCursor, getCurrentAccessToken());
        }
    }

    /**
     * Looks, after this frame, whether the reader is near enough the end of the feed for the next
     * page. Deferred because scroll callbacks can run inside a layout pass, when the adapter must
     * not change.
     */
    private void scheduleLoadMoreCheck() {
        if (mLoadMoreCheckPending || mRecyclerView == null) return;
        mLoadMoreCheckPending = true;
        mRecyclerView.post(
                () -> {
                    mLoadMoreCheckPending = false;
                    maybeLoadMorePosts();
                });
    }

    /**
     * Loads the next feed page once the reader is within {@link #FEED_PREFETCH_POSTS} posts of the
     * end. A failed page waits for the reader to tap the retry row.
     */
    private void maybeLoadMorePosts() {
        if (!mPostsLoaded || mPostsRequestInFlight || mPostsLoadFailed || mNextPostsCursor == null) {
            return;
        }
        if (mPostAdapter == null || mRecyclerView == null || !mRecyclerView.isAttachedToWindow()) {
            return;
        }
        if (!(mRecyclerView.getLayoutManager() instanceof LinearLayoutManager)) return;
        int lastVisible =
                ((LinearLayoutManager) mRecyclerView.getLayoutManager())
                        .findLastVisibleItemPosition();
        if (lastVisible == RecyclerView.NO_POSITION) return;
        int lastPost = mPostAdapter.getPostAdapterOffset() + mPosts.size() - 1;
        if (lastVisible < lastPost - FEED_PREFETCH_POSTS) return;
        requestPosts(mNextPostsCursor, getCurrentAccessToken());
    }

    /** The feed's last row: a spinner while a later page loads, a retry after a failure. */
    private void applyFeedFooter() {
        if (mPostAdapter == null) return;
        int state = PostListAdapter.FOOTER_NONE;
        if (mPostsLoadFailed) {
            state = PostListAdapter.FOOTER_ERROR;
        } else if (mPostsRequestInFlight && mPostsLoaded) {
            // The first page loads under the header's skeleton instead.
            state = PostListAdapter.FOOTER_LOADING;
        }
        mPostAdapter.setFooterState(state);
    }

    private void startClaimUsernameIfNeeded() {
        String accessToken = getCurrentAccessToken();
        if (accessToken != null) {
            sNtpClaimUsernameInFlight = false;
            sNtpManualAuthOpened = false;
            loadPostsAndProfile(accessToken);
            return;
        }

        if (BrowserExpressGuestLogin.isPending()) {
            // The user closed the sign-in screen, so it stays closed. The guest session closing it
            // asked for reaches the page through mGuestLoginObserver.
            BrowserExpressGuestLogin.resumeIfPending();
            loadPosts(null);
            return;
        }
        if (BrowserExpressClaimUsernameUtil.shouldRequireManualAuth()) {
            if (!sNtpManualAuthOpened) {
                sNtpManualAuthOpened = true;
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    activity.openBrowserExpressSignupSettings(true);
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(
                            TAG,
                            "Unable to open Browser Express signup: " + e.getMessage());
                }
            }
            loadPosts(null);
            return;
        }
        if (sNtpClaimUsernameInFlight) return;
        sNtpClaimUsernameInFlight = true;
        BrowserExpressClaimUsernameUtil.ClaimUsernameWorkerTask workerTask =
                new BrowserExpressClaimUsernameUtil.ClaimUsernameWorkerTask(claimUsernameCallback);
        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private String getCurrentAccessToken() {
        try {
            return BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Unable to check Browser Express auth state: " + e.getMessage());
            return null;
        }
    }

    // private void setNtpRecyclerView(LinearLayoutManager linearLayoutManager) {
    //     mIsTopSitesEnabled = NtpUtil.shouldDisplayTopSites();
    //     mIsBraveStatsEnabled = NtpUtil.shouldDisplayBraveStats();

    //     if (mNtpAdapter == null) {
    //         if (mActivity != null && !mActivity.isDestroyed() && !mActivity.isFinishing()) {
    //             mNtpAdapter = new BraveNtpAdapter(mActivity, this, Glide.with(mActivity),
    //                     mNewsItemsFeedCard, mBraveNewsController, mMvTilesContainerLayout,
    //                     mNtpImageGlobal, mSponsoredTab, mWallpaper, mSponsoredLogo,
    //                     mNTPBackgroundImagesBridge, false, mRecyclerView.getHeight(),
    //                     mIsTopSitesEnabled, mIsBraveStatsEnabled, mIsDisplayNewsFeed,
    //                     mIsDisplayNewsOptin);

    //             mRecyclerView.setAdapter(mNtpAdapter);

    //             if (mRecyclerView.getItemAnimator() != null) {
    //                 RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
    //                 if (itemAnimator instanceof SimpleItemAnimator) {
    //                     SimpleItemAnimator simpleItemAnimator = (SimpleItemAnimator) itemAnimator;
    //                     simpleItemAnimator.setSupportsChangeAnimations(false);
    //                 }
    //             }
    //         }
    //     } else {
    //         mNtpAdapter.setRecyclerViewHeight(mRecyclerView.getHeight());
    //         mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
    //         mNtpAdapter.setBraveStatsEnabled(mIsBraveStatsEnabled);
    //         mNtpAdapter.setDisplayNewsFeed(mIsDisplayNewsFeed);
    //     }

    //     if (mNtpAdapter == null) return;

    //     if (mIsDisplayNewsFeed) {
    //         try {
    //             boolean isFeedLoaded = BraveActivity.getBraveActivity().isLoadedFeed();
    //             boolean isFromNewTab = BraveActivity.getBraveActivity().isComesFromNewTab();

    //             Tab tab = BraveActivity.getBraveActivity().getActivityTab();
    //             int offsetPosition =
    //                     (tab != null)
    //                             ? ChromeSharedPreferences.getInstance()
    //                                     .readInt(
    //                                             BravePreferenceKeys
    //                                                             .BRAVE_RECYCLERVIEW_OFFSET_POSITION
    //                                                     + tab.getId(),
    //                                             0)
    //                             : 0;

    //             int itemPosition =
    //                     (tab != null)
    //                             ? ChromeSharedPreferences.getInstance()
    //                                     .readInt(
    //                                             BravePreferenceKeys.BRAVE_RECYCLERVIEW_POSITION
    //                                                     + tab.getId(),
    //                                             0)
    //                             : 0;

    //             if (offsetPosition == 0 && itemPosition == 0) {
    //                 isFeedLoaded = false;
    //             }

    //             if (!isFeedLoaded || isFromNewTab) {
    //                 mNtpAdapter.setNewsLoading(true);
    //                 getFeed(false);

    //             } else {
    //                 keepPosition();
    //             }
    //         } catch (BraveActivity.BraveActivityNotFoundException e) {
    //             Log.e(TAG, "setNtpRecyclerView " + e);
    //         }
    //     } else {
    //         keepPosition();
    //     }

    //     mPrevVisibleNewsCardPosition = firstNewsFeedPosition() - 1;

    //     mRecyclerView.addOnItemTouchListener(
    //             new OnItemTouchListener() {
    //                 @Override
    //                 public boolean onInterceptTouchEvent(
    //                         RecyclerView recyclerView, MotionEvent event) {
    //                     final View childView =
    //                             recyclerView.findChildViewUnder(event.getX(), event.getY());
    //                     if (childView == null && mSponsoredRichMediaWebView != null) {
    //                         mSponsoredRichMediaWebView.getView().dispatchTouchEvent(event);
    //                     }
    //                     return false;
    //                 }

    //                 @Override
    //                 public void onTouchEvent(RecyclerView recyclerView, MotionEvent event) {}

    //                 @Override
    //                 public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
    //             });

    //     mRecyclerView.addOnScrollListener(
    //             new RecyclerView.OnScrollListener() {
    //                 @Override
    //                 public void onScrollStateChanged(
    //                         @NonNull RecyclerView recyclerView, int newState) {
    //                     super.onScrollStateChanged(recyclerView, newState);

    //                     int firstVisibleItemPosition =
    //                             linearLayoutManager.findFirstVisibleItemPosition();

    //                     int newsFeedPosition = firstNewsFeedPosition();
    //                     int tabId = -1;
    //                     try {
    //                         Tab tab = BraveActivity.getBraveActivity().getActivityTab();
    //                         tabId = tab != null ? tab.getId() : -1;
    //                     } catch (BraveActivity.BraveActivityNotFoundException e) {
    //                         Log.e(TAG, "onScrollStateChanged " + e);
    //                     }
    //                     if (newState == RecyclerView.SCROLL_STATE_IDLE) {
    //                         if (tabId != -1 && mRecyclerView.getChildCount() > 0) {
    //                             View firstChild = mRecyclerView.getChildAt(0);
    //                             if (firstChild != null) {
    //                                 int firstVisiblePosition =
    //                                         mRecyclerView.getChildAdapterPosition(firstChild);
    //                                 int verticalOffset = firstChild.getTop();

    //                                 ChromeSharedPreferences.getInstance()
    //                                         .writeInt(
    //                                                 BravePreferenceKeys
    //                                                                 .BRAVE_RECYCLERVIEW_OFFSET_POSITION
    //                                                         + tabId,
    //                                                 verticalOffset);

    //                                 ChromeSharedPreferences.getInstance()
    //                                         .writeInt(
    //                                                 BravePreferenceKeys.BRAVE_RECYCLERVIEW_POSITION
    //                                                         + tabId,
    //                                                 firstVisiblePosition);
    //                             }
    //                         }
    //                     }
    //                     if (mIsDisplayNewsFeed
    //                             && firstVisibleItemPosition >= newsFeedPosition - 1) {
    //                         long nowMillis = System.currentTimeMillis();
    //                         if ((nowMillis - HOUR_MS) > mNewsFeedLastViewTime
    //                                 && mBraveNewsController != null) {
    //                             // Brave News interaction started
    //                             mBraveNewsController.onInteractionSessionStarted();
    //                         }
    //                         mNewsFeedLastViewTime = nowMillis;
    //                         if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
    //                             mEndCardViewTime = System.currentTimeMillis();
    //                             long timeDiff = mEndCardViewTime - mStartCardViewTime;
    //                             // if viewed for more than 100 ms send the event
    //                             if (timeDiff > BraveNewsUtils.BRAVE_NEWS_VIEWD_CARD_TIME) {
    //                                 if (mVisibleCard != null && mCardType != null) {
    //                                     // send viewed cards events
    //                                     if (mCardType.equals("promo")
    //                                             && !mCardType.equals("displayad")) {
    //                                         if (!mUuid.equals("")
    //                                                 && !mCreativeInstanceId.equals("")) {
    //                                             mVisibleCard.setViewStatSent(true);
    //                                             if (mBraveNewsController != null) {
    //                                                 mBraveNewsController.onPromotedItemView(
    //                                                         mUuid, mCreativeInstanceId);
    //                                             }
    //                                         }
    //                                     }
    //                                 }
    //                             }

    //                             int lastVisibleItemPosition =
    //                                     linearLayoutManager.findLastCompletelyVisibleItemPosition();
    //                             if (mNewsItemsFeedCard != null
    //                                     && mNewsItemsFeedCard.size() > 0
    //                                     && lastVisibleItemPosition >= newsFeedPosition
    //                                     && lastVisibleItemPosition > mPrevVisibleNewsCardPosition) {
    //                                 short newCardViews = 0;
    //                                 for (int i = mPrevVisibleNewsCardPosition + 1;
    //                                         i <= lastVisibleItemPosition;
    //                                         i++) {
    //                                     int itemCardPosition = i - newsFeedPosition;
    //                                     if (itemCardPosition >= 0
    //                                             && itemCardPosition < mNewsItemsFeedCard.size()) {
    //                                         FeedItemsCard itemsCard =
    //                                                 mNewsItemsFeedCard.get(itemCardPosition);
    //                                         if (itemsCard != null) {
    //                                             List<FeedItemCard> feedItems =
    //                                                     itemsCard.getFeedItems();
    //                                             // Two items are shown as two cards side by side,
    //                                             // and three or more items is shown as one card as a
    //                                             // list
    //                                             newCardViews =
    //                                                     (short)
    //                                                             (feedItems != null
    //                                                                             && feedItems.size()
    //                                                                                     == 2
    //                                                                     ? 2
    //                                                                     : 1);
    //                                             mNewsSessionCardViews += newCardViews;
    //                                         }
    //                                     }
    //                                 }
    //                                 if (mBraveNewsController != null) {
    //                                     mBraveNewsController.onNewCardsViewed(newCardViews);
    //                                 }
    //                                 mPrevVisibleNewsCardPosition = lastVisibleItemPosition;
    //                             }
    //                         }

    //                         if (newState == RecyclerView.SCROLL_STATE_IDLE
    //                                 || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
    //                             mStartCardViewTime = System.currentTimeMillis();
    //                             int lastVisibleItemPosition =
    //                                     linearLayoutManager.findLastVisibleItemPosition();

    //                             mFeedHash =
    //                                     ChromeSharedPreferences.getInstance()
    //                                             .readString(
    //                                                     BravePreferenceKeys.BRAVE_NEWS_FEED_HASH,
    //                                                     "");
    //                             // @TODO alex optimize feed availability check
    //                             if (mBraveNewsController != null) {
    //                                 mBraveNewsController.isFeedUpdateAvailable(
    //                                         mFeedHash,
    //                                         isNewsFeedAvailable -> {
    //                                             if (isNewsFeedAvailable) {
    //                                                 mPrevVisibleNewsCardPosition =
    //                                                         mPrevVisibleNewsCardPosition + 1;

    //                                                 setNewContentChanges(true);
    //                                             }
    //                                         });
    //                             }

    //                             Rect rvRect = new Rect();
    //                             mRecyclerView.getGlobalVisibleRect(rvRect);

    //                             int visiblePercentage = 0;
    //                             for (int viewPosition = firstVisibleItemPosition;
    //                                     viewPosition <= lastVisibleItemPosition;
    //                                     viewPosition++) {
    //                                 Rect rowRect = new Rect();
    //                                 if (linearLayoutManager.findViewByPosition(viewPosition)
    //                                         != null) {
    //                                     linearLayoutManager
    //                                             .findViewByPosition(viewPosition)
    //                                             .getGlobalVisibleRect(rowRect);

    //                                     if (linearLayoutManager
    //                                                     .findViewByPosition(viewPosition)
    //                                                     .getHeight()
    //                                             > 0) {
    //                                         if (rowRect.bottom >= rvRect.bottom) {
    //                                             int visibleHeightFirst =
    //                                                     rvRect.bottom - rowRect.top;
    //                                             visiblePercentage =
    //                                                     (visibleHeightFirst * 100)
    //                                                             / linearLayoutManager
    //                                                                     .findViewByPosition(
    //                                                                             viewPosition)
    //                                                                     .getHeight();
    //                                         } else {
    //                                             int visibleHeightFirst =
    //                                                     rowRect.bottom - rvRect.top;
    //                                             visiblePercentage =
    //                                                     (visibleHeightFirst * 100)
    //                                                             / linearLayoutManager
    //                                                                     .findViewByPosition(
    //                                                                             viewPosition)
    //                                                                     .getHeight();
    //                                         }
    //                                     }

    //                                     if (visiblePercentage > 100) {
    //                                         visiblePercentage = 100;
    //                                     }
    //                                 }

    //                                 final int visiblePercentageFinal = visiblePercentage;

    //                                 int newsFeedViewPosition = viewPosition - newsFeedPosition;
    //                                 if (newsFeedViewPosition >= 0
    //                                         && newsFeedViewPosition < mNewsItemsFeedCard.size()) {
    //                                     if (visiblePercentageFinal
    //                                             >= MINIMUM_VISIBLE_HEIGHT_THRESHOLD) {
    //                                         mVisibleCard =
    //                                                 mNewsItemsFeedCard.get(newsFeedViewPosition);
    //                                         // get params for view PROMOTED_ARTICLE
    //                                         if (mVisibleCard.getCardType()
    //                                                 == CardType.PROMOTED_ARTICLE) {
    //                                             mItemPosition = newsFeedViewPosition;
    //                                             mCreativeInstanceId =
    //                                                     BraveNewsUtils.getPromotionIdItem(
    //                                                             mVisibleCard);
    //                                             mUuid = mVisibleCard.getUuid();
    //                                             mCardType = "promo";
    //                                         }

    //                                         // get params for view DISPLAY_AD
    //                                         if (mVisibleCard.getCardType() == CardType.DISPLAY_AD) {
    //                                             mItemPosition = newsFeedViewPosition;
    //                                             DisplayAd currentDisplayAd =
    //                                                     BraveNewsUtils.getFromDisplayAdsMap(
    //                                                             newsFeedViewPosition);
    //                                             if (currentDisplayAd != null) {
    //                                                 mCreativeInstanceId =
    //                                                         currentDisplayAd != null
    //                                                                 ? currentDisplayAd
    //                                                                         .creativeInstanceId
    //                                                                 : "";
    //                                                 mUuid =
    //                                                         currentDisplayAd != null
    //                                                                 ? currentDisplayAd.uuid
    //                                                                 : "";
    //                                                 mCardType = "displayad";

    //                                                 // if viewed for more than 100 ms and is more
    //                                                 // than 50%
    //                                                 // visible send the event
    //                                                 final int tabIdForLambda = tabId;
    //                                                 Timer timer = new Timer();
    //                                                 timer.schedule(
    //                                                         new TimerTask() {
    //                                                             @Override
    //                                                             public void run() {
    //                                                                 new Thread() {
    //                                                                     @Override
    //                                                                     public void run() {
    //                                                                         if (!mDatabaseHelper
    //                                                                                         .isDisplayAdAlreadyAdded(
    //                                                                                                 mUuid)
    //                                                                                 && visiblePercentageFinal
    //                                                                                         > MINIMUM_VISIBLE_HEIGHT_THRESHOLD
    //                                                                                 && mBraveNewsController
    //                                                                                         != null) {
    //                                                                             mVisibleCard
    //                                                                                     .setViewStatSent(
    //                                                                                             true);
    //                                                                             mBraveNewsController
    //                                                                                     .onDisplayAdView(
    //                                                                                             mUuid,
    //                                                                                             mCreativeInstanceId);

    //                                                                             insertAd(
    //                                                                                     tabIdForLambda);
    //                                                                         }
    //                                                                     }
    //                                                                 }.start();
    //                                                             }
    //                                                         },
    //                                                         BraveNewsUtils
    //                                                                 .BRAVE_NEWS_VIEWD_CARD_TIME);
    //                                             }
    //                                         }
    //                                     }
    //                                 }
    //                             }
    //                         }
    //                     }
    //                 }

    //                 private void insertAd(int tabId) {
    //                     DisplayAd currentDisplayAd =
    //                             BraveNewsUtils.getFromDisplayAdsMap(mItemPosition);
    //                     if (tabId != -1) {
    //                         mDatabaseHelper.insertAd(currentDisplayAd, mItemPosition, tabId);
    //                     }
    //                 }

    //                 @Override
    //                 public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
    //                     super.onScrolled(recyclerView, dx, dy);

    //                     if (mIsDisplayNewsFeed) {
    //                         int lastVisibleItemPosition =
    //                                 linearLayoutManager.findLastCompletelyVisibleItemPosition();

    //                         if (!mNtpAdapter.shouldDisplayNewsLoading()
    //                                 && mNewsItemsFeedCard != null
    //                                 && mNewsItemsFeedCard.size() > 0
    //                                 && lastVisibleItemPosition
    //                                         > mNtpAdapter.getStatsCount()
    //                                                 + mNtpAdapter.getTopSitesCount()
    //                                                 + mNtpAdapter.getNewContentCount()) {
    //                             if (mNewsSettingsBar.getVisibility() != View.VISIBLE) {
    //                                 mNewsSettingsBar.setVisibility(View.VISIBLE);
    //                             }
    //                             mNtpAdapter.setImageCreditAlpha(0f);
    //                         } else if (lastVisibleItemPosition > -1) {
    //                             if (mNewsSettingsBar.getVisibility() != View.GONE) {
    //                                 mNewsSettingsBar.setVisibility(View.GONE);
    //                             }
    //                             mNtpAdapter.setImageCreditAlpha(1f);
    //                         }

    //                         if (mNtpAdapter.isNewContent()) {
    //                             int firstVisibleItemPosition =
    //                                     linearLayoutManager.findFirstVisibleItemPosition();

    //                             if (firstVisibleItemPosition
    //                                     >= mNtpAdapter.getStatsCount()
    //                                             + mNtpAdapter.getTopSitesCount()) {
    //                                 mNewContentLayout.setVisibility(View.VISIBLE);
    //                             } else {
    //                                 mNewContentLayout.setVisibility(View.GONE);
    //                             }
    //                         } else {
    //                             mNewContentLayout.setVisibility(View.GONE);
    //                         }
    //                     } else if (mIsDisplayNewsOptin) {
    //                         int lastVisibleItemPosition =
    //                                 linearLayoutManager.findLastCompletelyVisibleItemPosition();

    //                         if (lastVisibleItemPosition == mNtpAdapter.getItemCount() - 1) {
    //                             mNtpAdapter.setImageCreditAlpha(0f);
    //                         } else {
    //                             mNtpAdapter.setImageCreditAlpha(1f);
    //                         }
    //                     }
    //                 }
    //             });
    // }

    private void keepPosition() {
        try {
            Tab tab = BraveActivity.getBraveActivity().getActivityTab();
            if (tab != null) {
                int itemPosition =
                        ChromeSharedPreferences.getInstance()
                                .readInt(
                                        BravePreferenceKeys.BRAVE_RECYCLERVIEW_POSITION
                                                + tab.getId(),
                                        0);

                new Handler(Looper.getMainLooper())
                        .postDelayed(
                                () -> {
                                    if (mNtpAdapter != null
                                            && mNtpAdapter.getItemCount() > itemPosition) {
                                        RecyclerView.LayoutManager manager =
                                                mRecyclerView.getLayoutManager();
                                        if (manager instanceof LinearLayoutManager) {
                                            int offsetPosition =
                                                    ChromeSharedPreferences.getInstance()
                                                            .readInt(
                                                                    BravePreferenceKeys
                                                                                    .BRAVE_RECYCLERVIEW_OFFSET_POSITION
                                                                            + tab.getId(),
                                                                    0);

                                            if (itemPosition
                                                    == mNtpAdapter.getStatsCount()
                                                            + mNtpAdapter.getTopSitesCount()
                                                            + mNtpAdapter.getNewContentCount()) {
                                                offsetPosition -=
                                                        mNtpAdapter.getTopMarginImageCredit();
                                            }

                                            LinearLayoutManager linearLayoutManager =
                                                    (LinearLayoutManager) manager;
                                            linearLayoutManager.scrollToPositionWithOffset(
                                                    itemPosition, offsetPosition);
                                        }
                                    }
                                },
                                10);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "keepPosition " + e);
        }
    }

    // private int firstNewsFeedPosition() {
    //     if (mNtpAdapter != null) {
    //         return mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount()
    //                 + mNtpAdapter.getNewContentCount() + 1;
    //     }
    //     return 0;
    // }

    @Override
    public void updateNewsOptin(boolean isOptin) {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BraveNewsPreferencesV2.PREF_SHOW_OPTIN, false);
        if (isOptin) {
            BravePrefServiceBridge.getInstance().setNewsOptIn(true);
        }
        BravePrefServiceBridge.getInstance().setShowNews(isOptin);

        mIsDisplayNewsOptin = false;
        mIsDisplayNewsFeed = false;
        mNtpAdapter.removeNewsOptin();
        mNtpAdapter.setImageCreditAlpha(1f);
        mNtpAdapter.setDisplayNewsFeed(mIsDisplayNewsFeed);

        if (isOptin && mBraveNewsController != null && BraveNewsUtils.getLocale().isEmpty()) {
            BraveNewsUtils.getBraveNewsSettingsData(mBraveNewsController, null, null);
        }
    }

    private void initPreferenceObserver() {
        mPreferenceListener =
                (prefs, key) -> {
                    if (TextUtils.equals(key, BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE)) {
                        if (ChromeSharedPreferences.getInstance()
                                .readBoolean(BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE, false)) {
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(
                                            () -> {
                                                mPrevVisibleNewsCardPosition =
                                                        mPrevVisibleNewsCardPosition + 1;
                                                setNewContentChanges(true);
                                            },
                                            10);
                        }

                    } else if (TextUtils.equals(
                            key, BravePreferenceKeys.BRAVE_NEWS_PREF_SHOW_NEWS)) {
                        new Handler(Looper.getMainLooper())
                                .postDelayed(
                                        () -> {
                                            refreshFeed();
                                        },
                                        10);
                    } else if (TextUtils.equals(
                            key, BackgroundImagesPreferences.PREF_SHOW_TOP_SITES)) {
                        mIsTopSitesEnabled = NtpUtil.shouldDisplayTopSites();
                        mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
                    } else if (TextUtils.equals(
                            key, BackgroundImagesPreferences.PREF_SHOW_BRAVE_STATS)) {
                        mIsBraveStatsEnabled = NtpUtil.shouldDisplayBraveStats();
                        mNtpAdapter.setBraveStatsEnabled(mIsBraveStatsEnabled);
                    }
                };
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mWorkerTask != null && mWorkerTask.getStatus() == AsyncTask.Status.RUNNING) {
            mWorkerTask.cancel(true);
            mWorkerTask = null;
        }

        if (!mIsFromBottomSheet) {
            setBackgroundResource(0);
            if (mImageDrawable != null && mImageDrawable.getBitmap() != null
                    && !mImageDrawable.getBitmap().isRecycled()) {
                mImageDrawable.getBitmap().recycle();
            }
        }
        mNTPBackgroundImagesBridge.removeObserver(mNTPBackgroundImageServiceObserver);
        BrowserExpressGuestLogin.removeObserver(mGuestLoginObserver);

        if (mNewsItemsFeedCard != null && mNewsItemsFeedCard.size() > 0) {
            try {
                BraveActivity.getBraveActivity().setNewsItemsFeedCards(mNewsItemsFeedCard);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "onDetachedFromWindow " + e);
            }
        }

        if (mBraveNewsController != null) {
            mBraveNewsController.close();
            mBraveNewsController = null;
        }

        // Removes preference listener.
        ContextUtils.getAppSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
        mPreferenceListener = null;

        if (mPostAdapter != null) {
            mPostAdapter.releaseTutorialPlayer();
        }
        // Recycles every card, on screen and cached, which releases their video players and
        // the hardware decoders those hold. Coming back builds a fresh adapter (setNtpViews).
        mRecyclerView.setAdapter(null);

        mRecyclerView.clearOnScrollListeners();
        super.onDetachedFromWindow();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mActivity != null && (mActivity.isFinishing() || mActivity.isDestroyed())) {
            super.onConfigurationChanged(newConfig);
            return;
        }
        if (mSponsoredTab != null && NTPImageUtil.shouldEnableNTPFeature()) {
            NTPImage ntpImage = mSponsoredTab.getTabNTPImage(false);
            if (ntpImage == null) {
                mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
            } else if (ntpImage instanceof Wallpaper) {
                Wallpaper mWallpaper = (Wallpaper) ntpImage;
                if (mWallpaper == null) {
                    mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
                }
            }
            super.onConfigurationChanged(newConfig);
            // showNTPImage(ntpImage);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (mNtpAdapter != null) {
                    mNtpAdapter.setRecyclerViewHeight(mRecyclerView.getHeight());
                }
                keepPosition();
            }, 10);
        } else {
            super.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void loadNewContent() {
        mNtpAdapter.setNewContentLoading(true);
        mNewContentText.setVisibility(View.GONE);
        mNewContentProgressBar.setVisibility(View.VISIBLE);
        mNewContentLayout.setClickable(false);
        ChromeSharedPreferences.getInstance()
                .writeBoolean(BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE, false);

        getFeed(true);
    }

    @Override
    public void getFeed(boolean isNewContent) {
        if (!isNewContent) {
            mNtpAdapter.setImageCreditAlpha(1f);
            mNtpAdapter.setNewsLoading(true);
        }
        Runnable onBraveNewsControllerReady =
                () -> {
                    PostTask.postTask(
                            TaskTraits.BEST_EFFORT_MAY_BLOCK,
                            () -> {
                                if (mBraveNewsController != null) {
                                    mBraveNewsController.getFeed(
                                            feed -> {
                                                runFeed(isNewContent, feed);
                                            });
                                }
                            });
                };
        initBraveNewsController(onBraveNewsControllerReady);
    }

    private void runFeed(boolean isNewContent, Feed feed) {
        if (feed == null) {
            processFeed(isNewContent);
            return;
        }

        mFeedHash = feed.hash;
        int newsItemsFeedCardSize = mNewsItemsFeedCard.size();
        new Handler(Looper.getMainLooper()).post(() -> {
            mNtpAdapter.notifyItemRangeRemoved(
                    mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount() + 1,
                    newsItemsFeedCardSize);
        });

        mNewsItemsFeedCard.clear();
        BraveNewsUtils.initCurrentAds();
        ChromeSharedPreferences.getInstance()
                .writeString(BravePreferenceKeys.BRAVE_NEWS_FEED_HASH, feed.hash);

        if (feed.featuredItem != null) {
            // process Featured item
            FeedItem featuredItem = feed.featuredItem;
            FeedItemsCard featuredItemsCard = new FeedItemsCard();

            FeedItemCard featuredItemCard = new FeedItemCard();
            List<FeedItemCard> featuredCardItems = new ArrayList<>();

            featuredItemsCard.setCardType(CardType.HEADLINE);
            featuredItemsCard.setUuid(UUID.randomUUID().toString());

            featuredItemCard.setFeedItem(featuredItem);
            featuredCardItems.add(featuredItemCard);

            featuredItemsCard.setFeedItems(featuredCardItems);
            mNewsItemsFeedCard.add(featuredItemsCard);
        }

        if (mNewsItemsFeedCard.size() > 0 || (feed.pages != null && feed.pages.length > 0)) {
            //  adds empty card to trigger Display ad call for the second card, when the
            //  user starts scrolling
            FeedItemsCard displayAdCard = new FeedItemsCard();
            DisplayAd displayAd = new DisplayAd();
            displayAdCard.setCardType(CardType.DISPLAY_AD);
            displayAdCard.setDisplayAd(displayAd);
            displayAdCard.setUuid(UUID.randomUUID().toString());
            mNewsItemsFeedCard.add(displayAdCard);
        }

        // start page loop
        for (FeedPage page : feed.pages) {
            for (FeedPageItem cardData : page.items) {
                // if for any reason we get an empty object, unless it's a
                // DISPLAY_AD we skip it
                if (cardData.cardType != CardType.DISPLAY_AD) {
                    if (cardData.items.length == 0) {
                        continue;
                    }
                }

                FeedItemsCard feedItemsCard = new FeedItemsCard();
                feedItemsCard.setCardType(cardData.cardType);
                feedItemsCard.setUuid(UUID.randomUUID().toString());
                List<FeedItemCard> cardItems = new ArrayList<>();
                for (FeedItem item : cardData.items) {
                    FeedItemCard feedItemCard = new FeedItemCard();
                    feedItemCard.setFeedItem(item);

                    cardItems.add(feedItemCard);

                    feedItemsCard.setFeedItems(cardItems);
                }

                mNewsItemsFeedCard.add(feedItemsCard);

                // For show brave rating UI in news list at 10 th row
                if (RateUtils.getInstance().shouldShowRateDialog(mActivity)
                        && mNewsItemsFeedCard.size() == SHOW_BRAVE_RATE_ENTRY_AT) {
                    // Dummy entry for Rating prompt
                    FeedItemsCard dummy = new FeedItemsCard();
                    dummy.setCardType(CardBuilderFeedCard.CARDTYPE_BRAVE_RATING);
                    dummy.setUuid(UUID.randomUUID().toString());
                    mNewsItemsFeedCard.add(dummy);
                }
            }
        } // end page loop

        processFeed(isNewContent);
        try {
            BraveActivity.getBraveActivity().setNewsItemsFeedCards(mNewsItemsFeedCard);
            BraveActivity.getBraveActivity().setLoadedFeed(true);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "getFeed " + e);
        }
    }

    private void refreshFeed() {
        boolean isShowNewsOn = BravePrefServiceBridge.getInstance().getShowNews();
        // mIsDisplayNewsFeed = BraveNewsUtils.shouldDisplayNewsFeed();
        mIsDisplayNewsFeed = false;
        if (!isShowNewsOn) {
            mNtpAdapter.setDisplayNewsFeed(false);

            if (mNtpAdapter.isNewContent()) {
                mPrevVisibleNewsCardPosition = mPrevVisibleNewsCardPosition - 1;
                setNewContentChanges(false);
            }
            mNtpAdapter.setImageCreditAlpha(1f);
            mNewsSettingsBar.setVisibility(View.GONE);
            return;
        }

        if (mIsDisplayNewsFeed) {
            if (mIsDisplayNewsOptin) {
                mIsDisplayNewsOptin = false;
                mNtpAdapter.removeNewsOptin();
                mNtpAdapter.setImageCreditAlpha(1f);
            }
            mNtpAdapter.setDisplayNewsFeed(mIsDisplayNewsFeed);
            getFeed(false);
        }
    }

    private void processFeed(boolean isNewContent) {
        new Handler(Looper.getMainLooper()).post(() -> {
            mNtpAdapter.setNewsLoading(false);
            if (mNewsItemsFeedCard != null && mNewsItemsFeedCard.size() > 0) {
                mNtpAdapter.notifyItemRangeChanged(
                        mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount(),
                        mNtpAdapter.getItemCount() - mNtpAdapter.getStatsCount()
                                - mNtpAdapter.getTopSitesCount());
            }

            if (isNewContent) {
                mPrevVisibleNewsCardPosition = mPrevVisibleNewsCardPosition - 1;
                setNewContentChanges(false);
                RecyclerView.LayoutManager manager = mRecyclerView.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) manager;
                    linearLayoutManager.scrollToPositionWithOffset(
                            mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount() + 1,
                            dpToPx(mActivity, 60));
                }
            }
            try {
                BraveActivity.getBraveActivity().setComesFromNewTab(false);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "processFeed " + e);
            }
        });
    }

    /** The feed header's search box, while the header is laid out. */
    private @Nullable View headerSearchBox() {
        if (mRecyclerView == null) return null;
        RecyclerView.ViewHolder header = mRecyclerView.findViewHolderForAdapterPosition(0);
        if (header == null || !header.itemView.isAttachedToWindow()) return null;
        return header.itemView.findViewById(R.id.ntp_search_box);
    }

    private void notifyHeaderSearchBoxScrolled() {
        if (mHeaderSearchBoxScrollListener != null) {
            mHeaderSearchBoxScrollListener.onNtpScrollChanged(getToolbarTransitionPercentage());
        }
    }

    /**
     * How far the toolbar's location bar has taken over from the header's search box: 0 while the
     * box rests below the toolbar, 1 once it has slid under it (or the header has scrolled away).
     */
    @Override
    float getToolbarTransitionPercentage() {
        if (mRecyclerView == null || mRecyclerView.getChildCount() == 0) return 0f;
        View box = headerSearchBox();
        if (box == null) return 1f;
        float transitionLength = box.getHeight() / 2f;
        if (transitionLength <= 0) return 0f;
        int boxTop = locationInWindowY(box) - locationInWindowY(this);
        // The layout reserves its top padding for the toolbar.
        int toolbarBottom = getPaddingTop();
        return MathUtils.clamp(
                (toolbarBottom + transitionLength - boxTop) / transitionLength, 0f, 1f);
    }

    /**
     * Where the header's search box is within {@code parentView}; the toolbar grows its location
     * bar out of these bounds. Once the header has scrolled away the bounds sit above the view.
     *
     * <p>While the box hands focus to the location bar the bounds rise to the top. Chrome's own
     * new tab page scrolls its fake box up under the toolbar for that; this box stays put, and
     * reported where it rests, the toolbar laid the focused location bar out down there, outside
     * its own bounds: the keyboard came up with no field showing until the feed was scrolled.
     */
    @Override
    void getSearchBoxBounds(Rect bounds, Point translation, View parentView) {
        translation.set(0, 0);
        View box = headerSearchBox();
        if (box == null || parentView == null) {
            int height = box != null ? box.getHeight() : 0;
            bounds.set(0, -height, parentView != null ? parentView.getWidth() : 0, 0);
            return;
        }
        int[] boxLocation = new int[2];
        int[] parentLocation = new int[2];
        box.getLocationInWindow(boxLocation);
        parentView.getLocationInWindow(parentLocation);
        int left = boxLocation[0] - parentLocation[0];
        int top = boxLocation[1] - parentLocation[1];
        if (top > 0) top = Math.round(top * (1f - mUrlFocusPercent));
        bounds.set(left, top, left + box.getWidth(), top + box.getHeight());
    }

    @Override
    void setSearchBoxScrollListener(NewTabPage.OnSearchBoxScrollListener listener) {
        mHeaderSearchBoxScrollListener = listener;
        notifyHeaderSearchBoxScrolled();
    }

    /** The header's box fades out while the location bar takes focus from it, and back after. */
    @Override
    void setUrlFocusChangeAnimationPercent(float percent) {
        boolean changed = percent != mUrlFocusPercent;
        mUrlFocusPercent = percent;
        applyHeaderSearchBoxAlpha();
        // The toolbar reads the box's bounds before it hands over the new percent, and a focus
        // animation can jump straight to its end with no frame after it: have it lay the location
        // bar out again against the bounds that go with this percent.
        if (changed) {
            post(
                    () -> {
                        if (isAttachedToWindow()) notifyHeaderSearchBoxScrolled();
                    });
        }
    }

    @Override
    public void setSearchBoxAlpha(float alpha) {
        mHeaderSearchBoxAlpha = alpha;
        applyHeaderSearchBoxAlpha();
    }

    private void applyHeaderSearchBoxAlpha() {
        View box = headerSearchBox();
        if (box != null) box.setAlpha(mHeaderSearchBoxAlpha * (1f - mUrlFocusPercent));
    }

    private static int locationInWindowY(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return location[1];
    }

    public View createTile(Context context, TopSiteTable topSite) {
        View tileView = LayoutInflater.from(context).inflate(R.layout.top_site_tile_layout, null);

        // LinearLayout tileLayout = tileView.findViewById(R.id.tile_layout);
        ImageView imageView = tileView.findViewById(R.id.tile_image);
        TextView textView = tileView.findViewById(R.id.tile_text);

        if (topSite.getImagePath() != null) {
            File imgFile = new File(topSite.getImagePath());
            if (imgFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                RoundedBitmapDrawable roundedBitmap = RoundedBitmapDrawableFactory.create(context.getResources(), bitmap);
                roundedBitmap.setCircular(true);
                imageView.setImageDrawable(roundedBitmap);
            } else {
                imageView.setImageDrawable(generateAvatar(context, topSite.getName()));
            }
        } else {
            imageView.setImageDrawable(generateAvatar(context, topSite.getName()));
        }

        // Limit name length and set text
        String name = topSite.getName();
        if (name.length() > 30) {
            name = name.substring(0, 27) + "...";
        }
        textView.setText(name);

        tileView.setOnClickListener(v -> {
            if (topSite.getDestinationUrl().contains("youtube")) {
                trackYouTubeOpenedFromHome();
            }
            TabUtils.openUrlInSameTab(topSite.getDestinationUrl());
        });

        return tileView;
    }

    /** The header's YouTube button: YouTube, signed in so its premium features apply. */
    public void openYouTubeFromHome() {
        trackYouTubeOpenedFromHome();
        TabUtils.openUrlInSameTab(DatabaseHelper.DEFAULT_YOUTUBE_URL);
    }

    private void trackYouTubeOpenedFromHome() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null){
                return;
            }
            String accessToken = activity.getAccessToken();

            if (accessToken != null) {
                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                if (decodedAccessTokenObj != null) {
                    String pInfo = activity.getCurrentAppVersion();
                    JSONObject payload = new JSONObject();
                    payload.put("app_version", pInfo);
                    PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                        new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.YTP_PREMIUM_CLICKED_ON_HOME, decodedAccessTokenObj.getString("_id"), payload);
                    postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "maybeShowWalletPanel " + e);
        } catch (JSONException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }
    }

    private BitmapDrawable generateAvatar(Context context, String name) {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Generate a random color
        Random rnd = new Random();
        int color = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
        canvas.drawColor(color);

        // Draw the first letter of the name
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.valueOf(name.charAt(0)).toUpperCase(Locale.ROOT), 50, 70, paint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private void setNewContentChanges(boolean isNewContent) {
        if (isNewContent) {
            if (mNtpAdapter != null) {
                mNtpAdapter.setNewContent(true);
                RecyclerView.LayoutManager manager = mRecyclerView.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) manager;
                    int firstVisibleItemPosition =
                            linearLayoutManager.findFirstVisibleItemPosition();

                    if (firstVisibleItemPosition
                            >= mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount()) {
                        mNewContentLayout.setVisibility(View.VISIBLE);
                    }
                }
            }

        } else {
            if (mNtpAdapter != null) {
                mNtpAdapter.setNewContent(false);
            }
            mNewContentLayout.setVisibility(View.GONE);
            mNewContentProgressBar.setVisibility(View.GONE);
            mNewContentText.setVisibility(View.VISIBLE);
            mNewContentLayout.setClickable(true);
        }
    }

    @Override
    public void initialize(
            NewTabPageManager manager,
            Activity activity,
            Delegate tileGroupDelegate,
            boolean searchProviderHasLogo,
            boolean searchProviderIsGoogle,
            FeedSurfaceScrollDelegate scrollDelegate,
            TouchEnabledDelegate touchEnabledDelegate,
            UiConfig uiConfig,
            ActivityLifecycleDispatcher lifecycleDispatcher,
            Profile profile,
            WindowAndroid windowAndroid,
            boolean isTablet,
            ObservableSupplier<Integer> tabStripHeightSupplier,
            Supplier<GURL> composeplateUrlSupplier) {
        super.initialize(
                manager,
                activity,
                tileGroupDelegate,
                searchProviderHasLogo,
                searchProviderIsGoogle,
                scrollDelegate,
                touchEnabledDelegate,
                uiConfig,
                lifecycleDispatcher,
                profile,
                windowAndroid,
                isTablet,
                tabStripHeightSupplier,
                composeplateUrlSupplier);
        mNTPBackgroundImagesBridge = NTPBackgroundImagesBridge.getInstance(mProfile);
        mNTPBackgroundImagesBridge.setNewTabPageListener(mNewTabPageListener);
        mIsTablet = isTablet;
        // mWindowAndroid = windowAndroid;

        assert mMvTilesContainerLayout != null : "Something has changed in the upstream!";

        if (mMvTilesContainerLayout != null && useFixedMVTLayout()) {
            ViewGroup tilesLayout = mMvTilesContainerLayout.findViewById(R.id.mv_tiles_layout);

            assert tilesLayout instanceof BraveMostVisitedTilesLayoutBase
                    : "Something has changed in the upstream!";

            if (tilesLayout instanceof BraveMostVisitedTilesLayoutBase) {
                ((BraveMostVisitedTilesLayoutBase) tilesLayout).setUseFixedLayout(true);
            }
        }

        assert (activity instanceof BraveActivity);
        mActivity = activity;
        ((BraveActivity) mActivity).dismissShieldsTooltip();
        ((BraveActivity) mActivity).setNewTabPageManager(manager);
        mFeedHash = "";
        initBraveNewsController(null);
        if (BraveNewsUtils.shouldDisplayNewsFeed() && ((BraveActivity) mActivity).isLoadedFeed()) {
            CopyOnWriteArrayList<FeedItemsCard> existingNewsFeedObject =
                    ((BraveActivity) mActivity).getNewsItemsFeedCards();
            if (existingNewsFeedObject != null) {
                mNewsItemsFeedCard = existingNewsFeedObject;
            }
        }
    }

    protected boolean useFixedMVTLayout() {
        return !UserPrefs.get(ProfileManager.getLastUsedRegularProfile())
                .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE);
    }

    public void setTabProvider(Supplier<Tab> unused_tabProvider) {}

    // private void showNTPImage(NTPImage ntpImage) {
    //     Display display = mActivity.getWindowManager().getDefaultDisplay();
    //     Point size = new Point();
    //     display.getSize(size);

    //     mNtpImageGlobal = ntpImage;
    //     if (mNtpAdapter != null) {
    //         mNtpAdapter.setNtpImage(ntpImage);
    //     }

    //     boolean wasWallpaperShown = true;
    //     if (ntpImage instanceof Wallpaper && ((Wallpaper) ntpImage).isRichMedia()) {
    //         setupSponsoredBackgroundContent();
    //     } else if (ntpImage instanceof Wallpaper
    //             && NTPImageUtil.isReferralEnabled()
    //             && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    //         // setBackgroundImage(ntpImage);

    //     } else if (UserPrefs.get(ProfileManager.getLastUsedRegularProfile())
    //                     .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE)
    //             && mSponsoredTab != null
    //             && NTPImageUtil.shouldEnableNTPFeature()) {
    //         // setBackgroundImage(ntpImage);
    //     } else {
    //         wasWallpaperShown = false;
    //     }

    //     if (wasWallpaperShown
    //             && ntpImage instanceof Wallpaper
    //             && getTab() != null
    //             && mNewTabTakeoverInfobar == null) {
    //         mNewTabTakeoverInfobar = new BraveNewTabTakeoverInfobar(mProfile);
    //         mNewTabTakeoverInfobar.maybeDisplayAndIncrementCounter(
    //                 mActivity, getTab().getWebContents());
    //     }
    // }

    // private void setupSponsoredBackgroundContent() {
    //     // if (mSponsoredRichMediaWebView != null) {
    //     //     return;
    //     // }

    //     // mSponsoredRichMediaWebView =
    //     //         new SponsoredRichMediaWebView(mActivity, mWindowAndroid, mProfile);

    //     // mBackgroundSponsoredRichMediaView = findViewById(R.id.bg_sponsored_rich_media_view);
    //     // mBackgroundSponsoredRichMediaView.setVisibility(View.VISIBLE);
    //     // mBackgroundSponsoredRichMediaView.addView(mSponsoredRichMediaWebView.getView());

    //     // mSponsoredRichMediaWebView.loadSponsoredRichMedia();
    // }

    // private void setBackgroundImage(NTPImage ntpImage) {}

    // private void checkAndShowNTPImage(boolean isReset) {
    //     NTPImage ntpImage = mSponsoredTab.getTabNTPImage(isReset);
    //     if (ntpImage == null) {
    //         mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
    //     } else if (ntpImage instanceof Wallpaper) {
    //         Wallpaper mWallpaper = (Wallpaper) ntpImage;
    //         if (mWallpaper == null) {
    //             mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
    //         }
    //     }
    //     showNTPImage(ntpImage);
    // }

    private void initilizeSponsoredTab() {
        if (TabAttributes.from(getTab()).get(String.valueOf(getTab().getId())) == null) {
            SponsoredTab sponsoredTab = new SponsoredTab(mNTPBackgroundImagesBridge);
            TabAttributes.from(getTab()).set(String.valueOf(getTab().getId()), sponsoredTab);
        }
        mSponsoredTab = TabAttributes.from(getTab()).get(String.valueOf(getTab().getId()));
        if (shouldShowSuperReferral()) mNTPBackgroundImagesBridge.getTopSites();
    }

    private final NewTabPageListener mNewTabPageListener =
            new NewTabPageListener() {
                @Override
                public void updateInteractableFlag(boolean isBottomSheet) {
                    mIsFromBottomSheet = isBottomSheet;
                }

                @Override
                public void updateNTPImage() {
                    if (mSponsoredTab == null) {
                        initilizeSponsoredTab();
                    }
                    // checkAndShowNTPImage(false);
                }

                @Override
                public void updateTopSites(List<TopSite> topSites) {
                    new AsyncTask<List<TopSiteTable>>() {
                        @Override
                        protected List<TopSiteTable> doInBackground() {
                            for (TopSite topSite : topSites) {
                                mDatabaseHelper.insertTopSite(topSite);
                            }
                            return mDatabaseHelper.getAllTopSites();
                        }

                        @Override
                        protected void onPostExecute(List<TopSiteTable> topSites) {
                            assert ThreadUtils.runningOnUiThread();
                            if (isCancelled()) return;
                            loadTopSites(topSites);
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            };

    private final NTPBackgroundImagesBridge.NTPBackgroundImageServiceObserver
            mNTPBackgroundImageServiceObserver =
                    new NTPBackgroundImagesBridge.NTPBackgroundImageServiceObserver() {
                        @Override
                        public void onUpdated() {
                            if (NTPImageUtil.isReferralEnabled()) {
                                // checkAndShowNTPImage(true);
                                if (shouldShowSuperReferral()) {
                                    mNTPBackgroundImagesBridge.getTopSites();
                                }
                            }
                        }
                    };

    // private final FetchWallpaperWorkerTask.WallpaperRetrievedCallback mWallpaperRetrievedCallback =
    //         new FetchWallpaperWorkerTask.WallpaperRetrievedCallback() {
    //             @Override
    //             public void bgWallpaperRetrieved(Bitmap bgWallpaper) {
    //                 if (mBgImageView != null) {
    //                     mBgImageView.setImageBitmap(bgWallpaper);
    //                 }
    //             }

    //             @Override
    //             public void logoRetrieved(Wallpaper wallpaper, Bitmap logoWallpaper) {
    //                 if (!NTPImageUtil.isReferralEnabled()) {
    //                     mWallpaper = wallpaper;
    //                     mSponsoredLogo = logoWallpaper;
    //                     if (mNtpAdapter != null) {
    //                         mNtpAdapter.setSponsoredLogo(mWallpaper, logoWallpaper);
    //                     }
    //                 }
    //             }
    //         };

    private void loadTopSites(List<TopSiteTable> topSites) {
        mSuperReferralSitesLayout = new LinearLayout(mActivity);
        mSuperReferralSitesLayout.setWeightSum(1f);
        mSuperReferralSitesLayout.setOrientation(LinearLayout.HORIZONTAL);
        mSuperReferralSitesLayout.setBackgroundColor(mActivity.getColor(R.color.topsite_bg_color));

        LayoutInflater inflater =
                (LayoutInflater) mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        for (TopSiteTable topSite : topSites) {
            final View tileView = inflater.inflate(R.layout.suggestions_tile_view, null);

            TextView tileViewTitleTv = tileView.findViewById(R.id.tile_view_title);
            tileViewTitleTv.setText(topSite.getName());
            tileViewTitleTv.setTextAppearance(R.style.BraveSuggestionsTilesText);

            ImageView iconIv = tileView.findViewById(R.id.tile_view_icon);
            if (NTPImageUtil.imageCache.get(topSite.getDestinationUrl()) == null) {
                NTPImageUtil.imageCache.put(
                        topSite.getDestinationUrl(),
                        new java.lang.ref.SoftReference(
                                NTPImageUtil.getTopSiteBitmap(topSite.getImagePath())));
            }
            iconIv.setImageBitmap(NTPImageUtil.imageCache.get(topSite.getDestinationUrl()).get());
            iconIv.setBackgroundColor(mActivity.getColor(android.R.color.white));
            iconIv.setClickable(false);

            tileView.setOnClickListener(
                    view -> { TabUtils.openUrlInSameTab(topSite.getDestinationUrl()); });

            tileView.setPadding(0, dpToPx(mActivity, 12), 0, 0);

            LinearLayout.LayoutParams layoutParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
            layoutParams.weight = 0.25f;
            layoutParams.gravity = Gravity.CENTER;
            tileView.setLayoutParams(layoutParams);
            tileView.setOnCreateContextMenuListener(
                    new View.OnCreateContextMenuListener() {
                        @Override
                        public void onCreateContextMenu(
                                ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                            menu.add(R.string.contextmenu_open_in_new_tab)
                                    .setOnMenuItemClickListener(
                                            new MenuItem.OnMenuItemClickListener() {
                                                @Override
                                                public boolean onMenuItemClick(MenuItem item) {
                                                    TabUtils.openUrlInNewTab(
                                                            false, topSite.getDestinationUrl());
                                                    return true;
                                                }
                                            });
                            menu.add(R.string.contextmenu_open_in_incognito_tab)
                                    .setOnMenuItemClickListener(
                                            new MenuItem.OnMenuItemClickListener() {
                                                @Override
                                                public boolean onMenuItemClick(MenuItem item) {
                                                    TabUtils.openUrlInNewTab(
                                                            true, topSite.getDestinationUrl());
                                                    return true;
                                                }
                                            });
                            menu.add(R.string.contextmenu_save_link)
                                    .setOnMenuItemClickListener(
                                            new MenuItem.OnMenuItemClickListener() {
                                                @Override
                                                public boolean onMenuItemClick(MenuItem item) {
                                                    if (getTab() != null) {
                                                        OfflinePageBridge.getForProfile(mProfile)
                                                                .scheduleDownload(
                                                                        getTab().getWebContents(),
                                                                        OfflinePageBridge
                                                                                .NTP_SUGGESTIONS_NAMESPACE,
                                                                        topSite.getDestinationUrl(),
                                                                        DownloadUiActionFlags.ALL);
                                                    } else {
                                                        RequestCoordinatorBridge.getForProfile(
                                                                        mProfile)
                                                                .savePageLater(
                                                                        topSite.getDestinationUrl(),
                                                                        OfflinePageBridge
                                                                                .NTP_SUGGESTIONS_NAMESPACE,
                                                                        true /* userRequested */);
                                                    }
                                                    return true;
                                                }
                                            });
                            menu.add(R.string.remove)
                                    .setOnMenuItemClickListener(
                                            new MenuItem.OnMenuItemClickListener() {
                                                @Override
                                                public boolean onMenuItemClick(MenuItem item) {
                                                    NTPImageUtil.imageCache.remove(
                                                            topSite.getDestinationUrl());
                                                    mDatabaseHelper.deleteTopSite(
                                                            topSite.getDestinationUrl());
                                                    NTPImageUtil.addToRemovedTopSite(
                                                            topSite.getDestinationUrl());
                                                    mSuperReferralSitesLayout.removeView(tileView);
                                                    return true;
                                                }
                                            });
                        }
                    });
            mSuperReferralSitesLayout.addView(tileView);
        }
    }

    public void setTab(Tab tab) {
        mTab = tab;
    }

    private Tab getTab() {
        assert mTab != null;
        return mTab;
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
        mBraveNewsController = null;
        runOnUiThread(
                () -> {
                    initBraveNewsController(null);
                });
    }

    private void initBraveNewsController(final Runnable action) {
        ThreadUtils.assertOnUiThread();
        if (mBraveNewsController != null) {
            if (action != null) {
                action.run();
            }

            return;
        }

        BraveNewsControllerFactory.getInstance()
                .getForProfile(mProfile, this)
                .then(
                        braveNewsController -> {
                            if (braveNewsController == null) {
                                return;
                            }
                            mBraveNewsController = braveNewsController;
                            if (mNtpAdapter != null) {
                                mNtpAdapter.setBraveNewsController(mBraveNewsController);
                            }
                            if (action != null) {
                                action.run();
                            }
                        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mIsTablet) {
            if (mInitialTileNum == null) {
                // In the upstream `mMvTilesContainerLayout` is added as a view in
                // `insertSiteSectionView`/`initializeSiteSectionView`.
                // We override `insertSiteSectionView`/`initializeSiteSectionView` to add
                // `mMvTilesContainerLayout` in our own
                // RecyclerView to have own NTP UI.
                // Thus upstream's NewTabPageLayout.findViewById does not see `mv_tiles_layout` and
                // returns null.
                mInitialTileNum =
                        ((ViewGroup) mMvTilesContainerLayout.findViewById(R.id.mv_tiles_layout))
                                .getChildCount();
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private final BrowserExpressGetPostsUtil.GetPostsCallback getPostsCallback=
            new BrowserExpressGetPostsUtil.GetPostsCallback() {
                @Override
                public void getPostsSuccessful(List<Post> posts, @Nullable String nextCursor) {
                    mPostsRequestInFlight = false;
                    mPostsLoaded = true;
                    mPostsLoadFailed = false;
                    mNextPostsCursor = nextCursor;
                    mPendingPostsRetryAccessToken = null;
                    if (mPosts == null) mPosts = new ArrayList<Post>();

                    // The server's ranking can move between two pages, repeating a post the
                    // reader already has; each post shows once. Posts the reader marked "Not
                    // interested" never show.
                    List<Post> added = new ArrayList<>();
                    for (Post post : posts) {
                        if (mPostIds.add(post.getId()) && !HiddenFeedPosts.isHidden(post.getId())) {
                            added.add(post);
                        }
                    }
                    int len = mPosts.size();
                    mPosts.addAll(added);
                    // A later page with nothing after it: the reader has seen the whole feed.
                    if (nextCursor == null && len > 0) {
                        PostHogUtil.capture(
                                PostHogEventKeys.FEED_END_REACHED, "posts", mPosts.size());
                    }

                    if (mPostAdapter == null) return;
                    mPostAdapter.hideShimmer();
                    mPostAdapter.setLoading(false);
                    if (!added.isEmpty()) {
                        mPostAdapter.notifyItemRangeInserted(
                                len + mPostAdapter.getPostAdapterOffset(), added.size());
                    }
                    applyFeedFooter();
                    // A short page, or one of repeats only, can leave the end of the feed on
                    // screen with nothing left to scroll.
                    scheduleLoadMoreCheck();
                }

                @Override
                public void getPostsFailed(String error) {
                    mPostsRequestInFlight = false;
                    Log.e("BE_GET_POST", error);
                    PostHogUtil.capture(
                            PostHogEventKeys.FEED_LOAD_FAILED,
                            "page",
                            mPostsLoaded ? "next" : "first",
                            "error",
                            error);
                    if (mPostAdapter != null) {
                        mPostAdapter.hideShimmer();
                        mPostAdapter.setLoading(false);
                    }
                    if (mPendingPostsRetryAccessToken != null && !mPostsLoaded) {
                        String retryAccessToken = mPendingPostsRetryAccessToken;
                        mPendingPostsRetryAccessToken = null;
                        loadPosts(retryAccessToken);
                        return;
                    }
                    mPostsLoadFailed = true;
                    applyFeedFooter();
                }
            };

    private final BrowserExpressGetProfilePreferencesUtil.GetProfileCallback getProfileCallback =
            new BrowserExpressGetProfilePreferencesUtil.GetProfileCallback() {
                @Override
                public void getProfileSuccessful(String avatar, String xp, String lg, String lr) {
                    Context context = ContextUtils.getApplicationContext();
                    SharedPreferences sharedPref = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                    SharedPreferences.Editor editor = sharedPref.edit();

                    if (avatar != null && !avatar.isEmpty() && mProfileButton != null) {
                        ProfileAvatar.load(mProfileButton, avatar, null);
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

    /** A guest session, from closing the sign-in screen, arriving after the page opened. */
    private final BrowserExpressGuestLogin.Observer mGuestLoginObserver = this::onSessionArrived;

    /** Picks up a session that arrived after the page started out without one. */
    private void onSessionArrived(String accessToken) {
        fetchAndUpdateProfileImage();
        if (mPostsRequestInFlight) {
            mPendingPostsRetryAccessToken = accessToken;
        } else if (!mPostsLoaded && (mPosts == null || mPosts.isEmpty())) {
            loadPosts(accessToken);
        }
    }

    private static final int MAX_CLAIM_RETRIES = 3;
    private int mClaimRetryCount = 0;

    private final BrowserExpressClaimUsernameUtil.ClaimUsernameCallback claimUsernameCallback=
            new BrowserExpressClaimUsernameUtil.ClaimUsernameCallback() {
                @Override
                public void claimUsernameSuccessful(String accessToken, String refreshToken) {
                    sNtpClaimUsernameInFlight = false;
                    mClaimRetryCount = 0;
                    String currentAccessToken = getCurrentAccessToken();
                    if (currentAccessToken != null) {
                        loadPostsAndProfile(currentAccessToken);
                        return;
                    }
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setAccessToken(accessToken);
                        Toast.makeText(activity, "Login Successful", Toast.LENGTH_SHORT).show();
                        onSessionArrived(accessToken);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void claimUsernameFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED: " + error);
                    String currentAccessToken = getCurrentAccessToken();
                    if (currentAccessToken != null) {
                        sNtpClaimUsernameInFlight = false;
                        mClaimRetryCount = 0;
                        loadPostsAndProfile(currentAccessToken);
                        return;
                    }
                    if (BrowserExpressClaimUsernameUtil.ERROR_MANUAL_AUTH_REQUIRED.equals(error)) {
                        sNtpClaimUsernameInFlight = false;
                        // Authentication may have completed while this anonymous request was
                        // running. Re-enter the guarded path so a stale callback cannot put the
                        // registration activity back on top of the browser.
                        startClaimUsernameIfNeeded();
                        return;
                    }
                    // Retry with backoff
                    if (mClaimRetryCount < MAX_CLAIM_RETRIES) {
                        mClaimRetryCount++;
                        long delayMs = (long) (1000 * Math.pow(2, mClaimRetryCount - 1));
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            BrowserExpressClaimUsernameUtil.ClaimUsernameWorkerTask retryTask =
                                    new BrowserExpressClaimUsernameUtil.ClaimUsernameWorkerTask(
                                            claimUsernameCallback);
                            retryTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }, delayMs);
                    } else {
                        sNtpClaimUsernameInFlight = false;
                        Log.e("Express Browser LOGIN", "Claim username failed after " + MAX_CLAIM_RETRIES + " retries");
                        // Load posts without token as fallback so NTP isn't blank
                        loadPosts(null);
                    }
                }
            };

    private void fetchAndUpdateProfileImage() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null){
                return;
            }
            String accessToken = activity.getAccessToken();

            mProfileButton = activity.getProfileButton();

            if (accessToken != null && mProfileButton != null) {
                ProfileAvatar.loadSelf(mProfileButton, accessToken);

                BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask workerTask1 =
                    new BrowserExpressGetProfilePreferencesUtil.GetProfileWorkerTask(accessToken, getProfileCallback);
                workerTask1.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "maybeShowWalletPanel " + e);
        }
    }

    private JSONObject getDecodedToken(String accessToken){
        if (accessToken == null || accessToken.isEmpty()) return null;
        try{
            String[] split_string = accessToken.split("\\.");
            if (split_string.length < 2) {
                Log.e("TokenDecoder", "Invalid JWT format - token does not have header.payload.signature structure");
                return null;
            }
            String base64EncodedBody = split_string[1];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e("TokenDecoder", "JSON parsing error: " + e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("TokenDecoder", "Encoding error: " + e.getMessage());
            return null;
        }catch(Exception e){
            Log.e("TokenDecoder", "Unexpected error decoding token: " + e.getMessage());
            return null;
        }
        
    }
}
