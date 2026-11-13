package org.chromium.chrome.browser.ntp;

import android.view.GestureDetector;
import android.os.Build;
import android.os.Bundle;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import android.widget.TextView;
import android.view.View;
import org.chromium.base.Log;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.media.MediaPlayer;
import android.widget.ImageView;
import android.widget.VideoView;
import android.widget.Button;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;
import org.chromium.chrome.R;
import android.view.LayoutInflater;
import org.chromium.chrome.browser.app.BraveActivity;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import org.chromium.base.task.AsyncTask;
import java.util.Locale;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.view.inputmethod.InputMethodManager;
import android.view.HapticFeedbackConstants;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import org.chromium.chrome.browser.browser_express_comments.BrowserExpressGetCommentsUtil;
import org.chromium.chrome.browser.browser_express_comments.Vote;
import org.chromium.chrome.browser.browser_express_comments.BrowserExpressAddVoteUtil;
import com.bumptech.glide.Glide;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import android.content.Intent;
import android.net.Uri;
import androidx.cardview.widget.CardView;
import org.chromium.chrome.browser.util.TabUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.ProgressBar;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import org.chromium.chrome.browser.browser_express_comments.CommentListAdapter;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearSnapHelper;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.Util;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.widget.ProgressBar;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.view.animation.DecelerateInterpolator;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import org.chromium.chrome.browser.crypto_wallet.util.AndroidUtils;
import org.chromium.chrome.browser.local_database.TopSiteTable;
import static org.chromium.ui.base.ViewUtils.dpToPx;
import org.chromium.ui.base.ViewUtils;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import android.widget.Space;
import org.chromium.chrome.browser.lens.LensController;
import org.chromium.chrome.browser.lens.LensEntryPoint;
import org.chromium.chrome.browser.lens.LensIntentParams;
import org.chromium.chrome.browser.lens.LensQueryParams;
import org.chromium.chrome.browser.omnibox.LocationBar;
import org.chromium.chrome.browser.omnibox.OmniboxFocusReason;
import org.chromium.chrome.browser.omnibox.voice.VoiceRecognitionHandler;
import org.chromium.chrome.browser.toolbar.ToolbarManager;
import org.chromium.ui.base.DeviceFormFactor;
import android.content.res.Resources;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.widget.SeekBar;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import androidx.core.widget.ImageViewCompat;
import androidx.media3.common.VideoSize;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import org.chromium.chrome.browser.browser_express_comments.CommentMediaCache;
import org.chromium.chrome.browser.browser_express_comments.GlobalVideoPlaybackManager;
import org.chromium.chrome.browser.browser_express_comments.ReactionMix;
import org.chromium.chrome.browser.browser_express_comments.SourceChip;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.os.SystemClock;
import android.widget.PopupMenu;

public class PostListAdapter extends RecyclerView.Adapter {
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_POST = 1;
    private static final int VIEW_TYPE_FOOTER = 2;

    /** No row after the posts: the feed is between pages, or has ended. */
    public static final int FOOTER_NONE = 0;

    /** A spinner after the posts while the next page loads. */
    public static final int FOOTER_LOADING = 1;

    /** A page failed to load; tapping the row after the posts tries again. */
    public static final int FOOTER_ERROR = 2;

    private final Context mContext;
    private List<Post> mPostList;
    private RecyclerView mTopPostRecycler;
    private List<TopSiteTable> mTopSites;
    private BraveNewTabPageLayout mParentLayout;
    private HeaderViewHolder mHeaderViewHolder;
    private static final String TWITTER_TYPE = "Twitter";
    private static final String INSTAGRAM_TYPE = "Instagram";

    private boolean mIsLoading = true;
    private int mFooterState = FOOTER_NONE;
    @Nullable private Runnable mFeedRetryAction;
    private String mTutorialVideoUrl;
    private String mTutorialImageUrl;
    private boolean mTutorialVideoDismissed;

    /** The one voice post playing, if any; feed videos stay silent while it plays. */
    @Nullable private PostHolder mPlayingAudio;

    /**
     * How much of a video must be on screen before it can be the one playing. Keeps a card that
     * is barely peeking in from taking over mid-scroll.
     */
    private static final float MIN_PLAYING_VISIBLE_FRACTION = 0.5f;

    /**
     * Videos whose on-screen shares differ by less than this count as equally visible; the one
     * nearer the middle of the screen plays.
     */
    private static final float VISIBLE_FRACTION_TIE = 0.05f;

    /** Throttle for the visibility pass, so a fling does not re-run it on every frame. */
    private static final long VIDEO_CHECK_DELAY_MS = 100;

    /**
     * How much of a card must be on screen to count as seen: half of it, or half the screen for a
     * card taller than that.
     */
    private static final float MIN_IMPRESSION_VISIBLE_FRACTION = 0.5f;

    /** A post is only reported as seen once it has been on screen this long in a feed session. */
    private static final long MIN_IMPRESSION_MS = 1000;

    /**
     * This showing of the feed, for {@link FeedEvents}. The new tab page builds a fresh adapter
     * each time it is shown, so each visit is its own session.
     */
    private final String mFeedSessionId = UUID.randomUUID().toString();

    /** The time each post seen in this session has spent on screen, by post id. */
    private final Map<String, Impression> mImpressions = new HashMap<>();
    /** Posts whose "new takes" callout was reported seen this session. */
    private final Set<String> mNewTakesShown = new HashSet<>();

    /** What the reader did in this session, for {@link #reportFeedSession}. */
    private int mPostsOpened;
    private int mPostsHidden;
    private long mVideoWatchMs;

    /** A post's time on screen in this feed session. */
    private static final class Impression {
        final int position;
        /** {@link SystemClock#elapsedRealtime} when it last came on screen; -1 while off it. */
        long visibleSinceMs = -1;
        /** On-screen time not yet handed to {@link FeedEvents}. */
        long unreportedMs;
        /** All its on-screen time in the session. */
        long totalMs;
        /** Whether it has reached {@link #MIN_IMPRESSION_MS}, after which every stretch counts. */
        boolean reported;

        Impression(int position) {
            this.position = position;
        }
    }

    /**
     * Whether the feed plays sound at all. This is one setting for the whole feed rather than
     * per card: which card is audible follows the scroll position, so a per-card mute would be
     * undone the moment the reader scrolled on. Every speaker button toggles this.
     *
     * <p>Videos start muted, and the setting lasts for the app session: the new tab page builds a
     * fresh adapter every time it is shown again, which would otherwise mute the feed behind the
     * reader's back after they had turned the sound on.
     */
    private static boolean sSoundOn;

    /** The single card currently allowed to make sound: the one playing, unless muted. */
    @Nullable private PostHolder mAudibleHolder;

    /**
     * False while another window is in front — the comments sheet, the media viewer, or the app
     * in the background. No feed video plays underneath any of those.
     */
    private boolean mWindowFocused = true;

    private final Handler mVideoCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVideoCheckRunnable = this::updatePlayingVideo;
    private boolean mVideoCheckPending;

    private final RecyclerView.OnScrollListener mVideoScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    scheduleVideoCheck();
                }

                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updatePlayingVideo();
                }
            };

    private final ViewTreeObserver.OnWindowFocusChangeListener mWindowFocusListener =
            hasFocus -> {
                mWindowFocused = hasFocus;
                // A voice post must not keep talking under the comments sheet or in the background.
                if (!hasFocus && mPlayingAudio != null) mPlayingAudio.pauseAudio();
                updatePlayingVideo();
                // Back from the comments: the sheet may still be closing (and only then lets
                // videos play again), so look once more shortly after.
                if (hasFocus) scheduleVideoCheck();
            };

    public PostListAdapter(Context context, List<Post> postList, RecyclerView topPostRecycler, List<TopSiteTable> topSites, BraveNewTabPageLayout parentLayout) {
        mContext = context;
        mPostList = postList;
        mTopPostRecycler = topPostRecycler;
        mTopSites = topSites != null ? topSites : new ArrayList<>();
        mParentLayout = parentLayout;
    }

    public void setLoading(boolean isLoading) {
        mIsLoading = isLoading;
        if (mHeaderViewHolder != null) {
            notifyItemChanged(0);
        }
    }

    /**
     * Shows the row after the posts as {@code state}: {@link #FOOTER_NONE}, {@link
     * #FOOTER_LOADING} or {@link #FOOTER_ERROR}. Must not be called from a layout or scroll
     * callback, as it changes the adapter.
     */
    public void setFooterState(int state) {
        if (state == mFooterState) return;
        int position = mPostList.size() + 1;
        boolean wasShown = mFooterState != FOOTER_NONE;
        mFooterState = state;
        if (!wasShown) {
            notifyItemInserted(position);
        } else if (state == FOOTER_NONE) {
            notifyItemRemoved(position);
        } else {
            notifyItemChanged(position);
        }
    }

    /** What tapping the error row runs. */
    public void setFeedRetryAction(@Nullable Runnable retry) {
        mFeedRetryAction = retry;
    }

    private boolean hasTutorialMedia() {
        return (mTutorialVideoUrl != null || mTutorialImageUrl != null) && !mTutorialVideoDismissed;
    }

    @Override
    public int getItemCount() {
        // The header, the posts, then the loading or error row while one shows.
        return mPostList.size() + 1 + (mFooterState != FOOTER_NONE ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return VIEW_TYPE_HEADER;
        if (position > mPostList.size()) return VIEW_TYPE_FOOTER;
        return VIEW_TYPE_POST;
    }

    /** Returns the number of non-post rows (header) before posts start. */
    public int getPostAdapterOffset() {
        return 1; // header only
    }

    /** Returns the post index for a given adapter position, accounting for header row. */
    private int getPostIndex(int adapterPosition) {
        return adapterPosition - 1;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        recyclerView.addOnScrollListener(mVideoScrollListener);
        ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnWindowFocusChangeListener(mWindowFocusListener);
        scheduleVideoCheck();
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        recyclerView.removeOnScrollListener(mVideoScrollListener);
        ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(mWindowFocusListener);
        mVideoCheckHandler.removeCallbacks(mVideoCheckRunnable);
        mVideoCheckPending = false;
        mAudibleHolder = null;
        // The feed is leaving the screen: what was on it stops being seen, and the session's
        // events go out.
        endImpressions(Collections.emptySet());
        FeedEvents.flushAsync("feed_left");
        reportFeedSession();
    }

    /**
     * This showing of the feed in one PostHog event: how many posts were seen (a second or more
     * on screen), how far down, and what the reader did there. The per-post detail goes to the
     * server through {@link FeedEvents} instead.
     */
    private void reportFeedSession() {
        int seen = 0;
        int deepest = -1;
        long seenMs = 0;
        for (Impression impression : mImpressions.values()) {
            if (!impression.reported) continue;
            seen++;
            deepest = Math.max(deepest, impression.position);
            seenMs += impression.totalMs;
        }
        if (seen == 0 && mPostsOpened == 0) return;
        PostHogUtil.capture(
                PostHogEventKeys.FEED_SESSION_ENDED,
                "posts_seen",
                seen,
                "deepest_position",
                deepest,
                "seen_ms",
                seenMs,
                "posts_opened",
                mPostsOpened,
                "posts_hidden",
                Math.max(0, mPostsHidden),
                "video_watch_ms",
                mVideoWatchMs);
        // Shown again, the same adapter starts a fresh count.
        mImpressions.clear();
        mPostsOpened = 0;
        mPostsHidden = 0;
        mVideoWatchMs = 0;
    }

    /** Queues a visibility pass, at most one per {@link #VIDEO_CHECK_DELAY_MS}. */
    private void scheduleVideoCheck() {
        if (mVideoCheckPending) return;
        mVideoCheckPending = true;
        mVideoCheckHandler.postDelayed(mVideoCheckRunnable, VIDEO_CHECK_DELAY_MS);
    }

    /**
     * Plays the feed's most visible video and pauses every other one, so two videos on screen
     * never play at once. The playing video takes the slot the feed shares with the comments
     * ({@link GlobalVideoPlaybackManager}): starting it stops a comment video, and a comment video
     * starting stops it. It is also the audible one, unless the feed is muted or a voice post is
     * talking.
     *
     * <p>Most visible means the largest share of the video on screen, at least {@link
     * #MIN_PLAYING_VISIBLE_FRACTION}; of videos equally on screen, the one nearer the middle.
     * Nothing in the feed plays while another window is in front (the comments sheet, a viewer)
     * or the app is in the background. The state is re-derived from scratch on every call, which
     * makes this safe to run as often as the scroll, the bound cards or the window focus change.
     */
    private void updatePlayingVideo() {
        mVideoCheckHandler.removeCallbacks(mVideoCheckRunnable);
        mVideoCheckPending = false;
        if (mTopPostRecycler == null) return;

        GlobalVideoPlaybackManager.Playable chosen = null;
        Rect viewport = new Rect();
        if (mWindowFocused
                && mTopPostRecycler.isAttachedToWindow()
                && mTopPostRecycler.getGlobalVisibleRect(viewport)) {
            int viewportCenterY = viewport.centerY();
            float chosenFraction = 0f;
            int chosenDistance = Integer.MAX_VALUE;
            // The tutorial video in the header is a candidate like any card.
            int count = mTopPostRecycler.getChildCount();
            for (int i = -1; i < count; i++) {
                GlobalVideoPlaybackManager.Playable candidate;
                View videoView;
                if (i < 0) {
                    if (mHeaderViewHolder == null || !mHeaderViewHolder.hasTutorialVideo()) {
                        continue;
                    }
                    candidate = mHeaderViewHolder;
                    videoView = mHeaderViewHolder.tutorialPlayerView;
                } else {
                    PostHolder holder = videoHolderAt(i);
                    if (holder == null) continue;
                    candidate = holder;
                    videoView = holder.mVideoView;
                }
                float fraction = visibleFractionOf(videoView);
                if (fraction < MIN_PLAYING_VISIBLE_FRACTION) continue;
                int distance = Math.abs(centerYOnScreen(videoView) - viewportCenterY);
                boolean moreVisible = fraction > chosenFraction + VISIBLE_FRACTION_TIE;
                boolean asVisible = Math.abs(fraction - chosenFraction) <= VISIBLE_FRACTION_TIE;
                if (chosen == null || moreVisible || (asVisible && distance < chosenDistance)) {
                    chosen = candidate;
                    chosenFraction = fraction;
                    chosenDistance = distance;
                }
            }
        }

        for (int i = 0; i < mTopPostRecycler.getChildCount(); i++) {
            PostHolder holder = videoHolderAt(i);
            if (holder == null) continue;
            if (holder != chosen) pauseFeedVideo(holder);
            holder.applyAudioState(holder == chosen && mPlayingAudio == null);
        }
        if (mHeaderViewHolder != null && mHeaderViewHolder != chosen) {
            pauseFeedVideo(mHeaderViewHolder);
        }
        // With nothing to play, the feed only stops its own videos (above): the slot may belong
        // to a comment video in the sheet over it.
        boolean playing =
                chosen != null && GlobalVideoPlaybackManager.getInstance().play(chosen);
        mAudibleHolder =
                playing && chosen instanceof PostHolder && mPlayingAudio == null
                        ? (PostHolder) chosen
                        : null;

        // The same moments (scroll, cards coming and going, focus) move the impression clock.
        updateImpressions();
    }

    /**
     * Starts the on-screen clock of each card that is now at least {@link
     * #MIN_IMPRESSION_VISIBLE_FRACTION} visible and stops the others'. Nothing counts as seen
     * while another window is in front or the app is in the background.
     */
    private void updateImpressions() {
        Set<String> onScreen = new HashSet<>();
        Rect viewport = new Rect();
        if (mWindowFocused
                && mTopPostRecycler != null
                && mTopPostRecycler.isAttachedToWindow()
                && mTopPostRecycler.getGlobalVisibleRect(viewport)) {
            long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < mTopPostRecycler.getChildCount(); i++) {
                View child = mTopPostRecycler.getChildAt(i);
                RecyclerView.ViewHolder viewHolder = mTopPostRecycler.getChildViewHolder(child);
                if (!(viewHolder instanceof PostHolder)) continue;
                PostHolder holder = (PostHolder) viewHolder;
                Post post = holder.mPost;
                int position = holder.getBindingAdapterPosition();
                if (post == null || position == RecyclerView.NO_POSITION) continue;
                if (cardFractionOnScreen(holder.mCard, viewport)
                        < MIN_IMPRESSION_VISIBLE_FRACTION) {
                    continue;
                }
                onScreen.add(post.getId());
                NewTakes newTakes = post.getNewTakes();
                if (newTakes != null && mNewTakesShown.add(post.getId())) {
                    PostHogUtil.capture(
                            PostHogEventKeys.NEW_TAKES_SHOWN,
                            "post_id",
                            post.getId(),
                            "count",
                            newTakes.count);
                }
                Impression impression = mImpressions.get(post.getId());
                if (impression == null) {
                    impression = new Impression(getPostIndex(position));
                    mImpressions.put(post.getId(), impression);
                }
                if (impression.visibleSinceMs < 0) impression.visibleSinceMs = now;
            }
        }
        endImpressions(onScreen);
    }

    /** Stops the clock of every post on screen until now except {@code stillOnScreen}. */
    private void endImpressions(Set<String> stillOnScreen) {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Impression> entry : mImpressions.entrySet()) {
            Impression impression = entry.getValue();
            if (impression.visibleSinceMs < 0 || stillOnScreen.contains(entry.getKey())) continue;
            impression.unreportedMs += now - impression.visibleSinceMs;
            impression.totalMs += now - impression.visibleSinceMs;
            impression.visibleSinceMs = -1;
            if (!impression.reported && impression.unreportedMs < MIN_IMPRESSION_MS) continue;
            impression.reported = true;
            FeedEvents.recordImpression(
                    entry.getKey(), mFeedSessionId, impression.position, impression.unreportedMs);
            impression.unreportedMs = 0;
        }
    }

    /**
     * How much of a card is on screen, from 0 to 1. A card taller than the list counts by how much
     * of the list it fills, or it could never be half seen.
     */
    private static float cardFractionOnScreen(View card, Rect viewport) {
        int height = card.getHeight();
        if (card.getVisibility() != View.VISIBLE || !card.isAttachedToWindow() || height <= 0) {
            return 0f;
        }
        Rect visible = new Rect();
        if (!card.getGlobalVisibleRect(visible)) return 0f;
        return Math.min(1f, visible.height() / (float) Math.min(height, viewport.height()));
    }

    /** Pauses a feed video, giving up the shared slot if it held it. */
    private static void pauseFeedVideo(GlobalVideoPlaybackManager.Playable video) {
        GlobalVideoPlaybackManager.getInstance().stop(video);
        video.stopPlayback();
    }

    /** The recycler's i-th attached child as a post holder with a live video, or null. */
    @Nullable
    private PostHolder videoHolderAt(int childIndex) {
        View child = mTopPostRecycler.getChildAt(childIndex);
        if (child == null) return null;
        RecyclerView.ViewHolder viewHolder = mTopPostRecycler.getChildViewHolder(child);
        if (!(viewHolder instanceof PostHolder)) return null;
        PostHolder holder = (PostHolder) viewHolder;
        return holder.hasVideo() ? holder : null;
    }

    /** How much of {@code view} is on screen, from 0 (off screen) to 1 (whole). */
    private float visibleFractionOf(View view) {
        // A detached view (a header the list has cached off screen) would report its whole
        // self as visible.
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isAttachedToWindow()) {
            return 0f;
        }
        int height = view.getHeight();
        if (height <= 0) return 0f;
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return 0f;
        return Math.min(1f, visible.height() / (float) height);
    }

    /**
     * The view's midpoint in screen coordinates. Taken from the view's own bounds rather than
     * from its visible rect, whose centre drifts as the view is clipped by the viewport edge.
     */
    private int centerYOnScreen(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1] + view.getHeight() / 2;
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        // A card coming back from the list's off-screen cache gets its player back.
        if (holder instanceof PostHolder) ((PostHolder) holder).restoreVideo();
        // A card arriving may be the new most visible video, so playback may have to move.
        scheduleVideoCheck();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        // Off screen (scrolled away, or the new tab page itself left): its video stops.
        if (holder instanceof GlobalVideoPlaybackManager.Playable) {
            pauseFeedVideo((GlobalVideoPlaybackManager.Playable) holder);
        }
        if (!(holder instanceof PostHolder)) {
            scheduleVideoCheck();
            return;
        }
        PostHolder postHolder = (PostHolder) holder;
        postHolder.releaseVideoOffScreen();
        if (mAudibleHolder == postHolder) {
            postHolder.applyAudioState(false);
            mAudibleHolder = null;
        }
        // A voice post scrolled out of view stops rather than playing on unseen.
        postHolder.pauseAudio();
        scheduleVideoCheck();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof PostHolder) ((PostHolder) holder).cleanup();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

        try {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.ntp_header, parent, false);

                    mHeaderViewHolder = new HeaderViewHolder(view);
                    return mHeaderViewHolder;

                case VIEW_TYPE_FOOTER:
                    view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.browser_express_feed_footer, parent, false);
                    return new FooterHolder(view);

                default:
                    view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.browser_express_post, parent, false);
                    return new PostHolder(view);
            }
        } catch (Exception e) {
            Log.e("PostListAdapter", "Error in onCreateViewHolder", e);
            throw e;
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_HEADER:
                ((HeaderViewHolder) holder).bind(mTopSites, mIsLoading, mTutorialVideoUrl, mTutorialImageUrl, hasTutorialMedia());
                break;
            case VIEW_TYPE_POST:
                Post post = mPostList.get(getPostIndex(position));
                ((PostHolder) holder).bind(post);
                break;
            case VIEW_TYPE_FOOTER:
                ((FooterHolder) holder).bind(mFooterState);
                break;
        }
    }

    public void showShimmer() {
        if (mHeaderViewHolder != null) {
            mHeaderViewHolder.showShimmer();
        }
    }

    public void hideShimmer() {
        if (mHeaderViewHolder != null) {
            mHeaderViewHolder.hideShimmer();
        }
    }

    public void updateTopSites(List<TopSiteTable> topSites) {
        mTopSites = topSites != null ? topSites : new ArrayList<>();
        notifyItemChanged(0);
    }

    public void setTutorialMedia(String videoUrl, String imageUrl) {
        mTutorialVideoUrl = videoUrl;
        mTutorialImageUrl = imageUrl;
        mTutorialVideoDismissed = false;
        notifyItemChanged(0);
    }

    public void releaseTutorialPlayer() {
        if (mHeaderViewHolder != null) {
            mHeaderViewHolder.releasePlayer();
        }
    }

    /** The row after the posts: a spinner while the next page loads, or a retry after a failure. */
    private class FooterHolder extends RecyclerView.ViewHolder {
        private final View mProgress;
        private final View mRetry;

        FooterHolder(View itemView) {
            super(itemView);
            mProgress = itemView.findViewById(R.id.feed_footer_progress);
            mRetry = itemView.findViewById(R.id.feed_footer_retry);
            mRetry.setOnClickListener(
                    v -> {
                        if (mFeedRetryAction != null) mFeedRetryAction.run();
                    });
        }

        void bind(int state) {
            mProgress.setVisibility(state == FOOTER_LOADING ? View.VISIBLE : View.GONE);
            mRetry.setVisibility(state == FOOTER_ERROR ? View.VISIBLE : View.GONE);
        }
    }

    private static final String GOOGLE_LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true"
            + "&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue"
            + "%26app%3Dm%26hl%3Den-GB%26next%3D%252F&hl=en-GB";

    private class HeaderViewHolder extends RecyclerView.ViewHolder
            implements GlobalVideoPlaybackManager.Playable {
        private final LinearLayout topSitesContainer;
        private final ShimmerFrameLayout shimmerLoading;
        private final LinearLayout shimmerItems;
        private final FrameLayout tutorialContainer;
        private final View voiceButton;
        private final View lensButton;

        private PlayerView tutorialPlayerView;
        private ImageView tutorialImageView;
        private View tutorialCloseButton;
        private View tutorialCard;
        private ExoPlayer tutorialPlayer;
        private String currentTutorialUrl;

        HeaderViewHolder(View itemView) {
            super(itemView);

            try {
                topSitesContainer = itemView.findViewById(R.id.top_sites_container);

                shimmerLoading = itemView.findViewById(R.id.skeleton_shimmer);

                shimmerItems = itemView.findViewById(R.id.shimmer_items);

                tutorialContainer = itemView.findViewById(R.id.tutorial_video_container);

                // The search box stands in for the omnibox, as Chrome's fake box does.
                itemView.findViewById(R.id.ntp_search_box).setOnClickListener(v -> focusOmnibox());
                voiceButton = itemView.findViewById(R.id.ntp_search_voice);
                voiceButton.setOnClickListener(v -> startVoiceSearch());
                lensButton = itemView.findViewById(R.id.ntp_search_lens);
                lensButton.setOnClickListener(v -> startLens());

                setupShimmerItems();

            } catch (Exception e) {
                throw e;
            }
        }

        void bind(List<TopSiteTable> topSites, boolean isLoading, String tutorialVideoUrl, String tutorialImageUrl, boolean showTutorial) {
            VoiceRecognitionHandler voice = voiceRecognitionHandler();
            voiceButton.setVisibility(
                    voice != null && voice.isVoiceSearchEnabled() ? View.VISIBLE : View.GONE);
            lensButton.setVisibility(isLensEnabled() ? View.VISIBLE : View.GONE);
            setupTopSites(topSites);
            if (isLoading) {
                showShimmer();
            } else {
                hideShimmer();
            }
            bindTutorialMedia(tutorialVideoUrl, tutorialImageUrl, showTutorial);
        }

        private void bindTutorialMedia(String videoUrl, String imageUrl, boolean showTutorial) {
            if (!showTutorial || (videoUrl == null && imageUrl == null)) {
                tutorialContainer.setVisibility(View.GONE);
                releasePlayer();
                return;
            }

            if (tutorialCard == null) {
                View tutorialView = LayoutInflater.from(mContext)
                        .inflate(R.layout.ntp_tutorial_video, tutorialContainer, false);
                tutorialContainer.addView(tutorialView);

                tutorialPlayerView = tutorialView.findViewById(R.id.tutorial_player_view);
                tutorialImageView = tutorialView.findViewById(R.id.tutorial_image_view);
                tutorialCloseButton = tutorialView.findViewById(R.id.tutorial_close);
                tutorialCard = tutorialView.findViewById(R.id.tutorial_card);

                tutorialCard.setOnClickListener(v -> {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.firePostHogUserEvent(PostHogEventKeys.HOME_BANNER_CLICKED);
                        TabUtils.openUrlInSameTab(GOOGLE_LOGIN_URL);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e("TutorialVideo", "Could not open login URL", e);
                    }
                });

                tutorialCloseButton.setOnClickListener(v -> {
                    releasePlayer();
                    mTutorialVideoDismissed = true;
                    tutorialContainer.setVisibility(View.GONE);
                });
            }

            tutorialContainer.setVisibility(View.VISIBLE);

            boolean useImage = imageUrl != null && videoUrl == null;
            if (useImage) {
                releasePlayer();
                tutorialPlayerView.setVisibility(View.GONE);
                tutorialImageView.setVisibility(View.VISIBLE);
                Glide.with(mContext).load(imageUrl).centerCrop().into(tutorialImageView);
            } else {
                tutorialImageView.setVisibility(View.GONE);
                tutorialPlayerView.setVisibility(View.VISIBLE);
                if (!videoUrl.equals(currentTutorialUrl)) {
                    currentTutorialUrl = videoUrl;
                    initializeTutorialPlayer(videoUrl);
                }
            }
        }

        private void initializeTutorialPlayer(String url) {
            releasePlayer();
            tutorialPlayer =
                    new ExoPlayer.Builder(mContext)
                            .setMediaSourceFactory(
                                    org.chromium.chrome.browser.browser_express_comments
                                            .CommentMediaCache.mediaSourceFactory(mContext))
                            .build();
            tutorialPlayerView.setPlayer(tutorialPlayer);
            tutorialPlayerView.setUseController(false);
            tutorialPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
            tutorialPlayer.setVolume(0f);
            tutorialPlayer.setMediaItem(MediaItem.fromUri(url));
            tutorialPlayer.prepare();
            // It plays only while it is the feed's most visible video; see updatePlayingVideo.
            scheduleVideoCheck();
        }

        /** A tutorial video is up in the header (not dismissed, not an image). */
        boolean hasTutorialVideo() {
            return tutorialPlayer != null
                    && tutorialContainer.getVisibility() == View.VISIBLE
                    && tutorialPlayerView != null
                    && tutorialPlayerView.getVisibility() == View.VISIBLE;
        }

        @Override
        public void startPlayback() {
            if (tutorialPlayer != null) tutorialPlayer.setPlayWhenReady(true);
        }

        @Override
        public void stopPlayback() {
            if (tutorialPlayer != null) tutorialPlayer.setPlayWhenReady(false);
        }

        void releasePlayer() {
            GlobalVideoPlaybackManager.getInstance().stop(this);
            if (tutorialPlayer != null) {
                tutorialPlayer.stop();
                tutorialPlayer.release();
                tutorialPlayer = null;
            }
        }

        private void setupShimmerItems() {
            
            if (shimmerItems == null) {
                return;
            }
            
            try {
                shimmerItems.removeAllViews();
                
                // Add just a few shimmer items for testing
                for (int i = 0; i < 10; i++) {
                    View shimmerView = LayoutInflater.from(mContext).inflate(R.layout.shimmer_skeleton_item, shimmerItems, false);
                    shimmerItems.addView(shimmerView);
                }
                
            } catch (Exception e) {
            }
        }

        private void setupTopSites(List<TopSiteTable> topSites) {
            
            if (topSitesContainer == null) {
                Log.w("HeaderViewHolder", "topSitesContainer is null");
                return;
            }
            
            try {
                if (topSites != null && !topSites.isEmpty()) {
                    topSitesContainer.removeAllViews();
                    int maxSites = Math.min(4, topSites.size());

                    for (int i = 0; i < maxSites; i++) {
                        TopSiteTable topSite = topSites.get(i);
                        if (topSite != null && mParentLayout != null) {
                            View tileView = mParentLayout.createTile(mContext, topSite);
                            if (tileView != null) {
                                // Tiles spread edge to edge across the card, evenly spaced.
                                if (topSitesContainer.getChildCount() > 0) {
                                    topSitesContainer.addView(
                                            new Space(mContext),
                                            new LinearLayout.LayoutParams(0, 0, 1f));
                                }
                                topSitesContainer.addView(tileView);
                            }
                        }
                    }
                    topSitesContainer.setVisibility(View.VISIBLE);
                } else {
                    topSitesContainer.setVisibility(View.GONE);
                }
            } catch (Exception e) {
            }
        }

        @Nullable
        private ToolbarManager toolbarManager() {
            try {
                return BraveActivity.getBraveActivity().getToolbarManager();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                return null;
            }
        }

        private void focusOmnibox() {
            ToolbarManager toolbar = toolbarManager();
            if (toolbar != null) toolbar.setUrlBarFocus(true, OmniboxFocusReason.FAKE_BOX_TAP);
        }

        @Nullable
        private VoiceRecognitionHandler voiceRecognitionHandler() {
            ToolbarManager toolbar = toolbarManager();
            LocationBar locationBar = toolbar != null ? toolbar.getLocationBar() : null;
            return locationBar != null ? locationBar.getVoiceRecognitionHandler() : null;
        }

        private void startVoiceSearch() {
            VoiceRecognitionHandler voice = voiceRecognitionHandler();
            if (voice != null) {
                PostHogUtil.capture(PostHogEventKeys.VOICE_SEARCH_USED, "source", "ntp");
                voice.startVoiceRecognition(
                        VoiceRecognitionHandler.VoiceInteractionSource.NTP);
            }
        }

        private boolean isLensEnabled() {
            return LensController.getInstance()
                    .isLensEnabled(
                            new LensQueryParams.Builder(
                                            LensEntryPoint.NEW_TAB_PAGE,
                                            false,
                                            DeviceFormFactor.isNonMultiDisplayContextOnTablet(
                                                    mContext))
                                    .build());
        }

        private void startLens() {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                if (activity.getWindowAndroid() == null) return;
                PostHogUtil.capture(PostHogEventKeys.LENS_USED, "source", "ntp");
                LensController.getInstance()
                        .startLens(
                                activity.getWindowAndroid(),
                                new LensIntentParams.Builder(LensEntryPoint.NEW_TAB_PAGE, false)
                                        .build());
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e("HeaderViewHolder", "Can't start Lens: " + e);
            }
        }

        void showShimmer() {
            
            try {
                if (shimmerLoading != null) {
                    shimmerLoading.setVisibility(View.VISIBLE);
                    shimmerLoading.showShimmer(true);
                } else {
                }
            } catch (Exception e) {
            }
        }

        void hideShimmer() {
            
            try {
                if (shimmerLoading != null) {
                    shimmerLoading.setVisibility(View.GONE);
                    shimmerLoading.hideShimmer();
                }
            } catch (Exception e) {
            }
        }
    }

    /** The card's author line: the tweet or reel author's name, else the publisher's. */
    private static String cardAuthor(Post post) {
        SubPost sub = post.getSubPost();
        if (sub == null) return nonNull(post.getPublisherName());
        String name = urlOrNull(sub.getAuthorName());
        return name != null ? name : "@" + nonNull(sub.getAuthorUsername());
    }

    /** The name the comments sheet shows for the post. */
    private static String sheetUsername(Post post) {
        SubPost sub = post.getSubPost();
        return sub != null ? "@" + nonNull(sub.getAuthorUsername()) : nonNull(post.getPublisherName());
    }

    /** The author's picture, else their default face (see {@link ProfileAvatar#resolve}). */
    private static @Nullable String cardAvatarUrl(Post post) {
        SubPost sub = post.getSubPost();
        if (sub != null) {
            return ProfileAvatar.resolve(sub.getAuthorProfilePicture(), sub.getAuthorUsername());
        }
        return ProfileAvatar.resolve(post.getPublisherImageUrl(), post.getPublisherName());
    }

    private static String cardCaption(Post post) {
        SubPost sub = post.getSubPost();
        if (sub != null) return nonNull(sub.getContent());
        String title = nonNull(post.getTitle());
        String content = Boolean.TRUE.equals(post.getShowFull()) ? nonNull(post.getContent()) : "";
        if (title.isEmpty()) return content;
        return content.isEmpty() ? title : title + "\n" + content;
    }

    private static @Nullable String cardImageUrl(Post post) {
        SubPost sub = post.getSubPost();
        return urlOrNull(sub != null ? sub.getMediaImageUrl() : post.getImageUrl());
    }

    /**
     * The post's attachment and age for the chip at the top of its comments, and its hashtag and
     * totals for "Add your take".
     */
    private static Bundle sourceMedia(Post post) {
        return SourceChip.postMedia(
                cardImageUrl(post),
                cardVideoUrl(post),
                post.getAudioUrl(),
                post.getAudioDurationMs(),
                post.getCreatedAtMs(),
                post.getHashtag(),
                post.getCommentCount(),
                post.getReactionCount());
    }

    private static @Nullable String cardVideoUrl(@Nullable Post post) {
        if (post == null || post.getSubPost() == null) return null;
        return urlOrNull(post.getSubPost().getMediaVideoUrl());
    }

    /** {@code value}, or null when it is missing, empty or the string "null" the API sometimes sends. */
    private static @Nullable String urlOrNull(@Nullable String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static String nonNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    /**
     * "now", "5m ago", "3h ago", "2d ago", "3w ago", then the date. Comment cards use it too, so
     * a post and its comments read their times the same way.
     */
    public static String formatAge(Context context, long createdAtMs, long nowMs) {
        Resources res = context.getResources();
        long age = Math.max(0, nowMs - createdAtMs);
        if (age < DateUtils.MINUTE_IN_MILLIS) {
            return res.getString(R.string.browser_express_post_age_now);
        }
        if (age < DateUtils.HOUR_IN_MILLIS) {
            return quantity(res, R.plurals.browser_express_post_age_minutes, age / DateUtils.MINUTE_IN_MILLIS);
        }
        if (age < DateUtils.DAY_IN_MILLIS) {
            return quantity(res, R.plurals.browser_express_post_age_hours, age / DateUtils.HOUR_IN_MILLIS);
        }
        if (age < DateUtils.WEEK_IN_MILLIS) {
            return quantity(res, R.plurals.browser_express_post_age_days, age / DateUtils.DAY_IN_MILLIS);
        }
        if (age < 5 * DateUtils.WEEK_IN_MILLIS) {
            return quantity(res, R.plurals.browser_express_post_age_weeks, age / DateUtils.WEEK_IN_MILLIS);
        }
        return DateUtils.formatDateTime(
                context, createdAtMs, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private static String quantity(Resources res, int pluralsId, long count) {
        int value = (int) Math.min(count, Integer.MAX_VALUE);
        return res.getQuantityString(pluralsId, value, value);
    }

    /** "01:40", or "1:02:03" past an hour. */
    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /**
     * A reaction rail: "N reactions" (the post's comments) and, once its comments have emoji, the
     * top ones and the post's XP (all of them).
     */
    private static void bindRail(
            TextView headline, View mix, TextView[] slots, TextView xp, Post post) {
        int reactions = post.getReactionCount();
        headline.setText(
                ReactionMix.railHeadline(headline.getResources(), post.getCommentCount()));
        ReactionMix.bind(
                mix, slots, reactions > 0 ? post.getTopReactions() : Collections.emptyList());
        ReactionMix.bindXp(xp, reactions);
    }

    /**
     * A feed card (layout browser_express_post): author and time, caption, a photo/video or a
     * voice player, and the reaction rail into the post's comments.
     */
    private class PostHolder extends RecyclerView.ViewHolder
            implements GlobalVideoPlaybackManager.Playable {
        /** Caption lines before "… More", with or without media after it. */
        private static final int CAPTION_MAX_LINES = 6;

        /** Media keeps its own shape, between 4:5 portrait and 1.91:1 landscape. */
        private static final float MIN_MEDIA_RATIO = 0.8f;
        private static final float MAX_MEDIA_RATIO = 1.91f;
        private static final float DEFAULT_MEDIA_RATIO = 16f / 9f;

        private static final int SEEK_MAX = 1000;
        private static final long AUDIO_TICK_MS = 200;

        /** The rails' resting alphas (browser_express_post.xml), and theirs while demoted. */
        private static final float MIX_ALPHA = 0.86f;
        private static final float MEDIA_OPEN_ALPHA = 0.7f;
        private static final float RAIL_OPEN_ALPHA = 0.9f;
        private static final float DEMOTED_MEDIA_STATS_ALPHA = 0.6f;
        private static final float DEMOTED_MEDIA_OPEN_ALPHA = 0.5f;
        private static final float DEMOTED_RAIL_ALPHA = 0.65f;

        private final Context mCardContext;
        private final View mCard;
        private final ImageView mAvatar;
        private final TextView mAuthor;
        private final TextView mTime;
        private final TextView mCaption;

        private final ConstraintLayout mMedia;
        private final Space mMediaRatio;
        private final ImageView mImage;
        final PlayerView mVideoView;
        private final ImageView mVideoPlay;
        private final ImageButton mMuteButton;
        private final View mMediaRail;
        private final TextView mMediaRailHeadline;
        private final View mMediaRailMix;
        private final TextView[] mMediaRailSlots;
        private final TextView mMediaRailXp;
        private final View mMediaRailOpen;

        /**
         * "12 new takes since you last looked" (design_handoff_new_takes): over a photo or video,
         * the dim, the lighter fade under the rail and the callout; on any other post, the panel
         * under the caption.
         */
        private final View mNewTakesDim;
        private final View mNewTakesFade;
        private final NewTakesCallout mNewTakesCallout;
        private final NewTakesCallout mNewTakesPanel;
        /** Whether the card shows new takes, which keeps the play mark off the callout. */
        private boolean mShowingNewTakes;

        private final View mAudio;
        private final ImageButton mAudioPlay;
        private final SeekBar mAudioSeek;
        private final TextView mAudioTime;

        private final View mRail;
        private final TextView mRailHeadline;
        private final View mRailMix;
        private final TextView[] mRailSlots;
        private final TextView mRailXp;
        private final View mRailOpen;
        private final ImageView mRailChevron;

        private final View mMenuButton;
        /** "Post hidden · Undo", in the card's place once the reader picked "Not interested". */
        private final View mHiddenRow;

        @Nullable private Post mPost;

        @Nullable private ExoPlayer mPlayer;
        /** Where the video was when its player was let go off screen; 0 plays from the start. */
        private long mResumePositionMs;
        private boolean mHasVideo;
        private boolean mIsVideoInitialized;
        /** Whether the media box already has the media's real shape. */
        private boolean mMediaSizeKnown;
        /**
         * {@link SystemClock#elapsedRealtime} when the video last started playing, -1 while it
         * isn't; each stretch of play is reported to {@link FeedEvents}.
         */
        private long mWatchStartMs = -1;
        /** Whether the current stretch of play has sound. */
        private boolean mWatchAudible;

        @Nullable private ExoPlayer mAudioPlayer;
        private boolean mUserSeeking;
        private final Runnable mAudioTick = this::updateAudioProgress;

        private String mFullCaption = "";
        private boolean mCaptionTruncated;
        /** Whether the reader opened a cut caption; a second tap cuts it again. */
        private boolean mCaptionExpanded;
        private int mCaptionMaxLines;

        PostHolder(View itemView) {
            super(itemView);
            mCardContext = itemView.getContext();
            mCard = itemView.findViewById(R.id.post_card);
            mAvatar = itemView.findViewById(R.id.post_author_avatar);
            mAuthor = itemView.findViewById(R.id.post_author_name);
            mTime = itemView.findViewById(R.id.post_time);
            mCaption = itemView.findViewById(R.id.post_caption);

            mMedia = itemView.findViewById(R.id.post_media);
            mMediaRatio = itemView.findViewById(R.id.post_media_ratio);
            mImage = itemView.findViewById(R.id.post_image);
            mVideoView = itemView.findViewById(R.id.post_video);
            mVideoPlay = itemView.findViewById(R.id.post_video_play);
            mMuteButton = itemView.findViewById(R.id.post_video_mute);
            mMediaRail = itemView.findViewById(R.id.post_media_rail);
            mMediaRailHeadline = itemView.findViewById(R.id.post_media_rail_headline);
            mMediaRailMix = itemView.findViewById(R.id.post_media_rail_mix);
            mMediaRailSlots =
                    new TextView[] {
                        itemView.findViewById(R.id.post_media_rail_reaction_1),
                        itemView.findViewById(R.id.post_media_rail_reaction_2),
                        itemView.findViewById(R.id.post_media_rail_reaction_3)
                    };
            mMediaRailXp = itemView.findViewById(R.id.post_media_rail_xp);
            mMediaRailOpen = itemView.findViewById(R.id.post_media_rail_open);

            mNewTakesDim = itemView.findViewById(R.id.post_new_takes_dim);
            mNewTakesDim.setBackground(new NewTakesCallout.DimDrawable(mCardContext));
            mNewTakesFade = itemView.findViewById(R.id.post_new_takes_fade);
            mNewTakesCallout = itemView.findViewById(R.id.post_new_takes_callout);
            mNewTakesPanel = itemView.findViewById(R.id.post_new_takes_panel);

            mAudio = itemView.findViewById(R.id.post_audio);
            mAudioPlay = itemView.findViewById(R.id.post_audio_play);
            mAudioSeek = itemView.findViewById(R.id.post_audio_seek);
            mAudioTime = itemView.findViewById(R.id.post_audio_time);

            mRail = itemView.findViewById(R.id.post_reaction_rail);
            mRailHeadline = itemView.findViewById(R.id.post_rail_headline);
            mRailMix = itemView.findViewById(R.id.post_rail_mix);
            mRailSlots =
                    new TextView[] {
                        itemView.findViewById(R.id.post_rail_reaction_1),
                        itemView.findViewById(R.id.post_rail_reaction_2),
                        itemView.findViewById(R.id.post_rail_reaction_3)
                    };
            mRailXp = itemView.findViewById(R.id.post_rail_xp);
            mRailOpen = itemView.findViewById(R.id.post_rail_open);
            mRailChevron = itemView.findViewById(R.id.post_rail_chevron);

            mMenuButton = itemView.findViewById(R.id.post_menu);
            mHiddenRow = itemView.findViewById(R.id.post_hidden);
            mMenuButton.setOnClickListener(this::showMenu);
            itemView.findViewById(R.id.post_hidden_undo).setOnClickListener(v -> setHidden(false));

            // Photos, videos and the scrim over them are clipped to the box's rounded corners.
            mMedia.setClipToOutline(true);

            // A tap anywhere on a video post opens the comments with the video full screen over
            // them; on any other post it opens the comments.
            mCard.setOnClickListener(v -> openPost(tapVideoUrl()));
            // Either rail opens the comments from anywhere along it, not just its chevron. Over
            // media this keeps a tap on the rail from falling through to the video underneath.
            mRail.setOnClickListener(v -> openPost(null));
            mMediaRail.setOnClickListener(v -> openPost(null));
            mMedia.setOnClickListener(v -> openPost(tapVideoUrl()));
            mCaption.setOnClickListener(
                    v -> {
                        if (mCaptionTruncated) {
                            expandCaption();
                            if (mPost != null) {
                                FeedEvents.recordExpand(mPost.getId(), mFeedSessionId);
                            }
                        } else if (mCaptionExpanded) {
                            foldCaption();
                        } else {
                            openPost(tapVideoUrl());
                        }
                    });
            // The speaker turns the feed's sound on or off rather than this one card's: which
            // card is audible is decided by scroll position, so a per-card mute would be undone
            // as soon as the reader scrolled on.
            mMuteButton.setOnClickListener(
                    v -> {
                        sSoundOn = !sSoundOn;
                        PostHogUtil.capture(PostHogEventKeys.FEED_SOUND_TOGGLED, "sound_on", sSoundOn);
                        updatePlayingVideo();
                    });

            mAudioPlay.setOnClickListener(v -> toggleAudio());
            mAudioSeek.setMax(SEEK_MAX);
            mAudioSeek.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                            if (fromUser) {
                                mAudioTime.setText(
                                        formatDuration(progress * audioDurationMs() / SEEK_MAX));
                            }
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar bar) {
                            mUserSeeking = true;
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar bar) {
                            mUserSeeking = false;
                            long duration = audioDurationMs();
                            ExoPlayer player = ensureAudioPlayer();
                            if (player != null && duration > 0) {
                                player.seekTo(bar.getProgress() * duration / SEEK_MAX);
                            }
                            updateAudioProgress();
                        }
                    });
        }

        void bind(Post post) {
            cleanup();
            mPost = post;
            if (post == null) return;

            boolean hidden = HiddenFeedPosts.isHidden(post.getId());
            mCard.setVisibility(hidden ? View.GONE : View.VISIBLE);
            mMenuButton.setVisibility(hidden ? View.GONE : View.VISIBLE);
            mHiddenRow.setVisibility(hidden ? View.VISIBLE : View.GONE);
            if (hidden) return;

            bindHeader(post);

            String imageUrl = cardImageUrl(post);
            String videoUrl = cardVideoUrl(post);
            boolean hasMedia = imageUrl != null || videoUrl != null;
            boolean hasAudio = post.getAudioUrl() != null;
            bindCaption(cardCaption(post), CAPTION_MAX_LINES);

            if (hasMedia) bindMedia(post, imageUrl, videoUrl);
            if (hasAudio) bindAudio(post);

            // The rail rides on the media's bottom edge when the media ends the card, as
            // designed; otherwise it closes the card.
            boolean railOnMedia = hasMedia && !hasAudio;
            mMediaRail.setVisibility(railOnMedia ? View.VISIBLE : View.GONE);
            mRail.setVisibility(railOnMedia ? View.GONE : View.VISIBLE);
            if (railOnMedia) {
                bindRail(mMediaRailHeadline, mMediaRailMix, mMediaRailSlots, mMediaRailXp, post);
            } else {
                bindRail(mRailHeadline, mRailMix, mRailSlots, mRailXp, post);
            }
            applyNewTakes(post.getNewTakes());
        }

        /**
         * Shows the "new takes" callout, or takes it away (null). It goes over a photo or video
         * (§2) or, on a post without either, into the panel under the caption (§3). While it
         * shows, the post's own rail steps back so the two don't compete.
         */
        private void applyNewTakes(@Nullable NewTakes takes) {
            boolean hasMedia = mMedia.getVisibility() == View.VISIBLE;
            boolean onMedia = takes != null && hasMedia;
            boolean inPanel = takes != null && !hasMedia;
            mShowingNewTakes = takes != null;
            mNewTakesDim.setVisibility(onMedia ? View.VISIBLE : View.GONE);
            mNewTakesFade.setVisibility(onMedia ? View.VISIBLE : View.GONE);
            mNewTakesCallout.setVisibility(onMedia ? View.VISIBLE : View.GONE);
            mNewTakesPanel.setVisibility(inPanel ? View.VISIBLE : View.GONE);
            if (onMedia) mNewTakesCallout.bind(takes, false);
            if (inPanel) mNewTakesPanel.bind(takes, true);
            setRailsDemoted(takes != null);
            if (mHasVideo) showPlayMark(mPlayer == null || !mPlayer.isPlaying());
        }

        /** The centred play mark, shown while the video isn't playing unless the callout is up. */
        private void showPlayMark(boolean notPlaying) {
            mVideoPlay.setVisibility(notPlaying && !mShowingNewTakes ? View.VISIBLE : View.GONE);
        }

        /**
         * The post's lifetime stats while new takes are called out (§2d, §3). Over media the rail
         * is 34dp with its stats at 60% and its ring gone. Closing a text post it loses its grey
         * bar and is 20dp, in the author colour, at 65%. In both, the emoji are 10dp and
         * desaturated to 40%. False puts the rails back as the layout has them.
         */
        private void setRailsDemoted(boolean demoted) {
            ViewGroup.LayoutParams media = mMediaRail.getLayoutParams();
            media.height = dp(demoted ? 34 : 44);
            mMediaRail.setLayoutParams(media);
            if (demoted) {
                mMediaRail.setPaddingRelative(dp(10), 0, dp(6), dp(2));
            } else {
                mMediaRail.setPaddingRelative(dp(8), dp(16.2f), dp(4), dp(3.8f));
            }
            mMediaRail.setBackgroundResource(
                    demoted ? 0 : R.drawable.browser_express_post_media_tint);
            mMediaRailOpen.setBackgroundResource(
                    demoted ? 0 : R.drawable.browser_express_post_media_open_bg);
            mMediaRailOpen.setAlpha(demoted ? DEMOTED_MEDIA_OPEN_ALPHA : MEDIA_OPEN_ALPHA);
            mMediaRailXp.setTextColor(
                    ContextCompat.getColor(
                            mCardContext,
                            demoted
                                    ? R.color.browser_express_post_media_rail_text
                                    : R.color.browser_express_post_media_rail_xp));
            demoteStats(
                    mMediaRailHeadline,
                    mMediaRailMix,
                    mMediaRailSlots,
                    mMediaRailXp,
                    demoted,
                    DEMOTED_MEDIA_STATS_ALPHA);

            LinearLayout.LayoutParams rail = (LinearLayout.LayoutParams) mRail.getLayoutParams();
            rail.height = demoted ? dp(20) : ViewGroup.LayoutParams.WRAP_CONTENT;
            rail.topMargin = dp(demoted ? 10 : 16);
            mRail.setLayoutParams(rail);
            if (demoted) {
                mRail.setPaddingRelative(dp(2), 0, dp(2), 0);
            } else {
                mRail.setPaddingRelative(dp(10), dp(4), dp(5), dp(4));
            }
            mRail.setBackgroundResource(demoted ? 0 : R.drawable.browser_express_post_rail_bg);
            mRail.setAlpha(demoted ? DEMOTED_RAIL_ALPHA : 1f);
            int author = ContextCompat.getColor(mCardContext, R.color.browser_express_post_author);
            mRailHeadline.setTextColor(
                    demoted
                            ? author
                            : ContextCompat.getColor(
                                    mCardContext, R.color.browser_express_post_rail_text));
            mRailXp.setTextColor(
                    demoted
                            ? author
                            : ContextCompat.getColor(
                                    mCardContext, R.color.browser_express_post_rail_xp));
            ImageViewCompat.setImageTintList(
                    mRailChevron,
                    ColorStateList.valueOf(
                            demoted
                                    ? author
                                    : ContextCompat.getColor(
                                            mCardContext,
                                            R.color.browser_express_post_rail_chevron)));
            mRailOpen.setAlpha(demoted ? 1f : RAIL_OPEN_ALPHA);
            demoteStats(mRailHeadline, mRailMix, mRailSlots, mRailXp, demoted, 1f);
        }

        private void demoteStats(
                TextView headline,
                View mix,
                TextView[] slots,
                TextView xp,
                boolean demoted,
                float alpha) {
            headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, demoted ? 11 : 12);
            headline.setTypeface(Typeface.create(Typeface.SANS_SERIF, demoted ? 500 : 600, false));
            headline.setAlpha(demoted ? alpha : 1f);
            xp.setAlpha(demoted ? alpha : 1f);
            mix.setAlpha(demoted ? alpha : MIX_ALPHA);
            NewTakesCallout.setDesaturated(mix, demoted);
            int box = dp(demoted ? 12 : 16);
            for (TextView slot : slots) {
                ViewGroup.LayoutParams params = slot.getLayoutParams();
                params.width = box;
                params.height = box;
                slot.setLayoutParams(params);
                slot.setTextSize(TypedValue.COMPLEX_UNIT_DIP, demoted ? 10 : 12);
            }
        }

        private int dp(float value) {
            return Math.round(value * mCardContext.getResources().getDisplayMetrics().density);
        }

        private void bindHeader(Post post) {
            mAuthor.setText(cardAuthor(post));
            ProfileAvatar.load(mAvatar, cardAvatarUrl(post), null);
            long createdAt = post.getCreatedAtMs();
            mTime.setVisibility(createdAt > 0 ? View.VISIBLE : View.GONE);
            if (createdAt > 0) {
                mTime.setText(formatAge(mCardContext, createdAt, System.currentTimeMillis()));
            }
        }

        private void bindCaption(String text, int maxLines) {
            mFullCaption = text;
            mCaptionTruncated = false;
            mCaptionExpanded = false;
            mCaptionMaxLines = maxLines;
            mCaption.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            mCaption.setMaxLines(Integer.MAX_VALUE);
            mCaption.setText(text);
            if (text.isEmpty()) return;

            // Collapse before the first draw when the width is known (a recycled card, or from
            // the list's width), so a long caption never flashes at full length.
            int width = captionWidth();
            if (width > 0) {
                collapseCaption(buildCaptionLayout(text, width), maxLines);
                return;
            }
            final Post bound = mPost;
            mCaption.post(
                    () -> {
                        if (mPost == bound && mCaption.getLayout() != null) {
                            collapseCaption(mCaption.getLayout(), maxLines);
                        }
                    });
        }

        /** The caption's text width, from its last layout or else from the list's width. */
        private int captionWidth() {
            if (mCaption.getWidth() > 0) {
                return mCaption.getWidth() - mCaption.getPaddingLeft() - mCaption.getPaddingRight();
            }
            if (mTopPostRecycler == null || mTopPostRecycler.getWidth() <= 0) return 0;
            return mTopPostRecycler.getWidth()
                    - itemView.getPaddingLeft()
                    - itemView.getPaddingRight()
                    - mCard.getPaddingLeft()
                    - mCard.getPaddingRight();
        }

        private Layout buildCaptionLayout(String text, int width) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), mCaption.getPaint(), width)
                    .setIncludePad(mCaption.getIncludeFontPadding())
                    .setLineSpacing(
                            mCaption.getLineSpacingExtra(), mCaption.getLineSpacingMultiplier())
                    .setBreakStrategy(mCaption.getBreakStrategy())
                    .setHyphenationFrequency(mCaption.getHyphenationFrequency())
                    .build();
        }

        /** Cuts the caption to {@code maxLines} ending in "… More" when it runs longer. */
        private void collapseCaption(Layout layout, int maxLines) {
            String text = mFullCaption;
            if (layout.getLineCount() <= maxLines) return;
            int lastLine = maxLines - 1;
            int start = layout.getLineStart(lastLine);
            int end = layout.getLineEnd(lastLine);
            String more = mCaption.getResources().getString(R.string.browser_express_post_more);
            TextPaint paint = mCaption.getPaint();
            float available = layout.getWidth() - paint.measureText("… " + more);
            CharSequence lastLineText =
                    TextUtils.ellipsize(
                            text.substring(start, end).trim(),
                            paint,
                            available,
                            TextUtils.TruncateAt.END);

            SpannableStringBuilder collapsed =
                    new SpannableStringBuilder(text, 0, start).append(lastLineText);
            if (collapsed.length() == 0 || collapsed.charAt(collapsed.length() - 1) != '…') {
                collapsed.append('…');
            }
            collapsed.append(' ');
            int moreStart = collapsed.length();
            collapsed.append(more);
            collapsed.setSpan(
                    new ForegroundColorSpan(
                            ContextCompat.getColor(mCardContext, R.color.browser_express_post_more)),
                    moreStart,
                    collapsed.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mCaption.setText(collapsed);
            mCaption.setMaxLines(maxLines);
            mCaptionTruncated = true;
        }

        private void expandCaption() {
            mCaptionTruncated = false;
            mCaptionExpanded = true;
            mCaption.setMaxLines(Integer.MAX_VALUE);
            mCaption.setText(mFullCaption);
        }

        /** Cuts an opened caption back to its "… More" form. */
        private void foldCaption() {
            mCaptionExpanded = false;
            int width = captionWidth();
            Layout layout =
                    width > 0 ? buildCaptionLayout(mFullCaption, width) : mCaption.getLayout();
            if (layout != null) collapseCaption(layout, mCaptionMaxLines);
        }

        private void bindMedia(Post post, @Nullable String imageUrl, @Nullable String videoUrl) {
            mMedia.setVisibility(View.VISIBLE);
            SubPost sub = post.getSubPost();
            int width = sub != null ? sub.getMediaWidth() : 0;
            int height = sub != null ? sub.getMediaHeight() : 0;
            // Most writers send 0 × 0; then the loaded image or the video's first frame sets it.
            mMediaSizeKnown = width > 0 && height > 0;
            setMediaRatio(mMediaSizeKnown ? (float) width / height : DEFAULT_MEDIA_RATIO);

            if (imageUrl != null) {
                // Also a video's poster until the video draws over it.
                mImage.setVisibility(View.VISIBLE);
                final Post bound = post;
                Glide.with(mCardContext)
                        .load(imageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        // Untransformed, so the drawable reports the image's real shape.
                        .dontTransform()
                        .listener(
                                new RequestListener<Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(
                                            GlideException e,
                                            Object model,
                                            Target<Drawable> target,
                                            boolean isFirstResource) {
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(
                                            Drawable resource,
                                            Object model,
                                            Target<Drawable> target,
                                            DataSource dataSource,
                                            boolean isFirstResource) {
                                        int w = resource.getIntrinsicWidth();
                                        int h = resource.getIntrinsicHeight();
                                        if (mPost == bound && !mMediaSizeKnown && w > 0 && h > 0) {
                                            mMediaSizeKnown = true;
                                            setMediaRatio((float) w / h);
                                        }
                                        return false;
                                    }
                                })
                        .into(mImage);
            }

            if (videoUrl != null) {
                mHasVideo = true;
                mVideoView.setVisibility(View.VISIBLE);
                showPlayMark(true);
                mMuteButton.setVisibility(View.VISIBLE);
                initializePlayer(videoUrl);
            }
        }

        private void setMediaRatio(float ratio) {
            float clamped = Math.max(MIN_MEDIA_RATIO, Math.min(MAX_MEDIA_RATIO, ratio));
            ConstraintLayout.LayoutParams params =
                    (ConstraintLayout.LayoutParams) mMediaRatio.getLayoutParams();
            params.dimensionRatio = String.format(Locale.US, "H,%.4f:1", clamped);
            mMediaRatio.setLayoutParams(params);
        }

        public boolean hasVideo() {
            return mHasVideo && mIsVideoInitialized && mPlayer != null;
        }

        private void initializePlayer(String videoUrl) {
            // Feed videos read through the shared media cache — scrolling back to a card
            // replays from disk instead of re-streaming from the origin.
            mPlayer =
                    new ExoPlayer.Builder(mCardContext)
                            .setMediaSourceFactory(
                                    CommentMediaCache.mediaSourceFactory(mCardContext))
                            .build();
            mVideoView.setPlayer(mPlayer);
            mPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
            mPlayer.addListener(
                    new Player.Listener() {
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            showPlayMark(!isPlaying);
                            if (isPlaying) {
                                startWatch();
                            } else {
                                endWatch();
                            }
                        }

                        @Override
                        public void onVideoSizeChanged(VideoSize size) {
                            if (mMediaSizeKnown || size.width <= 0 || size.height <= 0) return;
                            mMediaSizeKnown = true;
                            setMediaRatio(size.width * size.pixelWidthHeightRatio / size.height);
                        }
                    });

            // Starts paused and silent; the visibility pass starts it if it is the feed's most
            // visible video, and gives it the sound.
            applyAudioState(false);
            mPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
            // Back from off screen: carry on from where it was.
            if (mResumePositionMs > 0) mPlayer.seekTo(mResumePositionMs);
            mResumePositionMs = 0;
            mPlayer.prepare();

            mIsVideoInitialized = true;
            scheduleVideoCheck();
        }

        @Override
        public void startPlayback() {
            if (mPlayer != null) mPlayer.setPlayWhenReady(true);
        }

        @Override
        public void stopPlayback() {
            if (mPlayer != null) mPlayer.setPlayWhenReady(false);
        }

        /**
         * Applies the feed's audio state to this card. Sound goes to the card in frame and only
         * while the feed is unmuted; the speaker icon shows the feed-wide setting, so every
         * visible card agrees on what it says.
         */
        void applyAudioState(boolean audible) {
            if (mPlayer != null) {
                boolean heard = audible && sSoundOn;
                // Watch time with sound is told apart from silent autoplay, so a change of sound
                // mid-play closes one stretch and opens the next.
                boolean watching = mWatchStartMs >= 0;
                if (watching && heard != mWatchAudible) endWatch();
                mPlayer.setVolume(heard ? 1f : 0f);
                if (watching) startWatch();
            }
            mMuteButton.setImageResource(sSoundOn ? R.drawable.volume_on : R.drawable.volume_off);
        }

        private void startWatch() {
            if (mWatchStartMs >= 0 || mPlayer == null) return;
            mWatchStartMs = SystemClock.elapsedRealtime();
            mWatchAudible = mPlayer.getVolume() > 0f;
        }

        /** Reports the stretch of play under way, if any. */
        private void endWatch() {
            if (mWatchStartMs < 0) return;
            long watchedMs = SystemClock.elapsedRealtime() - mWatchStartMs;
            mWatchStartMs = -1;
            if (mPost == null) return;
            mVideoWatchMs += watchedMs;
            FeedEvents.recordVideoWatch(
                    mPost.getId(),
                    mFeedSessionId,
                    FeedEvents.SURFACE_FEED,
                    watchedMs,
                    mWatchAudible ? watchedMs : 0,
                    mPlayer != null ? mPlayer.getDuration() : 0);
        }

        /**
         * Lets the player go while the card is off screen. The list keeps up to ten detached
         * cards cached, and a prepared player holds one of the device's few hardware video
         * decoders even while paused; kept alive, they left the comments' videos without one
         * until the app restarted. {@link #restoreVideo} picks the video up where it stopped.
         */
        void releaseVideoOffScreen() {
            if (mPlayer == null) return;
            mResumePositionMs = Math.max(0, mPlayer.getCurrentPosition());
            releasePlayer();
        }

        /** Back on screen after {@link #releaseVideoOffScreen}: the video gets a player again. */
        void restoreVideo() {
            if (mPlayer != null || mPost == null || mVideoView.getVisibility() != View.VISIBLE) {
                return;
            }
            String videoUrl = cardVideoUrl(mPost);
            if (videoUrl == null) return;
            mHasVideo = true;
            showPlayMark(true);
            initializePlayer(videoUrl);
        }

        private void releasePlayer() {
            // A released player says nothing more, so its last stretch of play is closed here.
            endWatch();
            if (mAudibleHolder == this) mAudibleHolder = null;
            GlobalVideoPlaybackManager.getInstance().stop(this);
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
            mVideoView.setPlayer(null);
            mIsVideoInitialized = false;
            mHasVideo = false;
        }

        private void bindAudio(Post post) {
            mAudio.setVisibility(View.VISIBLE);
            mAudioSeek.setProgress(0);
            mAudioTime.setText(formatDuration(post.getAudioDurationMs()));
            showAudioPlaying(false);
        }

        private void toggleAudio() {
            if (mAudioPlayer != null && mAudioPlayer.getPlayWhenReady()) {
                pauseAudio();
            } else {
                playAudio();
            }
        }

        private void playAudio() {
            ExoPlayer player = ensureAudioPlayer();
            if (player == null) return;
            if (mPlayingAudio != null && mPlayingAudio != this) mPlayingAudio.pauseAudio();
            mPlayingAudio = this;
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            player.play();
            showAudioPlaying(true);
            // Feed videos go quiet while a voice post plays.
            updatePlayingVideo();
            updateAudioProgress();
        }

        void pauseAudio() {
            if (mAudioPlayer != null) mAudioPlayer.pause();
            mAudioTime.removeCallbacks(mAudioTick);
            showAudioPlaying(false);
            if (mPlayingAudio == this) {
                mPlayingAudio = null;
                updatePlayingVideo();
            }
        }

        @Nullable
        private ExoPlayer ensureAudioPlayer() {
            if (mAudioPlayer != null) return mAudioPlayer;
            String url = mPost != null ? mPost.getAudioUrl() : null;
            if (url == null) return null;
            mAudioPlayer =
                    new ExoPlayer.Builder(mCardContext)
                            .setMediaSourceFactory(
                                    CommentMediaCache.mediaSourceFactory(mCardContext))
                            .build();
            mAudioPlayer.addListener(
                    new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == Player.STATE_ENDED) {
                                pauseAudio();
                                mAudioSeek.setProgress(0);
                                mAudioTime.setText(formatDuration(audioDurationMs()));
                            } else {
                                // Ready: the player now knows the real duration.
                                updateAudioProgress();
                            }
                        }
                    });
            mAudioPlayer.setMediaItem(MediaItem.fromUri(url));
            mAudioPlayer.prepare();
            return mAudioPlayer;
        }

        /** Moves the progress bar and the time (elapsed while under way, else the length). */
        private void updateAudioProgress() {
            mAudioTime.removeCallbacks(mAudioTick);
            ExoPlayer player = mAudioPlayer;
            if (player == null) return;
            long duration = audioDurationMs();
            long position = Math.max(0, player.getCurrentPosition());
            if (!mUserSeeking) {
                if (duration > 0) {
                    mAudioSeek.setProgress((int) Math.min(SEEK_MAX, position * SEEK_MAX / duration));
                }
                mAudioTime.setText(formatDuration(position > 0 ? position : duration));
            }
            if (player.getPlayWhenReady()) mAudioTime.postDelayed(mAudioTick, AUDIO_TICK_MS);
        }

        private long audioDurationMs() {
            if (mAudioPlayer != null && mAudioPlayer.getDuration() > 0) {
                return mAudioPlayer.getDuration();
            }
            return mPost != null ? mPost.getAudioDurationMs() : 0;
        }

        private void showAudioPlaying(boolean playing) {
            mAudioPlay.setImageResource(
                    playing
                            ? R.drawable.ic_browser_express_post_audio_pause
                            : R.drawable.ic_browser_express_post_audio_play);
            mAudioPlay.setContentDescription(
                    mCardContext.getString(
                            playing
                                    ? R.string.browser_express_post_audio_pause
                                    : R.string.browser_express_post_audio_play));
        }

        private void releaseAudio() {
            mAudioTime.removeCallbacks(mAudioTick);
            if (mPlayingAudio == this) {
                mPlayingAudio = null;
                updatePlayingVideo();
            }
            if (mAudioPlayer != null) {
                mAudioPlayer.release();
                mAudioPlayer = null;
            }
            mUserSeeking = false;
        }

        /** Releases players and pending loads and hides every optional part of the card. */
        void cleanup() {
            releasePlayer();
            mResumePositionMs = 0;
            releaseAudio();
            mPost = null;
            try {
                Glide.with(mCardContext).clear(mImage);
                Glide.with(mCardContext).clear(mAvatar);
            } catch (IllegalArgumentException e) {
                // The activity is already gone; there is nothing left to cancel.
            }
            mImage.setImageDrawable(null);
            mImage.setVisibility(View.GONE);
            mVideoView.setVisibility(View.GONE);
            mVideoPlay.setVisibility(View.GONE);
            mMuteButton.setVisibility(View.GONE);
            mMedia.setVisibility(View.GONE);
            mShowingNewTakes = false;
            mNewTakesDim.setVisibility(View.GONE);
            mNewTakesFade.setVisibility(View.GONE);
            mNewTakesCallout.setVisibility(View.GONE);
            mNewTakesPanel.setVisibility(View.GONE);
            mAudio.setVisibility(View.GONE);
        }

        private void openPost(@Nullable String videoUrl) {
            Post post = mPost;
            if (post == null) return;
            // While the card says "12 new takes", a tap anywhere opens the stream at the first of
            // them, a video post's too, instead of the post's media or article.
            NewTakes newTakes = post.getNewTakes();
            // A publisher's post may point straight at its article.
            if (newTakes == null
                    && post.getSubPost() == null
                    && Boolean.TRUE.equals(post.getRedirect())) {
                String url = urlOrNull(post.getUrl());
                if (url != null) {
                    reportOpen(post, FeedEvents.OPEN_ARTICLE);
                    TabUtils.openUrlInSameTab(url);
                    return;
                }
            }
            BraveActivity activity;
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                return;
            }
            if (mTopPostRecycler != null
                    && mTopPostRecycler.getLayoutManager() instanceof LinearLayoutManager) {
                ((LinearLayoutManager) mTopPostRecycler.getLayoutManager())
                        .scrollToPositionWithOffset(getBindingAdapterPosition(), 0);
            }
            String openAt = null;
            if (newTakes != null) {
                videoUrl = null;
                openAt = newTakes.firstNewTakeId;
                PostHogUtil.capture(
                        PostHogEventKeys.NEW_TAKES_TAPPED,
                        "post_id",
                        post.getId(),
                        "count",
                        newTakes.count);
                // The reader is on their way to them. The stream tells the server once it has
                // loaded, and the next feed counts from then.
                post.setNewTakes(null);
                applyNewTakes(null);
            }
            reportOpen(post, videoUrl != null ? FeedEvents.OPEN_VIDEO : FeedEvents.OPEN_COMMENTS);
            String username = sheetUsername(post);
            String caption = cardCaption(post);
            String avatarUrl = cardAvatarUrl(post);
            if (videoUrl != null) {
                showVideoViewer(activity, post, videoUrl);
            } else {
                activity.showPostComments(
                        post.getId(), username, caption, avatarUrl, sourceMedia(post), openAt);
            }
        }

        /** The post was tapped through to {@code target} (a {@code FeedEvents.OPEN_*}). */
        private void reportOpen(Post post, String target) {
            mPostsOpened++;
            FeedEvents.recordOpen(post.getId(), mFeedSessionId, target);
            PostHogUtil.capture(
                    PostHogEventKeys.POST_CLICKED_ON,
                    "post_id",
                    post.getId(),
                    "target",
                    target,
                    "position",
                    feedPosition());
        }

        /** The card's place in the feed (0 = first post), or -1 while it has none. */
        private int feedPosition() {
            int position = getBindingAdapterPosition();
            return position == RecyclerView.NO_POSITION ? -1 : getPostIndex(position);
        }

        private void showMenu(View anchor) {
            PopupMenu menu = new PopupMenu(mCardContext, anchor);
            menu.getMenu().add(0, 1, 0, R.string.browser_express_post_not_interested);
            menu.setOnMenuItemClickListener(
                    item -> {
                        setHidden(true);
                        return true;
                    });
            menu.show();
        }

        /**
         * "Not interested" ({@code hidden}) or its undo. The post is left out of this device's
         * feed from now on and the server hears of it; rebinding swaps the card for the "Post
         * hidden" row or back, which also stops the card's video and voice.
         */
        private void setHidden(boolean hidden) {
            Post post = mPost;
            int position = getBindingAdapterPosition();
            if (post == null || position == RecyclerView.NO_POSITION) return;
            if (hidden) {
                HiddenFeedPosts.hide(post.getId());
                mPostsHidden++;
            } else {
                HiddenFeedPosts.unhide(post.getId());
                mPostsHidden--;
            }
            FeedEvents.recordHide(post.getId(), mFeedSessionId, hidden);
            PostHogUtil.capture(
                    hidden ? PostHogEventKeys.POST_HIDDEN : PostHogEventKeys.POST_UNHIDDEN,
                    "post_id",
                    post.getId(),
                    "position",
                    getPostIndex(position));
            notifyItemChanged(position);
            // The card's size changed: what else is on screen, and playing, may have too.
            scheduleVideoCheck();
        }

        /** The video a tap on the card opens full screen, or null when it has none. */
        private @Nullable String tapVideoUrl() {
            return mHasVideo ? cardVideoUrl(mPost) : null;
        }

        /**
         * The post's comments with its video full screen over them (with sound); closing or
         * swiping the video away lands on the comments. It carries on from where the card had got
         * to when the reader was hearing it, and starts over when the card was muted, since they
         * haven't heard any of it yet.
         */
        private void showVideoViewer(BraveActivity activity, Post post, String videoUrl) {
            SubPost sub = post.getSubPost();
            String sourceUrl = urlOrNull(sub != null ? sub.getUrl() : post.getUrl());
            boolean heard = mPlayer != null && mPlayer.getVolume() > 0f;
            Bundle viewerArgs =
                    PostVideoViewerFragment.createArguments(
                            post.getId(),
                            videoUrl,
                            heard ? mPlayer.getCurrentPosition() : 0,
                            cardAuthor(post),
                            cardCaption(post),
                            sourceUrl,
                            post.getReactionCount(),
                            post.getTopReactions(),
                            post.getCommentCount());
            activity.showPostCommentsWithVideo(
                    post.getId(),
                    sheetUsername(post),
                    cardCaption(post),
                    cardAvatarUrl(post),
                    viewerArgs,
                    sourceMedia(post));
        }

    }
}
