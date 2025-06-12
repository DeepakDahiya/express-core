package org.chromium.chrome.browser.ntp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import org.chromium.chrome.browser.browser_express_comments.CommentListAdapter;
import org.chromium.chrome.browser.local_database.TopSiteTable;
import org.chromium.chrome.browser.util.TabUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class PostListAdapter extends RecyclerView.Adapter {
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_POST = 1;

    private BraveActivity mActivity;
    private List<Post> mPostList;
    private RecyclerView mTopPostRecycler;

    // Header-specific data
    private List<TopSiteTable> mTopSites = new ArrayList<>();
    private boolean mIsShimmering = true;

    private String INSHORTS_TYPE = "Inshorts";
    private String TWITTER_TYPE = "Twitter";
    private String INSTAGRAM_TYPE = "Instagram";
    private RecyclerView mTopPostRecycler;

    public PostListAdapter(BraveActivity activity, List<Post> postList, RecyclerView topPostRecycler) {
        mActivity = activity;
        mPostList = postList;
        mTopPostRecycler = topPostRecycler;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return VIEW_TYPE_HEADER;
        }
        return VIEW_TYPE_POST;
    }

    @Override
    public int getItemCount() {
        return mPostList.size() + 1;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.ntp_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_post, parent, false);
            return new PostHolder(view, mTopPostRecycler);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
            ((HeaderViewHolder) holder).bind();
        } else {
            Post post = mPostList.get(position - 1);
            ((PostHolder) holder).bind(post);
        }
    }

    public void updateTopSites(List<TopSiteTable> topSites) {
        mTopSites.clear();
        if (topSites != null) {
            mTopSites.addAll(topSites);
        }
        notifyItemChanged(0); // Notify header view to re-bind
    }

    public void showShimmer(boolean show) {
        if (mIsShimmering != show) {
            mIsShimmering = show;
            notifyItemChanged(0); // Notify header view to re-bind
        }
    }

    private class HeaderViewHolder extends RecyclerView.ViewHolder {
        LinearLayout topSitesContainer;
        ShimmerFrameLayout shimmerLoading;
        ViewGroup shimmerItems;

        HeaderViewHolder(View itemView) {
            super(itemView);
            topSitesContainer = itemView.findViewById(R.id.top_sites_container);
            shimmerLoading = itemView.findViewById(R.id.skeleton_shimmer);
            shimmerItems = itemView.findViewById(R.id.shimmer_items);
        }

        void bind() {
            if (mIsShimmering) {
                shimmerLoading.setVisibility(View.VISIBLE);
                if (shimmerItems.getChildCount() == 0) { // Inflate skeletons only if needed
                    int shimmerSkeletonRows = 5; // Or calculate dynamically
                    for (int i = 0; i < shimmerSkeletonRows; i++) {
                        LayoutInflater.from(itemView.getContext()).inflate(R.layout.shimmer_skeleton_item, shimmerItems, true);
                    }
                }
                shimmerLoading.showShimmer(true);
            } else {
                shimmerLoading.hideShimmer();
                shimmerLoading.setVisibility(View.GONE);
            }

            if (mTopSites != null && !mTopSites.isEmpty()) {
                topSitesContainer.setVisibility(View.VISIBLE);
                topSitesContainer.removeAllViews();
                int maxSites = Math.min(4, mTopSites.size());
                for (int i = 0; i < maxSites; i++) {
                    View tileView = createTile(itemView.getContext(), mTopSites.get(i));
                    topSitesContainer.addView(tileView);
                }
            } else {
                topSitesContainer.setVisibility(View.GONE);
            }
        }
    }

    private class PostHolder extends RecyclerView.ViewHolder {
        LinearLayout twitterPostLayout;
        ImageView twitterProfilePicture;
        TextView twitterUsername;
        TextView twitterContent;
        ImageView twitterImage;
        // VideoView twitterVideo;
        CardView twitterMediaCard;
        RecyclerView mTopCommentsRecycler;
        CommentListAdapter mCommentAdapter;
        List<Comment> mComments;
        LinearLayout editTextLayout;

        StyledPlayerView twitterVideo;
        ExoPlayer player;
        ImageView playPauseIcon;
        ProgressBar videoProgressBar;
        ValueAnimator progressAnimator;

        ImageView postImage;
        CardView cardView;
        TextView publisherNameText;
        TextView publishedTimeText;
        TextView titleText;
        TextView contentText;
        private Button mCommentButton;
        private BraveActivity activity;

        private Button mReadMoreButton;
        private Button mReadMoreButton2;

        private Context context;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition;

        private Handler autoScrollHandler;
        private Runnable autoScrollRunnable;
        private int currentPosition = 0;
        private boolean isAutoScrolling = false;

        PostHolder(View itemView, RecyclerView topPostRecycler) {
            super(itemView);
            twitterPostLayout = (LinearLayout) itemView.findViewById(R.id.twitter_post_layout);
            twitterProfilePicture = (ImageView) itemView.findViewById(R.id.twitter_profile_picture);
            twitterUsername = (TextView) itemView.findViewById(R.id.twitter_username);
            twitterContent = (TextView) itemView.findViewById(R.id.twitter_content);
            twitterImage = (ImageView) itemView.findViewById(R.id.twitter_image);
            twitterVideo = (StyledPlayerView) itemView.findViewById(R.id.twitter_video);
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
        }

        void bind(Post post) {
            try {
                activity = BraveActivity.getBraveActivity();
                mComments = new ArrayList<Comment>();
                mTopCommentsRecycler.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL,false));
                mCommentAdapter = new CommentListAdapter(activity, mComments, null, null, false, false);
                mTopCommentsRecycler.setAdapter(mCommentAdapter);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            stopAutoScroll();

            if (post.getComments() != null && post.getComments().size() > 0) {
                setupAutoScroll();
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

            // LinearSnapHelper snapHelper = new LinearSnapHelper();
            // snapHelper.attachToRecyclerView(mTopCommentsRecycler);

            try{

            Log.e("BE_GET_POST", "11"); 
            List<Comment> comments = post.getComments();
            int len = comments.size();

            if (len > 0 ) {
                mTopCommentsRecycler.setVisibility(View.VISIBLE);
                mComments.addAll(comments);
                mCommentAdapter.notifyItemRangeInserted(len, comments.size());
            }

            Log.e("BE_GET_POST", "11.5"); 

            if (len == 0){
                Log.e("BE_GET_POST", "11.6"); 
                mTopCommentsRecycler.setVisibility(View.GONE);
            }

            Log.e("BE_GET_POST", "12"); 

            mTopCommentsRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    int position = layoutManager.findFirstVisibleItemPosition();
                }
            });

            Log.e("BE_GET_POST", "13"); 
            
            myPosition = getBindingAdapterPosition();

            String postType = post.getType().toString();
            SubPost subPost = post.getSubPost();
            String name = subPost.getAuthorName();
            String username = "@" + subPost.getAuthorUsername();
            String content = subPost.getContent();
            String profilePicUrl = subPost.getAuthorProfilePicture();
            Boolean verified = subPost.getAuthorVerified();

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
                        activity.showCommentsBottomSheetFromPost(post.getId(), username, content, profilePicUrl, false);
                    }
                });

                twitterPostLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(myPosition, 0);
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
                                Log.d("VideoPlayer", "Parent view clicked");
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
            
                
            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            mCommentButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCommentButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(myPosition, 0);
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
        public void onViewRecycled() {
            releasePlayer();

            if (twitterImage != null) {
                Glide.with(context).clear(twitterImage);
                twitterImage.setImageDrawable(null);
            }
            if (twitterProfilePicture != null) {
                Glide.with(context).clear(twitterProfilePicture);
                twitterProfilePicture.setImageDrawable(null);
            }
        }

        // Make sure to release the player when the view is detached
        public void onViewDetachedFromWindow() {
            releasePlayer();
        }

        private void setupAutoScroll() {
            if (isAutoScrolling) return;
            
            isAutoScrolling = true;
            currentPosition = 0;
            
            autoScrollRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isAutoScrolling) return;
                    
                    if (mCommentAdapter != null && mCommentAdapter.getItemCount() > 0) {
                        currentPosition++;
                        if (currentPosition >= mCommentAdapter.getItemCount()) {
                            currentPosition = 0;
                        }
                        
                        try {
                            mTopCommentsRecycler.smoothScrollToPosition(currentPosition);
                        } catch (Exception e) {
                            Log.e("BE_GET_POST", "Error during auto-scroll", e);
                            stopAutoScroll();
                            return;
                        }
                    }
                    
                    autoScrollHandler.postDelayed(this, 5000);
                }
            };
            
            autoScrollHandler.postDelayed(autoScrollRunnable, 5000);
        }

        private void stopAutoScroll() {
            isAutoScrolling = false;
            if (autoScrollHandler != null && autoScrollRunnable != null) {
                autoScrollHandler.removeCallbacks(autoScrollRunnable);
            }
            currentPosition = 0;
        }
    }

    private View createTile(Context context, TopSiteTable topSite) {
        View tileView = LayoutInflater.from(context).inflate(R.layout.top_site_tile_layout, null);

        ImageView imageView = tileView.findViewById(R.id.tile_image);
        TextView textView = tileView.findViewById(R.id.tile_text);
        LinearLayout imageContainer = tileView.findViewById(R.id.image_container);

        try {
            int backgroundColor = android.graphics.Color.parseColor(topSite.getBackgroundColor());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(backgroundColor);
            imageContainer.setBackground(shape);
        } catch (IllegalArgumentException e) {
            imageContainer.setBackgroundColor(android.graphics.Color.LTGRAY);
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

        String name = topSite.getName();
        if (name.length() > 30) {
            name = name.substring(0, 27) + "...";
        }
        textView.setText(name);

        tileView.setOnClickListener(v -> TabUtils.openUrlInSameTab(topSite.getDestinationUrl()));
        return tileView;
    }

    private BitmapDrawable generateAvatar(Context context, String name) {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random rnd = new Random();
        int color = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
        canvas.drawColor(color);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(60);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.valueOf(name.charAt(0)).toUpperCase(Locale.ROOT), 50, 70, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}