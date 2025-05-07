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

public class CommentListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Comment> mCommentList;
    private EditText mMessageEditText;
    private RecyclerView mTopCommentRecycler;
    private BrowserExpressCommentsBottomSheetFragment mParentFragment;
    private boolean mIsReplyAdapter;
    private boolean mIsReplyTopComment;
    private boolean mIsReplyToReplyAdapter;

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment, boolean isReplyToReplyAdapter) {
        mContext = context;
        mCommentList = commentList;
        mMessageEditText = messageEditText;
        mTopCommentRecycler = topCommentRecycler;
        mParentFragment = parentFragment;
        mIsReplyAdapter = isReplyAdapter;
        mIsReplyTopComment = isReplyTopComment;
        mIsReplyToReplyAdapter = isReplyToReplyAdapter;
    }

    @Override
    public int getItemCount() {
        return mCommentList.size();
    }

    // Inflates the appropriate layout according to the ViewType.
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_comment, parent, false);
        return new CommentHolder(view, mMessageEditText, mTopCommentRecycler, mParentFragment, mIsReplyAdapter, mIsReplyTopComment, mIsReplyToReplyAdapter);
    }

    // Passes the comment object to a ViewHolder so that the contents can be bound to UI.
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Comment comment = (Comment) mCommentList.get(position);

        ((CommentHolder) holder).bind(comment);
    }

    private class CommentHolder extends RecyclerView.ViewHolder {
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
        private BraveActivity activity;
        private Button mReadMoreButton;

        private RecyclerView mTopCommentRecycler;
        private CommentListAdapter mCommentAdapter;
        private List<Comment> mComments;
        private int mPage = 1;
        private int mPerPage = 100;
        private Context context;
        private LinearLayout mActionItemsLayout;
        private LinearLayout mVoteLayout;

        private EditText mMessageEditText;
        private LinearLayout mCommentLayout;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition;
        private BrowserExpressCommentsBottomSheetFragment mParentFragment;

        private boolean mIsReplyAdapter;
        private boolean mIsReplyTopComment;
        private boolean mIsReplyToReplyAdapter;

        ImageView commentImage;
        CardView commentMediaCard;
        StyledPlayerView commentVideo;
        ExoPlayer player;
        ImageView playPauseIcon;
        ProgressBar videoProgressBar;
        ValueAnimator progressAnimator;

        CommentHolder(View itemView, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment, boolean isReplyToReplyAdapter) {
            super(itemView);

            mMessageEditText = messageEditText;
            mParentFragment = parentFragment;
            mIsReplyAdapter = isReplyAdapter;
            mIsReplyTopComment = isReplyTopComment;
            mIsReplyToReplyAdapter = isReplyToReplyAdapter;

            mTopCommentRecycler = topCommentRecycler;
            mAvatarImage = (ImageView) itemView.findViewById(R.id.avatar_image);
            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            mReplyButton = (Button) itemView.findViewById(R.id.btn_reply);
            mShareButton = (Button) itemView.findViewById(R.id.btn_share_image);
            mActionItemsLayout = (LinearLayout) itemView.findViewById(R.id.action_items);
            mCommentLayout = (LinearLayout) itemView.findViewById(R.id.comment_layout);
            mReadMoreButton = (Button) itemView.findViewById(R.id.btn_read_more_comment);

            commentImage = (ImageView) itemView.findViewById(R.id.comment_image);
            commentVideo = (StyledPlayerView) itemView.findViewById(R.id.comment_video);
            commentMediaCard = (CardView) itemView.findViewById(R.id.comment_media_card);

            playPauseIcon = (ImageView) itemView.findViewById(R.id.play_pause_icon);
            videoProgressBar = (ProgressBar) itemView.findViewById(R.id.video_progress);

            mVoteLayout = (LinearLayout) itemView.findViewById(R.id.vote_layout);
            context = itemView.getContext();
        }

        void bind(Comment comment) {
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            if(mIsReplyTopComment){
                mCommentLayout.setBackground(ResourcesCompat.getDrawable(activity.getResources(), R.drawable.rounded_corner_background, null));
                mActionItemsLayout.setVisibility(View.VISIBLE);
                mReplyButton.setVisibility(View.INVISIBLE);
            }

            myPosition = getBindingAdapterPosition();

            usernameText.setText(comment.getUser().getUsername().toString());
            if(comment.getContent().toString().length() > 150){
                String contentString = comment.getContent().toString().subSequence(0, 150) + "...";
                contentText.setText(contentString);
                mReadMoreButton.setVisibility(View.VISIBLE);
                mReadMoreButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        contentText.setText(comment.getContent().toString());
                        mReadMoreButton.setVisibility(View.GONE);
                    }
                });
            }else{
                contentText.setText(comment.getContent().toString());
            }

            finalVote = comment.getUpvoteCount() - comment.getDownvoteCount();
            voteCountText.setText(formatNumberCompact(finalVote));
            if(mIsReplyToReplyAdapter == false){
                mActionItemsLayout.setVisibility(View.VISIBLE);
                if(mIsReplyTopComment){
                    mActionItemsLayout.setVisibility(View.GONE);
                }
            }else{
                mActionItemsLayout.setVisibility(View.GONE);
            }

            String twitterImageUrl = comment.getMediaImageUrl();
            String videoUrl = comment.getMediaVideoUrl();

            if(twitterImageUrl != null){
                ImageLoader.downloadImage(twitterImageUrl, Glide.with(activity), false, 5, commentImage, null);
                commentMediaCard.setVisibility(View.VISIBLE);
                commentImage.setVisibility(View.VISIBLE);
            }

            if(videoUrl != null && !"null".equals(videoUrl)){
                releasePlayer();
                player = new ExoPlayer.Builder(context).build();

                commentVideo.setPlayer(player);
                commentVideo.setUseController(false); // Hide default controls
                
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

                commentVideo.setClickable(true);
                commentVideo.setFocusable(true);

                View videoParent = (View) commentVideo.getParent();
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
                            commentImage.setVisibility(View.GONE);
                            commentVideo.setVisibility(View.VISIBLE);
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
            }

            // This is used to make the comment work for post top comments
            if(mMessageEditText == null && mParentFragment == null && mTopCommentRecycler == null){
                mVoteLayout.setVisibility(View.GONE);
                mActionItemsLayout.setVisibility(View.GONE);

                usernameText.setTextSize(11);
                contentText.setTextSize(12);
                mReadMoreButton.setVisibility(View.GONE);

                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) contentText.getLayoutParams();
                params.bottomMargin = 0;
                contentText.setLayoutParams(params);

                mCommentLayout.setPadding(10, 10, 10, 0);

                usernameText.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.showCommentsBottomSheetFromPost(comment.getPostParent(), false);
                    }
                });

                mCommentLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.showCommentsBottomSheetFromPost(comment.getPostParent(), false);
                    }
                });

                contentText.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.showCommentsBottomSheetFromPost(comment.getPostParent(), false);
                    }
                });

                if(comment.getContent().toString().length() > 75){
                    String contentString = comment.getContent().toString().subSequence(0, 75) + "...";
                    contentText.setText(contentString);
                }

                // float density = activity.getResources().getDisplayMetrics().density;
                // LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Math.round(24 * density), Math.round(24 * density));

                // mAvatarImage.setLayoutParams(params);
            }
                
            if(comment.getUser().getAvatar() != null && !comment.getUser().getAvatar().isEmpty()){
                ImageLoader.downloadImage(comment.getUser().getAvatar(), Glide.with(activity), true, 5, mAvatarImage, null);
            }else{
                ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + comment.getUser().getId().toString() + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), true, 5, mAvatarImage, null);
            }

            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            SharedPreferences sharedPref = activity.getSharedPreferencesForReplyComment();
            SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if(key.equals(BraveActivity.BROWSER_EXPRESS_REPLY_COMMENT)){
                        if(activity.getReplyComment() != null && !activity.getReplyComment().equals("")){
                            try{
                                JSONObject commentObject = new JSONObject(activity.getReplyComment().toString());
                                
                                JSONObject user = commentObject.getJSONObject("user");
                                User u = new User(user.getString("_id"), user.getString("username"), user.optString("avatar", null));
                                Vote v = null;
                                String pageParent = null;
                                String postParent = null;
                                String commentParent = null;
                                if(commentObject.has("pageParent")){
                                    pageParent = commentObject.getString("pageParent");
                                }

                                if(commentObject.has("postParent")){
                                    postParent = commentObject.getString("postParent");
                                }

                                if(commentObject.has("commentParent")){
                                    commentParent = commentObject.getString("commentParent");
                                }

                                if(comment.getId().equals(commentParent)){
                                    Comment c = new Comment(
                                        commentObject.getString("_id"), 
                                        commentObject.getString("content"),
                                        commentObject.getInt("upvoteCount"),
                                        commentObject.getInt("downvoteCount"),
                                        commentObject.getInt("commentCount"),
                                        pageParent, 
                                        postParent,
                                        commentParent,
                                        u,
                                        v,
                                        null,
                                        null
                                    );
                                    mComments.add(0, c);
                                    mCommentAdapter.notifyItemInserted(0);
                                }
                            } catch (JSONException e) {
                                Log.e("BROWSER_EXPRESS_REPLY_COMMENT_EXTRACT", e.getMessage());
                            }
                        }
                    }
                }
            };

            sharedPref.registerOnSharedPreferenceChangeListener(listener);
            
            Vote didVote = comment.getDidVote();
            if(didVote != null){
                String type = didVote.getType();
                didVoteType = type;
                if(type.equals("up")){
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                }else if(type.equals("down")){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                }
            }

            if(comment.getCommentCount() > 0){
                String mReplyButtonText = comment.getCommentCount() + " replies";
                mReplyButton.setText(mReplyButtonText);
                mReplyButton.setTextColor(ContextCompat.getColor(activity, R.color.browser_express_blue_color));
            }

            if(mReplyButton != null){
                mReplyButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    try{
                                        try {
                                            activity = BraveActivity.getBraveActivity();
                                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                                        }

                                        LinearLayoutManager layoutManager = (LinearLayoutManager) mTopCommentRecycler.getLayoutManager();
                                        layoutManager.scrollToPositionWithOffset(myPosition, 0);

                                        String accessToken = activity.getAccessToken();
                                        // if (accessToken == null) {
                                        //     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                        //     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                                        //     activity.showGenerateUsernameBottomSheet();
                                        //     return;
                                        // }

                                        Log.e("REPLY_TO_REPLY", "1");

                                        if(mIsReplyAdapter){
                                            Log.e("REPLY_TO_REPLY", "2");
                                            mParentFragment.openRepliesToReply(comment.getId());
                                            return;
                                        }else if (!mIsReplyAdapter && !mIsReplyToReplyAdapter){
                                            Log.e("REPLY_TO_REPLY", "3");
                                            mParentFragment.openReplies(comment.getId());
                                            return;
                                        }
                                        Log.e("REPLY_TO_REPLY", "4");

                                        JSONObject json = new JSONObject();
                                        json.put("name", comment.getUser().getUsername());
                                        json.put("commentId", comment.getId());
                                        activity.setReplyTo(json.toString());
                                        mMessageEditText =  activity.getContentEditText();
                                        if(mMessageEditText != null){
                                            Log.e("REPLY TO", "INSIDE REPLY TO TEXT");
                                            String replyToString = "replying to " + comment.getUser().getUsername();
                                            mMessageEditText.requestFocus();
                                            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,InputMethodManager.HIDE_IMPLICIT_ONLY);
                                        }
                                        Log.e("REPLY TO", "OUTSIDE REPLY TO TEXT");
                                    } catch (JSONException e) {
                                        Log.e("BROWSER_EXPRESS_REPLY_TO_CLICK", e.getMessage());
                                    }
                                }
                            });
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "11");

            if(mShareButton != null){
                mShareButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String link = "https://browser.express/view?id=" + comment.getId();
                        String message = "People say the craziest stuff! 👀 Check this out 👇\n\n" + link + "\n\n" + "Dive in—it's where everyone’s talking about everything, nonstop.";
                        Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                        sharingIntent.setType("text/plain");
                        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, message);
                        activity.startActivity(Intent.createChooser(sharingIntent, null));
                    }
                });
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "12");

            if (mUpvoteButton != null) {
                mUpvoteButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String accessToken = activity.getAccessToken();
                        // if (accessToken == null) {
                        //     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        //     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                        //     activity.showGenerateUsernameBottomSheet();
                        //     return;
                        // }

                        mUpvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                        mUpvoteButton.startAnimation(bounceUp);

                        if(didVoteType != null){
                            if(didVoteType.equals("down")){
                                finalVote = finalVote + 2;
                                didVoteType = "up";
                                mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                            }else if(didVoteType.equals("up")){
                                finalVote = finalVote - 1;
                                mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                                didVoteType = null;
                            }
                        }else{
                            finalVote = finalVote + 1;
                            didVoteType = "up";
                            mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                        }
                        voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "up", "comment", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "13");

            if(mDownvoteButton != null) {
                mDownvoteButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String accessToken = activity.getAccessToken();
                        // if (accessToken == null) {
                        //     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        //     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                        //     activity.showGenerateUsernameBottomSheet();
                        //     return;
                        // }

                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                        mDownvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        mDownvoteButton.startAnimation(bounceDown);

                        if(didVoteType != null){
                            if(didVoteType.equals("up")){
                                finalVote = finalVote - 2;
                                didVoteType = "down";
                                mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                            }else if(didVoteType.equals("down")){
                                finalVote = finalVote + 1;
                                mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                                didVoteType = null;
                            }
                        }else{
                            finalVote = finalVote - 1;
                            didVoteType = "down";
                            mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                        }
                        voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "down", "comment", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });
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

            if (commentImage != null) {
                Glide.with(context).clear(commentImage);
                commentImage.setImageDrawable(null);
            }
            if (mAvatarImage != null) {
                Glide.with(context).clear(mAvatarImage);
                mAvatarImage.setImageDrawable(null);
            }
        }

        // Make sure to release the player when the view is detached
        public void onViewDetachedFromWindow() {
            releasePlayer();
        }

        private BrowserExpressAddVoteUtil.AddVoteCallback addVoteCallback=
            new BrowserExpressAddVoteUtil.AddVoteCallback() {
                @Override
                public void addVoteSuccessful(String newAccessToken, String newRefreshToken) {
                    Log.e("BROWSER_EXPRESS_ADD_VOTE", newRefreshToken);
                    if(newRefreshToken != null && !newRefreshToken.isEmpty()){
                        try {
                            Log.e("BROWSER_EXPRESS_ADD_VOTE", "setting token");
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            activity.setAccessToken(newAccessToken);
                            JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                            Intent intent = new Intent(activity, ChromeTabbedActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            intent.setAction(Intent.ACTION_VIEW);
                            Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " created. You can edit this in Profile.", Toast.LENGTH_SHORT).show();
                            activity.startActivity(intent);
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                        } catch (JSONException e) {
                        }
                    }
                }

                @Override
                public void addVoteFailed(String error) {
                    mDownvoteButton.setClickable(true);
                    mUpvoteButton.setClickable(true);
                }
            };

        public String formatNumberCompact(int number) {
            if (number >= 1_000_000) {
                return String.format(Locale.getDefault(), "%dM", number / 1_000_000);
            } else if (number >= 1_000) {
                return String.format(Locale.getDefault(), "%dK", number / 1_000);
            } else {
                return String.format(Locale.getDefault(), "%d", number);
            }
        }

        private JSONObject getDecodedToken(String accessToken){
            try{
                String[] split_string = accessToken.split("\\.");
                String base64EncodedHeader = split_string[0];
                String base64EncodedBody = split_string[1];
                String base64EncodedSignature = split_string[2];

                byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
                String decodedString = new String(data, "UTF-8");
                JSONObject jsonObj = new JSONObject(decodedString.toString());
                return jsonObj;
            }catch(JSONException e){
                Log.e("Express Browser Access Token", e.getMessage());
                return null;
            }catch(UnsupportedEncodingException e){
                Log.e("Express Browser Access Token", e.getMessage());
                return null;
            }
            
        }
    }
}