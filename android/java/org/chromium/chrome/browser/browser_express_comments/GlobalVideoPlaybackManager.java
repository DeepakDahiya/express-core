package org.chromium.chrome.browser.browser_express_comments;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one video that may play at a time, across the new tab page feed and the comments: starting
 * any video stops whichever other one was playing. Voice notes have a slot of their own below.
 */
public class GlobalVideoPlaybackManager {
    /** A video that plays in the shared slot: a comment's, or a feed card's. */
    public interface Playable {
        void startPlayback();

        void stopPlayback();
    }

    private static GlobalVideoPlaybackManager instance;
    @Nullable private Playable mCurrentlyPlaying;
    private CommentListAdapter.CommentHolder mHolderToResume;
    private CommentListAdapter.CommentHolder mCurrentlyPlayingAudioHolder;
    /** Stops a voice note that holds the audio slot without being a comment row: the source chip's. */
    private Runnable mOtherAudioStop;
    /** A full-screen viewer covers the comments; see {@link #setFullScreenViewerOpen}. */
    private boolean mFullScreenViewerOpen;
    /** The "Add your take" sheet is up over the comments; see {@link #setTakeComposerOpen}. */
    private boolean mTakeComposerOpen;

    public static GlobalVideoPlaybackManager getInstance() {
        if (instance == null) {
            instance = new GlobalVideoPlaybackManager();
        }
        return instance;
    }

    public void setHolderToResume(CommentListAdapter.CommentHolder holder) {
        mHolderToResume = holder;
    }

    /**
     * Marks a full-screen viewer (a post's video, a comment's photo or video) as covering the
     * comments, or as gone. While one covers them no video starts: the page underneath may still
     * be loading, and a reel that autoplays when it lands would otherwise play behind the
     * viewer, sound and all.
     */
    public void setFullScreenViewerOpen(boolean open) {
        mFullScreenViewerOpen = open;
        if (open) stopCurrent();
    }

    /**
     * Marks the "Add your take" sheet as up over the comments, or as gone. While it is up no video
     * starts, as under a full-screen viewer: the keyboard or the attachment picker coming up lays
     * the page out again, and a reel's autoplay would otherwise start it again behind the sheet.
     * Whoever closes the sheet asks the page to autoplay again.
     */
    public void setTakeComposerOpen(boolean open) {
        mTakeComposerOpen = open;
        if (open) stopCurrent();
    }

    /** Something covers the comments, so nothing may start playing under it. */
    boolean isHeld() {
        return mFullScreenViewerOpen || mTakeComposerOpen;
    }

    public void resumePlaybackIfPossible() {
        if (isHeld()) return;
        if (mHolderToResume != null) {
            if (mHolderToResume.itemView.isAttachedToWindow()) {
                play(mHolderToResume);
                mHolderToResume = null;
            }
        }
    }

    public void playVideo(CommentListAdapter.CommentHolder holderToPlay) {
        if (holderToPlay != null && !holderToPlay.hasVideo()) {
            // Nothing to play; whatever comment video had the slot still gives it up.
            pauseCurrentlyPlayingVideo();
            return;
        }
        play(holderToPlay);
    }

    /**
     * Gives the slot to {@code video}, stopping whichever video had it. Null frees the slot.
     *
     * @return whether {@code video} now plays; false while a full-screen viewer or the take sheet
     *     is open.
     */
    public boolean play(@Nullable Playable video) {
        if (isHeld()) {
            // Whatever covered the comments asks the page under it to autoplay again as it goes.
            stopCurrent();
            return false;
        }
        if (video == null) {
            stopCurrent();
            return false;
        }
        if (video != mCurrentlyPlaying) {
            stopCurrent();
            // A video starting takes the audio focus off any voice note that was playing.
            pauseCurrentlyPlayingAudio();
        }
        // Started again even when it already held the slot: cheap, and it cannot be left paused.
        video.startPlayback();
        mCurrentlyPlaying = video;
        return true;
    }

    /** Frees the slot if {@code video} holds it, stopping it. */
    public void stop(Playable video) {
        if (video != null && video == mCurrentlyPlaying) stopCurrent();
    }

    /**
     * Stops the comment video that holds the slot, if one does. A feed video is left alone: the
     * comments call this as they pause or close, and by then the feed may already have taken the
     * slot back for the card the reader returned to.
     */
    public void pauseCurrentlyPlayingVideo() {
        if (mCurrentlyPlaying instanceof CommentListAdapter.CommentHolder) stopCurrent();
    }

    private void stopCurrent() {
        if (mCurrentlyPlaying != null) {
            Playable stopping = mCurrentlyPlaying;
            mCurrentlyPlaying = null;
            stopping.stopPlayback();
        }
    }

    /**
     * Starts a voice note, stopping whatever else was making sound.
     *
     * <p>Video and audio share one slot each rather than one between them, because the video
     * currently on screen is chosen automatically by visibility while a voice note is only ever
     * started by a deliberate tap. Letting both run would mean a tap on play competing with a
     * video that autoplayed underneath it.
     */
    public void playAudio(CommentListAdapter.CommentHolder holderToPlay) {
        if (holderToPlay == mCurrentlyPlayingAudioHolder) return;

        pauseCurrentlyPlayingAudio();
        pauseCurrentlyPlayingVideo();

        if (holderToPlay != null) {
            holderToPlay.startAudioPlayback();
            mCurrentlyPlayingAudioHolder = holderToPlay;
        }
    }

    public void pauseCurrentlyPlayingAudio() {
        if (mCurrentlyPlayingAudioHolder != null) {
            mCurrentlyPlayingAudioHolder.stopAudioPlayback();
            mCurrentlyPlayingAudioHolder = null;
        }
        if (mOtherAudioStop != null) {
            Runnable stop = mOtherAudioStop;
            mOtherAudioStop = null;
            stop.run();
        }
    }

    /**
     * Gives the audio slot to a voice note outside the comment list (the source chip's), stopping
     * whatever else was making sound. {@code stop} runs when something else takes the slot.
     */
    public void playOtherAudio(Runnable stop) {
        pauseCurrentlyPlayingAudio();
        pauseCurrentlyPlayingVideo();
        mOtherAudioStop = stop;
    }

    /** Frees the slot when that voice note stops by itself. */
    public void onOtherAudioStopped(Runnable stop) {
        if (mOtherAudioStop == stop) {
            mOtherAudioStop = null;
        }
    }

    /** Clears the slot when a holder tears its own audio player down. */
    public void onAudioHolderReleased(CommentListAdapter.CommentHolder holder) {
        if (mCurrentlyPlayingAudioHolder == holder) {
            mCurrentlyPlayingAudioHolder = null;
        }
    }

    public CommentListAdapter.CommentHolder getCurrentlyPlayingAudioHolder() {
        return mCurrentlyPlayingAudioHolder;
    }

    /** The comments are going: their video and any voice note stop. */
    public void releaseAllResources() {
        pauseCurrentlyPlayingVideo();
        pauseCurrentlyPlayingAudio();
        // A row waiting to resume belongs to a page that is going too.
        mHolderToResume = null;
    }

    /**
     * Longest the reel that opened, or was swiped to, keeps the connection to itself. If it isn't
     * playable by then, the pages beside it start loading anyway.
     */
    private static final long REEL_HEAD_START_MAX_MS = 2500;

    /**
     * The reel page loading ahead of the others: the one the reader is on, until it can play.
     * The pager binds the pages beside it straight away, and their players would otherwise
     * download alongside it and share the bandwidth on its way to its first frame.
     */
    @Nullable private Object mLoadingReel;
    /** Reels bound while another loads, and how each starts loading, in the order they came. */
    private final Map<Object, Runnable> mWaitingReels = new LinkedHashMap<>();
    private final Handler mReelHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReelHeadStartOver = () -> finishReelLoad(mLoadingReel);

    /**
     * A reel page has bound and wants to load its video.
     *
     * @return true when it may load now, and takes the head start because no other reel is
     *     loading; false when another is, in which case {@code load} runs once that one can play.
     */
    boolean requestReelLoad(Object reel, Runnable load) {
        if (mLoadingReel == null || mLoadingReel == reel) {
            startReelLoad(reel);
            return true;
        }
        mWaitingReels.put(reel, load);
        return false;
    }

    /** The reader is on {@code reel}: it loads now and has the head start over any other. */
    void claimReelLoad(Object reel) {
        mWaitingReels.remove(reel);
        startReelLoad(reel);
    }

    private void startReelLoad(Object reel) {
        mLoadingReel = reel;
        mReelHandler.removeCallbacks(mReelHeadStartOver);
        mReelHandler.postDelayed(mReelHeadStartOver, REEL_HEAD_START_MAX_MS);
    }

    /**
     * {@code reel} can play, failed, or its row went. If it had the head start, the reels
     * waiting behind it start loading.
     */
    void finishReelLoad(@Nullable Object reel) {
        if (reel == null) return;
        mWaitingReels.remove(reel);
        if (reel != mLoadingReel) return;
        mLoadingReel = null;
        mReelHandler.removeCallbacks(mReelHeadStartOver);
        List<Runnable> waiting = new ArrayList<>(mWaitingReels.values());
        mWaitingReels.clear();
        for (Runnable load : waiting) load.run();
    }

    /** The comment video holding the slot, or null (also when a feed video holds it). */
    public CommentListAdapter.CommentHolder getCurrentlyPlayingHolder() {
        return mCurrentlyPlaying instanceof CommentListAdapter.CommentHolder
                ? (CommentListAdapter.CommentHolder) mCurrentlyPlaying
                : null;
    }
}
