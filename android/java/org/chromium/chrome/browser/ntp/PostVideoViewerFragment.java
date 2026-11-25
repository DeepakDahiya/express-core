/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.browser_express_comments.BrowserExpressCommentsBottomSheetFragment;
import org.chromium.chrome.browser.browser_express_comments.CommentMediaCache;
import org.chromium.chrome.browser.browser_express_comments.ExpandableCaption;
import org.chromium.chrome.browser.browser_express_comments.GlobalVideoPlaybackManager;
import org.chromium.chrome.browser.browser_express_comments.HoldToFastForward;
import org.chromium.chrome.browser.browser_express_comments.EdgeToEdgeUtil;
import org.chromium.chrome.browser.browser_express_comments.ReactionMix;
import org.chromium.chrome.browser.browser_express_share.ShareLinks;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen player for a feed post's video (Figma "Express/Full-screen Video", 1:548): close
 * and a ⋯ menu on top, the video, the post's author and caption, a timeline with play/pause,
 * time, mute, speed and full-screen controls, and the post's reaction rail.
 *
 * <p>It opens over the post's comments (BrowserExpressCommentsBottomSheetFragment hosts it), so
 * closing it, tapping the rail or swiping the video up or down all land the reader on them. Back
 * is the exception: it leaves the post altogether, comments included, for the feed.
 */
public class PostVideoViewerFragment extends DialogFragment {
    public static final String TAG = "PostVideoViewerFragment";

    private static final String ARG_POST_ID = "post_id";
    private static final String ARG_VIDEO_URL = "video_url";
    private static final String ARG_START_POSITION_MS = "start_position_ms";
    private static final String ARG_AUTHOR = "author";
    private static final String ARG_CAPTION = "caption";
    /** The caption's lines until a tap opens the rest. */
    private static final int CAPTION_COLLAPSED_LINES = 2;
    private static final String ARG_SOURCE_URL = "source_url";
    private static final String ARG_REACTION_COUNT = "reaction_count";
    private static final String ARG_TOP_REACTIONS = "top_reactions";
    private static final String ARG_COMMENT_COUNT = "comment_count";

    private static final String STATE_POSITION_MS = "position_ms";
    private static final String STATE_PLAY_WHEN_READY = "play_when_ready";
    private static final String STATE_MUTED = "muted";
    private static final String STATE_SPEED_INDEX = "speed_index";
    private static final String STATE_FULLSCREEN = "fullscreen";
    private static final String STATE_ORIGINAL_ORIENTATION = "original_orientation";

    /** The "1×" control cycles through these. */
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f};

    private static final int SEEK_MAX = 1000;
    private static final long PROGRESS_TICK_MS = 250;

    /** How far a vertical drag must carry the video before letting go sends it to the comments. */
    private static final int SWIPE_AWAY_DISTANCE_DP = 96;

    /** The feed post the video belongs to, for {@link FeedEvents}. */
    @Nullable private String mPostId;
    private String mVideoUrl;
    private String mCaption;
    @Nullable private String mSourceUrl;

    private long mPositionMs;
    private boolean mPlayWhenReady = true;
    private boolean mMuted;
    private int mSpeedIndex;
    private boolean mFullscreen;
    private int mOriginalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    /** Closed by Back: take the comments down with it. */
    private boolean mBackToFeed;

    @Nullable private ExoPlayer mPlayer;
    /** {@link SystemClock#elapsedRealtime} when the video last started playing; -1 while not. */
    private long mWatchStartMs = -1;
    private boolean mWatchAudible;
    /** Everything watched while the viewer was up, for "Post Video Viewer Closed". */
    private long mViewerWatchMs;
    /** How the reader left: close, rail, swipe or back. */
    private String mExit = "other";
    private boolean mUserSeeking;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mProgressTick = this::updateProgress;

    private PlayerView mPlayerView;
    private View mContext;
    private View mRail;
    private SeekBar mSeek;
    private ImageButton mPlayButton;
    private TextView mTime;
    private ImageButton mMuteButton;
    private TextView mSpeed;
    private ImageButton mFullscreenButton;
    /** Holding the right half of the video plays it at 2× until the finger lifts. */
    private HoldToFastForward mFastForward;

    /**
     * The viewer's arguments, handed to the comments sheet that opens it.
     *
     * @param postId the feed post the video belongs to.
     * @param startPositionMs where the feed card had got to, so the video carries on from there.
     * @param sourceUrl the post's original (the tweet or reel it mirrors), or null.
     */
    public static Bundle createArguments(
            String postId,
            String videoUrl,
            long startPositionMs,
            String author,
            String caption,
            @Nullable String sourceUrl,
            int reactionCount,
            List<String> topReactions,
            int commentCount) {
        Bundle args = new Bundle();
        args.putString(ARG_POST_ID, postId);
        args.putString(ARG_VIDEO_URL, videoUrl);
        args.putLong(ARG_START_POSITION_MS, startPositionMs);
        args.putString(ARG_AUTHOR, author);
        args.putString(ARG_CAPTION, caption);
        args.putString(ARG_SOURCE_URL, sourceUrl);
        args.putInt(ARG_REACTION_COUNT, reactionCount);
        args.putStringArrayList(ARG_TOP_REACTIONS, new ArrayList<>(topReactions));
        args.putInt(ARG_COMMENT_COUNT, commentCount);
        return args;
    }

    public static PostVideoViewerFragment fromArguments(Bundle args) {
        PostVideoViewerFragment fragment = new PostVideoViewerFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Bundle args = requireArguments();
        mPostId = args.getString(ARG_POST_ID);
        mVideoUrl = args.getString(ARG_VIDEO_URL);
        mCaption = args.getString(ARG_CAPTION, "");
        mSourceUrl = args.getString(ARG_SOURCE_URL);
        // The comments underneath may be playing a video of their own, or start one when they
        // finish loading; neither may play while this covers them.
        GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(true);
        mPositionMs = args.getLong(ARG_START_POSITION_MS, 0);
        mOriginalOrientation = requireActivity().getRequestedOrientation();
        if (savedInstanceState != null) {
            mPositionMs = savedInstanceState.getLong(STATE_POSITION_MS, mPositionMs);
            mPlayWhenReady = savedInstanceState.getBoolean(STATE_PLAY_WHEN_READY, true);
            mMuted = savedInstanceState.getBoolean(STATE_MUTED, false);
            mSpeedIndex = savedInstanceState.getInt(STATE_SPEED_INDEX, 0);
            mFullscreen = savedInstanceState.getBoolean(STATE_FULLSCREEN, false);
            mOriginalOrientation =
                    savedInstanceState.getInt(STATE_ORIGINAL_ORIENTATION, mOriginalOrientation);
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(
                shown -> {
                    if (dialog.getWindow() != null) {
                        dialog.getWindow()
                                .setLayout(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT);
                        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    }
                });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.browser_express_post_video_viewer, container, false);
        // The video fills the screen; the controls and the reaction rail over it stay clear of
        // the system bars. onStart() pins the window to the model this padding assumes.
        EdgeToEdgeUtil.padAllBySystemBars(view.findViewById(R.id.post_video_viewer_overlay));
        Bundle args = requireArguments();

        mPlayerView = view.findViewById(R.id.post_video_viewer_player);
        mContext = view.findViewById(R.id.post_video_viewer_context);
        mRail = view.findViewById(R.id.post_video_viewer_rail);
        mSeek = view.findViewById(R.id.post_video_viewer_seek);
        mPlayButton = view.findViewById(R.id.post_video_viewer_play);
        mTime = view.findViewById(R.id.post_video_viewer_time);
        mMuteButton = view.findViewById(R.id.post_video_viewer_mute);
        mSpeed = view.findViewById(R.id.post_video_viewer_speed);
        mFullscreenButton = view.findViewById(R.id.post_video_viewer_fullscreen);
        mFastForward =
                new HoldToFastForward(
                        () -> mPlayer, view.findViewById(R.id.video_fast_forward_badge));

        ((TextView) view.findViewById(R.id.post_video_viewer_author))
                .setText(args.getString(ARG_AUTHOR, ""));
        ScrollView captionScroll = view.findViewById(R.id.post_video_viewer_caption_scroll);
        new ExpandableCaption(
                        captionScroll,
                        view.findViewById(R.id.post_video_viewer_caption),
                        CAPTION_COLLAPSED_LINES)
                .setText(mCaption);
        captionScroll.setVisibility(mCaption.isEmpty() ? View.GONE : View.VISIBLE);

        int reactionCount = args.getInt(ARG_REACTION_COUNT, 0);
        List<String> topReactions = args.getStringArrayList(ARG_TOP_REACTIONS);
        ((TextView) view.findViewById(R.id.post_video_viewer_rail_headline))
                .setText(
                        ReactionMix.railHeadline(getResources(), args.getInt(ARG_COMMENT_COUNT, 0)));
        ReactionMix.bind(
                view.findViewById(R.id.post_video_viewer_rail_mix),
                new TextView[] {
                    view.findViewById(R.id.post_video_viewer_reaction_1),
                    view.findViewById(R.id.post_video_viewer_reaction_2),
                    view.findViewById(R.id.post_video_viewer_reaction_3)
                },
                reactionCount > 0 && topReactions != null ? topReactions : Collections.emptyList());
        ReactionMix.bindXp(view.findViewById(R.id.post_video_viewer_rail_xp), reactionCount);

        view.findViewById(R.id.post_video_viewer_close)
                .setOnClickListener(
                        v -> {
                            mExit = "close";
                            dismiss();
                        });
        view.findViewById(R.id.post_video_viewer_more).setOnClickListener(this::showMenu);
        mRail.setOnClickListener(
                v -> {
                    mExit = "rail";
                    dismiss();
                });
        mPlayerView.setOnClickListener(v -> togglePlayback());
        mPlayerView.setOnTouchListener(new SwipeToComments());
        mPlayButton.setOnClickListener(v -> togglePlayback());
        mMuteButton.setOnClickListener(
                v -> {
                    mMuted = !mMuted;
                    applyVolume();
                });
        mSpeed.setOnClickListener(
                v -> {
                    mSpeedIndex = (mSpeedIndex + 1) % SPEEDS.length;
                    applySpeed();
                });
        mFullscreenButton.setOnClickListener(v -> setFullscreen(!mFullscreen));

        mSeek.setMax(SEEK_MAX);
        mSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        showTime(progress * durationMs() / SEEK_MAX);
                        // Keyboard and accessibility steps have no drag to finish; seek now.
                        if (!mUserSeeking && mPlayer != null && durationMs() > 0) {
                            mPlayer.seekTo(progress * durationMs() / SEEK_MAX);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        mUserSeeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        mUserSeeking = false;
                        if (mPlayer != null && durationMs() > 0) {
                            mPlayer.seekTo(bar.getProgress() * durationMs() / SEEK_MAX);
                        }
                        updateProgress();
                    }
                });

        applyVolume();
        applySpeed();
        applyFullscreen();
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // The platform full-screen theme leaves it to the device whether the window draws under
        // the navigation bar; on Android 15+ it did, with the reaction rail behind the bar.
        if (getDialog() != null) EdgeToEdgeUtil.makeWindowEdgeToEdge(getDialog().getWindow());
        initializePlayer();
    }

    @Override
    public void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mPlayer != null) {
            mPositionMs = mPlayer.getCurrentPosition();
            mPlayWhenReady = mPlayer.getPlayWhenReady();
        }
        outState.putLong(STATE_POSITION_MS, mPositionMs);
        outState.putBoolean(STATE_PLAY_WHEN_READY, mPlayWhenReady);
        outState.putBoolean(STATE_MUTED, mMuted);
        outState.putInt(STATE_SPEED_INDEX, mSpeedIndex);
        outState.putBoolean(STATE_FULLSCREEN, mFullscreen);
        outState.putInt(STATE_ORIGINAL_ORIENTATION, mOriginalOrientation);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        // Only Back cancels the viewer (it covers the whole screen, so there is no outside to
        // tap), and Back goes to the feed rather than to the comments underneath.
        mBackToFeed = true;
        mExit = "back";
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(false);
        FragmentActivity activity = getActivity();
        if (mPostId != null && (activity == null || !activity.isChangingConfigurations())) {
            // The stretch under way counts too; the player is still there to say how long the
            // video is.
            endWatch();
            PostHogUtil.capture(
                    PostHogEventKeys.POST_VIDEO_VIEWER_CLOSED,
                    "post_id",
                    mPostId,
                    "exit",
                    mExit,
                    "watch_ms",
                    mViewerWatchMs,
                    "duration_ms",
                    durationMs());
        }
        if (getParentFragment() instanceof BrowserExpressCommentsBottomSheetFragment) {
            BrowserExpressCommentsBottomSheetFragment sheet =
                    (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
            if (mBackToFeed) {
                sheet.dismissAllowingStateLoss();
            } else {
                // Back on the comments: their videos may play again.
                sheet.resumeVisibleVideos();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Leaving the viewer hands the screen back in the orientation it was in.
        FragmentActivity activity = getActivity();
        if (mFullscreen && activity != null && !activity.isChangingConfigurations()) {
            activity.setRequestedOrientation(mOriginalOrientation);
        }
    }

    private void initializePlayer() {
        if (mPlayer != null || getContext() == null || mVideoUrl == null) return;
        // The feed card streamed this video moments ago; the shared cache lets the viewer start
        // from the bytes already on disk.
        mPlayer =
                new ExoPlayer.Builder(requireContext())
                        .setMediaSourceFactory(CommentMediaCache.mediaSourceFactory(requireContext()))
                        .build();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.addListener(
                new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        showPlaying(isPlaying);
                        updateProgress();
                        if (isPlaying) {
                            startWatch();
                        } else {
                            endWatch();
                        }
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_ENDED) {
                            // Stop at the end; play starts it over.
                            mPlayer.setPlayWhenReady(false);
                            showPlaying(false);
                        }
                        updateProgress();
                    }
                });
        mPlayer.setMediaItem(MediaItem.fromUri(mVideoUrl));
        mPlayer.seekTo(mPositionMs);
        mPlayer.setPlayWhenReady(mPlayWhenReady);
        mPlayer.prepare();
        applyVolume();
        applySpeed();
        showPlaying(mPlayWhenReady);
    }

    private void releasePlayer() {
        mHandler.removeCallbacks(mProgressTick);
        if (mFastForward != null) mFastForward.cancel();
        if (mPlayer == null) return;
        // A released player says nothing more, so its last stretch of play is closed here.
        endWatch();
        mPositionMs = mPlayer.getCurrentPosition();
        mPlayWhenReady = mPlayer.getPlayWhenReady();
        mPlayer.release();
        mPlayer = null;
        mPlayerView.setPlayer(null);
    }

    private void togglePlayback() {
        if (mPlayer == null) return;
        if (mPlayer.getPlayWhenReady()) {
            mPlayer.pause();
        } else {
            if (mPlayer.getPlaybackState() == Player.STATE_ENDED) mPlayer.seekTo(0);
            mPlayer.play();
        }
        showPlaying(mPlayer.getPlayWhenReady());
    }

    private void showPlaying(boolean playing) {
        mPlayButton.setImageResource(
                playing
                        ? R.drawable.ic_browser_express_viewer_pause
                        : R.drawable.ic_browser_express_viewer_play);
        mPlayButton.setContentDescription(
                getString(
                        playing
                                ? R.string.browser_express_video_viewer_pause
                                : R.string.browser_express_video_viewer_play));
    }

    /** Starts timing a stretch of play, with or without sound. */
    private void startWatch() {
        if (mWatchStartMs >= 0) return;
        mWatchStartMs = SystemClock.elapsedRealtime();
        mWatchAudible = !mMuted;
    }

    /** Reports the stretch of play under way, if any, as the post's full-screen watch time. */
    private void endWatch() {
        if (mWatchStartMs < 0) return;
        long watchedMs = SystemClock.elapsedRealtime() - mWatchStartMs;
        mWatchStartMs = -1;
        mViewerWatchMs += watchedMs;
        if (mPostId == null) return;
        FeedEvents.recordVideoWatch(
                mPostId,
                null,
                FeedEvents.SURFACE_VIEWER,
                watchedMs,
                mWatchAudible ? watchedMs : 0,
                durationMs());
    }

    private void applyVolume() {
        // A change of sound mid-play closes one stretch and opens the next.
        if (mWatchStartMs >= 0 && mWatchAudible == mMuted) {
            endWatch();
            startWatch();
        }
        if (mPlayer != null) mPlayer.setVolume(mMuted ? 0f : 1f);
        mMuteButton.setImageResource(
                mMuted
                        ? R.drawable.ic_browser_express_viewer_volume_off
                        : R.drawable.ic_browser_express_viewer_volume);
        mMuteButton.setContentDescription(
                getString(
                        mMuted
                                ? R.string.browser_express_video_viewer_unmute
                                : R.string.browser_express_video_viewer_mute));
    }

    private void applySpeed() {
        float speed = SPEEDS[mSpeedIndex];
        if (mPlayer != null) mPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        String label =
                new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault()))
                        .format(speed);
        mSpeed.setText(getString(R.string.browser_express_video_viewer_speed, label));
        mSpeed.setContentDescription(
                getString(R.string.browser_express_video_viewer_speed_description)
                        + " "
                        + mSpeed.getText());
    }

    /** Landscape with only the video and its controls, or back to the portrait page. */
    private void setFullscreen(boolean fullscreen) {
        mFullscreen = fullscreen;
        requireActivity()
                .setRequestedOrientation(
                        fullscreen
                                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                : mOriginalOrientation);
        applyFullscreen();
    }

    private void applyFullscreen() {
        mContext.setVisibility(mFullscreen ? View.GONE : View.VISIBLE);
        mRail.setVisibility(mFullscreen ? View.GONE : View.VISIBLE);
        mFullscreenButton.setImageResource(
                mFullscreen
                        ? R.drawable.ic_browser_express_viewer_fullscreen_exit
                        : R.drawable.ic_browser_express_viewer_fullscreen);
        mFullscreenButton.setContentDescription(
                getString(
                        mFullscreen
                                ? R.string.browser_express_video_viewer_fullscreen_exit
                                : R.string.browser_express_video_viewer_fullscreen));
    }

    /** Moves the timeline (played and buffered) and the time, while the video plays. */
    private void updateProgress() {
        mHandler.removeCallbacks(mProgressTick);
        if (mPlayer == null) return;
        long duration = durationMs();
        long position = Math.max(0, mPlayer.getCurrentPosition());
        if (duration > 0) {
            if (!mUserSeeking) {
                mSeek.setProgress((int) Math.min(SEEK_MAX, position * SEEK_MAX / duration));
            }
            mSeek.setSecondaryProgress(
                    (int) Math.min(SEEK_MAX, mPlayer.getBufferedPosition() * SEEK_MAX / duration));
        }
        if (!mUserSeeking) showTime(position);
        if (mPlayer.isPlaying()) mHandler.postDelayed(mProgressTick, PROGRESS_TICK_MS);
    }

    private void showTime(long positionMs) {
        mTime.setText(
                getString(
                        R.string.browser_express_video_viewer_time,
                        formatTime(positionMs),
                        formatTime(durationMs())));
    }

    private long durationMs() {
        return mPlayer != null && mPlayer.getDuration() > 0 ? mPlayer.getDuration() : 0;
    }

    /** "0:32", or "1:02:03" past an hour. */
    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /**
     * A vertical drag on the video carries it with the finger; let go far or fast enough and it
     * slides away in that direction, uncovering the comments. A tap still plays or pauses.
     */
    private final class SwipeToComments implements View.OnTouchListener {
        private final int mTouchSlop;
        private final int mMinFlingVelocity;
        private final float mSwipeAwayDistance;
        private float mDownX;
        private float mDownY;
        private boolean mDragging;
        @Nullable private VelocityTracker mVelocityTracker;

        SwipeToComments() {
            ViewConfiguration config = ViewConfiguration.get(requireContext());
            mTouchSlop = config.getScaledTouchSlop();
            mMinFlingVelocity = config.getScaledMinimumFlingVelocity() * 8;
            mSwipeAwayDistance =
                    SWIPE_AWAY_DISTANCE_DP * getResources().getDisplayMetrics().density;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            View page = getView();
            if (page == null) return false;
            if (mFastForward.onTouch(v, event)) {
                // A hold on the right half has the rest of this press: no drag, no tap.
                int action = event.getActionMasked();
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        && mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                return true;
            }
            float dx = event.getRawX() - mDownX;
            float dy = event.getRawY() - mDownY;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = event.getRawX();
                    mDownY = event.getRawY();
                    mDragging = false;
                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                    if (!mDragging && Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        page.setTranslationY(dy);
                        page.setAlpha(1f - 0.5f * Math.min(1f, Math.abs(dy) / page.getHeight()));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float velocityY = 0f;
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(event);
                        mVelocityTracker.computeCurrentVelocity(1000);
                        velocityY = mVelocityTracker.getYVelocity();
                        mVelocityTracker.recycle();
                        mVelocityTracker = null;
                    }
                    if (!mDragging) {
                        v.performClick();
                    } else if (Math.abs(dy) > mSwipeAwayDistance
                            || Math.abs(velocityY) > mMinFlingVelocity) {
                        slideAwayToComments(page, Math.signum(dy != 0 ? dy : velocityY));
                    } else {
                        page.animate().translationY(0f).alpha(1f).setDuration(150).start();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (mVelocityTracker != null) {
                        mVelocityTracker.recycle();
                        mVelocityTracker = null;
                    }
                    page.animate().translationY(0f).alpha(1f).setDuration(150).start();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void slideAwayToComments(View page, float direction) {
        mExit = "swipe";
        page.animate()
                .translationY(direction * page.getHeight())
                .alpha(0f)
                .setDuration(200)
                .withEndAction(this::dismissAllowingStateLoss)
                .start();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.browser_express_video_viewer_share);
        menu.setOnMenuItemClickListener(
                item -> {
                    share();
                    return true;
                });
        menu.show();
    }

    /**
     * Shares the post's page on browser.express (which opens the post in the app for people who
     * have it), else the original source, else the video itself.
     */
    private void share() {
        if (mPostId != null) {
            FeedEvents.recordShare(mPostId, FeedEvents.SURFACE_VIEWER);
            PostHogUtil.capture(
                    PostHogEventKeys.POST_SHARED,
                    "post_id",
                    mPostId,
                    "surface",
                    FeedEvents.SURFACE_VIEWER);
        }
        String link = ShareLinks.forPost(mPostId);
        if (link == null) link = mSourceUrl != null ? mSourceUrl : mVideoUrl;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(send, null));
    }
}
