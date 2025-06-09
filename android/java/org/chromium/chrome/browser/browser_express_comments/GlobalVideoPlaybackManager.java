package org.chromium.chrome.browser.browser_express_comments;

public class GlobalVideoPlaybackManager {
    private static GlobalVideoPlaybackManager instance;
    private CommentListAdapter.CommentHolder mCurrentlyPlayingHolder;
    private CommentListAdapter.CommentHolder mHolderToResume;
    
    public static GlobalVideoPlaybackManager getInstance() {
        if (instance == null) {
            instance = new GlobalVideoPlaybackManager();
        }
        return instance;
    }

    public void setHolderToResume(CommentListAdapter.CommentHolder holder) {
        mHolderToResume = holder;
    }

    public void resumePlaybackIfPossible() {
        if (mHolderToResume != null) {
            if (mHolderToResume.itemView.isAttachedToWindow()) {
                mHolderToResume.startPlayback();
                mHolderToResume = null; 
            }
        }
    }
    
    public void playVideo(CommentListAdapter.CommentHolder holderToPlay) {
        if (holderToPlay == mCurrentlyPlayingHolder) return;
        
        if (mCurrentlyPlayingHolder != null) {
            mCurrentlyPlayingHolder.stopPlayback();
        }
        
        if (holderToPlay != null && holderToPlay.hasVideo()) {
            holderToPlay.startPlayback();
            mCurrentlyPlayingHolder = holderToPlay;
        }
    }
    
    public void pauseCurrentlyPlayingVideo() {
        if (mCurrentlyPlayingHolder != null) {
            mCurrentlyPlayingHolder.stopPlayback();
            mCurrentlyPlayingHolder = null;
        }
    }
    
    public void releaseAllResources() {
        pauseCurrentlyPlayingVideo();
    }
    
    public CommentListAdapter.CommentHolder getCurrentlyPlayingHolder() {
        return mCurrentlyPlayingHolder;
    }
}