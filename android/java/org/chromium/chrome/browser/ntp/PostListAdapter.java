package org.chromium.chrome.browser.ntp;

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

public class PostListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Post> mPostList;
    private String INSHORTS_TYPE = "Inshorts";
    private String TWITTER_TYPE = "Twitter";
    private String INSTAGRAM_TYPE = "Instagram";
    private RecyclerView mTopPostRecycler;

    public PostListAdapter(Context context, List<Post> postList, RecyclerView topPostRecycler) {
        mContext = context;
        mPostList = postList;
        mTopPostRecycler = topPostRecycler;
    }

    @Override
    public int getItemCount() {
        return mPostList.size();
    }

    // Inflates the appropriate layout according to the ViewType.
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_post, parent, false);
        return new PostHolder(view, mTopPostRecycler);
    }

    // Passes the post object to a ViewHolder so that the contents can be bound to UI.
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Post post = (Post) mPostList.get(position);

        ((PostHolder) holder).bind(post);
    }

    private class PostHolder extends RecyclerView.ViewHolder {
        LinearLayout twitterPostLayout;
        ImageView twitterProfilePicture;
        TextView twitterName;
        TextView twitterUsername;
        TextView twitterContent;
        ImageView twitterImage;
        VideoView twitterVideo;
        ImageButton twitterPlayButton;
        ImageView twitterVerifiedImage;
        CardView twitterMediaCard;

        ImageView postImage;
        CardView cardView;
        TextView publisherNameText;
        TextView publishedTimeText;
        TextView titleText;
        TextView contentText;
        TextView voteCountText;
        TextView commentCountText;
        private LinearLayout mCommentLayout;
        private ImageButton mCommentButton;
        private ImageButton mUpvoteButton;
        private ImageButton mDownvoteButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity;

        private Context context;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition;

        PostHolder(View itemView, RecyclerView topPostRecycler) {
            super(itemView);
            twitterPostLayout = (LinearLayout) itemView.findViewById(R.id.twitter_post_layout);
            twitterProfilePicture = (ImageView) itemView.findViewById(R.id.twitter_profile_picture);
            twitterName = (TextView) itemView.findViewById(R.id.twitter_name);
            twitterUsername = (TextView) itemView.findViewById(R.id.twitter_username);
            twitterContent = (TextView) itemView.findViewById(R.id.twitter_content);
            twitterImage = (ImageView) itemView.findViewById(R.id.twitter_image);
            twitterVideo = (VideoView) itemView.findViewById(R.id.twitter_video);
            twitterPlayButton = (ImageButton) itemView.findViewById(R.id.twitter_play_button);
            twitterVerifiedImage = (ImageView) itemView.findViewById(R.id.twitter_verified);
            twitterMediaCard = (CardView) itemView.findViewById(R.id.twitter_media_card);
        
            cardView = (CardView) itemView.findViewById(R.id.card_view);
            postImage = (ImageView) itemView.findViewById(R.id.post_image);
            publisherNameText = (TextView) itemView.findViewById(R.id.publisher_name);
            // publishedTimeText = (TextView) itemView.findViewById(R.id.published_time);
            titleText = (TextView) itemView.findViewById(R.id.title);
            contentText = (TextView) itemView.findViewById(R.id.post_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mCommentLayout = (LinearLayout) itemView.findViewById(R.id.comment_layout);
            commentCountText = (TextView) itemView.findViewById(R.id.comment_count);
            mCommentButton = (ImageButton) itemView.findViewById(R.id.btn_comment);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            context = itemView.getContext();
            mTopPostRecycler = topPostRecycler;
        }

        void bind(Post post) {
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            myPosition = getBindingAdapterPosition();

            String postType = post.getType().toString();

            Boolean t = true;

            if (postType.equals(TWITTER_TYPE)) {
                twitterPostLayout.setVisibility(View.VISIBLE);
                TweetSubPost tweetSubPost = post.getTweetSubPost();
                String name = tweetSubPost.getAuthorName();
                String username = "@" + tweetSubPost.getAuthorUsername();
                String content = tweetSubPost.getContent();
                String profilePicUrl = tweetSubPost.getAuthorProfilePicture();
                Boolean verified = tweetSubPost.getAuthorVerified();

                if(verified){
                    twitterVerifiedImage.setVisibility(View.VISIBLE);
                }

                String twitterImageUrl = tweetSubPost.getMediaImageUrl();
                String videoUrl = tweetSubPost.getMediaVideoUrl();

                twitterName.setText(name);
                twitterUsername.setText(username);
                twitterContent.setText(content);

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

                Log.e("VIDEO_URL", videoUrl);

                if(videoUrl != null && videoUrl.length() > 0){
                    Uri uri = Uri.parse(videoUrl);
                    twitterVideo.setVideoURI(uri);

                    MediaController mediaController = new MediaController(context);
                    twitterVideo.setMediaController(mediaController);
                    mediaController.setAnchorView(twitterVideo);

                    twitterPlayButton.setVisibility(View.VISIBLE);

                    twitterImage.post(new Runnable() {
                        @Override
                        public void run() {
                            int h = twitterImage.getHeight();
                            twitterVideo.getLayoutParams().height = h;
                            twitterVideo.requestLayout();
                        }
                    });

                    twitterVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            twitterImage.setVisibility(View.GONE);
                            twitterVideo.setVisibility(View.VISIBLE);
                            twitterVideo.start();
                        }
                    });

                    twitterPlayButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            twitterPlayButton.setVisibility(View.GONE);
                            twitterImage.post(new Runnable() {
                                @Override
                                public void run() {
                                    int h = twitterImage.getHeight();
                                    twitterVideo.getLayoutParams().height = h;
                                    twitterVideo.requestLayout();
                                }
                            });
                            twitterVideo.setVisibility(View.VISIBLE);
                            twitterVideo.start();
                        }
                    });
                }
            } else {
                twitterPostLayout.setVisibility(View.GONE);
                titleText.setText(post.getTitle().toString());

                if(post.getShowFull()){
                    contentText.setText(post.getContent().toString());
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
                            activity.showCommentsBottomSheetFromPost(post.getId());
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
                            activity.showCommentsBottomSheetFromPost(post.getId());
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
                            activity.showCommentsBottomSheetFromPost(post.getId());
                        }
                    }
                });
            }

            finalVote = post.getUpvoteCount() - post.getDownvoteCount();
            voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));
            commentCountText.setText(String.format(Locale.getDefault(), "%d", post.getCommentCount()));
                
            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            // if(post.getType().toString().equals(INSHORTS_TYPE)){
            //     ViewGroup.LayoutParams params = postLayout.getLayoutParams();
            //     ViewGroup.LayoutParams paramsForImage = postImage.getLayoutParams();
            //     paramsForImage.height = (int)(params.width * 0.57);
            //     paramsForImage.width = params.width;
            //     postImage.setLayoutParams(paramsForImage);
            // }

            Vote didVote = post.getDidVote();
            if(didVote != null){
                String type = didVote.getType();
                didVoteType = type;
                if(type.equals("up")){
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote_orange);
                }else if(type.equals("down")){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote_orange);
                }
            }

            mCommentButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCommentButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(myPosition, 0);
                    activity.showCommentsBottomSheetFromPost(post.getId());
                }
            });

            mCommentLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCommentLayout.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(myPosition, 0);
                    activity.showCommentsBottomSheetFromPost(post.getId());
                }
            });

            mUpvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String accessToken = activity.getAccessToken();
                    if (accessToken == null) {
                        InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                        activity.showGenerateUsernameBottomSheet();
                        activity.dismissCommentsBottomSheet();
                        return;
                    }

                    mUpvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                    mUpvoteButton.startAnimation(bounceUp);

                    if(didVoteType != null){
                        if(didVoteType.equals("down")){
                            finalVote = finalVote + 2;
                            didVoteType = "up";
                            mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote_orange);
                        }else if(didVoteType.equals("up")){
                            finalVote = finalVote - 1;
                            mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                            didVoteType = null;
                        }
                    }else{
                        finalVote = finalVote + 1;
                        didVoteType = "up";
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote_orange);
                    }
                    voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                post.getId(), "up", "post", accessToken, addVoteCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });

            mDownvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String accessToken = activity.getAccessToken();
                    if (accessToken == null) {
                        InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                        activity.showGenerateUsernameBottomSheet();
                        activity.dismissCommentsBottomSheet();
                        return;
                    }

                    mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                    mDownvoteButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    mDownvoteButton.startAnimation(bounceDown);

                    if(didVoteType != null){
                        if(didVoteType.equals("up")){
                            finalVote = finalVote - 2;
                            didVoteType = "down";
                            mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote_orange);
                        }else if(didVoteType.equals("down")){
                            finalVote = finalVote + 1;
                            mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);
                            didVoteType = null;
                        }
                    }else{
                        finalVote = finalVote - 1;
                        didVoteType = "down";
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote_orange);
                    }
                    voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                    BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                        new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                post.getId(), "down", "post", accessToken, addVoteCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });
        }

        private BrowserExpressAddVoteUtil.AddVoteCallback addVoteCallback=
            new BrowserExpressAddVoteUtil.AddVoteCallback() {
                @Override
                public void addVoteSuccessful() {
                    mDownvoteButton.setClickable(true);
                    mUpvoteButton.setClickable(true);
                }

                @Override
                public void addVoteFailed(String error) {
                    mDownvoteButton.setClickable(true);
                    mUpvoteButton.setClickable(true);
                }
            };
    }
}