package org.chromium.chrome.browser.ntp;

import android.view.GestureDetector;
import android.os.Build;
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
import android.graphics.PorterDuffColorFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import org.chromium.base.task.AsyncTask;
import java.util.Locale;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.recyclerview.widget.LinearLayoutManager;
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
import org.chromium.chrome.browser.app.helpers.ImageLoader;
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
import androidx.recyclerview.widget.LinearSnapHelper;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import android.view.MotionEvent;
import android.widget.ProgressBar;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
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

public class PostListAdapter extends RecyclerView.Adapter {
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_POST = 1;

    private final Context mContext;
    private List<Post> mPostList;
    private RecyclerView mTopPostRecycler;
    private List<TopSiteTable> mTopSites;
    private BraveNewTabPageLayout mParentLayout;
    private HeaderViewHolder mHeaderViewHolder;
    private static final String TWITTER_TYPE = "Twitter";
    private static final String INSTAGRAM_TYPE = "Instagram";

    private boolean mIsLoading = true;

    private final RecyclerView.RecycledViewPool mCommentRecycledViewPool;

    public PostListAdapter(Context context, List<Post> postList, RecyclerView topPostRecycler, List<TopSiteTable> topSites, BraveNewTabPageLayout parentLayout) {
        mContext = context;
        mPostList = postList;
        mTopPostRecycler = topPostRecycler;
        mTopSites = topSites != null ? topSites : new ArrayList<>();
        mParentLayout = parentLayout;

        mCommentRecycledViewPool = new RecyclerView.RecycledViewPool();
    }

    public void setLoading(boolean isLoading) {
        mIsLoading = isLoading;
        if (mHeaderViewHolder != null) {
            notifyItemChanged(0);
        }
    }

    @Override
    public int getItemCount() {
        int count = mPostList.size() + 1;
        return count;
    }

    @Override
    public int getItemViewType(int position) {
        int type = position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_POST;
        return type;
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
                    
                default:
                    view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.browser_express_post, parent, false);
                    return new PostHolder(view, mTopPostRecycler, mCommentRecycledViewPool);
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
                ((HeaderViewHolder) holder).bind(mTopSites, mIsLoading);
                break;
            case VIEW_TYPE_POST:
                Post post = mPostList.get(position - 1);
                ((PostHolder) holder).bind(post);
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

    private class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout topSitesContainer;
        private final ShimmerFrameLayout shimmerLoading;
        private final LinearLayout shimmerItems;

        HeaderViewHolder(View itemView) {
            super(itemView);
            
            try {
                topSitesContainer = itemView.findViewById(R.id.top_sites_container);
                
                shimmerLoading = itemView.findViewById(R.id.skeleton_shimmer);
                
                shimmerItems = itemView.findViewById(R.id.shimmer_items);

                setupShimmerItems();
                
            } catch (Exception e) {
                throw e;
            }
        }

        void bind(List<TopSiteTable> topSites, boolean isLoading) {
            setupTopSites(topSites);
            if (isLoading) {
                showShimmer();
            } else {
                hideShimmer();
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

    private class PostHolder extends RecyclerView.ViewHolder {
        final LinearLayout twitterPostLayout;
        final ImageView twitterProfilePicture;
        final TextView twitterUsername;
        final TextView twitterContent;
        final ImageView twitterImage;
        // VideoView twitterVideo;
        final CardView twitterMediaCard;
        final RecyclerView mTopCommentsRecycler;
        CommentListAdapter mCommentAdapter;
        List<Comment> mComments;
        final LinearLayout editTextLayout;

        final PlayerView twitterVideo;
        ExoPlayer player;
        final ImageView playPauseIcon;
        final ProgressBar videoProgressBar;
        ValueAnimator progressAnimator;

        final ImageView postImage;
        final CardView cardView;
        final TextView publisherNameText;
        final TextView titleText;
        final TextView contentText;
        private final Button mCommentButton;
        private BraveActivity activity;

        private final Button mReadMoreButton;
        private final Button mReadMoreButton2;

        private final Context context;

        private int myPosition;

        private final Handler autoScrollHandler;
        private Runnable autoScrollRunnable;
        private boolean isAutoScrolling;

        PostHolder(View itemView, RecyclerView topPostRecycler, RecyclerView.RecycledViewPool commentRecycledViewPool) {
            super(itemView);
            twitterPostLayout = (LinearLayout) itemView.findViewById(R.id.twitter_post_layout);
            twitterProfilePicture = (ImageView) itemView.findViewById(R.id.twitter_profile_picture);
            twitterUsername = (TextView) itemView.findViewById(R.id.twitter_username);
            twitterContent = (TextView) itemView.findViewById(R.id.twitter_content);
            twitterImage = (ImageView) itemView.findViewById(R.id.twitter_image);
            twitterVideo = (PlayerView) itemView.findViewById(R.id.twitter_video);
            twitterMediaCard = (CardView) itemView.findViewById(R.id.twitter_media_card);

            playPauseIcon = (ImageView) itemView.findViewById(R.id.play_pause_icon);
            videoProgressBar = (ProgressBar) itemView.findViewById(R.id.video_progress);

            editTextLayout = (LinearLayout) itemView.findViewById(R.id.edit_text_layout);

            mTopCommentsRecycler = (RecyclerView) itemView.findViewById(R.id.recycler_top_comments);

            cardView = (CardView) itemView.findViewById(R.id.card_view);
            postImage = (ImageView) itemView.findViewById(R.id.post_image);
            publisherNameText = (TextView) itemView.findViewById(R.id.publisher_name);
            titleText = (TextView) itemView.findViewById(R.id.title);
            contentText = (TextView) itemView.findViewById(R.id.post_content);
            mCommentButton = (Button) itemView.findViewById(R.id.btn_comment);
            mReadMoreButton = (Button) itemView.findViewById(R.id.btn_read_more_post);
            mReadMoreButton2 = (Button) itemView.findViewById(R.id.btn_read_more_post2);
            context = itemView.getContext();
            mTopPostRecycler = topPostRecycler;

            autoScrollHandler = new Handler(Looper.getMainLooper());

            if (mTopCommentsRecycler != null) {
                try {
                    activity = BraveActivity.getBraveActivity();
                    mTopCommentsRecycler.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
                    mComments = new ArrayList<Comment>();
                    mCommentAdapter = new CommentListAdapter(activity, mComments, null, null, false, false);
                    mTopCommentsRecycler.setAdapter(mCommentAdapter);
                    mTopCommentsRecycler.setRecycledViewPool(commentRecycledViewPool);
                    mTopCommentsRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                            super.onScrolled(recyclerView, dx, dy);
                        }
                    });
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                }
            }

            itemView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        if (mComments.size() > 0 && !isAutoScrolling) {
                            setupAutoScroll();
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        stopAutoScroll();
                        if (player != null && player.isPlaying()) {
                            player.pause();
                            updatePlayPauseUI(false);
                        }
                    }
                });
        }

        void bind(Post post) {
            cleanup();
            if (post == null) return;

            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            String accessToken = activity.getAccessToken();

            stopAutoScroll();

            try{

            List<Comment> comments = post.getComments();
            int len = comments.size();
            mComments.clear();
            if (len > 0 ) {
                mTopCommentsRecycler.setVisibility(View.VISIBLE);
                mComments.addAll(comments);
                mCommentAdapter.notifyItemRangeInserted(len, comments.size());
                setupAutoScroll();
            }

            if (len == 0){
                mTopCommentsRecycler.setVisibility(View.GONE);
            }
            
            myPosition = getBindingAdapterPosition();

            String postType = post.getType().toString();
            SubPost subPost = post.getSubPost();
            String username = "@" + subPost.getAuthorUsername();
            String content = subPost.getContent();
            String profilePicUrl = subPost.getAuthorProfilePicture();

            if (postType.equals(TWITTER_TYPE) || postType.equals(INSTAGRAM_TYPE)) {
                twitterPostLayout.setVisibility(View.VISIBLE);

                String twitterImageUrl = subPost.getMediaImageUrl();
                String videoUrl = subPost.getMediaVideoUrl();

                twitterUsername.setText(username);
                if(content.toString().length() > 150){
                    String contentString = content.toString().subSequence(0, 150) + "...";
                    twitterContent.setText(contentString);
                    mReadMoreButton.setVisibility(View.VISIBLE);
                    mReadMoreButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            twitterContent.setText(content.toString());
                            mReadMoreButton.setVisibility(View.GONE);
                        }
                    });
                }else{
                    twitterContent.setText(content.toString());
                    mReadMoreButton.setVisibility(View.GONE);
                }

                twitterContent.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(myPosition, 0);
                        sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                        activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                    }
                });

                twitterPostLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(myPosition, 0);
                        sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                        activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                    }
                });
                

                ImageLoader.downloadImage(profilePicUrl, Glide.with(activity), false, 5, twitterProfilePicture, null);
                if(twitterImageUrl != null){
                    ImageLoader.downloadImage(twitterImageUrl, Glide.with(activity), false, 5, twitterImage, null);
                    twitterMediaCard.setVisibility(View.VISIBLE);
                    twitterImage.setVisibility(View.VISIBLE);
                }

                titleText.setVisibility(View.GONE);
                contentText.setVisibility(View.GONE);
                publisherNameText.setVisibility(View.GONE);
                postImage.setVisibility(View.GONE);

                if(videoUrl != null && !"null".equals(videoUrl)){
                    releasePlayer();
                    player = new ExoPlayer.Builder(context).build();

                    twitterVideo.setPlayer(player);
                    twitterVideo.setUseController(false); // Hide default controls
                    
                    // Create MediaItem
                    MediaItem mediaItem = MediaItem.fromUri(videoUrl);
                    player.setMediaItem(mediaItem);
                    
                    // Set player properties
                    player.setRepeatMode(Player.REPEAT_MODE_ALL);
                    player.setPlayWhenReady(false);

                    // Prepare player
                    player.prepare();

                    playPauseIcon.setImageResource(R.drawable.ic_play_circle2);
                    playPauseIcon.setVisibility(View.VISIBLE);

                    twitterVideo.setClickable(true);
                    twitterVideo.setFocusable(true);

                    View videoParent = (View) twitterVideo.getParent();
                    if (videoParent != null) {
                        videoParent.setClickable(true);
                        videoParent.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Log.e("VideoPlayer", "Parent view clicked");
                                togglePlayPause();
                            }
                        });
                    }

                    View.OnClickListener videoClickListener = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            togglePlayPause();
                        }
                    };

                    twitterVideo.setOnClickListener(videoClickListener);
                    playPauseIcon.setOnClickListener(videoClickListener);

                    twitterImage.post(new Runnable() {
                        @Override
                        public void run() {
                            int h = twitterImage.getHeight();
                            twitterVideo.getLayoutParams().height = h;
                            twitterVideo.requestLayout();
                        }
                    });

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == Player.STATE_READY) {
                                twitterImage.setVisibility(View.GONE);
                                twitterVideo.setVisibility(View.VISIBLE);
                                setupProgressBar();
                            }
                        }
                        
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            updatePlayPauseIcon(isPlaying);
                            if (isPlaying) {
                                startProgressAnimation();
                            } else {
                                pauseProgressAnimation();
                            }
                        }
                    });

                    // twitterVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    //     @Override
                    //     public void onPrepared(MediaPlayer mp) {
                    //         twitterImage.setVisibility(View.GONE);
                    //         twitterVideo.setVisibility(View.VISIBLE);
                    //         twitterVideo.start();
                    //     }
                    // });
                }
            } else {
                twitterPostLayout.setVisibility(View.GONE);
                titleText.setText(post.getTitle().toString());

                if(post.getShowFull()){
                    if(post.getContent().toString().length() > 150){
                        String contentString = post.getContent().toString().subSequence(0, 150) + "...";
                        contentText.setText(contentString);
                        mReadMoreButton2.setVisibility(View.VISIBLE);
                        mReadMoreButton2.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                contentText.setText(post.getContent().toString());
                                mReadMoreButton.setVisibility(View.GONE);
                            }
                        });
                    }else{
                        contentText.setText(post.getContent().toString());
                        mReadMoreButton2.setVisibility(View.INVISIBLE);
                    }

                    contentText.setVisibility(View.VISIBLE);
                }

                publisherNameText.setText(post.getPublisherName().toString());
                publisherNameText.setTextSize(9);

                ImageLoader.downloadImage(post.getImageUrl().toString(), Glide.with(activity), false, 5, postImage, null);

                titleText.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(post.getRedirect()){
                            TabUtils.openUrlInSameTab(post.getUrl().toString());
                        }else{
                            LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                            layoutManager.scrollToPositionWithOffset(myPosition, 0);
                            sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                            activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                        }
                    }
                });

                contentText.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(post.getRedirect()){
                            TabUtils.openUrlInSameTab(post.getUrl().toString());
                        }else{
                            LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                            layoutManager.scrollToPositionWithOffset(myPosition, 0);
                            sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                            activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                        }
                    }
                });

                cardView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(post.getRedirect()){
                            TabUtils.openUrlInSameTab(post.getUrl().toString());
                        }else{
                            LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                            layoutManager.scrollToPositionWithOffset(myPosition, 0);
                            sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                            activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                        }
                    }
                });
            }

            editTextLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(post.getRedirect()){
                            TabUtils.openUrlInSameTab(post.getUrl().toString());
                        }else{
                            LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                            layoutManager.scrollToPositionWithOffset(myPosition, 0);
                            sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                            activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, true);
                        }
                    }
                });

            if (post.getCommentCount() > 0) {
                String commentCountText = "View " + post.getCommentCount() + " comments";
                mCommentButton.setText(commentCountText);
            } else {
                String commentCountText = "View comments";
                mCommentButton.setText(commentCountText);
            }
            
            mCommentButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCommentButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(myPosition, 0);
                    sendEventToPostHog(PostHogEventKeys.POST_CLICKED_ON, accessToken, post.getId());
                    activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                }
            });
            }catch(Exception ex){
                Log.e("BE_GET_POST", "Exception occurred", ex);
                stopAutoScroll();
                releasePlayer();
            }
        }

        private void togglePlayPause() {
            if (player != null) {
                boolean isCurrentlyPlaying = player.isPlaying();
                player.setPlayWhenReady(!isCurrentlyPlaying);
                updatePlayPauseUI(!isCurrentlyPlaying);
            }
        }
        
        private void updatePlayPauseUI(boolean isPlaying) {
            if (isPlaying) {
                // Show pause icon briefly when video starts playing
                playPauseIcon.setImageResource(R.drawable.ic_pause_circle2);
                playPauseIcon.setVisibility(View.VISIBLE);
                playPauseIcon.setAlpha(1f);
                
                // Fade out after 2 seconds when playing
                playPauseIcon.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .setStartDelay(2000) // Show for 2 seconds before fading
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (player != null && player.isPlaying()) {
                                playPauseIcon.setVisibility(View.GONE);
                            }
                            playPauseIcon.setAlpha(1f);
                        }
                    })
                    .start();
            } else {
                // Show play icon and keep it visible when paused
                playPauseIcon.animate().cancel(); // Cancel any ongoing animation
                playPauseIcon.setImageResource(R.drawable.ic_play_circle2);
                playPauseIcon.setVisibility(View.VISIBLE);
                playPauseIcon.setAlpha(1f);
            }
        }

        private void setupProgressBar() {
            if (player != null) {
                videoProgressBar.setMax(1000); // Use 1000 for smoother progress
                videoProgressBar.setProgress(0);
            }
        }

        private void startProgressAnimation() {
            if (progressAnimator != null) {
                progressAnimator.cancel();
            }

            long duration = player.getDuration();
            long currentPosition = player.getCurrentPosition();
            
            progressAnimator = ValueAnimator.ofInt((int)(currentPosition * 1000 / duration), 1000);
            progressAnimator.setDuration(duration - currentPosition);
            progressAnimator.setInterpolator(new LinearInterpolator());
            progressAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (videoProgressBar != null) {
                        int progress = (int) animation.getAnimatedValue();
                        videoProgressBar.setProgress(progress);
                    }
                }
            });
            progressAnimator.start();
        }

        private void pauseProgressAnimation() {
            if (progressAnimator != null) {
                progressAnimator.pause();
            }
        }

        private void updatePlayPauseIcon(boolean isPlaying) {
            playPauseIcon.setImageResource(isPlaying ? 
                R.drawable.ic_pause_circle2 : R.drawable.ic_play_circle2);
        }

        private void releasePlayer() {
            if (progressAnimator != null) {
                progressAnimator.cancel();
                progressAnimator = null;
            }
            if (player != null) {
                player.release();
                player = null;
            }
            if (playPauseIcon != null) {
                playPauseIcon.animate().cancel();
            }
        }

        // Make sure to release the player when the view is recycled
        // public void onViewRecycled() {
        //     releasePlayer();
        //     stopAutoScroll(); // Add this

        //     if (twitterImage != null && mContext != null) { // Add mContext null check
        //         Glide.with(mContext).clear(twitterImage); // Use mContext from constructor
        //         twitterImage.setImageDrawable(null);
        //     }
        //     if (twitterProfilePicture != null && mContext != null) {
        //         Glide.with(mContext).clear(twitterProfilePicture);
        //         twitterProfilePicture.setImageDrawable(null);
        //     }
        //     if (postImage != null && mContext != null) {
        //         Glide.with(mContext).clear(postImage);
        //         postImage.setImageDrawable(null);
        //     }
        // }

        // Make sure to release the player when the view is detached
        // public void onViewDetachedFromWindow() {
        //     releasePlayer();
        // }

        private void setupAutoScroll() {
            if (isAutoScrolling) return;
            isAutoScrolling = true;
            autoScrollRunnable =
                () -> {
                if (!isAutoScrolling || mCommentAdapter == null || mCommentAdapter.getItemCount() == 0 || mTopCommentsRecycler.getLayoutManager() == null) {
                    stopAutoScroll();
                    return;
                }
                int currentPosition = ((LinearLayoutManager) mTopCommentsRecycler.getLayoutManager()).findFirstVisibleItemPosition();
                int nextPosition = (currentPosition + 1) % mCommentAdapter.getItemCount();
                mTopCommentsRecycler.smoothScrollToPosition(nextPosition);
                autoScrollHandler.postDelayed(autoScrollRunnable, 5000);
                };
            autoScrollHandler.postDelayed(autoScrollRunnable, 5000);
        }

        private void cleanup() {
            // 1. Stop the Auto-Scroll Handler.
            stopAutoScroll();

            // 2. Release the player if it exists.
            releasePlayer();

            if (mComments != null) {
                mComments.clear();
            }

            if (mCommentAdapter != null && !mComments.isEmpty()) {
                // Get the number of items before clearing the list
                int itemCount = mComments.size();
                // Clear the data source
                mComments.clear();
                // Notify the adapter that the items were removed
                mCommentAdapter.notifyItemRangeRemoved(0, itemCount);
            }

            // 3. Clear any pending image loads.
            Context safeContext = itemView.getContext();
            if (safeContext != null) {
                try {
                    if (twitterImage != null) Glide.with(safeContext).clear(twitterImage);
                    if (twitterProfilePicture != null) Glide.with(safeContext).clear(twitterProfilePicture);
                    if (postImage != null) Glide.with(safeContext).clear(postImage);
                } catch (Exception e) {
                    Log.w("PostHolder", "Error clearing Glide images.", e);
                }
            }

            // 4. Reset view visibility to default state.
            if (twitterPostLayout != null) twitterPostLayout.setVisibility(View.GONE);
            if (twitterMediaCard != null) twitterMediaCard.setVisibility(View.GONE);
            if (cardView != null) cardView.setVisibility(View.VISIBLE);
        }

        private void sendEventToPostHog(String event, String accessToken, String postId) {
            try {
                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);    
                if (decodedAccessTokenObj != null) {
                    String pInfo = activity.getCurrentAppVersion();
                    JSONObject payload = new JSONObject();
                    payload.put("post_id", postId);
                    payload.put("app_version", pInfo);
                    PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                        new PostHogUtil.PostHogWorkerTask(event, decodedAccessTokenObj.getString("_id"), payload);
                    postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            } catch (JSONException e) {
                Log.e("TokenHandler", "Error decoding new access token", e);
            }
        }

        private JSONObject getDecodedToken(String accessToken){
            if (accessToken == null || accessToken.isEmpty()) return null;
            try{
                String[] split_string = accessToken.split("\\.");
                if (split_string.length < 2) { // JWT must have at least header and payload
                    Log.e("TokenDecoder", "Invalid JWT format");
                    return null;
                }
                String base64EncodedBody = split_string[1];
                byte[] data = Base64.decode(base64EncodedBody, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); // Use URL_SAFE for JWTs
                String decodedString = new String(data, "UTF-8");
                return new JSONObject(decodedString);
            }catch(JSONException e){
                Log.e("TokenDecoder", "JSON parsing error in token: " + e.getMessage());
                return null;
            }catch(UnsupportedEncodingException e){
                Log.e("TokenDecoder", "UTF-8 encoding not supported: " + e.getMessage());
                return null;
            } catch(IllegalArgumentException e) {
                Log.e("TokenDecoder", "Base64 decoding error: " + e.getMessage());
                return null;
            }
        }

        private void stopAutoScroll() {
            if (autoScrollHandler != null) {
                autoScrollHandler.removeCallbacksAndMessages(null);
            }
            isAutoScrolling = false;
        }
    }
}