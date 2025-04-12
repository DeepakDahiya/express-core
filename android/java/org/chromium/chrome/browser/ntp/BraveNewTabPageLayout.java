/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import static org.chromium.ui.base.ViewUtils.dpToPx;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.airbnb.lottie.LottieAnimationView;
import com.bumptech.glide.Glide;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.supplier.Supplier;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.Article;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.CardType;
import org.chromium.brave_news.mojom.DisplayAd;
import org.chromium.brave_news.mojom.Feed;
import org.chromium.brave_news.mojom.FeedItem;
import org.chromium.brave_news.mojom.FeedItemMetadata;
import org.chromium.brave_news.mojom.FeedPage;
import org.chromium.brave_news.mojom.FeedPageItem;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveRewardsHelper;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_news.BraveNewsControllerFactory;
import org.chromium.chrome.browser.brave_news.BraveNewsUtils;
import org.chromium.chrome.browser.brave_news.CardBuilderFeedCard;
import org.chromium.chrome.browser.brave_news.LinearLayoutManagerWrapper;
import org.chromium.chrome.browser.brave_news.models.FeedItemCard;
import org.chromium.chrome.browser.brave_news.models.FeedItemsCard;
import org.chromium.chrome.browser.brave_stats.BraveStatsUtil;
import org.chromium.chrome.browser.feed.FeedSurfaceScrollDelegate;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
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
import org.chromium.chrome.browser.ntp_background_images.util.NTPUtil;
import org.chromium.chrome.browser.ntp_background_images.util.NewTabPageListener;
import org.chromium.chrome.browser.ntp_background_images.util.SponsoredImageUtil;
import org.chromium.chrome.browser.offlinepages.DownloadUiActionFlags;
import org.chromium.chrome.browser.offlinepages.OfflinePageBridge;
import org.chromium.chrome.browser.offlinepages.RequestCoordinatorBridge;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.query_tiles.BraveQueryTileSection;
import org.chromium.chrome.browser.rate.RateUtils;
import org.chromium.chrome.browser.settings.BackgroundImagesPreferences;
import org.chromium.chrome.browser.settings.BraveNewsPreferencesV2;
import org.chromium.chrome.browser.settings.SettingsLauncherImpl;
import org.chromium.chrome.browser.suggestions.tile.MostVisitedTilesGridLayout;
import org.chromium.chrome.browser.suggestions.tile.TileGroup.Delegate;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabAttributes;
import org.chromium.chrome.browser.tab.TabImpl;
import org.chromium.chrome.browser.ui.native_page.TouchEnabledDelegate;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.components.browser_ui.settings.SettingsLauncher;
import org.chromium.components.browser_ui.widget.displaystyle.UiConfig;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.ui.base.DeviceFormFactor;
import org.chromium.ui.base.WindowAndroid;

import java.util.ArrayList;
import java.util.List;
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

public class BraveNewTabPageLayout
        extends NewTabPageLayout implements ConnectionErrorHandler, OnBraveNtpListener {
    private static final String TAG = "BraveNewTabPage";

    private static final int MINIMUM_VISIBLE_HEIGHT_THRESHOLD = 50;
    private static final String BRAVE_RECYCLERVIEW_POSITION = "recyclerview_visible_position_";
    private static final String BRAVE_RECYCLERVIEW_OFFSET_POSITION =
            "recyclerview_offset_position_";

    // To delete in bytecode, parent variable will be used instead.
    private ViewGroup mMvTilesContainerLayout;
    private LogoCoordinator mLogoCoordinator;

    // Own members.
    private final Context mContext;
    private ImageView mBgImageView;
    private Profile mProfile;
    private SponsoredTab mSponsoredTab;

    private BitmapDrawable mImageDrawable;

    private FetchWallpaperWorkerTask mWorkerTask;
    private boolean mIsFromBottomSheet;
    private NTPBackgroundImagesBridge mNTPBackgroundImagesBridge;
    private ViewGroup mMainLayout;
    private DatabaseHelper mDatabaseHelper;

    private LottieAnimationView mBadgeAnimationView;

    private Tab mTab;
    private Activity mActivity;
    private LinearLayout mSuperReferralSitesLayout;

    private BraveNtpAdapter mNtpAdapter;
    private Bitmap mSponsoredLogo;
    private Wallpaper mWallpaper;

    private CopyOnWriteArrayList<FeedItemsCard> mNewsItemsFeedCard =
            new CopyOnWriteArrayList<FeedItemsCard>();
    private RecyclerView mRecyclerView;
    private LinearLayout mNewsSettingsBar;
    private LinearLayout mNewContentLayout;
    private TextView mNewContentText;
    private ProgressBar mNewContentProgressBar;
    private PostListAdapter mPostAdapter;
    private List<Post> mPosts;

    private NTPImage mNtpImageGlobal;
    private BraveNewsController mBraveNewsController;

    private long mStartCardViewTime;
    private long mEndCardViewTime;
    private String mCreativeInstanceId;
    private String mUuid;
    //@TODO alex make an enum
    private String mCardType;
    private int mItemPosition;
    private int mPrevVisibleNewsCardPosition = -1;
    private int mNewsSessionCardViews;
    private FeedItemsCard mVisibleCard;
    private String mFeedHash;
    private SharedPreferencesManager.Observer mPreferenceObserver;
    private boolean mComesFromNewTab;
    private boolean mIsTopSitesEnabled;
    private boolean mIsBraveStatsEnabled;
    private boolean mIsDisplayNewsFeed;
    private boolean mIsDisplayNewsOptin;
    private boolean mNewsFeedViewedOnce;
    private ProgressBar mFeedProgress;

    private Supplier<Tab> mTabProvider;

    private static final int SHOW_BRAVE_RATE_ENTRY_AT = 10; // 10th row

    public BraveNewTabPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mProfile = Profile.getLastUsedRegularProfile();
        mNTPBackgroundImagesBridge = NTPBackgroundImagesBridge.getInstance(mProfile);
        mNTPBackgroundImagesBridge.setNewTabPageListener(mNewTabPageListener);
        mDatabaseHelper = DatabaseHelper.getInstance();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mComesFromNewTab = false;

        NTPUtil.showBREBottomBanner(this);
        mFeedHash = "";
        initBraveNewsController();
        try {
            if (BraveNewsUtils.shouldDisplayNewsFeed()
                    && BraveActivity.getBraveActivity().isLoadedFeed()) {
                CopyOnWriteArrayList<FeedItemsCard> existingNewsFeedObject =
                        BraveActivity.getBraveActivity().getNewsItemsFeedCards();
                if (existingNewsFeedObject != null) {
                    mNewsItemsFeedCard = existingNewsFeedObject;
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "onFinishInflate " + e);
        }
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

    protected void insertSiteSectionView() {
        mMainLayout = findViewById(R.id.ntp_content);

        mMvTilesContainerLayout = (ViewGroup) LayoutInflater.from(mMainLayout.getContext())
                                          .inflate(R.layout.mv_tiles_container, mMainLayout, false);
        mMvTilesContainerLayout.setVisibility(View.VISIBLE);

        mMvTilesContainerLayout.post(new Runnable() {
            @Override
            public void run() {
                mMvTilesContainerLayout.addOnLayoutChangeListener(
                        (View view, int left, int top, int right, int bottom, int oldLeft,
                                int oldTop, int oldRight, int oldBottom) -> {
                            int oldHeight = oldBottom - oldTop;
                            int newHeight = bottom - top;

                            if (oldHeight != newHeight && mIsTopSitesEnabled
                                    && mNtpAdapter != null) {
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    mNtpAdapter.notifyItemRangeChanged(mNtpAdapter.getStatsCount(),
                                            mNtpAdapter.getNewContentCount() + 2);
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
        checkAndShowNTPImage(false);
        mNTPBackgroundImagesBridge.addObserver(mNTPBackgroundImageServiceObserver);

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
        if (mPreferenceObserver != null) {
            SharedPreferencesManager.getInstance().addObserver(mPreferenceObserver);
        }
        setNtpViews();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setNtpViews() {
        mRecyclerView = findViewById(R.id.recycler_posts);
        mFeedProgress = findViewById(R.id.feed_progress);
        mPosts = new ArrayList<Post>();
        mFeedProgress.setVisibility(View.VISIBLE);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mActivity));
        mPostAdapter = new PostListAdapter(mActivity, mPosts, mRecyclerView);
        mRecyclerView.setAdapter(mPostAdapter);

        mMainLayout = findViewById(R.id.ntp_content);
        mMainLayout.setBackgroundColor(mActivity.getResources().getColor(R.color.be_background_black));
        LinearLayout topSitesContainer = mMainLayout.findViewById(R.id.top_sites_container);

        List<TopSiteTable> topSites = mDatabaseHelper.getAllTopSites();

        if (topSites != null && !topSites.isEmpty()) {
            topSitesContainer.removeAllViews(); // Clear existing views.

            int maxSites = Math.min(4, topSites.size()); // Limit to 4 sites.

            for (int i = 0; i < maxSites; i++) {
                TopSiteTable topSite = topSites.get(i);
                View tileView = createTile(getContext(), topSite);
                topSitesContainer.addView(tileView);
            }
            topSitesContainer.setVisibility(View.VISIBLE);
        } else {
            topSitesContainer.setVisibility(View.GONE); // Hide if no top sites.
        }

        String accessToken = ((BraveActivity)mActivity).getAccessToken();
        BrowserExpressGetPostsUtil.GetPostsWorkerTask workerTask =
            new BrowserExpressGetPostsUtil.GetPostsWorkerTask(1, 20, accessToken, getPostsCallback);
        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private boolean shouldDisplayTopSites() {
        return ContextUtils.getAppSharedPreferences().getBoolean(
                BackgroundImagesPreferences.PREF_SHOW_TOP_SITES, true);
    }

    private boolean shouldDisplayBraveStats() {
        return false;
    }

    private void setNtpRecyclerView(LinearLayoutManager linearLayoutManager) {
        mIsTopSitesEnabled = shouldDisplayTopSites();
        mIsBraveStatsEnabled = shouldDisplayBraveStats();

        if (mNtpAdapter == null) {
            if (mActivity != null && !mActivity.isDestroyed() && !mActivity.isFinishing()) {
                mNtpAdapter = new BraveNtpAdapter(mActivity, this, Glide.with(mActivity),
                        mNewsItemsFeedCard, mBraveNewsController, mMvTilesContainerLayout,
                        mNtpImageGlobal, mSponsoredTab, mWallpaper, mSponsoredLogo,
                        mNTPBackgroundImagesBridge, false, mRecyclerView.getHeight(),
                        mIsTopSitesEnabled, mIsBraveStatsEnabled, mIsDisplayNewsFeed,
                        mIsDisplayNewsOptin);

                mRecyclerView.setAdapter(mNtpAdapter);

                if (mRecyclerView.getItemAnimator() != null) {
                    RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
                    if (itemAnimator instanceof SimpleItemAnimator) {
                        SimpleItemAnimator simpleItemAnimator = (SimpleItemAnimator) itemAnimator;
                        simpleItemAnimator.setSupportsChangeAnimations(false);
                    }
                }
            }
        } else {
            mNtpAdapter.setRecyclerViewHeight(mRecyclerView.getHeight());
            mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
            mNtpAdapter.setBraveStatsEnabled(mIsBraveStatsEnabled);
            mNtpAdapter.setDisplayNewsFeed(mIsDisplayNewsFeed);
        }

        if (mNtpAdapter == null) return;

        if (mIsDisplayNewsFeed) {
            try {
                boolean isFeedLoaded = BraveActivity.getBraveActivity().isLoadedFeed();
                boolean isFromNewTab = BraveActivity.getBraveActivity().isComesFromNewTab();

                Tab tab = BraveActivity.getBraveActivity().getActivityTab();
                int offsetPosition = (tab != null) ? SharedPreferencesManager.getInstance().readInt(
                                             BRAVE_RECYCLERVIEW_OFFSET_POSITION + tab.getId(), 0)
                                                   : 0;

                int itemPosition = (tab != null) ? SharedPreferencesManager.getInstance().readInt(
                                           BRAVE_RECYCLERVIEW_POSITION + tab.getId(), 0)
                                                 : 0;

                if (offsetPosition == 0 && itemPosition == 0) {
                    isFeedLoaded = false;
                }

                if (!isFeedLoaded || isFromNewTab) {
                    mNtpAdapter.setNewsLoading(true);
                    getFeed(false);

                } else {
                    keepPosition();
                }
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "setNtpRecyclerView " + e);
            }
        } else {
            keepPosition();
        }

        mPrevVisibleNewsCardPosition = firstNewsFeedPosition() - 1;
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                int firstVisibleItemPosition = linearLayoutManager.findFirstVisibleItemPosition();

                int newsFeedPosition = firstNewsFeedPosition();

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    try {
                        if (BraveActivity.getBraveActivity().getActivityTab() != null
                                && mRecyclerView.getChildCount() > 0) {
                            View firstChild = mRecyclerView.getChildAt(0);
                            if (firstChild != null) {
                                int firstVisiblePosition =
                                        mRecyclerView.getChildAdapterPosition(firstChild);
                                int verticalOffset = firstChild.getTop();

                                SharedPreferencesManager.getInstance().writeInt(
                                        BRAVE_RECYCLERVIEW_OFFSET_POSITION
                                                + BraveActivity.getBraveActivity()
                                                          .getActivityTab()
                                                          .getId(),
                                        verticalOffset);

                                SharedPreferencesManager.getInstance().writeInt(
                                        BRAVE_RECYCLERVIEW_POSITION
                                                + BraveActivity.getBraveActivity()
                                                          .getActivityTab()
                                                          .getId(),
                                        firstVisiblePosition);
                            }
                        }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e(TAG, "onScrollStateChanged " + e);
                    }
                }
                if (mIsDisplayNewsFeed && firstVisibleItemPosition >= newsFeedPosition - 1) {
                    if (!mNewsFeedViewedOnce && mBraveNewsController != null) {
                        // Brave News interaction started
                        mBraveNewsController.onInteractionSessionStarted();
                        mNewsFeedViewedOnce = true;
                    }
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        mEndCardViewTime = System.currentTimeMillis();
                        long timeDiff = mEndCardViewTime - mStartCardViewTime;
                        // if viewed for more than 100 ms send the event
                        if (timeDiff > BraveNewsUtils.BRAVE_NEWS_VIEWD_CARD_TIME) {
                            if (mVisibleCard != null && mCardType != null) {
                                // send viewed cards events
                                if (mCardType.equals("promo") && !mCardType.equals("displayad")) {
                                    if (!mUuid.equals("") && !mCreativeInstanceId.equals("")) {
                                        mVisibleCard.setViewStatSent(true);
                                        if (mBraveNewsController != null) {
                                            mBraveNewsController.onPromotedItemView(
                                                    mUuid, mCreativeInstanceId);
                                        }
                                    }
                                }
                            }
                        }

                        int lastVisibleItemPosition =
                                linearLayoutManager.findLastCompletelyVisibleItemPosition();
                        if (mNewsItemsFeedCard != null && mNewsItemsFeedCard.size() > 0
                                && lastVisibleItemPosition >= newsFeedPosition
                                && lastVisibleItemPosition > mPrevVisibleNewsCardPosition) {
                            short newCardViews = 0;
                            for (int i = mPrevVisibleNewsCardPosition + 1;
                                    i <= lastVisibleItemPosition; i++) {
                                int itemCardPosition = i - newsFeedPosition;
                                if (itemCardPosition >= 0
                                        && itemCardPosition < mNewsItemsFeedCard.size()) {
                                    FeedItemsCard itemsCard =
                                            mNewsItemsFeedCard.get(itemCardPosition);
                                    if (itemsCard != null) {
                                        List<FeedItemCard> feedItems = itemsCard.getFeedItems();
                                        // Two items are shown as two cards side by side,
                                        // and three or more items is shown as one card as a list
                                        newCardViews =
                                                (short) (feedItems != null && feedItems.size() == 2
                                                                ? 2
                                                                : 1);
                                        mNewsSessionCardViews += newCardViews;
                                    }
                                }
                            }
                            if (mBraveNewsController != null) {
                                mBraveNewsController.onSessionCardViewsCountChanged(
                                        (short) mNewsSessionCardViews, newCardViews);
                            }
                            mPrevVisibleNewsCardPosition = lastVisibleItemPosition;
                        }
                    }

                    if (newState == RecyclerView.SCROLL_STATE_IDLE || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        mStartCardViewTime = System.currentTimeMillis();
                        int lastVisibleItemPosition =
                                linearLayoutManager.findLastVisibleItemPosition();

                        mFeedHash = SharedPreferencesManager.getInstance().readString(
                                BravePreferenceKeys.BRAVE_NEWS_FEED_HASH, "");
                        //@TODO alex optimize feed availability check
                        if (mBraveNewsController != null) {
                            mBraveNewsController.isFeedUpdateAvailable(
                                    mFeedHash, isNewsFeedAvailable -> {
                                        if (isNewsFeedAvailable) {
                                            mPrevVisibleNewsCardPosition =
                                                    mPrevVisibleNewsCardPosition + 1;

                                            setNewContentChanges(true);
                                        }
                                    });
                        }

                        Rect rvRect = new Rect();
                        mRecyclerView.getGlobalVisibleRect(rvRect);

                        int visiblePercentage = 0;
                        for (int viewPosition = firstVisibleItemPosition;
                                viewPosition <= lastVisibleItemPosition; viewPosition++) {
                            Rect rowRect = new Rect();
                            if (linearLayoutManager.findViewByPosition(viewPosition) != null) {
                                linearLayoutManager.findViewByPosition(viewPosition)
                                        .getGlobalVisibleRect(rowRect);

                                if (linearLayoutManager.findViewByPosition(viewPosition).getHeight()
                                        > 0) {
                                    if (rowRect.bottom >= rvRect.bottom) {
                                        int visibleHeightFirst = rvRect.bottom - rowRect.top;
                                        visiblePercentage = (visibleHeightFirst * 100)
                                                / linearLayoutManager
                                                          .findViewByPosition(viewPosition)
                                                          .getHeight();
                                    } else {
                                        int visibleHeightFirst = rowRect.bottom - rvRect.top;
                                        visiblePercentage = (visibleHeightFirst * 100)
                                                / linearLayoutManager
                                                          .findViewByPosition(viewPosition)
                                                          .getHeight();
                                    }
                                }

                                if (visiblePercentage > 100) {
                                    visiblePercentage = 100;
                                }
                            }

                            final int visiblePercentageFinal = visiblePercentage;

                            int newsFeedViewPosition = viewPosition - newsFeedPosition;
                            if (newsFeedViewPosition >= 0
                                    && newsFeedViewPosition < mNewsItemsFeedCard.size()) {
                                if (visiblePercentageFinal >= MINIMUM_VISIBLE_HEIGHT_THRESHOLD) {
                                    mVisibleCard = mNewsItemsFeedCard.get(newsFeedViewPosition);
                                    // get params for view PROMOTED_ARTICLE
                                    if (mVisibleCard.getCardType() == CardType.PROMOTED_ARTICLE) {
                                        mItemPosition = newsFeedViewPosition;
                                        mCreativeInstanceId =
                                                BraveNewsUtils.getPromotionIdItem(mVisibleCard);
                                        mUuid = mVisibleCard.getUuid();
                                        mCardType = "promo";
                                    }

                                    // get params for view DISPLAY_AD
                                    if (mVisibleCard.getCardType() == CardType.DISPLAY_AD) {
                                        mItemPosition = newsFeedViewPosition;
                                        DisplayAd currentDisplayAd =
                                                BraveNewsUtils.getFromDisplayAdsMap(
                                                        newsFeedViewPosition);
                                        if (currentDisplayAd != null) {
                                            mCreativeInstanceId = currentDisplayAd != null
                                                    ? currentDisplayAd.creativeInstanceId
                                                    : "";
                                            mUuid = currentDisplayAd != null ? currentDisplayAd.uuid
                                                                             : "";
                                            mCardType = "displayad";

                                            // if viewed for more than 100 ms and is more than 50%
                                            // visible send the event
                                            Timer timer = new Timer();
                                            timer.schedule(new TimerTask() {
                                                @Override
                                                public void run() {
                                                    new Thread() {
                                                        @Override
                                                        public void run() {
                                                            if (!mDatabaseHelper
                                                                            .isDisplayAdAlreadyAdded(
                                                                                    mUuid)
                                                                    && visiblePercentageFinal
                                                                            > MINIMUM_VISIBLE_HEIGHT_THRESHOLD
                                                                    && mBraveNewsController
                                                                            != null) {
                                                                mVisibleCard.setViewStatSent(true);
                                                                mBraveNewsController.onDisplayAdView(
                                                                        mUuid, mCreativeInstanceId);

                                                                insertAd();
                                                            }
                                                        }
                                                    }.start();
                                                }
                                            }, BraveNewsUtils.BRAVE_NEWS_VIEWD_CARD_TIME);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            private void insertAd() {
                DisplayAd currentDisplayAd = BraveNewsUtils.getFromDisplayAdsMap(mItemPosition);
                try {
                    mDatabaseHelper.insertAd(currentDisplayAd, mItemPosition,
                            BraveActivity.getBraveActivity().getActivityTab().getId());
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e(TAG, "insertAd " + e);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                if (mIsDisplayNewsFeed) {
                    int lastVisibleItemPosition =
                            linearLayoutManager.findLastCompletelyVisibleItemPosition();

                    if (!mNtpAdapter.shouldDisplayNewsLoading() && mNewsItemsFeedCard != null
                            && mNewsItemsFeedCard.size() > 0
                            && lastVisibleItemPosition > mNtpAdapter.getStatsCount()
                                            + mNtpAdapter.getTopSitesCount()
                                            + mNtpAdapter.getNewContentCount()) {
                        if (mNewsSettingsBar.getVisibility() != View.VISIBLE) {
                            mNewsSettingsBar.setVisibility(View.VISIBLE);
                        }
                        mNtpAdapter.setImageCreditAlpha(0f);
                    } else if (lastVisibleItemPosition > -1) {
                        if (mNewsSettingsBar.getVisibility() != View.GONE) {
                            mNewsSettingsBar.setVisibility(View.GONE);
                        }
                        mNtpAdapter.setImageCreditAlpha(1f);
                    }

                    if (mNtpAdapter.isNewContent()) {
                        int firstVisibleItemPosition =
                                linearLayoutManager.findFirstVisibleItemPosition();

                        if (firstVisibleItemPosition
                                >= mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount()) {
                            mNewContentLayout.setVisibility(View.VISIBLE);
                        } else {
                            mNewContentLayout.setVisibility(View.GONE);
                        }
                    } else {
                        mNewContentLayout.setVisibility(View.GONE);
                    }
                } else if (mIsDisplayNewsOptin) {
                    int lastVisibleItemPosition =
                            linearLayoutManager.findLastCompletelyVisibleItemPosition();

                    if (lastVisibleItemPosition == mNtpAdapter.getItemCount() - 1) {
                        mNtpAdapter.setImageCreditAlpha(0f);
                    } else {
                        mNtpAdapter.setImageCreditAlpha(1f);
                    }
                }
            }
        });
    }

    private void keepPosition() {
        try {
            Tab tab = BraveActivity.getBraveActivity().getActivityTab();
            if (tab != null) {
                int itemPosition = SharedPreferencesManager.getInstance().readInt(
                        BRAVE_RECYCLERVIEW_POSITION + tab.getId(), 0);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mNtpAdapter != null && mNtpAdapter.getItemCount() > itemPosition) {
                        RecyclerView.LayoutManager manager = mRecyclerView.getLayoutManager();
                        if (manager instanceof LinearLayoutManager) {
                            int offsetPosition = SharedPreferencesManager.getInstance().readInt(
                                    BRAVE_RECYCLERVIEW_OFFSET_POSITION + tab.getId(), 0);

                            if (itemPosition
                                    == mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount()
                                            + mNtpAdapter.getNewContentCount()) {
                                offsetPosition -= mNtpAdapter.getTopMarginImageCredit();
                            }

                            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) manager;
                            linearLayoutManager.scrollToPositionWithOffset(
                                    itemPosition, offsetPosition);
                        }
                    }
                }, 10);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "keepPosition " + e);
        }
    }

    private int firstNewsFeedPosition() {
        if (mNtpAdapter != null) {
            return mNtpAdapter.getStatsCount() + mNtpAdapter.getTopSitesCount()
                    + mNtpAdapter.getNewContentCount() + 1;
        }
        return 0;
    }

    @Override
    public void updateNewsOptin(boolean isOptin) {
        SharedPreferences sharedPreferences = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor sharedPreferencesEditor = sharedPreferences.edit();
        sharedPreferencesEditor.putBoolean(BraveNewsPreferencesV2.PREF_SHOW_OPTIN, false);
        sharedPreferencesEditor.apply();
        if (isOptin) {
            BravePrefServiceBridge.getInstance().setNewsOptIn(true);
        }
        BravePrefServiceBridge.getInstance().setShowNews(isOptin);

        mIsDisplayNewsOptin = false;
        mIsDisplayNewsFeed = false;
        mNtpAdapter.removeNewsOptin();
        mNtpAdapter.setImageCreditAlpha(1f);
        mNtpAdapter.setDisplayNewsFeed(mIsDisplayNewsFeed);

        if (isOptin && mBraveNewsController != null && BraveNewsUtils.getLocale() == null) {
            BraveNewsUtils.getBraveNewsSettingsData(mBraveNewsController, null);
        }
    }

    private void initPreferenceObserver() {
        mPreferenceObserver = (key) -> {
            if (TextUtils.equals(key, BackgroundImagesPreferences.PREF_SHOW_TOP_SITES)) {
                mIsTopSitesEnabled = shouldDisplayTopSites();
                mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
            }
            // if (TextUtils.equals(key, BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE)) {
            //     if (SharedPreferencesManager.getInstance().readBoolean(
            //                 BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE, false)) {
            //         new Handler(Looper.getMainLooper()).postDelayed(() -> {
            //             mPrevVisibleNewsCardPosition = mPrevVisibleNewsCardPosition + 1;
            //             setNewContentChanges(true);
            //         }, 10);
            //     }

            // } else if (TextUtils.equals(key, BravePreferenceKeys.BRAVE_NEWS_PREF_SHOW_NEWS)) {
            //     new Handler(Looper.getMainLooper()).postDelayed(() -> { refreshFeed(); }, 10);
            // } else if (TextUtils.equals(key, BackgroundImagesPreferences.PREF_SHOW_TOP_SITES)) {
            //     mIsTopSitesEnabled = shouldDisplayTopSites();
            //     mNtpAdapter.setTopSitesEnabled(mIsTopSitesEnabled);
            // } else if (TextUtils.equals(key, BackgroundImagesPreferences.PREF_SHOW_BRAVE_STATS)) {
            //     mIsBraveStatsEnabled = shouldDisplayBraveStats();
            //     mNtpAdapter.setBraveStatsEnabled(mIsBraveStatsEnabled);
            // }
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

        // removes preference observer
        SharedPreferencesManager.getInstance().removeObserver(mPreferenceObserver);
        mPreferenceObserver = null;

        mRecyclerView.clearOnScrollListeners();
        super.onDetachedFromWindow();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mSponsoredTab != null && NTPUtil.shouldEnableNTPFeature()) {
            NTPImage ntpImage = mSponsoredTab.getTabNTPImage(false);
            if (ntpImage == null) {
                mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
            } else if (ntpImage instanceof Wallpaper) {
                Wallpaper mWallpaper = (Wallpaper) ntpImage;
                if (mWallpaper == null) {
                    mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
                }
            }
            checkForNonDisruptiveBanner(ntpImage);
            super.onConfigurationChanged(newConfig);
            showNTPImage(ntpImage);

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
        SharedPreferencesManager.getInstance().writeBoolean(
                BravePreferenceKeys.BRAVE_NEWS_CHANGE_SOURCE, false);

        getFeed(true);
    }

    @Override
    public void getFeed(boolean isNewContent) {
        if (!isNewContent) {
            mNtpAdapter.setImageCreditAlpha(1f);
            mNtpAdapter.setNewsLoading(true);
        }
        initBraveNewsController();
        PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK, () -> {
            if (mBraveNewsController != null) {
                mBraveNewsController.getFeed(feed -> { runFeed(isNewContent, feed); });
            }
        });
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
        SharedPreferencesManager.getInstance().writeString(
                BravePreferenceKeys.BRAVE_NEWS_FEED_HASH, feed.hash);

        if (feed.featuredItem != null) {
            // process Featured item
            FeedItem featuredItem = feed.featuredItem;
            FeedItemsCard featuredItemsCard = new FeedItemsCard();

            FeedItemMetadata featuredItemMetaData = new FeedItemMetadata();
            Article featuredArticle = featuredItem.getArticle();
            FeedItemMetadata featuredArticleData = featuredArticle.data;

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
        int noPages = 0;
        int itemIndex = 0;
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
                    FeedItemMetadata itemMetaData = new FeedItemMetadata();
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

    private View createTile(Context context, TopSiteTable topSite) {
        View tileView = LayoutInflater.from(context).inflate(R.layout.top_site_tile_layout, null);

        LinearLayout tileLayout = tileView.findViewById(R.id.tile_layout);
        ImageView imageView = tileView.findViewById(R.id.tile_image);
        TextView textView = tileView.findViewById(R.id.tile_text);
        LinearLayout imageContainer = tileView.findViewById(R.id.image_container);

        // Set background color for image container
        try {
            int backgroundColor = android.graphics.Color.parseColor(topSite.getBackgroundColor());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL); // Circular background
            shape.setColor(backgroundColor);
            imageContainer.setBackground(shape);
        } catch (IllegalArgumentException e) {
            // Handle invalid color string
            imageContainer.setBackgroundColor(android.graphics.Color.LTGRAY); // Default background
        }

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

        // Click listener to open website
        tileView.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(topSite.getDestinationUrl()));
            context.startActivity(intent);
        });

        return tileView;
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
    public void initialize(NewTabPageManager manager, Activity activity, Delegate tileGroupDelegate,
            boolean searchProviderHasLogo, boolean searchProviderIsGoogle,
            FeedSurfaceScrollDelegate scrollDelegate, TouchEnabledDelegate touchEnabledDelegate,
            UiConfig uiConfig, ActivityLifecycleDispatcher lifecycleDispatcher, NewTabPageUma uma,
            boolean isIncognito, WindowAndroid windowAndroid, boolean isNtpAsHomeSurfaceEnabled,
            boolean isSurfacePolishEnabled, boolean isSurfacePolishOmniboxColorEnabled) {
        super.initialize(manager, activity, tileGroupDelegate, searchProviderHasLogo,
                searchProviderIsGoogle, scrollDelegate, touchEnabledDelegate, uiConfig,
                lifecycleDispatcher, uma, isIncognito, windowAndroid, isNtpAsHomeSurfaceEnabled,
                isSurfacePolishEnabled, isSurfacePolishOmniboxColorEnabled);

        assert mMvTilesContainerLayout != null : "Something has changed in the upstream!";

        if (mMvTilesContainerLayout != null && !isScrollableMvtEnabled()) {
            ViewGroup tilesLayout = mMvTilesContainerLayout.findViewById(R.id.mv_tiles_layout);

            assert tilesLayout
                    instanceof MostVisitedTilesGridLayout
                : "Something has changed in the upstream!";

            if (tilesLayout instanceof MostVisitedTilesGridLayout) {
                ((MostVisitedTilesGridLayout) tilesLayout)
                        .setMaxRows(
                                BraveQueryTileSection.getMaxRowsForMostVisitedTiles(getContext()));
            }
        }

        assert (activity instanceof BraveActivity);
        mActivity = activity;
        ((BraveActivity) mActivity).dismissShieldsTooltip();
        ((BraveActivity) mActivity).setNewTabPageManager(manager);
    }

    public void setTabProvider(Supplier<Tab> tabProvider) {
        mTabProvider = tabProvider;
    }

    private void showNTPImage(NTPImage ntpImage) {
        Display display = mActivity.getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        mNtpImageGlobal = ntpImage;
        if (mNtpAdapter != null) {
            mNtpAdapter.setNtpImage(ntpImage);
        }
    }

    private void checkForNonDisruptiveBanner(NTPImage ntpImage) {
        int brOption = NTPUtil.checkForNonDisruptiveBanner(ntpImage, mSponsoredTab);
        if (SponsoredImageUtil.BR_INVALID_OPTION != brOption && !NTPUtil.isReferralEnabled()
                && ((BraveRewardsHelper.isRewardsEnabled()
                        || BraveRewardsHelper.shouldShowBraveRewardsOnboardingModal()))
                && (!ContextUtils.getAppSharedPreferences().getBoolean(
                            BraveNewsPreferencesV2.PREF_SHOW_OPTIN, true)
                        && !BravePrefServiceBridge.getInstance().getShowNews())) {
            NTPUtil.showNonDisruptiveBanner(
                    (BraveActivity) mActivity, this, brOption, mSponsoredTab, mNewTabPageListener);
        }
    }

    private void checkAndShowNTPImage(boolean isReset) {
        NTPImage ntpImage = mSponsoredTab.getTabNTPImage(isReset);
        if (ntpImage == null) {
            mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
        } else if (ntpImage instanceof Wallpaper) {
            Wallpaper mWallpaper = (Wallpaper) ntpImage;
            if (mWallpaper == null) {
                mSponsoredTab.setNTPImage(SponsoredImageUtil.getBackgroundImage());
            }
        }
        checkForNonDisruptiveBanner(ntpImage);
        showNTPImage(ntpImage);
    }

    private void initilizeSponsoredTab() {
        if (TabAttributes.from(getTab()).get(String.valueOf(getTabImpl().getId())) == null) {
            SponsoredTab sponsoredTab = new SponsoredTab(mNTPBackgroundImagesBridge);
            TabAttributes.from(getTab()).set(String.valueOf(getTabImpl().getId()), sponsoredTab);
        }
        mSponsoredTab = TabAttributes.from(getTab()).get(String.valueOf((getTabImpl()).getId()));
        if (shouldShowSuperReferral()) mNTPBackgroundImagesBridge.getTopSites();
    }

    private NewTabPageListener mNewTabPageListener = new NewTabPageListener() {
        @Override
        public void updateInteractableFlag(boolean isBottomSheet) {
            mIsFromBottomSheet = isBottomSheet;
        }

        @Override
        public void updateNTPImage() {
            if (mSponsoredTab == null) {
                initilizeSponsoredTab();
            }
            checkAndShowNTPImage(false);
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

    private NTPBackgroundImagesBridge.NTPBackgroundImageServiceObserver mNTPBackgroundImageServiceObserver = new NTPBackgroundImagesBridge.NTPBackgroundImageServiceObserver() {
        @Override
        public void onUpdated() {
            if (NTPUtil.isReferralEnabled()) {
                checkAndShowNTPImage(true);
                if (shouldShowSuperReferral()) {
                    mNTPBackgroundImagesBridge.getTopSites();
                }
            }
        }
    };

    private FetchWallpaperWorkerTask.WallpaperRetrievedCallback mWallpaperRetrievedCallback =
            new FetchWallpaperWorkerTask.WallpaperRetrievedCallback() {
                @Override
                public void bgWallpaperRetrieved(Bitmap bgWallpaper) {
                    if (mBgImageView != null) {
                        mBgImageView.setImageBitmap(bgWallpaper);
                    }
                }

                @Override
                public void logoRetrieved(Wallpaper wallpaper, Bitmap logoWallpaper) {
                    if (!NTPUtil.isReferralEnabled()) {
                        mWallpaper = wallpaper;
                        mSponsoredLogo = logoWallpaper;
                        if (mNtpAdapter != null) {
                            mNtpAdapter.setSponsoredLogo(mWallpaper, logoWallpaper);
                        }
                    }
                }
            };

    private void loadTopSites(List<TopSiteTable> topSites) {
        mSuperReferralSitesLayout = new LinearLayout(mActivity);
        mSuperReferralSitesLayout.setWeightSum(1f);
        mSuperReferralSitesLayout.setOrientation(LinearLayout.HORIZONTAL);
        mSuperReferralSitesLayout.setBackgroundColor(
                mActivity.getResources().getColor(R.color.topsite_bg_color));

        LayoutInflater inflater =
                (LayoutInflater) mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        for (TopSiteTable topSite : topSites) {
            final View tileView = inflater.inflate(R.layout.suggestions_tile_view, null);

            TextView tileViewTitleTv = tileView.findViewById(R.id.tile_view_title);
            tileViewTitleTv.setText(topSite.getName());
            tileViewTitleTv.setTextColor(
                    getResources().getColor(R.color.brave_state_time_count_color));

            ImageView iconIv = tileView.findViewById(R.id.tile_view_icon);
            if (NTPUtil.imageCache.get(topSite.getDestinationUrl()) == null) {
                NTPUtil.imageCache.put(topSite.getDestinationUrl(),
                        new java.lang.ref.SoftReference(
                                NTPUtil.getTopSiteBitmap(topSite.getImagePath())));
            }
            iconIv.setImageBitmap(NTPUtil.imageCache.get(topSite.getDestinationUrl()).get());
            iconIv.setBackgroundColor(mActivity.getResources().getColor(android.R.color.white));
            iconIv.setClickable(false);

            tileView.setOnClickListener(
                    view -> { TabUtils.openUrlInSameTab(topSite.getDestinationUrl()); });

            tileView.setPadding(0, dpToPx(mActivity, 12), 0, 0);

            LinearLayout.LayoutParams layoutParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
            layoutParams.weight = 0.25f;
            layoutParams.gravity = Gravity.CENTER;
            tileView.setLayoutParams(layoutParams);
            tileView.setOnCreateContextMenuListener(new View.OnCreateContextMenuListener() {
                @Override
                public void onCreateContextMenu(
                        ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                    menu.add(R.string.contextmenu_open_in_new_tab)
                            .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    TabUtils.openUrlInNewTab(false, topSite.getDestinationUrl());
                                    return true;
                                }
                            });
                    menu.add(R.string.contextmenu_open_in_incognito_tab)
                            .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    TabUtils.openUrlInNewTab(true, topSite.getDestinationUrl());
                                    return true;
                                }
                            });
                    menu.add(R.string.contextmenu_save_link)
                            .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    if (getTab() != null) {
                                        OfflinePageBridge.getForProfile(mProfile).scheduleDownload(
                                                getTab().getWebContents(),
                                                OfflinePageBridge.NTP_SUGGESTIONS_NAMESPACE,
                                                topSite.getDestinationUrl(),
                                                DownloadUiActionFlags.ALL);
                                    } else {
                                        RequestCoordinatorBridge.getForProfile(mProfile)
                                                .savePageLater(topSite.getDestinationUrl(),
                                                        OfflinePageBridge.NTP_SUGGESTIONS_NAMESPACE,
                                                        true /* userRequested */);
                                    }
                                    return true;
                                }
                            });
                    menu.add(R.string.remove)
                            .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    NTPUtil.imageCache.remove(topSite.getDestinationUrl());
                                    mDatabaseHelper.deleteTopSite(topSite.getDestinationUrl());
                                    NTPUtil.addToRemovedTopSite(topSite.getDestinationUrl());
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

    private TabImpl getTabImpl() {
        return (TabImpl) getTab();
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
        mBraveNewsController = null;
        initBraveNewsController();
    }

    private void initBraveNewsController() {
        if (mBraveNewsController != null) {
            return;
        }

        mBraveNewsController =
                BraveNewsControllerFactory.getInstance().getBraveNewsController(this);

        if (mNtpAdapter != null) {
            mNtpAdapter.setBraveNewsController(mBraveNewsController);
        }
    }

    protected boolean isScrollableMvtEnabled() {
        return ChromeFeatureList.isEnabled(ChromeFeatureList.SHOW_SCROLLABLE_MVT_ON_NTP_ANDROID)
                && !DeviceFormFactor.isNonMultiDisplayContextOnTablet(mContext)
                && UserPrefs.get(Profile.getLastUsedRegularProfile())
                           .getBoolean(BravePref.NEW_TAB_PAGE_SHOW_BACKGROUND_IMAGE);
    }

    @Override
    void setSearchProviderTopMargin(int topMargin) {
        if (mLogoCoordinator != null) mLogoCoordinator.setTopMargin(topMargin);
    }

    @Override
    void setSearchProviderBottomMargin(int bottomMargin) {
        if (mLogoCoordinator != null) mLogoCoordinator.setBottomMargin(bottomMargin);
    }

    private BrowserExpressGetPostsUtil.GetPostsCallback getPostsCallback=
            new BrowserExpressGetPostsUtil.GetPostsCallback() {
                @Override
                public void getPostsSuccessful(List<Post> posts) {
                    Log.e("BE_GET_POST", "9"); 
                    mFeedProgress.setVisibility(View.GONE);
                    int len = mPosts.size();
                    mPosts.addAll(posts);
                    Log.e("BE_GET_POST", "10"); 
                    mPostAdapter.notifyItemRangeInserted(len-1, posts.size());
                }

                @Override
                public void getPostsFailed(String error) {
                    Log.e("BE_GET_POST", error);
                }
            };
}


// Copyright 2015 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.ntp;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.content.res.AppCompatResources;

import org.chromium.base.Callback;
import org.chromium.base.CallbackController;
import org.chromium.base.MathUtils;
import org.chromium.base.TraceEvent;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.compositor.layouts.content.InvalidationAwareThumbnailProvider;
import org.chromium.chrome.browser.cryptids.ProbabilisticCryptidRenderer;
import org.chromium.chrome.browser.feed.FeedSurfaceScrollDelegate;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.lens.LensEntryPoint;
import org.chromium.chrome.browser.lens.LensMetrics;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.logo.LogoBridge.Logo;
import org.chromium.chrome.browser.logo.LogoCoordinator;
import org.chromium.chrome.browser.logo.LogoUtils;
import org.chromium.chrome.browser.logo.LogoView;
import org.chromium.chrome.browser.ntp.NewTabPage.OnSearchBoxScrollListener;
import org.chromium.chrome.browser.ntp.search.SearchBoxCoordinator;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.query_tiles.QueryTileSection;
import org.chromium.chrome.browser.query_tiles.QueryTileUtils;
import org.chromium.chrome.browser.suggestions.tile.MostVisitedTilesCoordinator;
import org.chromium.chrome.browser.suggestions.tile.TileGroup;
import org.chromium.chrome.browser.suggestions.tile.TileGroup.Delegate;
import org.chromium.chrome.browser.ui.native_page.TouchEnabledDelegate;
import org.chromium.chrome.browser.user_education.IPHCommandBuilder;
import org.chromium.chrome.browser.user_education.UserEducationHelper;
import org.chromium.chrome.browser.util.BrowserUiUtils;
import org.chromium.chrome.browser.util.BrowserUiUtils.HostSurface;
import org.chromium.chrome.browser.util.BrowserUiUtils.ModuleTypeOnStartAndNTP;
import org.chromium.chrome.features.start_surface.StartSurfaceConfiguration;
import org.chromium.components.browser_ui.styles.ChromeColors;
import org.chromium.components.browser_ui.widget.displaystyle.UiConfig;
import org.chromium.components.browser_ui.widget.highlight.ViewHighlighter;
import org.chromium.components.browser_ui.widget.highlight.ViewHighlighter.HighlightParams;
import org.chromium.components.browser_ui.widget.highlight.ViewHighlighter.HighlightShape;
import org.chromium.components.feature_engagement.FeatureConstants;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.ui.base.DeviceFormFactor;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.text.EmptyTextWatcher;

/**
 * Layout for the new tab page. This positions the page elements in the correct vertical positions.
 * There are no separate phone and tablet UIs; this layout adapts based on the available space.
 */
public class NewTabPageLayout extends LinearLayout {
    private static final String TAG = "NewTabPageLayout";

    // Used to signify the cached resource value is unset.
    private static final int UNSET_RESOURCE_FLAG = -1;

    private final int mTileGridLayoutBleed;
    private int mSearchBoxTwoSideMargin;
    private final Context mContext;

    private final int mMvtLandscapeLateralMarginTablet;
    private final int mMvtExtraRightMarginTablet;

    private View mMiddleSpacer; // Spacer between toolbar and Most Likely.

    private LogoCoordinator mLogoCoordinator;
    private SearchBoxCoordinator mSearchBoxCoordinator;
    private QueryTileSection mQueryTileSection;
    private ImageView mCryptidHolder;
    private ViewGroup mMvTilesContainerLayout;
    private MostVisitedTilesCoordinator mMostVisitedTilesCoordinator;

    private OnSearchBoxScrollListener mSearchBoxScrollListener;

    private NewTabPageManager mManager;
    private Activity mActivity;
    private UiConfig mUiConfig;
    private CallbackController mCallbackController = new CallbackController();

    /**
     * Whether the tiles shown in the layout have finished loading.
     * With {@link #mHasShownView}, it's one of the 2 flags used to track initialisation progress.
     */
    private boolean mTilesLoaded;

    /**
     * Whether the view has been shown at least once.
     * With {@link #mTilesLoaded}, it's one of the 2 flags used to track initialization progress.
     */
    private boolean mHasShownView;

    private boolean mSearchProviderHasLogo = true;
    private boolean mSearchProviderIsGoogle;
    private boolean mShowingNonStandardLogo;

    private boolean mInitialized;

    private float mUrlFocusChangePercent;
    private boolean mDisableUrlFocusChangeAnimations;
    private boolean mIsViewMoving;

    /** Flag used to request some layout changes after the next layout pass is completed. */
    private boolean mTileCountChanged;
    private boolean mSnapshotTileGridChanged;
    private boolean mIsIncognito;
    private WindowAndroid mWindowAndroid;
    private boolean mIsNtpAsHomeSurfaceEnabled;

    /**
     * Vertical inset to add to the top and bottom of the search box bounds. May be 0 if no inset
     * should be applied. See {@link Rect#inset(int, int)}.
     */
    private int mSearchBoxBoundsVerticalInset;

    private FeedSurfaceScrollDelegate mScrollDelegate;

    private NewTabPageUma mNewTabPageUma;

    private int mTileViewWidth;
    private int mTileViewMinIntervalPaddingTablet;
    private Integer mInitialTileNum;
    private Boolean mIsHalfMvtLandscape;
    private Boolean mIsHalfMvtPortrait;
    private boolean mIsSurfacePolishEnabled;
    private boolean mIsSurfacePolishOmniboxColorEnabled;

    /**
     * Constructor for inflating from XML.
     */
    public NewTabPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        Resources res = getResources();
        mTileGridLayoutBleed = res.getDimensionPixelSize(R.dimen.tile_grid_layout_bleed);
        mMvtLandscapeLateralMarginTablet =
                res.getDimensionPixelSize(R.dimen.ntp_search_box_start_margin);
        mMvtExtraRightMarginTablet = res.getDimensionPixelSize(
                R.dimen.mvt_container_to_ntp_right_extra_margin_two_feed_tablet);
        mTileViewWidth =
                getResources().getDimensionPixelOffset(org.chromium.chrome.R.dimen.tile_view_width);
        mTileViewMinIntervalPaddingTablet = getResources().getDimensionPixelOffset(
                org.chromium.chrome.R.dimen.tile_carousel_layout_min_interval_margin_tablet);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMiddleSpacer = findViewById(R.id.ntp_middle_spacer);
        insertSiteSectionView();
    }

    /**
     * Initializes the NewTabPageLayout. This must be called immediately after inflation, before
     * this object is used in any other way.
     * @param manager NewTabPageManager used to perform various actions when the user interacts
     *                with the page.
     * @param activity The activity that currently owns the new tab page
     * @param tileGroupDelegate Delegate for {@link TileGroup}.
     * @param searchProviderHasLogo Whether the search provider has a logo.
     * @param searchProviderIsGoogle Whether the search provider is Google.
     * @param scrollDelegate The delegate used to obtain information about scroll state.
     * @param touchEnabledDelegate The {@link TouchEnabledDelegate} for handling whether touch
     *         events are allowed.
     * @param uiConfig UiConfig that provides display information about this view.
     * @param lifecycleDispatcher Activity lifecycle dispatcher.
     * @param uma {@link NewTabPageUma} object recording user metrics.
     * @param isIncognito Whether the new tab page is in incognito mode.
     * @param windowAndroid An instance of a {@link WindowAndroid}
     * @param isNtpAsHomeSurfaceEnabled {@code true} if the NTP is showing as the home surface.
     * @param isSurfacePolishEnabled {@code true} if the NTP surface is polished.
     */
    public void initialize(NewTabPageManager manager, Activity activity, Delegate tileGroupDelegate,
            boolean searchProviderHasLogo, boolean searchProviderIsGoogle,
            FeedSurfaceScrollDelegate scrollDelegate, TouchEnabledDelegate touchEnabledDelegate,
            UiConfig uiConfig, ActivityLifecycleDispatcher lifecycleDispatcher, NewTabPageUma uma,
            boolean isIncognito, WindowAndroid windowAndroid, boolean isNtpAsHomeSurfaceEnabled,
            boolean isSurfacePolishEnabled, boolean isSurfacePolishOmniboxColorEnabled) {
        TraceEvent.begin(TAG + ".initialize()");
        mScrollDelegate = scrollDelegate;
        mManager = manager;
        mActivity = activity;
        mUiConfig = uiConfig;
        mNewTabPageUma = uma;
        mIsIncognito = isIncognito;
        mWindowAndroid = windowAndroid;
        mIsNtpAsHomeSurfaceEnabled = isNtpAsHomeSurfaceEnabled;
        mIsSurfacePolishEnabled = isSurfacePolishEnabled;
        mIsSurfacePolishOmniboxColorEnabled = isSurfacePolishOmniboxColorEnabled;
        Profile profile = Profile.getLastUsedRegularProfile();

        mSearchBoxCoordinator = new SearchBoxCoordinator(getContext(), this);
        mSearchBoxCoordinator.initialize(lifecycleDispatcher, mIsIncognito, mWindowAndroid);
        if (!DeviceFormFactor.isNonMultiDisplayContextOnTablet(activity)) {
            if (isSurfacePolishEnabled) {
                int searchBoxHeightPolish =
                        getResources().getDimensionPixelSize(R.dimen.ntp_search_box_height_polish);
                mSearchBoxCoordinator.getView().getLayoutParams().height = searchBoxHeightPolish;
                mSearchBoxBoundsVerticalInset = (searchBoxHeightPolish
                                                        - getResources().getDimensionPixelSize(
                                                                R.dimen.toolbar_height_no_shadow))
                        / 2;
            } else {
                mSearchBoxBoundsVerticalInset = getResources().getDimensionPixelSize(
                        R.dimen.ntp_search_box_bounds_vertical_inset_modern);
            }
        }

        if (mIsNtpAsHomeSurfaceEnabled) {
            // We add extra side margins to the fake search box when multiple column Feeds are
            // shown. There is only one exception that we don't shorten the width of the fake search
            // box: one row of MV tiles in portrait mode.
            mSearchBoxTwoSideMargin =
                    getResources().getDimensionPixelSize(R.dimen.ntp_search_box_start_margin) * 2;
        } else if (mIsSurfacePolishEnabled) {
            mSearchBoxTwoSideMargin = getResources().getDimensionPixelSize(
                                              R.dimen.mvt_container_lateral_margin_polish)
                    * 2;
        }
        initializeLogoCoordinator(searchProviderHasLogo, searchProviderIsGoogle);
        initializeMostVisitedTilesCoordinator(profile, lifecycleDispatcher, tileGroupDelegate,
                touchEnabledDelegate, isScrollableMvtEnabled(), searchProviderIsGoogle);
        initializeSearchBoxBackground();
        initializeSearchBoxTextView();
        initializeVoiceSearchButton();
        initializeLensButton();
        initializeLayoutChangeListener();

        if (searchProviderIsGoogle && QueryTileUtils.isQueryTilesEnabledOnNTP()) {
            mQueryTileSection = new QueryTileSection(
                    findViewById(R.id.query_tiles), profile, mManager::performSearchQuery);
        }

        manager.addDestructionObserver(NewTabPageLayout.this::onDestroy);
        mInitialized = true;

        TraceEvent.end(TAG + ".initialize()");

        if (mIsSurfacePolishEnabled) {
            setBackground(
                    AppCompatResources.getDrawable(mContext, R.drawable.home_surface_background));
        }
    }

    /**
     * @return The {@link FeedSurfaceScrollDelegate} for this class.
     */
    FeedSurfaceScrollDelegate getScrollDelegate() {
        return mScrollDelegate;
    }

    /**
     * Sets up the search box background or background tint.
     */
    private void initializeSearchBoxBackground() {
        if (mIsSurfacePolishOmniboxColorEnabled) {
            findViewById(R.id.search_box)
                    .setBackground(AppCompatResources.getDrawable(
                            mContext, R.drawable.home_surface_search_box_background_colorful));
            return;
        }

        if (mIsSurfacePolishEnabled) {
            findViewById(R.id.search_box)
                    .setBackground(AppCompatResources.getDrawable(
                            mContext, R.drawable.home_surface_search_box_background_neutral));
            return;
        }

        final int elevationDimenId = ChromeFeatureList.sBaselineGm3SurfaceColors.isEnabled()
                ? R.dimen.default_elevation_4
                : R.dimen.toolbar_text_box_elevation;
        final int searchBoxColor = ChromeColors.getSurfaceColor(getContext(), elevationDimenId);
        final ColorStateList colorStateList = ColorStateList.valueOf(searchBoxColor);
        findViewById(R.id.search_box).setBackgroundTintList(colorStateList);
    }

    /**
     * Sets up the hint text and event handlers for the search box text view.
     */
    private void initializeSearchBoxTextView() {
        TraceEvent.begin(TAG + ".initializeSearchBoxTextView()");

        mSearchBoxCoordinator.setSearchBoxClickListener(v -> mManager.focusSearchBox(false, null));
        mSearchBoxCoordinator.setSearchBoxTextWatcher(new EmptyTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                mManager.focusSearchBox(false, s.toString());
                mSearchBoxCoordinator.setSearchText("");
            }
        });
        TraceEvent.end(TAG + ".initializeSearchBoxTextView()");
    }

    private void initializeVoiceSearchButton() {
        TraceEvent.begin(TAG + ".initializeVoiceSearchButton()");
        mSearchBoxCoordinator.addVoiceSearchButtonClickListener(
                v -> mManager.focusSearchBox(true, null));
        updateActionButtonVisibility();
        TraceEvent.end(TAG + ".initializeVoiceSearchButton()");
    }

    private void initializeLensButton() {
        TraceEvent.begin(TAG + ".initializeLensButton()");
        // TODO(b/181067692): Report user action for this click.
        mSearchBoxCoordinator.addLensButtonClickListener(v -> {
            LensMetrics.recordClicked(LensEntryPoint.NEW_TAB_PAGE);
            mSearchBoxCoordinator.startLens(LensEntryPoint.NEW_TAB_PAGE);
        });
        updateActionButtonVisibility();
        TraceEvent.end(TAG + ".initializeLensButton()");
    }

    private void initializeLayoutChangeListener() {
        TraceEvent.begin(TAG + ".initializeLayoutChangeListener()");
        addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int oldHeight = oldBottom - oldTop;
                    int newHeight = bottom - top;

                    if (oldHeight == newHeight && !mTileCountChanged) return;
                    mTileCountChanged = false;

                    // Re-apply the url focus change amount after a rotation to ensure the views are
                    // correctly placed with their new layout configurations.
                    onUrlFocusAnimationChanged();
                    updateSearchBoxOnScroll();

                    // The positioning of elements may have been changed (since the elements expand
                    // to fill the available vertical space), so adjust the scroll.
                    mScrollDelegate.snapScroll();
                });
        TraceEvent.end(TAG + ".initializeLayoutChangeListener()");
    }

    private void initializeLogoCoordinator(
            boolean searchProviderHasLogo, boolean searchProviderIsGoogle) {
        Callback<LoadUrlParams> logoClickedCallback =
                mCallbackController.makeCancelable((urlParams) -> {
                    mManager.getNativePageHost().loadUrl(urlParams, /*isIncognito=*/false);
                    BrowserUiUtils.recordModuleClickHistogram(
                            HostSurface.NEW_TAB_PAGE, ModuleTypeOnStartAndNTP.DOODLE);
                });
        Callback<Logo> onLogoAvailableCallback = mCallbackController.makeCancelable((logo) -> {
            mSnapshotTileGridChanged = true;
            mShowingNonStandardLogo = logo != null;
            maybeKickOffCryptidRendering();
        });
        Runnable onCachedLogoRevalidatedRunnable =
                mCallbackController.makeCancelable(this::maybeKickOffCryptidRendering);

        // If pull up Feed position is enabled, doodle is not supported since there is not enough
        // room, we don't need to fetch logo image.
        boolean shouldFetchDoodle = !FeedPositionUtils.isFeedPullUpEnabled();
        LogoView logoView = findViewById(R.id.search_provider_logo);
        if (mIsSurfacePolishEnabled) {
            LogoUtils.setLogoViewLayoutParams(logoView, getResources(),
                    DeviceFormFactor.isNonMultiDisplayContextOnTablet(getContext()),
                    StartSurfaceConfiguration.SURFACE_POLISH_LESS_BRAND_SPACE.getValue());
        } else if (mIsNtpAsHomeSurfaceEnabled) {
            logoView.getLayoutParams().height =
                    mContext.getResources().getDimensionPixelSize(R.dimen.ntp_logo_height_shrink);
        }

        mLogoCoordinator = new LogoCoordinator(mContext, logoClickedCallback, logoView,
                shouldFetchDoodle, onLogoAvailableCallback, onCachedLogoRevalidatedRunnable,
                /*isParentSurfaceShown=*/true,
                /*visibilityObserver=*/null);
        mLogoCoordinator.initWithNative();
        setSearchProviderInfo(searchProviderHasLogo, searchProviderIsGoogle);
    }

    private void initializeMostVisitedTilesCoordinator(Profile profile,
            ActivityLifecycleDispatcher activityLifecycleDispatcher,
            TileGroup.Delegate tileGroupDelegate, TouchEnabledDelegate touchEnabledDelegate,
            boolean isScrollableMvtEnabled, boolean searchProviderIsGoogle) {
        assert mMvTilesContainerLayout != null;

        int maxRows = 2;
        if (searchProviderIsGoogle && QueryTileUtils.isQueryTilesEnabledOnNTP()) {
            maxRows = QueryTileSection.getMaxRowsForMostVisitedTiles(getContext());
        }

        mMostVisitedTilesCoordinator = new MostVisitedTilesCoordinator(mActivity,
                activityLifecycleDispatcher, mMvTilesContainerLayout, mWindowAndroid,
                /*shouldShowSkeletonUIPreNative=*/false, isScrollableMvtEnabled, maxRows,
                () -> mSnapshotTileGridChanged = true, () -> {
                    if (mUrlFocusChangePercent == 1f) mTileCountChanged = true;
                });

        mMostVisitedTilesCoordinator.initWithNative(
                mManager, tileGroupDelegate, touchEnabledDelegate);
    }

    /**
     * Updates the search box when the parent view's scroll position is changed.
     */
    void updateSearchBoxOnScroll() {
        if (mDisableUrlFocusChangeAnimations || mIsViewMoving) return;

        // When the page changes (tab switching or new page loading), it is possible that events
        // (e.g. delayed view change notifications) trigger calls to these methods after
        // the current page changes. We check it again to make sure we don't attempt to update the
        // wrong page.
        if (!mManager.isCurrentPage()) return;

        if (mSearchBoxScrollListener != null) {
            mSearchBoxScrollListener.onNtpScrollChanged(getToolbarTransitionPercentage());
        }
    }

    /**
     * Calculates the percentage (between 0 and 1) of the transition from the search box to the
     * omnibox at the top of the New Tab Page, which is determined by the amount of scrolling and
     * the position of the search box.
     *
     * @return the transition percentage
     */
    float getToolbarTransitionPercentage() {
        // During startup the view may not be fully initialized.
        if (!mScrollDelegate.isScrollViewInitialized()) return 0f;

        if (isSearchBoxOffscreen()) {
            // getVerticalScrollOffset is valid only for the scroll view if the first item is
            // visible. If the search box view is offscreen, we must have scrolled quite far and we
            // know the toolbar transition should be 100%. This might be the initial scroll position
            // due to the scroll restore feature, so the search box will not have been laid out yet.
            return 1f;
        }

        // During startup the view may not be fully initialized, so we only calculate the current
        // percentage if some basic view properties (position of the search box) are sane.
        int searchBoxTop = getSearchBoxView().getTop();
        if (searchBoxTop == 0) return 0f;

        // For all other calculations, add the search box padding, because it defines where the
        // visible "border" of the search box is.
        searchBoxTop += getSearchBoxView().getPaddingTop();

        final int scrollY = mScrollDelegate.getVerticalScrollOffset();
        // Use int pixel size instead of float dimension to avoid precision error on the percentage.
        final float transitionLength =
                getResources().getDimensionPixelSize(R.dimen.ntp_search_box_transition_length);
        // Tab strip height is zero on phones, nonzero on tablets.
        int tabStripHeight = getResources().getDimensionPixelSize(R.dimen.tab_strip_height);

        // |scrollY - searchBoxTop + tabStripHeight| gives the distance the search bar is from the
        // top of the tab.
        return MathUtils.clamp(
                (scrollY - searchBoxTop + tabStripHeight + transitionLength) / transitionLength, 0f,
                1f);
    }

    private void insertSiteSectionView() {
        int insertionPoint = indexOfChild(mMiddleSpacer) + 1;

        if (ChromeFeatureList.sSurfacePolish.isEnabled()) {
            mMvTilesContainerLayout =
                    (ViewGroup) LayoutInflater.from(getContext())
                            .inflate(R.layout.mv_tiles_container_polish, this, false);
        } else {
            mMvTilesContainerLayout = (ViewGroup) LayoutInflater.from(getContext())
                                              .inflate(R.layout.mv_tiles_container, this, false);
        }
        mMvTilesContainerLayout.setVisibility(View.VISIBLE);
        addView(mMvTilesContainerLayout, insertionPoint);
        // The page contents are initially hidden; otherwise they'll be drawn centered on the
        // page before the tiles are available and then jump upwards to make space once the
        // tiles are available.
        if (getVisibility() != View.VISIBLE) setVisibility(View.VISIBLE);
    }

    /**
     * @return The fake search box view.
     */
    public View getSearchBoxView() {
        return mSearchBoxCoordinator.getView();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mIsNtpAsHomeSurfaceEnabled && isScrollableMvtEnabled()) {
            calculateTabletMvtMargin(MeasureSpec.getSize(widthMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        unifyElementWidths();
    }

    /**
     * Update the right margin for the MV tiles container if needed to have half tile element
     * in the end of the MV tiles when used in NTP on the tablet.
     */
    private void calculateTabletMvtMargin(int widthMeasureSpec) {
        if (mMvTilesContainerLayout.getVisibility() == GONE) return;

        if (mInitialTileNum == null) {
            mInitialTileNum = ((ViewGroup) findViewById(R.id.mv_tiles_layout)).getChildCount();
        }

        int currentOrientation = getResources().getConfiguration().orientation;
        if ((currentOrientation == Configuration.ORIENTATION_LANDSCAPE
                    && mIsHalfMvtLandscape == null)
                || (currentOrientation == Configuration.ORIENTATION_PORTRAIT
                        && mIsHalfMvtPortrait == null)) {
            MarginLayoutParams marginLayoutParams =
                    (MarginLayoutParams) mMvTilesContainerLayout.getLayoutParams();
            int mvtContainerWidth = widthMeasureSpec - marginLayoutParams.leftMargin
                    - marginLayoutParams.rightMargin;
            boolean isHalfMvt = mInitialTileNum * mTileViewWidth
                            + (mInitialTileNum - 1) * mTileViewMinIntervalPaddingTablet
                    > mvtContainerWidth;
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                mIsHalfMvtLandscape = isHalfMvt;
            } else {
                mIsHalfMvtPortrait = isHalfMvt;
            }
            updateTilesLayoutLeftAndRightMarginsOnTablet(marginLayoutParams);
        }
    }

    public void onSwitchToForeground() {
        if (mMostVisitedTilesCoordinator != null) {
            mMostVisitedTilesCoordinator.onSwitchToForeground();
        }
    }

    /**
     * Should be called every time one of the flags used to track initialization progress changes.
     * Finalizes initialization once all the preliminary steps are complete.
     *
     * @see #mHasShownView
     * @see #mTilesLoaded
     */
    private void onInitializationProgressChanged() {
        if (!hasLoadCompleted()) return;

        mManager.onLoadingComplete();

        // Load the logo after everything else is finished, since it's lower priority.
        mLogoCoordinator.loadSearchProviderLogoWithAnimation();
    }

    /**
     * To be called to notify that the tiles have finished loading. Will do nothing if a load was
     * previously completed.
     */
    public void onTilesLoaded() {
        if (mTilesLoaded) return;
        mTilesLoaded = true;

        onInitializationProgressChanged();
    }

    /**
     * Changes the layout depending on whether the selected search provider (e.g. Google, Bing)
     * has a logo.
     * @param hasLogo Whether the search provider has a logo.
     * @param isGoogle Whether the search provider is Google.
     */
    public void setSearchProviderInfo(boolean hasLogo, boolean isGoogle) {
        if (hasLogo == mSearchProviderHasLogo && isGoogle == mSearchProviderIsGoogle
                && mInitialized) {
            return;
        }
        mSearchProviderHasLogo = hasLogo;
        mSearchProviderIsGoogle = isGoogle;

        updateTilesLayoutMargins();

        // Hide or show the views above the tile grid as needed, including search box, and
        // spacers. The visibility of Logo is handled by LogoCoordinator.
        mSearchBoxCoordinator.setVisibility(mSearchProviderHasLogo);

        onUrlFocusAnimationChanged();

        mSnapshotTileGridChanged = true;
    }

    /**
     * Updates the margins for the tile grid based on what is shown above it.
     */
    private void updateTilesLayoutMargins() {
        MarginLayoutParams marginLayoutParams =
                (MarginLayoutParams) mMvTilesContainerLayout.getLayoutParams();

        if (mIsSurfacePolishEnabled) {
            if (mIsNtpAsHomeSurfaceEnabled) {
                if (isScrollableMvtEnabled()) {
                    marginLayoutParams.topMargin = getResources().getDimensionPixelSize(
                            shouldShowLogo() ? R.dimen.tile_grid_layout_top_margin
                                             : R.dimen.tile_grid_layout_no_logo_top_margin);
                } else {
                    // Set a bit more top padding on the tile grid if there is no logo.
                    ViewGroup.LayoutParams layoutParams = mMvTilesContainerLayout.getLayoutParams();
                    layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    marginLayoutParams.topMargin = getGridMvtTopMargin();
                }
            }
            return;
        }

        if (isScrollableMvtEnabled()) {
            // Let mMvTilesContainerLayout attached to the edge of the screen.
            setClipToPadding(false);
            if (mIsNtpAsHomeSurfaceEnabled) {
                updateTilesLayoutLeftAndRightMarginsOnTablet(marginLayoutParams);
            } else {
                int lateralPaddingsForNTP = -getResources().getDimensionPixelSize(
                        R.dimen.ntp_header_lateral_paddings_v2);
                marginLayoutParams.leftMargin = lateralPaddingsForNTP;
                marginLayoutParams.rightMargin = lateralPaddingsForNTP;
            }
            marginLayoutParams.topMargin = getResources().getDimensionPixelSize(shouldShowLogo()
                            ? R.dimen.tile_grid_layout_top_margin
                            : R.dimen.tile_grid_layout_no_logo_top_margin);
            marginLayoutParams.bottomMargin = getResources().getDimensionPixelOffset(
                    R.dimen.tile_carousel_layout_bottom_margin);
        } else {
            // Set a bit more top padding on the tile grid if there is no logo.
            ViewGroup.LayoutParams layoutParams = mMvTilesContainerLayout.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            marginLayoutParams.topMargin = getGridMvtTopMargin();
            marginLayoutParams.bottomMargin = getGridMvtBottomMargin();
        }

        if (mIsNtpAsHomeSurfaceEnabled) {
            marginLayoutParams.bottomMargin = getResources().getDimensionPixelOffset(
                    R.dimen.mvt_container_bottom_margin_tablet);
        }
    }

    /**
     * Updates whether the NewTabPage should animate on URL focus changes.
     * @param disable Whether to disable the animations.
     */
    void setUrlFocusAnimationsDisabled(boolean disable) {
        if (disable == mDisableUrlFocusChangeAnimations) return;
        mDisableUrlFocusChangeAnimations = disable;
        if (!disable) onUrlFocusAnimationChanged();
    }

    /**
     * @return Whether URL focus animations are currently disabled.
     */
    boolean urlFocusAnimationsDisabled() {
        return mDisableUrlFocusChangeAnimations;
    }

    /**
     * Specifies the percentage the URL is focused during an animation.  1.0 specifies that the URL
     * bar has focus and has completed the focus animation.  0 is when the URL bar is does not have
     * any focus.
     *
     * @param percent The percentage of the URL bar focus animation.
     */
    void setUrlFocusChangeAnimationPercent(float percent) {
        mUrlFocusChangePercent = percent;
        onUrlFocusAnimationChanged();
    }

    /**
     * @return The percentage that the URL bar is focused during an animation.
     */
    @VisibleForTesting
    float getUrlFocusChangeAnimationPercent() {
        return mUrlFocusChangePercent;
    }

    void onUrlFocusAnimationChanged() {
        if (mDisableUrlFocusChangeAnimations || mIsViewMoving) return;

        // Translate so that the search box is at the top, but only upwards.
        float percent = mSearchProviderHasLogo ? mUrlFocusChangePercent : 0;
        int basePosition = mScrollDelegate.getVerticalScrollOffset() + getPaddingTop();
        int target = Math.max(basePosition,
                getSearchBoxView().getBottom() - getSearchBoxView().getPaddingBottom()
                        - mSearchBoxBoundsVerticalInset);

        setTranslationY(percent * (basePosition - target));
        if (mQueryTileSection != null) mQueryTileSection.onUrlFocusAnimationChanged(percent);
    }

    void onLoadUrl(boolean isNtpUrl) {
        if (isNtpUrl && mQueryTileSection != null) mQueryTileSection.reloadTiles();
    }

    /**
     * Sets whether this view is currently moving within its parent view. When the view is moving
     * certain animations will be disabled or prevented.
     * @param isViewMoving Whether this view is currently moving.
     */
    void setIsViewMoving(boolean isViewMoving) {
        mIsViewMoving = isViewMoving;
    }

    /**
     * Updates the opacity of the search box when scrolling.
     *
     * @param alpha opacity (alpha) value to use.
     */
    public void setSearchBoxAlpha(float alpha) {
        mSearchBoxCoordinator.setAlpha(alpha);
    }

    /**
     * Updates the opacity of the search provider logo when scrolling.
     *
     * @param alpha opacity (alpha) value to use.
     */
    public void setSearchProviderLogoAlpha(float alpha) {
        mLogoCoordinator.setAlpha(alpha);
    }

    /**
     * Set the search box background drawable.
     *
     * @param drawable The search box background.
     */
    public void setSearchBoxBackground(Drawable drawable) {
        mSearchBoxCoordinator.setBackground(drawable);
    }

    /**
     * Get the bounds of the search box in relation to the top level {@code parentView}.
     *
     * @param bounds The current drawing location of the search box.
     * @param translation The translation applied to the search box by the parent view hierarchy up
     *                    to the {@code parentView}.
     * @param parentView The top level parent view used to translate search box bounds.
     */
    void getSearchBoxBounds(Rect bounds, Point translation, View parentView) {
        int searchBoxX = (int) getSearchBoxView().getX();
        int searchBoxY = (int) getSearchBoxView().getY();
        bounds.set(searchBoxX, searchBoxY, searchBoxX + getSearchBoxView().getWidth(),
                searchBoxY + getSearchBoxView().getHeight());

        translation.set(0, 0);

        if (isSearchBoxOffscreen()) {
            translation.y = Integer.MIN_VALUE;
        } else {
            View view = getSearchBoxView();
            while (true) {
                view = (View) view.getParent();
                if (view == null) {
                    // The |mSearchBoxView| is not a child of this view. This can happen if the
                    // RecyclerView detaches the NewTabPageLayout after it has been scrolled out of
                    // view. Set the translation to the minimum Y value as an approximation.
                    translation.y = Integer.MIN_VALUE;
                    break;
                }
                translation.offset(-view.getScrollX(), -view.getScrollY());
                if (view == parentView) break;
                translation.offset((int) view.getX(), (int) view.getY());
            }
        }

        bounds.offset(translation.x, translation.y);
        if (translation.y != Integer.MIN_VALUE) {
            bounds.inset(0, mSearchBoxBoundsVerticalInset);
        }
    }

    void setSearchProviderTopMargin(int topMargin) {
        mLogoCoordinator.setTopMargin(topMargin);
    }

    void setSearchProviderBottomMargin(int bottomMargin) {
        mLogoCoordinator.setBottomMargin(bottomMargin);
    }

    /**
     * @return Whether the search box view is scrolled off the screen.
     */
    private boolean isSearchBoxOffscreen() {
        return !mScrollDelegate.isChildVisibleAtPosition(0)
                || mScrollDelegate.getVerticalScrollOffset() > getSearchBoxView().getTop();
    }

    /**
     * Sets the listener for search box scroll changes.
     * @param listener The listener to be notified on changes.
     */
    void setSearchBoxScrollListener(OnSearchBoxScrollListener listener) {
        mSearchBoxScrollListener = listener;
        if (mSearchBoxScrollListener != null) updateSearchBoxOnScroll();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        assert mManager != null;

        if (!mHasShownView) {
            mHasShownView = true;
            onInitializationProgressChanged();
            TraceEvent.instant("NewTabPageSearchAvailable)");
        }
    }

    /** Update the visibility of the action buttons. */
    void updateActionButtonVisibility() {
        mSearchBoxCoordinator.setVoiceSearchButtonVisibility(mManager.isVoiceSearchEnabled());
        boolean shouldShowLensButton =
                mSearchBoxCoordinator.isLensEnabled(LensEntryPoint.NEW_TAB_PAGE);
        LensMetrics.recordShown(LensEntryPoint.NEW_TAB_PAGE, shouldShowLensButton);
        mSearchBoxCoordinator.setLensButtonVisibility(shouldShowLensButton);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);

        // On first run, the NewTabPageLayout is initialized behind the First Run Experience,
        // meaning the UiConfig will pickup the screen layout then. However onConfigurationChanged
        // is not called on orientation changes until the FRE is completed. This means that if a
        // user starts the FRE in one orientation, changes an orientation and then leaves the FRE
        // the UiConfig will have the wrong orientation. https://crbug.com/683886.
        mUiConfig.updateDisplayStyle();

        if (visibility == VISIBLE) {
            updateActionButtonVisibility();
        }
    }

    /**
     * @see InvalidationAwareThumbnailProvider#shouldCaptureThumbnail()
     */
    public boolean shouldCaptureThumbnail() {
        return mSnapshotTileGridChanged;
    }

    /**
     * Should be called before a thumbnail of the parent view is captured.
     * @see InvalidationAwareThumbnailProvider#captureThumbnail(Canvas)
     */
    public void onPreCaptureThumbnail() {
        mLogoCoordinator.endFadeAnimation();
        mSnapshotTileGridChanged = false;
    }

    private boolean shouldShowLogo() {
        return mSearchProviderHasLogo;
    }

    private boolean hasLoadCompleted() {
        return mHasShownView && mTilesLoaded;
    }

    private void maybeKickOffCryptidRendering() {
        if (!mSearchProviderIsGoogle || mShowingNonStandardLogo) {
            // Cryptid rendering is disabled when the logo is not the standard Google logo.
            return;
        }

        ProbabilisticCryptidRenderer renderer = ProbabilisticCryptidRenderer.getInstance();
        renderer.getCryptidForLogo(Profile.getLastUsedRegularProfile(),
                mCallbackController.makeCancelable((drawable) -> {
                    if (drawable == null || mCryptidHolder != null) {
                        return;
                    }
                    ViewStub stub =
                            findViewById(R.id.logo_holder).findViewById(R.id.cryptid_holder);
                    ImageView view = (ImageView) stub.inflate();
                    view.setImageDrawable(drawable);
                    mCryptidHolder = view;
                    renderer.recordRenderEvent();
                }));
    }

    private void onDestroy() {
        if (mCallbackController != null) {
            mCallbackController.destroy();
            mCallbackController = null;
        }

        if (mLogoCoordinator != null) {
            mLogoCoordinator.destroy();
            mLogoCoordinator = null;
        }

        mSearchBoxCoordinator.destroy();

        if (mMostVisitedTilesCoordinator != null) {
            mMostVisitedTilesCoordinator.destroyMvtiles();
            mMostVisitedTilesCoordinator = null;
        }
    }

    MostVisitedTilesCoordinator getMostVisitedTilesCoordinatorForTesting() {
        return mMostVisitedTilesCoordinator;
    }

    void maybeShowFeatureNotificationVoiceSearchIPH() {
        IPHCommandBuilder iphCommandBuilder = createIPHCommandBuilder(mActivity.getResources(),
                R.string.feature_notification_guide_tooltip_message_voice_search,
                R.string.feature_notification_guide_tooltip_message_voice_search,
                mSearchBoxCoordinator.getVoiceSearchButton(), true);
        UserEducationHelper userEducationHelper = new UserEducationHelper(mActivity, new Handler());
        userEducationHelper.requestShowIPH(iphCommandBuilder.build());
    }

    private static IPHCommandBuilder createIPHCommandBuilder(Resources resources,
            @StringRes int stringId, @StringRes int accessibilityStringId, View anchorView,
            boolean showHighlight) {
        IPHCommandBuilder iphCommandBuilder = new IPHCommandBuilder(resources,
                FeatureConstants.FEATURE_NOTIFICATION_GUIDE_VOICE_SEARCH_HELP_BUBBLE_FEATURE,
                stringId, accessibilityStringId);
        iphCommandBuilder.setAnchorView(anchorView);
        int yInsetPx = resources.getDimensionPixelOffset(R.dimen.ntp_iph_searchbox_y_inset);
        iphCommandBuilder.setInsetRect(new Rect(0, 0, 0, -yInsetPx));
        if (showHighlight) {
            iphCommandBuilder.setOnShowCallback(
                    ()
                            -> ViewHighlighter.turnOnHighlight(
                                    anchorView, new HighlightParams(HighlightShape.CIRCLE)));
            iphCommandBuilder.setOnDismissCallback(() -> new Handler().postDelayed(() -> {
                ViewHighlighter.turnOffHighlight(anchorView);
            }, ViewHighlighter.IPH_MIN_DELAY_BETWEEN_TWO_HIGHLIGHTS));
        }

        return iphCommandBuilder;
    }

    /**
     * Makes the Search Box and Logo as wide as Most Visited.
     */
    private void unifyElementWidths() {
        View searchBoxView = getSearchBoxView();
        if (mMvTilesContainerLayout.getVisibility() != GONE) {
            final int width =
                    getMeasuredWidth() - (mIsSurfacePolishEnabled ? 0 : mTileGridLayoutBleed);
            if (!isScrollableMvtEnabled()) {
                measureExactly(searchBoxView, width - mSearchBoxTwoSideMargin,
                        searchBoxView.getMeasuredHeight());
                mLogoCoordinator.measureExactlyLogoView(width);
            } else {
                // We reset the extra margins of the fake search box if the scrollable MV tiles are
                // showing in the portrait mode with multiple column Feeds.
                int searchBoxTwoSideMargin = mSearchBoxTwoSideMargin;
                if (mSearchBoxTwoSideMargin != 0
                        && getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT
                        && !mIsSurfacePolishEnabled) {
                    searchBoxTwoSideMargin = 0;
                }

                measureExactly(searchBoxView, width - searchBoxTwoSideMargin,
                        searchBoxView.getMeasuredHeight());
                mLogoCoordinator.measureExactlyLogoView(width);
            }
        }
    }

    private boolean isScrollableMvtEnabled() {
        return NewTabPage.isScrollableMvtEnabled(mContext);
    }

    // TODO(crbug.com/1329288): Remove this method when the Feed position experiment is cleaned up.
    private int getGridMvtTopMargin() {
        if (!shouldShowLogo()) {
            return getResources().getDimensionPixelOffset(
                    R.dimen.tile_grid_layout_no_logo_top_margin);
        }

        int resourcesId = R.dimen.tile_grid_layout_top_margin;

        if (FeedPositionUtils.isFeedPushDownLargeEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_top_margin_push_down_large;
        } else if (FeedPositionUtils.isFeedPushDownSmallEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_top_margin_push_down_small;
        } else if (FeedPositionUtils.isFeedPullUpEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_top_margin_pull_up;
        }

        return getResources().getDimensionPixelOffset(resourcesId);
    }

    // TODO(crbug.com/1329288): Remove this method when the Feed position experiment is cleaned up.
    private int getGridMvtBottomMargin() {
        int resourcesId = R.dimen.tile_grid_layout_bottom_margin;

        if (!shouldShowLogo()) return getResources().getDimensionPixelOffset(resourcesId);

        if (FeedPositionUtils.isFeedPushDownLargeEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_bottom_margin_push_down_large;
        } else if (FeedPositionUtils.isFeedPushDownSmallEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_bottom_margin_push_down_small;
        } else if (FeedPositionUtils.isFeedPullUpEnabled()) {
            resourcesId = R.dimen.tile_grid_layout_bottom_margin_pull_up;
        }

        return getResources().getDimensionPixelOffset(resourcesId);
    }

    /**
     * Convenience method to call measure() on the given View with MeasureSpecs converted from the
     * given dimensions (in pixels) with MeasureSpec.EXACTLY.
     */
    private static void measureExactly(View view, int widthPx, int heightPx) {
        view.measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY));
    }

    LogoCoordinator getLogoCoordinatorForTesting() {
        return mLogoCoordinator;
    }

    /**
     * Modify the margins of the container for MV tiles when the orientation of the tablet changes.
     * @param newConfig The new resource configuration.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mIsNtpAsHomeSurfaceEnabled || !isScrollableMvtEnabled()) {
            return;
        }
        MarginLayoutParams marginLayoutParams =
                (MarginLayoutParams) mMvTilesContainerLayout.getLayoutParams();
        updateTilesLayoutLeftAndRightMarginsOnTablet(marginLayoutParams);
    }

    /**
     * Updates the margins for the MV tiles container when used in NTP on the tablet.
     * @param marginLayoutParams The {@link MarginLayoutParams} of the MV tiles container.
     */
    private void updateTilesLayoutLeftAndRightMarginsOnTablet(
            MarginLayoutParams marginLayoutParams) {
        ((LayoutParams) marginLayoutParams).gravity = Gravity.CENTER_HORIZONTAL;
        int leftMarginForNtp = mTileGridLayoutBleed / 2;
        int rightMarginForNtp = leftMarginForNtp;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            leftMarginForNtp = leftMarginForNtp + mMvtLandscapeLateralMarginTablet;
            rightMarginForNtp = rightMarginForNtp + mMvtLandscapeLateralMarginTablet;
            if (mIsHalfMvtLandscape != null && mIsHalfMvtLandscape) {
                ((LayoutParams) marginLayoutParams).gravity = Gravity.START;
                rightMarginForNtp = rightMarginForNtp + mMvtExtraRightMarginTablet;
            }
        } else if (mIsHalfMvtPortrait != null && mIsHalfMvtPortrait) {
            ((LayoutParams) marginLayoutParams).gravity = Gravity.START;
            rightMarginForNtp = rightMarginForNtp + mMvtExtraRightMarginTablet;
        }
        marginLayoutParams.leftMargin = leftMarginForNtp;
        marginLayoutParams.rightMargin = rightMarginForNtp;
    }
}
