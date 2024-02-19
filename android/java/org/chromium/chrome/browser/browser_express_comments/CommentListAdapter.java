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
import android.view.LayoutInflater;
import org.chromium.chrome.browser.app.BraveActivity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import org.chromium.base.task.AsyncTask;
import java.util.Locale;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.recyclerview.widget.LinearLayoutManager;

public class CommentListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Comment> mCommentList;

    public CommentListAdapter(Context context, List<Comment> commentList) {
        mContext = context;
        mCommentList = commentList;
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
        return new CommentHolder(view);
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
        private ImageButton mUpvoteButton;
        private ImageButton mDownvoteButton;
        private Button mReplyButton;
        private Button mShareButton;
        private Button mShowMoreButton;
        private String didVoteType;
        private int finalVote;
        private BraveActivity activity;

        private RecyclerView mCommentRecycler;
        private CommentListAdapter mCommentAdapter;
        private List<Comment> mComments;
        private int mPage = 1;
        private int mPerPage = 30;
        private Context context;

        CommentHolder(View itemView) {
            super(itemView);

            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
            mReplyButton = (Button) itemView.findViewById(R.id.btn_reply);
            mShareButton = (Button) itemView.findViewById(R.id.btn_share);
            mShowMoreButton = (Button) itemView.findViewById(R.id.btn_more_comments);
            mCommentRecycler = (RecyclerView) itemView.findViewById(R.id.recycler_replies);
            context = itemView.getContext();
        }

        void bind(Comment comment) {
            try {
                activity = BraveActivity.getBraveActivity();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            usernameText.setText(comment.getUser().getUsername().toString());
            contentText.setText(comment.getContent().toString());
            finalVote = comment.getUpvoteCount() - comment.getDownvoteCount();
            voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));
            mShowMoreButton.setVisibility(comment.getCommentCount() > 0 ? View.VISIBLE : View.GONE);


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

            mReplyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try{
                        JSONObject json = new JSONObject();
                        json.put("name", comment.getUser().getUsername());
                        json.put("commentId", comment.getId());
                        activity.setReplyTo(json.toString());
                    } catch (JSONException e) {
                        Log.e("BROWSER_EXPRESS_REPLY_TO_CLICK", e.getMessage());
                    }
                }
            });

            mShowMoreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mComments = new ArrayList<Comment>();
                    mCommentRecycler.setLayoutManager(new LinearLayoutManager(context));

                    mCommentAdapter = new CommentListAdapter(context, mComments);
                    mCommentRecycler.setAdapter(mCommentAdapter);

                    String accessToken = activity.getAccessToken();
                    Log.e("BROWSER EXPRESS SHOW MORE", "BEFORE API");
                    BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                        new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                                null, comment.getId(), mPage, mPerPage, accessToken, getCommentsCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });


            mUpvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(didVoteType != null && didVoteType.equals("up")){
                        return;
                    }

                    String accessToken = activity.getAccessToken();
                    if (accessToken == null) {
                        // activity.showGenerateUsernameBottomSheet();
                    } else {
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote_orange);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote);

                        mDownvoteButton.setClickable(false);
                        mUpvoteButton.setClickable(false);

                        if(didVoteType != null && didVoteType.equals("down")){
                            finalVote = finalVote + 2;
                        }else{
                            finalVote = finalVote + 1;
                        }
                        voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                        didVoteType = "up";

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "up", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                }
            });

            mDownvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if(didVoteType != null && didVoteType.equals("down")){
                        return;
                    }

                    String accessToken = activity.getAccessToken();
                    if (accessToken == null) {
                        // activity.showGenerateUsernameBottomSheet();
                    } else {
                        mUpvoteButton.setBackgroundResource(R.drawable.btn_upvote);
                        mDownvoteButton.setBackgroundResource(R.drawable.btn_downvote_orange);

                        mDownvoteButton.setClickable(false);
                        mUpvoteButton.setClickable(false);

                        if(didVoteType != null && didVoteType.equals("up")){
                            finalVote = finalVote - 2;
                        }else{
                            finalVote = finalVote - 1;
                        }
                        voteCountText.setText(String.format(Locale.getDefault(), "%d", finalVote));

                        didVoteType = "down";

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "down", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
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
                    Log.e("BROWSER EXPRESS LOGIN", "INSIDE LOGIN FAILED");
                    mDownvoteButton.setClickable(true);
                    mUpvoteButton.setClickable(true);
                }
            };

        private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments) {
                    Log.e("BROWSER EXPRESS SHOW MORE", "IN API RESPONSE");
                    int len = comments.size();
                    Log.e("BROWSER EXPRESS SHOW MORE", Integer.toString(len));
                    Log.e("BROWSER EXPRESS SHOW MORE", comments.toString());
                    // mComments.clear();
                    // mCommentAdapter.notifyItemRangeRemoved(0, len);
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());

                    mPage = mPage + 1;

                    Log.e("BROWSER EXPRESS SHOW MORE", "AFTER API RESPONSE");
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
                    Log.e("BROWSER EXPRESS LOGIN", "INSIDE LOGIN FAILED");
                }
            };
    }
}