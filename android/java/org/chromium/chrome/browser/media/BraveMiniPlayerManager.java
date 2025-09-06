package org.chromium.chrome.browser.media;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveYouTubeScriptInjectorNativeHelper;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.content_public.browser.WebContents;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.LoadUrlParams;

public class BraveMiniPlayerManager implements SurfaceHolder.Callback {
    private static final String TAG = "BraveMiniPlayer";
    private static BraveMiniPlayerManager sInstance;
    
    private Activity mActivity;
    private ViewGroup mRootView;
    private View mMiniPlayerView;
    private SurfaceView mSurfaceView;
    private Surface mSurface;
    private ImageButton mPlayPauseButton;
    private ImageButton mCloseButton;
    private TextView mTitleText;
    private TextView mChannelText;
    private ImageView mThumbnailView;
    
    private VideoData mCurrentVideo;
    private WebContents mSourceWebContents;
    private boolean mIsPlaying = false;
    private boolean mIsDragging = false;
    private float mInitialY;
    private boolean mSurfaceReady = false;

    public static class VideoData {
        public String url;
        public String title;
        public String channel;
        public String thumbnailUrl;
        public double currentTime;
        public double duration;
        public boolean isPlaying;
        public int tabId;
        
        public VideoData(String url, String title, String channel, String thumbnailUrl, 
                        double currentTime, double duration, boolean isPlaying, int tabId) {
            this.url = url;
            this.title = title;
            this.channel = channel;
            this.thumbnailUrl = thumbnailUrl;
            this.currentTime = currentTime;
            this.duration = duration;
            this.isPlaying = isPlaying;
            this.tabId = tabId;
        }
    }

    public static BraveMiniPlayerManager getInstance() {
        if (sInstance == null) {
            sInstance = new BraveMiniPlayerManager();
        }
        return sInstance;
    }

    public void initialize(Activity activity) {
        mActivity = activity;
        mRootView = activity.findViewById(android.R.id.content);
        createMiniPlayerView();
    }

    private void createMiniPlayerView() {
        if (mMiniPlayerView != null) return;

        LayoutInflater inflater = LayoutInflater.from(mActivity);
        mMiniPlayerView = inflater.inflate(R.layout.mini_player_layout, mRootView, false);
        
        mSurfaceView = mMiniPlayerView.findViewById(R.id.mini_player_surface);
        mPlayPauseButton = mMiniPlayerView.findViewById(R.id.mini_player_play_pause);
        mCloseButton = mMiniPlayerView.findViewById(R.id.mini_player_close);
        mTitleText = mMiniPlayerView.findViewById(R.id.mini_player_title);
        mChannelText = mMiniPlayerView.findViewById(R.id.mini_player_channel);
        mThumbnailView = mMiniPlayerView.findViewById(R.id.mini_player_thumbnail);
        
        setupSurfaceView();
        setupControls();
        setupDragHandling();
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.BOTTOM;
        mRootView.addView(mMiniPlayerView, params);
    }

    private void setupSurfaceView() {
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    private void setupControls() {
        mPlayPauseButton.setOnClickListener(v -> togglePlayPause());
        mCloseButton.setOnClickListener(v -> hideMiniPlayer());
        
        mMiniPlayerView.setOnClickListener(v -> {
            if (!mIsDragging) {
                expandToFullPlayer();
            }
        });
    }

    private void setupDragHandling() {
        View dragHandle = mMiniPlayerView.findViewById(R.id.mini_player_drag_handle);
        dragHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mInitialY = event.getRawY();
                    mIsDragging = false;
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    float deltaY = event.getRawY() - mInitialY;
                    if (Math.abs(deltaY) > 20) {
                        mIsDragging = true;
                        if (deltaY > 0) {
                            float alpha = Math.max(0, 1 - (deltaY / 300));
                            mMiniPlayerView.setAlpha(alpha);
                            mMiniPlayerView.setTranslationY(deltaY);
                        }
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    float finalDeltaY = event.getRawY() - mInitialY;
                    if (finalDeltaY > 150) {
                        hideMiniPlayer();
                    } else {
                        mMiniPlayerView.animate()
                            .alpha(1f)
                            .translationY(0)
                            .setDuration(200)
                            .start();
                    }
                    mIsDragging = false;
                    return true;
            }
            return false;
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurface = holder.getSurface();
        mSurfaceReady = true;
        Log.d(TAG, "Surface created");
        
        if (mCurrentVideo != null && mSourceWebContents != null) {
            startSurfacePip();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurface = null;
        mSurfaceReady = false;
        Log.d(TAG, "Surface destroyed");
        
        if (mSourceWebContents != null) {
            BraveYouTubeScriptInjectorNativeHelper.stopGlobalPip(mSourceWebContents);
        }
    }

    public void showMiniPlayer(VideoData videoData, WebContents sourceWebContents) {
        mCurrentVideo = videoData;
        mSourceWebContents = sourceWebContents;
        
        updateMiniPlayerUI();
        
        if (mMiniPlayerView.getVisibility() != View.VISIBLE) {
            mMiniPlayerView.setVisibility(View.VISIBLE);
            mMiniPlayerView.setAlpha(0f);
            mMiniPlayerView.setTranslationY(mMiniPlayerView.getHeight());
            
            mMiniPlayerView.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(300)
                .start();
        }
        
        if (mSurfaceReady) {
            startSurfacePip();
        }
    }

    private void startSurfacePip() {
        if (mSurface != null && mSourceWebContents != null) {
            BraveYouTubeScriptInjectorNativeHelper.startGlobalPip(mSourceWebContents, mSurface);
            Log.d(TAG, "Started surface PiP redirection");
        }
    }

    private void updateMiniPlayerUI() {
        if (mCurrentVideo == null) return;
        
        mTitleText.setText(mCurrentVideo.title);
        mChannelText.setText(mCurrentVideo.channel);
        mIsPlaying = mCurrentVideo.isPlaying;
        
        mPlayPauseButton.setImageResource(mIsPlaying ? 
            R.drawable.ic_pause_white_24dp : R.drawable.ic_play_arrow_white_24dp);
    }

    private void togglePlayPause() {
        if (mSourceWebContents != null) {
            mIsPlaying = !mIsPlaying;
            mPlayPauseButton.setImageResource(mIsPlaying ? 
                R.drawable.ic_pause_white_24dp : R.drawable.ic_play_arrow_white_24dp);
            
            BraveYouTubeScriptInjectorNativeHelper.togglePipPlayback(mSourceWebContents);
        }
    }

    public void hideMiniPlayer() {
        if (mMiniPlayerView.getVisibility() != View.VISIBLE) return;
        
        if (mSourceWebContents != null) {
            BraveYouTubeScriptInjectorNativeHelper.stopGlobalPip(mSourceWebContents);
        }
        
        mMiniPlayerView.animate()
            .alpha(0f)
            .translationY(mMiniPlayerView.getHeight())
            .setDuration(300)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mMiniPlayerView.setVisibility(View.GONE);
                    mCurrentVideo = null;
                    mSourceWebContents = null;
                }
            })
            .start();
    }

    private void expandToFullPlayer() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (mCurrentVideo != null) {
                if (mSourceWebContents != null) {
                    BraveYouTubeScriptInjectorNativeHelper.stopGlobalPip(mSourceWebContents);
                }
                
                Tab currentTab = activity.getActivityTab();
                if (currentTab != null) {
                    currentTab.loadUrl(new LoadUrlParams(mCurrentVideo.url));
                }

                hideMiniPlayer();
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Failed to expand to full player", e);
        }
    }

    public boolean isVisible() {
        return mMiniPlayerView != null && mMiniPlayerView.getVisibility() == View.VISIBLE;
    }

    public void transitionToAndroidPip() {
        if (mCurrentVideo == null || mSourceWebContents == null) return;
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
                
                mMiniPlayerView.findViewById(R.id.mini_player_controls).setVisibility(View.GONE);
                mActivity.enterPictureInPictureMode(params);
                
                Log.d(TAG, "Transitioned to Android PiP with active surface");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to transition to Android PiP", e);
        }
    }

    public VideoData getCurrentVideo() {
        return mCurrentVideo;
    }

    public WebContents getSourceWebContents() {
        return mSourceWebContents;
    }

    public void updateVideoTime(double currentTime) {
        if (mCurrentVideo != null) {
            mCurrentVideo.currentTime = currentTime;
        }
    }
}