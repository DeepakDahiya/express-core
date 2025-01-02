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
        TextView twitterUsername;
        TextView twitterContent;
        ImageView twitterImage;
        VideoView twitterVideo;
        ImageButton twitterPlayButton;
        CardView twitterMediaCard;
        ImageView xLogo;
        ImageView instagramLogo;

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

        PostHolder(View itemView, RecyclerView topPostRecycler) {
            super(itemView);
            twitterPostLayout = (LinearLayout) itemView.findViewById(R.id.twitter_post_layout);
            twitterProfilePicture = (ImageView) itemView.findViewById(R.id.twitter_profile_picture);
            twitterUsername = (TextView) itemView.findViewById(R.id.twitter_username);
            twitterContent = (TextView) itemView.findViewById(R.id.twitter_content);
            twitterImage = (ImageView) itemView.findViewById(R.id.twitter_image);
            twitterVideo = (VideoView) itemView.findViewById(R.id.twitter_video);
            twitterPlayButton = (ImageButton) itemView.findViewById(R.id.twitter_play_button);
            twitterMediaCard = (CardView) itemView.findViewById(R.id.twitter_media_card);
            xLogo = (ImageView) itemView.findViewById(R.id.x_logo);
            instagramLogo = (ImageView) itemView.findViewById(R.id.instagram_logo);
        
            cardView = (CardView) itemView.findViewById(R.id.card_view);
            postImage = (ImageView) itemView.findViewById(R.id.post_image);
            publisherNameText = (TextView) itemView.findViewById(R.id.publisher_name);
            // publishedTimeText = (TextView) itemView.findViewById(R.id.published_time);
            titleText = (TextView) itemView.findViewById(R.id.title);
            contentText = (TextView) itemView.findViewById(R.id.post_content);
            mCommentButton = (Button) itemView.findViewById(R.id.btn_comment);
            mReadMoreButton = (Button) itemView.findViewById(R.id.btn_read_more_post);
            mReadMoreButton2 = (Button) itemView.findViewById(R.id.btn_read_more_post2);
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

            if (postType.equals(TWITTER_TYPE) || postType.equals(INSTAGRAM_TYPE)) {
                twitterPostLayout.setVisibility(View.VISIBLE);
                SubPost subPost = post.getSubPost();
                String name = subPost.getAuthorName();
                String username = "@" + subPost.getAuthorUsername();
                String content = subPost.getContent();
                String profilePicUrl = subPost.getAuthorProfilePicture();
                Boolean verified = subPost.getAuthorVerified();

                if(postType.equals(INSTAGRAM_TYPE)){
                    instagramLogo.setVisibility(View.VISIBLE);
                    xLogo.setVisibility(View.GONE);
                }

                if(postType.equals(TWITTER_TYPE)){
                    instagramLogo.setVisibility(View.GONE);
                    xLogo.setVisibility(View.VISIBLE);
                }

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
                    mReadMoreButton.setVisibility(View.INVISIBLE);
                }
                

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

            if (post.getCommentCount() > 0) {
                String commentCountText = "View " + post.getCommentCount() + " comments";
                mCommentButton.setText(commentCountText);
            } else {
                String commentCountText = "View comments";
                mCommentButton.setText(commentCountText);
            }
            
                
            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            // if(post.getType().toString().equals(INSHORTS_TYPE)){
            //     ViewGroup.LayoutParams params = postLayout.getLayoutParams();
            //     ViewGroup.LayoutParams paramsForImage = postImage.getLayoutParams();
            //     paramsForImage.height = (int)(params.width * 0.57);
            //     paramsForImage.width = params.width;
            //     postImage.setLayoutParams(paramsForImage);
            // }

            mCommentButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCommentButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mTopPostRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(myPosition, 0);
                    activity.showCommentsBottomSheetFromPost(post.getId());
                }
            });
        }
    }
}