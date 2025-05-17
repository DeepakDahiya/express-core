package org.chromium.chrome.browser.browser_express_comments;

import android.content.Intent;
import java.util.UUID;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.util.ArrayList;
import android.widget.TextView;
import android.view.View;
import org.chromium.base.Log;
import android.widget.ImageButton;
import android.widget.Button;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;
import org.chromium.chrome.R;
import com.bumptech.glide.Glide;
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
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import android.os.Bundle;
import androidx.core.content.res.ResourcesCompat;
import org.chromium.ui.widget.Toast;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import androidx.cardview.widget.CardView;
import android.widget.ProgressBar;
import androidx.recyclerview.widget.LinearSnapHelper;
import android.os.Handler;
import android.os.Looper;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.util.Util;
import android.view.MotionEvent;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import androidx.annotation.NonNull; 
import com.google.android.exoplayer2.MediaMetadata;
import com.bumptech.glide.request.target.Target;
import android.util.TypedValue;

public class CommentListAdapter extends RecyclerView.Adapter<CommentListAdapter.CommentHolder> {
    private Context mContext;
    private List<Comment> mCommentList;
    private EditText mMessageEditText;
    private RecyclerView mTopCommentRecycler;
    private BrowserExpressCommentsBottomSheetFragment mParentFragment;
    private boolean mIsReplyAdapter;
    private boolean mIsReplyTopComment;
    private boolean mIsReplyToReplyAdapter;
    private final VideoPlaybackManager videoPlaybackManager;

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment, boolean isReplyToReplyAdapter) {
        mContext = context;
        mCommentList = commentList;
        mMessageEditText = messageEditText;
        mTopCommentRecycler = topCommentRecycler;
        mParentFragment = parentFragment;
        mIsReplyAdapter = isReplyAdapter;
        mIsReplyTopComment = isReplyTopComment;
        mIsReplyToReplyAdapter = isReplyToReplyAdapter;
        videoPlaybackManager = new VideoPlaybackManager();
    }

    public VideoPlaybackManager getVideoPlaybackManager() {
        return videoPlaybackManager;
    }

    @Override
    public int getItemCount() {
        return mCommentList.size();
    }

    @NonNull // Added NonNull
    @Override
    public CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_comment, parent, false);
        return new CommentHolder(view, mMessageEditText, mTopCommentRecycler, mParentFragment, mIsReplyAdapter, mIsReplyTopComment, mIsReplyToReplyAdapter, videoPlaybackManager);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentHolder holder, int position) {
        Comment comment = mCommentList.get(position);
        holder.bind(comment);
    }

    public class VideoPlaybackManager { // This is your INSTANCE-BASED manager
        // Instance fields for the manager
        private ExoPlayer mCurrentlyPlayingVideo;
        private CommentHolder mCurrentlyPlayingHolder;
        private final List<CommentHolder> mActiveHolders = new ArrayList<>(); // Ensure this is defined
        private static final String TAG = "VideoPlaybackManagerInst"; // Or your preferred tag

        // Constructor (can be empty or initialize things if needed)
        public VideoPlaybackManager() {}

        public synchronized void addActiveHolder(CommentHolder holder) {
            if (!mActiveHolders.contains(holder)) {
                mActiveHolders.add(holder);
                Log.d(TAG, "Added active holder. Count: " + mActiveHolders.size());
            }
        }

        public synchronized void removeActiveHolder(CommentHolder holder) {
            boolean removed = mActiveHolders.remove(holder);
            if (removed) {
                Log.d(TAG, "Removed active holder. Count: " + mActiveHolders.size());
            }
            if (mCurrentlyPlayingHolder == holder) {
                mCurrentlyPlayingVideo = null;
                mCurrentlyPlayingHolder = null;
                Log.d(TAG, "Removed holder was the currently playing one.");
            }
        }

        public synchronized void onVideoPlayRequest(ExoPlayer newPlayer, CommentHolder newHolder) {
            if (mCurrentlyPlayingVideo != null && mCurrentlyPlayingVideo != newPlayer && mCurrentlyPlayingHolder != newHolder) {
                Log.d(TAG, "Pausing previous video for new request.");
                mCurrentlyPlayingVideo.setPlayWhenReady(false);
                if (mCurrentlyPlayingHolder != null && mCurrentlyPlayingHolder.playPauseIcon != null) {
                    mCurrentlyPlayingHolder.updatePlayPauseIcon(false);
                }
            }
            mCurrentlyPlayingVideo = newPlayer;
            mCurrentlyPlayingHolder = newHolder;
            if (newPlayer != null) {
                Log.d(TAG, "Playing new video.");
                newPlayer.setPlayWhenReady(true);
            }
        }

        public synchronized void onVideoStop(ExoPlayer playerToStop) {
            if (playerToStop != null) {
                playerToStop.setPlayWhenReady(false);
                Log.d(TAG, "Video stopped/paused by user action.");
            }
            // No need to null out mCurrentlyPlayingVideo/Holder here if it's just a pause
        }

        public synchronized void pauseCurrentlyPlayingVideo() {
            if (mCurrentlyPlayingVideo != null) {
                Log.d(TAG, "Pausing currently playing video (manager request).");
                mCurrentlyPlayingVideo.setPlayWhenReady(false);
            }
        }

        // THIS IS THE SINGLE DEFINITION OF pauseAllPlayers
        public synchronized void pauseAllPlayers() {
            Log.d(TAG, "Pausing all " + mActiveHolders.size() + " active players (manager instance).");
            List<CommentHolder> holdersToPause = new ArrayList<>(mActiveHolders); // Use instance field
            for (CommentHolder holder : holdersToPause) {
                if (holder.player != null && holder.player.isPlaying()) {
                    holder.player.setPlayWhenReady(false);
                }
            }
        }
        
        public synchronized CommentHolder getCurrentlyPlayingHolder() {
            return mCurrentlyPlayingHolder;
        }

        public synchronized void clearCurrentlyPlayingVideoIfMatches(ExoPlayer player) {
            if (mCurrentlyPlayingVideo == player) {
                mCurrentlyPlayingVideo = null;
                mCurrentlyPlayingHolder = null;
                Log.d(TAG, "Cleared currently playing video reference as it matched released player.");
            }
        }

        // THIS IS THE SINGLE DEFINITION OF releaseAllResources
        public synchronized void releaseAllResources() {
            Log.d(TAG, "Releasing all resources in VideoPlaybackManager instance.");
            pauseAllPlayers(); // Call the existing pauseAllPlayers method

            List<CommentHolder> holdersToRelease = new ArrayList<>(mActiveHolders); // Use instance field
            for (CommentHolder holder : holdersToRelease) {
                if (holder != null) {
                    holder.releasePlayer(); // This will call removeActiveHolder and clearCurrentlyPlayingVideoIfMatches
                }
            }
            mActiveHolders.clear(); // Use instance field

            // These should be null if holders called clearCurrentlyPlayingVideoIfMatches
            mCurrentlyPlayingVideo = null; // Use instance field
            mCurrentlyPlayingHolder = null; // Use instance field
            Log.d(TAG, "All resources released. Active holders: " + mActiveHolders.size()); // Use instance field
        }
    }

    public static class CommentHolder extends RecyclerView.ViewHolder {
        TextView usernameText;
        TextView contentText;
        TextView voteCountText;
        private ImageView mAvatarImage;
        private ImageButton mUpvoteButton;
        private ImageButton mDownvoteButton;
        private Button mReplyButton;
        private Button mShareButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity; // Consider how this is used, if context is enough
        private Button mReadMoreButton;

        private RecyclerView mTopCommentRecycler; // From constructor
        // private CommentListAdapter mCommentAdapter; // Not used in this class, consider removing
        // private List<Comment> mComments; // Not used in this class, consider removing

        private LinearLayout mActionItemsLayout;
        private LinearLayout mVoteLayout;

        private EditText mMessageEditText; // From constructor
        private LinearLayout mCommentLayout;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition; // Set in bind
        private BrowserExpressCommentsBottomSheetFragment mParentFragment; // From constructor

        private boolean mIsReplyAdapter; // From constructor
        private boolean mIsReplyTopComment; // From constructor
        private boolean mIsReplyToReplyAdapter; // From constructor

        ImageView commentImage;
        CardView commentMediaCard;
        StyledPlayerView commentVideo;
        ExoPlayer player;
        ImageView playPauseIcon;
        ProgressBar videoProgressBar;
        ValueAnimator progressAnimator;
        private Context context; // Should be initialized from itemView.getContext()

        private final VideoPlaybackManager mVideoManagerInstance;

        CommentHolder(@NonNull View itemView, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment, boolean isReplyToReplyAdapter, VideoPlaybackManager videoManager) {
            super(itemView);
            this.context = itemView.getContext(); // Initialize context

            mMessageEditText = messageEditText;
            mTopCommentRecycler = topCommentRecycler;
            mParentFragment = parentFragment;
            mIsReplyAdapter = isReplyAdapter;
            mIsReplyTopComment = isReplyTopComment;
            mIsReplyToReplyAdapter = isReplyToReplyAdapter;

            mVideoManagerInstance = videoManager; // Store the instance

            mAvatarImage = itemView.findViewById(R.id.avatar_image);
            usernameText = itemView.findViewById(R.id.username);
            contentText = itemView.findViewById(R.id.comment_content);
            voteCountText = itemView.findViewById(R.id.vote_count);
            mUpvoteButton = itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = itemView.findViewById(R.id.btn_downvote);
            mReplyButton = itemView.findViewById(R.id.btn_reply);
            mShareButton = itemView.findViewById(R.id.btn_share_image);
            mActionItemsLayout = itemView.findViewById(R.id.action_items);
            mCommentLayout = itemView.findViewById(R.id.comment_layout);
            mReadMoreButton = itemView.findViewById(R.id.btn_read_more_comment);

            commentImage = itemView.findViewById(R.id.comment_image);
            commentVideo = itemView.findViewById(R.id.comment_video);
            commentMediaCard = itemView.findViewById(R.id.comment_media_card);

            playPauseIcon = itemView.findViewById(R.id.play_pause_icon);
            videoProgressBar = itemView.findViewById(R.id.video_progress);

            mVoteLayout = itemView.findViewById(R.id.vote_layout);

            // Assign activity carefully. itemView.getContext() might not always be BraveActivity.
            // It's better to pass specific callbacks or data if needed, or check instance.
            if (this.context instanceof BraveActivity) {
                this.activity = (BraveActivity) this.context;
            } else {
                try {
                    this.activity = BraveActivity.getBraveActivity(); // Fallback, use with caution
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e("CommentHolder", "BraveActivity not found for holder", e);
                    // Handle case where activity is null - dependent UIs might fail
                }
            }
        }

        private void setVideoHeightToAspectRatio(final StyledPlayerView videoView) {
            if (videoView == null || videoView.getContext() == null) return;
            videoView.post(new Runnable() {
                @Override
                public void run() {
                    int viewWidth = videoView.getWidth();
                    ViewGroup.LayoutParams params = videoView.getLayoutParams();
                    if (viewWidth > 0) {
                        // Calculate height for a 16:9 aspect ratio
                        params.height = (int) (viewWidth * (9.0 / 16.0));
                    } else {
                        // Fallback to a fixed DP height if width is not available (e.g., 200dp)
                        params.height = (int) TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 200,
                                videoView.getContext().getResources().getDisplayMetrics());
                        Log.w("VideoHeight", "VideoView width was 0, used fixed DP for height.");
                    }
                    videoView.setLayoutParams(params);
                    videoView.requestLayout();
                    Log.d("VideoHeight", "Set video height to aspect ratio or default: " + params.height);
                }
            });
        }

        void bind(Comment comment) {
            myPosition = getBindingAdapterPosition(); // getAbsoluteAdapterPosition() is also an option

            // Ensure activity is not null before using it extensively
            if (activity == null) {
                Log.e("CommentHolder.bind", "Activity is null, some UI updates might fail.");
                // Potentially return or disable UI elements that depend on activity
            }

            if (mIsReplyTopComment && activity != null) {
                mCommentLayout.setBackground(ResourcesCompat.getDrawable(activity.getResources(), R.drawable.rounded_corner_background, null));
                mActionItemsLayout.setVisibility(View.VISIBLE);
                mReplyButton.setVisibility(View.INVISIBLE);
            }

            usernameText.setText(comment.getUser().getUsername());
            if(comment.getContent().length() > 150){
                String contentString = comment.getContent().substring(0, 150) + "...";
                contentText.setText(contentString);
                mReadMoreButton.setVisibility(View.VISIBLE);
                mReadMoreButton.setOnClickListener(v -> {
                    contentText.setText(comment.getContent());
                    mReadMoreButton.setVisibility(View.GONE);
                });
            }else{
                contentText.setText(comment.getContent());
                mReadMoreButton.setVisibility(View.GONE); // Ensure it's hidden if not needed
            }

            finalVote = comment.getUpvoteCount() - comment.getDownvoteCount();
            voteCountText.setText(formatNumberCompact(finalVote));

            if(!mIsReplyToReplyAdapter){
                mActionItemsLayout.setVisibility(View.VISIBLE);
                if(mIsReplyTopComment){
                    mActionItemsLayout.setVisibility(View.GONE);
                }
            }else{
                mActionItemsLayout.setVisibility(View.GONE);
            }

            String twitterImageUrl = comment.getMediaImageUrl();
            String videoUrl = comment.getMediaVideoUrl();

            // Reset visibility before setting
            commentMediaCard.setVisibility(View.GONE);
            commentImage.setImageDrawable(null);
            commentImage.setVisibility(View.GONE);
            commentVideo.setVisibility(View.GONE);
            playPauseIcon.setVisibility(View.GONE);
            videoProgressBar.setVisibility(View.GONE);
            if (commentVideo != null) {
                commentVideo.setPlayer(null);
            }


            if (player != null) {
                releasePlayer(); // Release existing player before creating a new one or if no video
            }

            boolean hasImage = twitterImageUrl != null && !"null".equals(twitterImageUrl) && !twitterImageUrl.isEmpty();
            boolean hasVideo = videoUrl != null && !"null".equals(videoUrl) && !videoUrl.isEmpty();

            if (hasImage || hasVideo) {
                commentMediaCard.setVisibility(View.VISIBLE);
                commentImage.setVisibility(View.VISIBLE);
            }

            if (hasImage) {
                ImageLoader.downloadImage(twitterImageUrl, Glide.with(activity), false, 5, commentImage, new ImageLoader.Callback() {
                    @Override
                    public boolean onLoadFailed() {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Target<Drawable> target) {
                        if (hasVideo) {
                            commentImage.post(() -> {
                                if (commentVideo == null || commentImage == null) return;
                                int h = commentImage.getHeight();
                                if (h > 0) {
                                    ViewGroup.LayoutParams videoParams = commentVideo.getLayoutParams();
                                    videoParams.height = h;
                                    commentVideo.setLayoutParams(videoParams);
                                    commentVideo.requestLayout();
                                    Log.d("VideoHeight", "Set video height to image placeholder: " + h);
                                } else {
                                    Log.w("VideoHeight", "Image resource ready but height is 0. Falling back for video.");
                                    setVideoHeightToAspectRatio(commentVideo);
                                }
                            });
                        }
                        return false;
                    }
                });
            }

            if (hasVideo) {
                if (this.context != null) {
                    player = new ExoPlayer.Builder(this.context).build();
                    mVideoManagerInstance.addActiveHolder(this);

                    commentVideo.setPlayer(player);
                    commentVideo.setUseController(false);

                    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(videoUrl);
                    mediaItemBuilder.setMediaMetadata(new MediaMetadata.Builder()
                        .setArtworkUri(Uri.parse(twitterImageUrl))
                        .build());
                    MediaItem mediaItem = mediaItemBuilder.build();
                    player.setMediaItem(mediaItem);
                    player.setRepeatMode(Player.REPEAT_MODE_ALL); // Or REPEAT_MODE_OFF if you don't want looping by default
                    player.setPlayWhenReady(false); // Important: start paused
                    player.prepare();

                    // Visibility handled by listener
                    // commentVideo.setVisibility(View.VISIBLE);

                    playPauseIcon.setImageResource(R.drawable.ic_play_circle2);
                    playPauseIcon.setVisibility(View.VISIBLE);

                    View.OnClickListener videoClickListener = v -> togglePlayPause();
                    commentVideo.setOnClickListener(videoClickListener);
                    playPauseIcon.setOnClickListener(videoClickListener);

                    commentImage.post(new Runnable() {
                        @Override
                        public void run() {
                            int h = commentImage.getHeight();
                            commentVideo.getLayoutParams().height = h;
                            commentVideo.requestLayout();
                        }
                    });

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int state) {
                            if (state == Player.STATE_READY) {
                                // Once video is ready, hide image placeholder and show video view
                                if (commentImage.getVisibility() == View.VISIBLE) {
                                    commentImage.setVisibility(View.GONE);
                                }
                                commentVideo.setVisibility(View.VISIBLE);
                                setupProgressBar();
                            } else if (state == Player.STATE_BUFFERING) {
                                // Optionally show a loading indicator
                            } else if (state == Player.STATE_ENDED) {
                                // Handle end of video if not repeating
                                 videoProgressBar.setProgress(videoProgressBar.getMax()); // Show full progress
                            }
                        }
                        
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            updatePlayPauseIcon(isPlaying); // Corrected: was updatePlayPauseUI
                            if (isPlaying) {
                                startProgressAnimation();
                            } else {
                                pauseProgressAnimation();
                            }
                        }
                    });
                } else {
                     Log.e("CommentHolder.bind", "Context is null, cannot initialize ExoPlayer.");
                }

            }


            if(mMessageEditText == null && mParentFragment == null && mTopCommentRecycler == null && activity != null){
                // Post top comments specific UI adjustments
                mVoteLayout.setVisibility(View.GONE);
                mActionItemsLayout.setVisibility(View.GONE);

                usernameText.setTextSize(11);
                contentText.setTextSize(12);
                mReadMoreButton.setVisibility(View.GONE);

                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) contentText.getLayoutParams();
                params.bottomMargin = 0;
                contentText.setLayoutParams(params);

                mCommentLayout.setPadding(10, 10, 10, 0);

                View.OnClickListener postClickListener = v -> {
                    if (activity != null) { // Check activity again
                         activity.showCommentsBottomSheetFromPost(comment.getPostParent(), false);
                    }
                };
                usernameText.setOnClickListener(postClickListener);
                mCommentLayout.setOnClickListener(postClickListener);
                contentText.setOnClickListener(postClickListener);

                if(comment.getContent().length() > 75){
                    String contentString = comment.getContent().substring(0, 75) + "...";
                    contentText.setText(contentString);
                }
            }
                
            if(comment.getUser().getAvatar() != null && !comment.getUser().getAvatar().isEmpty() && activity != null){
                ImageLoader.downloadImage(comment.getUser().getAvatar(), Glide.with(activity), true, 5, mAvatarImage, null);
            } else if (activity != null) { // Ensure activity isn't null for Glide
                ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + comment.getUser().getId() + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), true, 5, mAvatarImage, null);
            }


            if (activity != null) { // Ensure activity isn't null
                bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
                bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

                SharedPreferences sharedPref = activity.getSharedPreferencesForReplyComment();
                SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                    if(key != null && key.equals(BraveActivity.BROWSER_EXPRESS_REPLY_COMMENT)){
                        String replyCommentJson = activity.getReplyComment(); // Renamed for clarity
                        if(replyCommentJson != null && !replyCommentJson.isEmpty()){
                            try{
                                JSONObject commentObject = new JSONObject(replyCommentJson);
                                
                                JSONObject userJson = commentObject.getJSONObject("user");
                                User u = new User(userJson.getString("_id"), userJson.getString("username"), userJson.optString("avatar", null));
                                // Vote v = null; // Vote is not typically part of a new comment structure from server reply
                                String pageParent = commentObject.optString("pageParent", null);
                                String postParent = commentObject.optString("postParent", null);
                                String commentParent = commentObject.optString("commentParent", null);

                                if(comment.getId().equals(commentParent)){ // Check if this comment is the parent of the new reply
                                    Comment newReplyComment = new Comment(
                                        commentObject.getString("_id"), 
                                        commentObject.getString("content"),
                                        commentObject.getInt("upvoteCount"),
                                        commentObject.getInt("downvoteCount"),
                                        commentObject.getInt("commentCount"),
                                        pageParent, 
                                        postParent,
                                        commentParent,
                                        u,
                                        null, // New comments usually don't have a "didVote" status for the current user yet
                                        commentObject.optString("mediaImageUrl", null), // Add media fields
                                        commentObject.optString("mediaVideoUrl", null)
                                    );
                                    // This logic of adding to mComments and notifying mCommentAdapter
                                    // should ideally be handled by the adapter itself or a higher-level component
                                    // that manages the data list. Directly modifying mComments here if it's not
                                    // the adapter's list is problematic. Assuming mCommentList is the adapter's list.
                                    // if (mCommentList != null && mCommentAdapter != null) {
                                    //    mCommentList.add(0, newReplyComment);
                                    //    mCommentAdapter.notifyItemInserted(0);
                                    // }
                                    // For now, I will assume this listener's purpose is for something else or needs refactoring
                                    // if mComments and mCommentAdapter are not the adapter's properties.
                                }
                            } catch (JSONException e) {
                                Log.e("BROWSER_EXPRESS_REPLY_COMMENT_EXTRACT", "Error parsing reply JSON", e);
                            }
                        }
                    }
                };
                sharedPref.registerOnSharedPreferenceChangeListener(listener);
                // TODO: Remember to unregister this listener in onViewRecycled or when holder is no longer needed
                // itemView.addOnAttachStateChangeListener(...) with unregister in onViewDetachedFromWindow
            }
            
            Vote didVote = comment.getDidVote();
            // Reset button backgrounds first
            if (mUpvoteButton != null) mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
            if (mDownvoteButton != null) mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);

            if(didVote != null){
                String type = didVote.getType();
                didVoteType = type; // Store initial vote state
                if(type.equals("up") && mUpvoteButton != null){
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                }else if(type.equals("down") && mDownvoteButton != null){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                }
            } else {
                didVoteType = null; // No initial vote
            }

            if(mReplyButton != null && activity != null) { // Check activity
                if(comment.getCommentCount() > 0){
                    String mReplyButtonText = comment.getCommentCount() + " replies";
                    mReplyButton.setText(mReplyButtonText);
                    mReplyButton.setTextColor(ContextCompat.getColor(activity, R.color.browser_express_blue_color));
                }
            }


            if(mReplyButton != null){
                mReplyButton.setOnClickListener(v -> {
                    if (activity == null || mParentFragment == null) return; // Guard clause

                    // Ensure mTopCommentRecycler is not null and has a LayoutManager
                    if (mTopCommentRecycler != null && mTopCommentRecycler.getLayoutManager() instanceof LinearLayoutManager) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) mTopCommentRecycler.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(myPosition, 0);
                    }


                    if(mIsReplyAdapter){
                        mParentFragment.openRepliesToReply(comment.getId());
                    } else if (!mIsReplyToReplyAdapter){ // This condition was: !mIsReplyAdapter && !mIsReplyToReplyAdapter
                        mParentFragment.openReplies(comment.getId());
                    } else { // This case implies mIsReplyToReplyAdapter is true
                        // This block was for setting replyTo in BraveActivity, seems specific for direct replies in the same list
                        try {
                            JSONObject json = new JSONObject();
                            json.put("name", comment.getUser().getUsername());
                            json.put("commentId", comment.getId());
                            activity.setReplyTo(json.toString());
                            EditText contentEditText = activity.getContentEditText(); // Re-fetch, mMessageEditText might be stale
                            if(contentEditText != null){
                                String replyToString = "replying to " + comment.getUser().getUsername(); // Not directly used here
                                contentEditText.requestFocus();
                                // Show keyboard logic might be better handled by activity/fragment after setting replyTo
                            }
                        } catch (JSONException e) {
                            Log.e("BROWSER_EXPRESS_REPLY_TO_CLICK", "JSON error", e);
                        }
                    }
                });
            }


            if(mShareButton != null){
                mShareButton.setOnClickListener(v -> {
                    if (activity == null) return;
                    String link = "https://browser.express/view?id=" + comment.getId();
                    String message = "People say the craziest stuff! 👀 Check this out 👇\n\n" + link + "\n\n" + "Dive in—it's where everyone’s talking about everything, nonstop.";
                    Intent sharingIntent = new Intent(Intent.ACTION_SEND);
                    sharingIntent.setType("text/plain");
                    sharingIntent.putExtra(Intent.EXTRA_TEXT, message);
                    activity.startActivity(Intent.createChooser(sharingIntent, "Share via")); // Added title
                });
            }


            if (mUpvoteButton != null) {
                mUpvoteButton.setOnClickListener(v -> {
                    if (activity == null) return;
                    String accessToken = activity.getAccessToken();
                    // Handle accessToken == null case (e.g., show login/prompt)
                    // if (accessToken == null) { activity.showGenerateUsernameBottomSheet(); return; }


                    mUpvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (bounceUp != null) mUpvoteButton.startAnimation(bounceUp);
                    
                    int oldFinalVote = finalVote; // Store for rollback on error

                    if(didVoteType != null && didVoteType.equals("down")){ // Was downvoted, now upvoting
                        finalVote += 2;
                        didVoteType = "up";
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    } else if (didVoteType != null && didVoteType.equals("up")){ // Was upvoted, now un-upvoting
                        finalVote -= 1;
                        didVoteType = null;
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                    } else { // No vote or other vote, now upvoting
                        finalVote += 1;
                        didVoteType = "up";
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote); // Ensure downvote is normal
                    }
                    voteCountText.setText(formatNumberCompact(finalVote));
                    mUpvoteButton.setClickable(false); // Prevent multi-click
                    mDownvoteButton.setClickable(false);


                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                comment.getId(), "up", "comment", accessToken, new BrowserExpressAddVoteUtil.AddVoteCallback() {
                                    @Override
                                    public void addVoteSuccessful(String newAccessToken, String newRefreshToken) {
                                        // Vote successful, UI is already updated optimistically
                                        mUpvoteButton.setClickable(true);
                                        mDownvoteButton.setClickable(true);
                                        if (newRefreshToken != null && !newRefreshToken.isEmpty() && activity != null) {
                                            handleNewToken(newAccessToken, newRefreshToken);
                                        }
                                    }

                                    @Override
                                    public void addVoteFailed(String error) {
                                        // Rollback UI
                                        finalVote = oldFinalVote;
                                        // Re-evaluate didVoteType based on oldFinalVote or comment.getDidVote() if fetched again
                                        // For simplicity, just reset to previous text. A more robust rollback would reset button states too.
                                        voteCountText.setText(formatNumberCompact(finalVote));
                                        // Reset button backgrounds to before click based on 'oldFinalVote' and previous 'didVoteType'
                                        // This part is complex and depends on how 'didVoteType' was before this click.
                                        // For now, just re-enable buttons.
                                        mUpvoteButton.setClickable(true);
                                        mDownvoteButton.setClickable(true);
                                        Toast.makeText(context, "Vote failed: " + error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                });
            }


            if(mDownvoteButton != null) {
                mDownvoteButton.setOnClickListener(v -> {
                    if (activity == null) return;
                    String accessToken = activity.getAccessToken();
                    // if (accessToken == null) { activity.showGenerateUsernameBottomSheet(); return; }

                    mDownvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (bounceDown != null) mDownvoteButton.startAnimation(bounceDown);

                    int oldFinalVote = finalVote;

                    if(didVoteType != null && didVoteType.equals("up")){ // Was upvoted, now downvoting
                        finalVote -= 2;
                        didVoteType = "down";
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                    } else if (didVoteType != null && didVoteType.equals("down")){ // Was downvoted, now un-downvoting
                        finalVote += 1;
                        didVoteType = null;
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    } else { // No vote or other vote, now downvoting
                        finalVote -= 1;
                        didVoteType = "down";
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote); // Ensure upvote is normal
                    }
                    voteCountText.setText(formatNumberCompact(finalVote));
                    mUpvoteButton.setClickable(false);
                    mDownvoteButton.setClickable(false);

                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                comment.getId(), "down", "comment", accessToken, new BrowserExpressAddVoteUtil.AddVoteCallback() {
                            @Override
                            public void addVoteSuccessful(String newAccessToken, String newRefreshToken) {
                                mUpvoteButton.setClickable(true);
                                mDownvoteButton.setClickable(true);
                                if (newRefreshToken != null && !newRefreshToken.isEmpty() && activity != null) {
                                   handleNewToken(newAccessToken, newRefreshToken);
                                }
                            }

                            @Override
                            public void addVoteFailed(String error) {
                                finalVote = oldFinalVote;
                                voteCountText.setText(formatNumberCompact(finalVote));
                                mUpvoteButton.setClickable(true);
                                mDownvoteButton.setClickable(true);
                                Toast.makeText(context, "Vote failed: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                });
            }
        }

        private void togglePlayPause() {
            if (player != null) {
                if (!player.isPlaying()) {
                    mVideoManagerInstance.onVideoPlayRequest(player, this);
                } else {
                    mVideoManagerInstance.onVideoStop(player);
                }
            }
        }
        
        // Renamed from updatePlayPauseUI to match call in onIsPlayingChanged
        private void updatePlayPauseIcon(boolean isPlaying) {
            if (playPauseIcon == null) return;

            playPauseIcon.animate().cancel(); // Cancel any ongoing animation
            if (isPlaying) {
                playPauseIcon.setImageResource(R.drawable.ic_pause_circle2);
                playPauseIcon.setVisibility(View.VISIBLE);
                playPauseIcon.setAlpha(1f); // Ensure it's fully visible
                
                playPauseIcon.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .setStartDelay(2000)
                    .withEndAction(() -> {
                        if (player != null && player.isPlaying()) { // Check again before hiding
                            playPauseIcon.setVisibility(View.GONE);
                        }
                        playPauseIcon.setAlpha(1f); // Reset alpha for next time
                    })
                    .start();
            } else {
                playPauseIcon.setImageResource(R.drawable.ic_play_circle2);
                playPauseIcon.setVisibility(View.VISIBLE);
                playPauseIcon.setAlpha(1f);
            }
        }


        private void setupProgressBar() {
            if (player != null && videoProgressBar != null) {
                videoProgressBar.setMax(1000); 
                videoProgressBar.setProgress(0);
                videoProgressBar.setVisibility(View.VISIBLE); // Show progress bar
            }
        }

        private void startProgressAnimation() {
            if (player == null || videoProgressBar == null || player.getDuration() <= 0) return;

            if (progressAnimator != null) {
                progressAnimator.cancel();
            }

            long duration = player.getDuration();
            long currentPosition = player.getCurrentPosition();
            
            // Ensure currentPosition is not greater than duration
            currentPosition = Math.min(currentPosition, duration);
            
            int startProgress = (int) (currentPosition * 1000 / duration);
            progressAnimator = ValueAnimator.ofInt(startProgress, 1000);
            progressAnimator.setDuration(duration - currentPosition);
            progressAnimator.setInterpolator(new LinearInterpolator());
            progressAnimator.addUpdateListener(animation -> {
                if (videoProgressBar != null) {
                    int progress = (int) animation.getAnimatedValue();
                    videoProgressBar.setProgress(progress);
                }
            });
            progressAnimator.start();
        }

        private void pauseProgressAnimation() {
            if (progressAnimator != null && progressAnimator.isRunning()) { // Check if running before pausing
                progressAnimator.pause();
            }
        }

        // This was duplicated, removing one.
        // private void updatePlayPauseIcon(boolean isPlaying) {
        //     playPauseIcon.setImageResource(isPlaying ? 
        //         R.drawable.ic_pause_circle2 : R.drawable.ic_play_circle2);
        // }

        private void releasePlayer() {
            mVideoManagerInstance.removeActiveHolder(this);
            if (progressAnimator != null) {
                progressAnimator.cancel();
                progressAnimator = null;
            }
            if (player != null) {
                mVideoManagerInstance.clearCurrentlyPlayingVideoIfMatches(player);
                player.release();
                player = null;
            }
            if (playPauseIcon != null) {
                playPauseIcon.animate().cancel(); // Cancel any animations on the icon
                playPauseIcon.setVisibility(View.GONE); // Hide it
            }
            if (videoProgressBar != null) {
                videoProgressBar.setVisibility(View.GONE); // Hide progress bar
            }
            if (commentVideo != null) {
                commentVideo.setPlayer(null); // Detach player from view
                commentVideo.setVisibility(View.GONE); // Hide video view
                ViewGroup.LayoutParams params = commentVideo.getLayoutParams();
                if (params != null) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT; // Or specific initial value
                    commentVideo.setLayoutParams(params);
                }
            }
        }

        public void onViewRecycled() { // This is your custom method
            releasePlayer();
            if (commentImage != null && context != null) {
                Glide.with(context).clear(commentImage);
                commentImage.setImageDrawable(null);
            }
            if (mAvatarImage != null && context != null) {
                Glide.with(context).clear(mAvatarImage);
                mAvatarImage.setImageDrawable(null);
            }
            // Unregister SharedPreferences listener if it was registered in bind
            // This requires storing the listener instance in the holder.
        }

        // Called by itemView's OnAttachStateChangeListener or similar
        public void onViewDetachedFromWindow() {
            releasePlayer();
        }
        
        private void handleNewToken(String newAccessToken, String newRefreshToken) {
            if (activity == null) return;
            try {
                activity.setAccessToken(newAccessToken); // Assuming this method exists and handles storage
                // Potentially update other parts of UI if needed based on new token
                JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                if (decodedAccessTokenObj != null && decodedAccessTokenObj.has("username")) {
                    // Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " session updated.", Toast.LENGTH_SHORT).show();
                    // The original code showed a "Username created" toast and started ChromeTabbedActivity.
                    // This might be too disruptive for just a token refresh during a vote.
                    // Consider if this exact behavior is desired here.
                    // If this is for first-time username generation via voting, it might be okay.
                    // Intent intent = new Intent(activity, ChromeTabbedActivity.class);
                    // intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    // intent.setAction(Intent.ACTION_VIEW);
                    // Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " created. You can edit this in Profile.", Toast.LENGTH_SHORT).show();
                    // activity.startActivity(intent);
                    Log.i("TokenHandler", "Token refreshed. New username (if changed): " + decodedAccessTokenObj.getString("username"));
                }
            } catch (JSONException e) {
                Log.e("TokenHandler", "Error decoding new access token", e);
            }
        }


        // Removed duplicate addVoteCallback, as it's now inline in button listeners
        // private BrowserExpressAddVoteUtil.AddVoteCallback addVoteCallback = ...

        public String formatNumberCompact(long number) { // Changed to long for safety
            if (number >= 1_000_000_000) { // Billions
                return String.format(Locale.getDefault(), "%.1fB", number / 1_000_000_000.0);
            } else if (number >= 1_000_000) { // Millions
                return String.format(Locale.getDefault(), "%.1fM", number / 1_000_000.0);
            } else if (number >= 10_000) { // 10K+
                 return String.format(Locale.getDefault(), "%dK", number / 1_000);
            } else if (number >= 1_000) { // 1.0K to 9.9K
                return String.format(Locale.getDefault(), "%.1fK", number / 1_000.0);
            } else {
                return String.format(Locale.getDefault(), "%d", number);
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
    }

    // Corrected signature for onViewRecycled in the Adapter
    @Override
    public void onViewRecycled(@NonNull CommentHolder holder) {
        super.onViewRecycled(holder); // This now calls the correct super method
        holder.onViewRecycled();      // Call the custom cleanup in your CommentHolder
    }
}