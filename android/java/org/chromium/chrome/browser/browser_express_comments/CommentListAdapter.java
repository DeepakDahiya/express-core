package org.chromium.chrome.browser.browser_express_comments;

import java.util.UUID;
import java.util.List;
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

public class CommentListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Comment> mCommentList;
    private ImageButton mCanceReplyButton;
    private TextView mReplyToText;
    private EditText mMessageEditText;
    private RecyclerView mTopCommentRecycler;
    private RecyclerView mReplyRecycler;
    private LinearLayout mReplyContainer;

    public CommentListAdapter(Context context, List<Comment> commentList, TextView  replyToText, ImageButton canceReplyButton, EditText messageEditText, RecyclerView topCommentRecycler, LinearLayout replyContainerLayout, RecyclerView replyRecycler) {
        mContext = context;
        mCommentList = commentList;
        mReplyToText = replyToText;
        mCanceReplyButton = canceReplyButton;
        mMessageEditText = messageEditText;
        mTopCommentRecycler = topCommentRecycler;
        mReplyContainer = replyContainerLayout;
        mReplyRecycler = replyRecycler;
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
        return new CommentHolder(view, mReplyToText, mCanceReplyButton, mMessageEditText, mTopCommentRecycler, mReplyContainer, mReplyRecycler);
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
        private Button mShowMoreButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity;

        private RecyclerView mCommentRecycler;
        private RecyclerView mTopCommentRecycler;
        private CommentListAdapter mCommentAdapter;
        private List<Comment> mComments;
        private int mPage = 1;
        private int mPerPage = 100;
        private Context context;
        private LinearLayout mActionItemsLayout;
        private LinearLayout mCommentLayout;
        private LinearLayout mReplyContainer;

        private ImageButton mCanceReplyButton;
        private TextView mReplyToText;
        private EditText mMessageEditText;

        private Animation bounceUp;
        private Animation bounceDown;

        private int myPosition;


        CommentHolder(View itemView, TextView replyToText, ImageButton canceReplyButton, EditText messageEditText, RecyclerView topCommentRecycler, LinearLayout replyContainerLayout, RecyclerView replyRecycler) {
            super(itemView);

            mReplyToText = replyToText;
            mCanceReplyButton = canceReplyButton;
            mMessageEditText = messageEditText;

            mReplyContainer = replyContainerLayout;

            mTopCommentRecycler = topCommentRecycler;
            mAvatarImage = (ImageView) itemView.findViewById(R.id.avatar_image);
            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            mReplyButton = (Button) itemView.findViewById(R.id.btn_reply);
            mShareButton = (Button) itemView.findViewById(R.id.btn_share);
            mShowMoreButton = (Button) itemView.findViewById(R.id.btn_more_comments);
            // mCommentRecycler = (RecyclerView) itemView.findViewById(R.id.recycler_replies);
            mCommentRecycler = replyRecycler;
            mActionItemsLayout = (LinearLayout) itemView.findViewById(R.id.action_items);
            mCommentLayout = (LinearLayout) itemView.findViewById(R.id.comment_layout);
            context = itemView.getContext();

            mReplyButton.setTextSize(10);
            mShareButton.setTextSize(10);
            mShowMoreButton.setTextSize(10);
        }

        void bind(Comment comment) {
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            myPosition = getBindingAdapterPosition();

            usernameText.setText(comment.getUser().getUsername().toString());
            contentText.setText(comment.getContent().toString());
            finalVote = comment.getUpvoteCount() - comment.getDownvoteCount();
            voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));
            mShowMoreButton.setVisibility(comment.getCommentCount() > 0 ? View.VISIBLE : View.GONE);
            if(comment.getCommentParent() == null){
                mActionItemsLayout.setVisibility(View.VISIBLE);
            }

            ImageLoader.downloadImage("https://api.multiavatar.com/" + comment.getUser().getId().toString() + ".png?apikey=ewsXMRIAbcdY5F", Glide.with(activity), false, 5, mAvatarImage, null);

            mComments = new ArrayList<Comment>();
            mCommentRecycler.setLayoutManager(new LinearLayoutManager(context));

            mCommentAdapter = new CommentListAdapter(context, mComments, mReplyToText, mCanceReplyButton, mMessageEditText, null, null, null);
            mCommentRecycler.setAdapter(mCommentAdapter);

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
                                mReplyToText.setText(R.string.browser_express_empty_text);
                                mCanceReplyButton.setVisibility(View.INVISIBLE);
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
                    mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote_orange);
                }else if(type.equals("down")){
                    mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote_orange);
                }
            }

            mCanceReplyButton.setOnClickListener((new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        activity = BraveActivity.getBraveActivity();
                        activity.setReplyTo(null);
                        mReplyToText.setText(R.string.browser_express_empty_text);
                        mCanceReplyButton.setVisibility(View.INVISIBLE);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e("Express Browser Access Token", e.getMessage());
                    }
                }
            }));

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

                        JSONObject json = new JSONObject();
                        json.put("name", comment.getUser().getUsername());
                        json.put("commentId", comment.getId());
                        activity.setReplyTo(json.toString());
                        // mCanceReplyButton = activity.getReplyToCancelButton();
                        // mReplyToText = activity.getReplyToText();
                        // mMessageEditText =  activity.getContentEditText();
                        if(mReplyToText != null || mCanceReplyButton != null || mMessageEditText != null){
                            Log.e("REPLY TO", "INSIDE REPLY TO TEXT");
                            String replyToString = "replying to " + comment.getUser().getUsername();
                            mReplyToText.setText(replyToString);
                            mCanceReplyButton.setVisibility(View.VISIBLE);
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

            mShowMoreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String accessToken = activity.getAccessToken();
                    Log.e("Express Browser SHOW MORE", "BEFORE API");
                    BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                        new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                                null, comment.getId(), null, mPage, mPerPage, accessToken, getCommentsCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
                                comment.getId(), "up", "comment", accessToken, addVoteCallback);
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
                                comment.getId(), "down", "comment", accessToken, addVoteCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });
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

        private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments) {
                    int len = comments.size();
                    // mComments.clear();
                    // mCommentAdapter.notifyItemRangeRemoved(0, len);
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());

                    mPage = mPage + 1;

                    mReplyContainer.setVisibility(View.VISIBLE);
                    mTopCommentRecycler.setVisibility(View.GONE);

                    // mShowMoreButton.setVisibility(View.GONE);
                    // mCommentRecycler.setVisibility(View.VISIBLE);

                    // data.addAll(insertIndex, items);
                    // mCommentAdapter.notifyItemRangeInserted(insertIndex, items.size());

                    // DisplayMetrics displaymetrics = new DisplayMetrics();
                    // getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

                    // int a =  (displaymetrics.heightPixels*70)/100;

                    // mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
                    // mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

                    // mCommentAdapter = new CommentListAdapter(requireContext(), mComments);
                    // mCommentRecycler.setAdapter(mCommentAdapter);

                    // ViewGroup.LayoutParams params=mCommentRecycler.getLayoutParams();
                    // params.height=a;
                    // mCommentRecycler.setLayoutParams(params);

                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };
    }
}