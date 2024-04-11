package org.chromium.chrome.browser.ntp;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import android.widget.TextView;
import android.view.View;
import org.chromium.base.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
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

public class PostListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Post> mPostList;
    private String INSHORTS_TYPE = "Inshorts";

    public PostListAdapter(Context context, List<Post> postList) {
        mContext = context;
        mPostList = postList;
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
        return new PostHolder(view);
    }

    // Passes the post object to a ViewHolder so that the contents can be bound to UI.
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Post post = (Post) mPostList.get(position);

        ((PostHolder) holder).bind(post);
    }

    private class PostHolder extends RecyclerView.ViewHolder {
        ImageView postImage;
        LinearLayout postLayout;
        TextView publisherNameText;
        TextView publishedTimeText;
        TextView titleText;
        TextView contentText;
        TextView voteCountText;
        private ImageButton mUpvoteButton;
        private ImageButton mDownvoteButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity;

        private Context context;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition;

        PostHolder(View itemView) {
            super(itemView);
            postLayout = (LinearLayout) itemView.findViewById(R.id.post_layout);
            postImage = (ImageView) itemView.findViewById(R.id.post_image);
            publisherNameText = (TextView) itemView.findViewById(R.id.publisher_name);
            // publishedTimeText = (TextView) itemView.findViewById(R.id.published_time);
            titleText = (TextView) itemView.findViewById(R.id.title);
            contentText = (TextView) itemView.findViewById(R.id.post_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            context = itemView.getContext();
        }

        void bind(Post post) {
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            myPosition = getBindingAdapterPosition();

            titleText.setText(post.getTitle().toString());

            if(post.getShowFull()){
                contentText.setText(post.getContent().toString());
                contentText.setVisibility(View.VISIBLE);
            }
            
            finalVote = post.getUpvoteCount() - post.getDownvoteCount();
            voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

            publisherNameText.setText(post.getPublisherName().toString());
            publisherNameText.setTextSize(9);

            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            ImageLoader.downloadImage(post.getImageUrl().toString(), Glide.with(activity), false, 5, postImage, null);

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

            postLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(post.getRedirect()){
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(post.getUrl().toString()));
                        activity.startActivity(webIntent);
                    }else{

                    }
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