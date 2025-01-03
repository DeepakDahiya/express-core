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

public class CommentListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Comment> mCommentList;
    private EditText mMessageEditText;
    private RecyclerView mTopCommentRecycler;
    private BrowserExpressCommentsBottomSheetFragment mParentFragment;
    private boolean mIsReplyAdapter;
    private boolean mIsReplyTopComment;

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment) {
        mContext = context;
        mCommentList = commentList;
        mMessageEditText = messageEditText;
        mTopCommentRecycler = topCommentRecycler;
        mParentFragment = parentFragment;
        mIsReplyAdapter = isReplyAdapter;
        mIsReplyTopComment = isReplyTopComment;
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
        return new CommentHolder(view, mMessageEditText, mTopCommentRecycler, mParentFragment, mIsReplyAdapter, mIsReplyTopComment);
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
        private ImageButton mShareButton;
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

        CommentHolder(View itemView, EditText messageEditText, RecyclerView topCommentRecycler, BrowserExpressCommentsBottomSheetFragment parentFragment, boolean isReplyAdapter, boolean isReplyTopComment) {
            super(itemView);

            mMessageEditText = messageEditText;
            mParentFragment = parentFragment;
            mIsReplyAdapter = isReplyAdapter;
            mIsReplyTopComment = isReplyTopComment;

            mTopCommentRecycler = topCommentRecycler;
            mAvatarImage = (ImageView) itemView.findViewById(R.id.avatar_image);
            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            mReplyButton = (Button) itemView.findViewById(R.id.btn_reply);
            mShareButton = (ImageButton) itemView.findViewById(R.id.btn_share_image);
            mActionItemsLayout = (LinearLayout) itemView.findViewById(R.id.action_items);
            mCommentLayout = (LinearLayout) itemView.findViewById(R.id.comment_layout);
            mReadMoreButton = (Button) itemView.findViewById(R.id.btn_read_more_comment);

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
            voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));
            if(mIsReplyAdapter == false){
                Log.e("BROWSER_EXPRESS_REPLY_COMMENT_PARENT", "NOT_FOUND");
                mActionItemsLayout.setVisibility(View.VISIBLE);
            }else{
                Log.e("BROWSER_EXPRESS_REPLY_COMMENT_PARENT", "FOUND");
                mActionItemsLayout.setVisibility(View.GONE);
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "1");

            // This is used to make the comment work for post top comments
            if(mMessageEditText == null && mParentFragment == null && mTopCommentRecycler == null){
                Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "2");
                mVoteLayout.setVisibility(View.GONE);
                mActionItemsLayout.setVisibility(View.GONE);

                usernameText.setTextSize(9);
                contentText.setTextSize(10);
                mReadMoreButton.setVisibility(View.GONE);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(16, 16);

                mAvatarImage.setLayoutParams(params);
            }
                
            ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + comment.getUser().getId().toString() + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), false, 5, mAvatarImage, null);

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "3");

            bounceUp = AnimationUtils.loadAnimation(activity ,R.anim.bounce_up);
            bounceDown = AnimationUtils.loadAnimation(activity ,R.anim.bounce_down);

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "4");

            SharedPreferences sharedPref = activity.getSharedPreferencesForReplyComment();
            SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if(key.equals(BraveActivity.BROWSER_EXPRESS_REPLY_COMMENT)){
                        if(activity.getReplyComment() != null && !activity.getReplyComment().equals("")){
                            try{
                                JSONObject commentObject = new JSONObject(activity.getReplyComment().toString());
                                
                                JSONObject user = commentObject.getJSONObject("user");
                                User u = new User(user.getString("_id"), user.getString("username"));
                                Vote v = null;
                                String pageParent = null;
                                String commentParent = null;
                                if(commentObject.has("pageParent")){
                                    pageParent = commentObject.getString("pageParent");
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
                                        commentParent,
                                        u,
                                        v);
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

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "5");

            sharedPref.registerOnSharedPreferenceChangeListener(listener);
            
            Vote didVote = comment.getDidVote();
            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "6");
            if(didVote != null){
                Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "7");
                String type = didVote.getType();
                didVoteType = type;
                if(type.equals("up")){
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_blue_upvote);
                }else if(type.equals("down")){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_white_downvote);
                }
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "8");

            if(comment.getCommentCount() > 0){
                Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "9");
                String mReplyButtonText = comment.getCommentCount() + " replies";
                mReplyButton.setText(mReplyButtonText);
                mReplyButton.setTextColor(ContextCompat.getColor(activity, R.color.browser_express_blue_color));
            }

            Log.e("BROWSER_EXPRESS_REPLY_COMMENT", "10");

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
                                        if (accessToken == null) {
                                            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                            imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                                            activity.showGenerateUsernameBottomSheet();
                                            activity.dismissCommentsBottomSheet();
                                            return;
                                        }

                                        if(!mIsReplyAdapter){
                                            mParentFragment.openReplies(comment.getId());
                                            return;
                                        }

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

        private BrowserExpressAddVoteUtil.AddVoteCallback addVoteCallback=
            new BrowserExpressAddVoteUtil.AddVoteCallback() {
                @Override
                public void addVoteSuccessful() {
                    // mDownvoteButton.setClickable(true);
                    // mUpvoteButton.setClickable(true);
                }

                @Override
                public void addVoteFailed(String error) {
                    mDownvoteButton.setClickable(true);
                    mUpvoteButton.setClickable(true);
                }
            };
    }
}