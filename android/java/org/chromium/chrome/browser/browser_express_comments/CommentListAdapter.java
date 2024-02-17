package org.chromium.chrome.browser.browser_express_comments;

import java.util.UUID;
import java.util.List;
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
import org.json.JSONObject;

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
                    JSONObject json = new JSONObject();
                    json.put("name", comment.getUser().getUsername());
                    json.put("commentId", comment.getId());
                    activity.setReplyTo(json.toString());
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
    }
}