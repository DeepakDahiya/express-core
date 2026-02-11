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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.Util;
import android.view.MotionEvent;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import androidx.annotation.NonNull; 
import androidx.media3.common.MediaMetadata;
import com.bumptech.glide.request.target.Target;
import android.util.TypedValue;
import android.media.MediaMetadataRetriever;
import android.widget.Space;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import java.util.concurrent.Executor;
import androidx.annotation.Nullable;
import android.graphics.Rect;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;

public class CommentListAdapter extends RecyclerView.Adapter<CommentListAdapter.CommentHolder> {
    private static final int VIEW_TYPE_TOP_COMMENT = 1;
    private static final int VIEW_TYPE_REPLY_COMMENT = 2;

    private final List<Comment> mCommentList;
    private final EditText mMessageEditText;
    private final BrowserExpressCommentsBottomSheetFragment mParentFragment;
    private final boolean mIsReplyAdapter;
    private final boolean mIsReplyToReplyAdapter;

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyToReplyAdapter) {
        mCommentList = commentList;
        mMessageEditText = messageEditText;
        mParentFragment = parentFragment;
        mIsReplyAdapter = isReplyAdapter;
        mIsReplyToReplyAdapter = isReplyToReplyAdapter;
    }

    /**
     * Updates a comment after a successful upload.
     * Finds the temporary item, replaces it with the real item, and notifies the specific row.
     */
    public void updateCommentForSuccess(String tempId, Comment realComment) {
        if (mCommentList == null || tempId == null) return;
        
        for (int i = 0; i < mCommentList.size(); i++) {
            Comment c = mCommentList.get(i);
            // Check for ID match. Safe check for null IDs.
            if (c.getId() != null && c.getId().equals(tempId)) {
                // Replace the temporary comment with the real one from server
                mCommentList.set(i, realComment);
                // Trigger the bind method again for this position to remove progress bar
                notifyItemChanged(i);
                return;
            }
        }
    }

    /**
     * Updates a comment after a failed upload.
     * Finds the temporary item, marks it as failed, and notifies the specific row.
     */
    public void updateCommentForFailure(String tempId) {
        if (mCommentList == null || tempId == null) return;

        for (int i = 0; i < mCommentList.size(); i++) {
            Comment c = mCommentList.get(i);
            if (c.getId() != null && c.getId().equals(tempId)) {
                c.setUploadStatus(Comment.UploadStatus.FAILED);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0 && mIsReplyAdapter) {
            return VIEW_TYPE_TOP_COMMENT;
        }
        return VIEW_TYPE_REPLY_COMMENT;
    }

    @Override
    public int getItemCount() {
        return mCommentList.size();
    }

    @NonNull
    @Override
    public CommentListAdapter.CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        if (viewType == VIEW_TYPE_TOP_COMMENT) {
            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_comment, parent, false);
            return new CommentHolder(view, mMessageEditText, mParentFragment, mIsReplyAdapter, true, mIsReplyToReplyAdapter, viewType);
        } else {
            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_comment, parent, false);
            return new CommentHolder(view, mMessageEditText, mParentFragment, mIsReplyAdapter, false, mIsReplyToReplyAdapter, viewType);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull CommentListAdapter.CommentHolder holder, int position) {
        Comment comment = mCommentList.get(position);
        holder.bind(comment, position);
    }

    public class CommentHolder extends RecyclerView.ViewHolder {
        TextView usernameText;
        TextView contentText;
        TextView voteCountText;
        private final ImageView mAvatarImage;
        private final ImageButton mUpvoteButton;
        private final ImageButton mDownvoteButton;
        private final Button mReplyButton;
        private final Button mShareButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity;
        private final Button mReadMoreButton;

        private final ProgressBar mPostingProgressBar;
        private final TextView mFailedTextView;

        private final LinearLayout mActionItemsLayout;
        private final LinearLayout mVoteLayout;

        private final EditText mMessageEditText;
        private final LinearLayout mCommentLayout;

        private Animation bounceUp;
        private Animation bounceDown;

        private final BrowserExpressCommentsBottomSheetFragment mParentFragment;

        private final boolean mIsReplyAdapter;
        private final boolean mIsReplyTopComment;
        private final boolean mIsReplyToReplyAdapter;

        ImageView commentImage;
        CardView commentMediaCard;
        PlayerView commentVideo;
        ExoPlayer player;
        ImageView playPauseIcon;
        ProgressBar videoProgressBar;
        private Context context;

        ImageButton muteButton;
        Space mediaAspectRatioSpacer;
        ConstraintLayout mediaContainer;

        private boolean mHasVideo;
        private boolean mIsVideoInitialized;

        private boolean shouldCloseKeyboardOnReply;

        CommentHolder(@NonNull View itemView, EditText messageEditText, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment, boolean isReplyToReplyAdapter, int viewType) {
            super(itemView);
            this.context = itemView.getContext();

            mMessageEditText = messageEditText;
            mParentFragment = parentFragment;
            mIsReplyAdapter = isReplyAdapter;
            mIsReplyTopComment = isReplyTopComment;
            mIsReplyToReplyAdapter = isReplyToReplyAdapter;

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

            mPostingProgressBar = itemView.findViewById(R.id.posting_progress_bar);
            mFailedTextView = itemView.findViewById(R.id.failed_text_view);

            mVoteLayout = itemView.findViewById(R.id.vote_layout);

            muteButton = itemView.findViewById(R.id.video_mute_button);
            mediaAspectRatioSpacer = itemView.findViewById(R.id.media_aspect_ratio_spacer);
            mediaContainer = (ConstraintLayout) mediaAspectRatioSpacer.getParent();

            if (this.context instanceof BraveActivity) {
                this.activity = (BraveActivity) this.context;
            } else {
                try {
                    this.activity = BraveActivity.getBraveActivity();
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e("CommentHolder", "BraveActivity not found for holder", e);
                }
            }
        }

        void bind(Comment comment, int position) {
            if (activity == null) {
                Log.e("CommentHolder.bind", "Activity is null, some UI updates might fail.");
            }

            Comment.UploadStatus status = comment.getUploadStatus();
            
            // 1. RESET STATE: Always assume success/normal state first
            mCommentLayout.setAlpha(1.0f);
            mPostingProgressBar.setVisibility(View.GONE);
            mFailedTextView.setVisibility(View.GONE);
            mUpvoteButton.setEnabled(true);
            mDownvoteButton.setEnabled(true);
            mReplyButton.setEnabled(true);

            // 2. APPLY STATUS: Override defaults if status is special
            if (status == Comment.UploadStatus.POSTING) {
                mCommentLayout.setAlpha(0.6f);
                mPostingProgressBar.setVisibility(View.VISIBLE);
                mUpvoteButton.setEnabled(false);
                mDownvoteButton.setEnabled(false);
                mReplyButton.setEnabled(false);
            } else if (status == Comment.UploadStatus.FAILED) {
                mFailedTextView.setVisibility(View.VISIBLE);
            }

            if (mIsReplyTopComment && activity != null) {
                mCommentLayout.setBackground(ResourcesCompat.getDrawable(activity.getResources(), R.drawable.rounded_corner_background, null));
                mActionItemsLayout.setVisibility(View.VISIBLE);
                mReplyButton.setVisibility(View.INVISIBLE);
            } else if (mIsReplyAdapter || mIsReplyToReplyAdapter){
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mCommentLayout.getLayoutParams();
                int margin20dp = (int) (20 * context.getResources().getDisplayMetrics().density);
                params.setMarginStart(margin20dp);
                mCommentLayout.setLayoutParams(params);
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
                mReadMoreButton.setVisibility(View.GONE);
            }

            finalVote = comment.getTrendingScore();
            voteCountText.setText(formatNumberCompact(finalVote));

            if(!mIsReplyToReplyAdapter){
                mActionItemsLayout.setVisibility(View.VISIBLE);
                if(mIsReplyTopComment){
                    mActionItemsLayout.setVisibility(View.GONE);
                }
            }else{
                mActionItemsLayout.setVisibility(View.GONE);
            }

            mHasVideo = false;
            mIsVideoInitialized = false;
            commentMediaCard.setVisibility(View.GONE);
            commentImage.setVisibility(View.GONE);
            commentVideo.setVisibility(View.GONE);
            muteButton.setVisibility(View.GONE);

            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }

            String imageUrl = comment.getMediaImageUrl();
            String videoUrl = comment.getMediaVideoUrl();

            boolean hasImage = imageUrl != null && !imageUrl.isEmpty() && !imageUrl.equals("null");
            boolean hasVideo = videoUrl != null && !videoUrl.isEmpty() && !videoUrl.equals("null");
            boolean hasMedia = hasImage || hasVideo;

            if (hasMedia) {
                commentMediaCard.setVisibility(View.VISIBLE);

                String urlString;
                String mediaType;

                if (hasVideo) {
                    urlString = videoUrl;
                    mediaType = "video";
                } else {
                    urlString = imageUrl;
                    mediaType = "image";
                }
                
                Uri mediaUri = Uri.parse(urlString);
                commentMediaCard.setOnClickListener(v -> openFullScreenViewer(mediaUri, mediaType));

                setAspectRatio(16, 9);
                bindMediaContent(mediaUri, mediaType);
            }

            if(mMessageEditText == null && mParentFragment == null && activity != null){
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
                    if (activity != null) {
                        JSONObject payload = new JSONObject();
                        try {
                            payload.put("comment_id", comment.getId());
                            payload.put("post_id", comment.getPostParent());
                        } catch (JSONException e) {
                        }
                        String accessToken = activity.getAccessToken();
                        sendEventToPostHog(PostHogEventKeys.FEED_CLICKED_ON, accessToken, activity.getCurrentAppVersion(), payload);

                        activity.showCommentsBottomSheetFromPost(comment.getPostParent(), comment.getPostUsername(), comment.getPostContent(), comment.getPostAvatarUrl(), false);
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
            } else if (activity != null) {
                ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + comment.getUser().getId() + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), true, 5, mAvatarImage, null);
            }

            if (activity != null) {
                bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
                bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);
            }
            
            Vote didVote = comment.getDidVote();
            if (mUpvoteButton != null) mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
            if (mDownvoteButton != null) mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);

            if(didVote != null){
                String type = didVote.getType();
                didVoteType = type; 
                if(type.equals("up") && mUpvoteButton != null){
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                }else if(type.equals("down") && mDownvoteButton != null){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                }
            } else {
                didVoteType = null;
            }

            if(mReplyButton != null && activity != null) {
                if(comment.getCommentCount() > 0){
                    String mReplyButtonText = comment.getCommentCount() == 1
                            ? "1 reply"
                            : comment.getCommentCount() + " replies";
                    mReplyButton.setText(mReplyButtonText);
                    mReplyButton.setTextColor(ContextCompat.getColor(activity, R.color.browser_express_blue_color));
                    shouldCloseKeyboardOnReply = true;
                } else {
                    String t = "Reply";
                    mReplyButton.setText(t); 
                    shouldCloseKeyboardOnReply = false;
                }
            }

            if(mReplyButton != null){
                mReplyButton.setOnClickListener(v -> {
                    if (activity == null || mParentFragment == null) return; 

                    if(shouldCloseKeyboardOnReply){
                        mParentFragment.hideKeyboard();
                    }else{
                        mParentFragment.showKeyboardWithFocus();
                    }

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    String accessToken = activity.getAccessToken();

                    if(mIsReplyAdapter){
                        sendEventToPostHog(PostHogEventKeys.CLICKED_TO_VIEW_REPLY2REPLY, accessToken, activity.getCurrentAppVersion(), payload);
                        mParentFragment.openRepliesToReply(comment.getId());
                    } else if (!mIsReplyToReplyAdapter){
                        sendEventToPostHog(PostHogEventKeys.CLICKED_TO_VIEW_REPLIES, accessToken, activity.getCurrentAppVersion(), payload);
                        mParentFragment.openReplies(comment.getId());
                    }
                });
            }

            if(mShareButton != null){
                mShareButton.setOnClickListener(v -> {
                    if (activity == null) return;

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    String accessToken = activity.getAccessToken();
                    sendEventToPostHog(PostHogEventKeys.COMMENT_REPLY_SHARE_CLICKED, accessToken, activity.getCurrentAppVersion(), payload);

                    String link = "https://browser.express/view?id=" + comment.getId();
                    String message = "People say the craziest stuff! 👀 Check this out 👇\n\n" + link + "\n\n" + "Dive in—it's where everyone’s talking about everything, nonstop.";
                    Intent sharingIntent = new Intent(Intent.ACTION_SEND);
                    sharingIntent.setType("text/plain");
                    sharingIntent.putExtra(Intent.EXTRA_TEXT, message);
                    activity.startActivity(Intent.createChooser(sharingIntent, "Share via")); 
                });
            }

            if (mUpvoteButton != null) {
                mUpvoteButton.setOnClickListener(v -> {
                    if (activity == null) return;
                    String accessToken = activity.getAccessToken();

                    mUpvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (bounceUp != null) mUpvoteButton.startAnimation(bounceUp);
                    
                    int oldFinalVote = finalVote;

                    if(didVoteType != null && didVoteType.equals("down")){ 
                        finalVote += 2;
                        didVoteType = "up";
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    } else if (didVoteType != null && didVoteType.equals("up")){ 
                        finalVote -= 1;
                        didVoteType = null;
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                    } else { 
                        finalVote += 1;
                        didVoteType = "up";
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    }
                    voteCountText.setText(formatNumberCompact(finalVote));
                    mUpvoteButton.setClickable(false); 
                    mDownvoteButton.setClickable(false);

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    sendEventToPostHog(PostHogEventKeys.UPVOTE_GIVEN, accessToken, activity.getCurrentAppVersion(),payload);

                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                comment.getId(), "up", "comment", accessToken, new BrowserExpressAddVoteUtil.AddVoteCallback() {
                                    @Override
                                    public void addVoteSuccessful(String newAccessToken, String newRefreshToken) {
                                        mUpvoteButton.setClickable(true);
                                        mDownvoteButton.setClickable(true);
                                        if (newRefreshToken != null && !newRefreshToken.isEmpty() && activity != null) {
                                            handleNewToken(newAccessToken);
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

            if(mDownvoteButton != null) {
                mDownvoteButton.setOnClickListener(v -> {
                    if (activity == null) return;
                    String accessToken = activity.getAccessToken();

                    mDownvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (bounceDown != null) mDownvoteButton.startAnimation(bounceDown);

                    int oldFinalVote = finalVote;

                    if(didVoteType != null && didVoteType.equals("up")){ 
                        finalVote -= 2;
                        didVoteType = "down";
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                    } else if (didVoteType != null && didVoteType.equals("down")){ 
                        finalVote += 1;
                        didVoteType = null;
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    } else { 
                        finalVote -= 1;
                        didVoteType = "down";
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote); 
                    }
                    voteCountText.setText(formatNumberCompact(finalVote));
                    mUpvoteButton.setClickable(false);
                    mDownvoteButton.setClickable(false);

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    sendEventToPostHog(PostHogEventKeys.DOWNVOTE_GIVEN, accessToken, activity.getCurrentAppVersion(),payload);

                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                comment.getId(), "down", "comment", accessToken, new BrowserExpressAddVoteUtil.AddVoteCallback() {
                            @Override
                            public void addVoteSuccessful(String newAccessToken, String newRefreshToken) {
                                mUpvoteButton.setClickable(true);
                                mDownvoteButton.setClickable(true);
                                if (newAccessToken != null && !newAccessToken.isEmpty() && activity != null) {
                                   handleNewToken(newAccessToken);
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

        public boolean hasVideo() {
            return mHasVideo && mIsVideoInitialized && player != null;
        }

        public void startPlayback() { if (player != null) player.setPlayWhenReady(true); }
        public void stopPlayback() { if (player != null) player.setPlayWhenReady(false); }

        private void bindMediaContent(Uri mediaUri, String mediaType) {
            if ("video".equals(mediaType)) {
                mHasVideo = true; 
                commentImage.setVisibility(View.GONE);
                commentVideo.setVisibility(View.VISIBLE);
                muteButton.setVisibility(View.VISIBLE);
                
                initializePlayer(mediaUri);
            } else {
                mHasVideo = false;
                GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();

                commentVideo.setVisibility(View.GONE);
                muteButton.setVisibility(View.GONE);
                commentImage.setVisibility(View.VISIBLE);

                ImageLoader.downloadImage(mediaUri.toString(), Glide.with(activity), false, 5, commentImage, null);
            }
        }

        private void setAspectRatio(int width, int height) {
            if (width > 0 && height > 0) {
                ConstraintSet constraintSet = new ConstraintSet();
                constraintSet.clone(mediaContainer);
                constraintSet.setDimensionRatio(mediaAspectRatioSpacer.getId(), String.format(Locale.US, "H,%d:%d", width, height));
                constraintSet.applyTo(mediaContainer);
            }
        }

        private void initializePlayer(Uri videoUri) {
            if (player != null) {
                player.stop();
                player.release();
            }
            
            player = new ExoPlayer.Builder(context).build();
            commentVideo.setPlayer(player);
            commentVideo.setUseController(false);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);

            muteButton.setOnClickListener(v -> {
                if (player != null) {
                    if (player.getVolume() > 0) {
                        player.setVolume(0f);
                        muteButton.setImageResource(R.drawable.volume_off);
                    } else {
                        player.setVolume(1f);
                        muteButton.setImageResource(R.drawable.volume_on);
                    }
                }
            });
            
            player.setVolume(0f);
            muteButton.setImageResource(R.drawable.volume_off);
            
            MediaItem mediaItem;
            String urlString = videoUri.toString();
            if (urlString.startsWith("http")) {
                mediaItem = MediaItem.fromUri(urlString);
            } else {
                mediaItem = MediaItem.fromUri(videoUri);
            }
            
            player.setMediaItem(mediaItem);
            player.prepare();

            mIsVideoInitialized = true;
        }

        public void onViewRecycled() { 
            if (GlobalVideoPlaybackManager.getInstance().getCurrentlyPlayingHolder() == this) {
                GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
            }

            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
            if (commentImage != null && context != null) {
                Glide.with(context).clear(commentImage);
            }

            mIsVideoInitialized = false;
        }

        private void handleNewToken(String newAccessToken) {
            if (activity == null) return;
            try {
                activity.setAccessToken(newAccessToken);
                JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                if (decodedAccessTokenObj != null && decodedAccessTokenObj.has("username")) {
                    Log.i("TokenHandler", "Token refreshed. New username (if changed): " + decodedAccessTokenObj.getString("username"));
                }
            } catch (JSONException e) {
                Log.e("TokenHandler", "Error decoding new access token", e);
            }
        }


        public String formatNumberCompact(long number) { 
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

        private void sendEventToPostHog(String event, String accessToken, String pInfo, JSONObject payload) {
            try {
                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);    
                if (decodedAccessTokenObj != null) {
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
                if (split_string.length < 2) {
                    Log.e("TokenDecoder", "Invalid JWT format");
                    return null;
                }
                String base64EncodedBody = split_string[1];
                byte[] data = Base64.decode(base64EncodedBody, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); 
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

    private void openFullScreenViewer(Uri mediaUri, String mediaType) {
        if (mParentFragment != null && mParentFragment.isAdded()) {
            CommentHolder currentHolder = GlobalVideoPlaybackManager.getInstance().getCurrentlyPlayingHolder();
        
            if (currentHolder != null) {
                GlobalVideoPlaybackManager.getInstance().setHolderToResume(currentHolder);
            }

            GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();

            MediaViewerFragment viewer = MediaViewerFragment.newInstance(mediaUri, mediaType, false);
            viewer.show(mParentFragment.getChildFragmentManager(), MediaViewerFragment.class.getSimpleName());
        }
    }

    @Override
    public void onViewRecycled(@NonNull CommentHolder holder) {
        super.onViewRecycled(holder); 
        holder.onViewRecycled();      
    }
}