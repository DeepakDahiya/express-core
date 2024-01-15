package org.chromium.chrome.browser.browser_express_comments;

import java.util.UUID;
import java.util.List;
import android.widget.TextView;
import android.view.View;
import org.chromium.base.Log;
import android.widget.ImageButton;
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
import androidx.core.content.ContextCompat;

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

        CommentHolder(View itemView) {
            super(itemView);

            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            voteCountText = (TextView) itemView.findViewById(R.id.vote_count);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
        }

        void bind(Comment comment) {
            // commentText.setText(comment.getMessage());

            // Format the stored timestamp into a readable String using method.
            usernameText.setText(comment.getUser().getUsername().toString());
            contentText.setText(comment.getContent().toString());
            int count = comment.getUpvoteCount() - comment.getDownvoteCount();
            voteCountText.setText(Integer.toString(count));

            mUpvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String accessToken = activity.getAccessToken();
                        int orangeColor = ContextCompat.getColor(activity, R.color.browser_express_orange_color);
                        int grayColor = ContextCompat.getColor(activity, R.color.onboarding_gray);

                        // Get the drawable from the ImageButton
                        Drawable mUpvoteDrawable = mUpvoteButton.getDrawable();
                        Drawable mDownvoteDrawable = mDownvoteButton.getDrawable();

                        // Apply the tint color using setColorFilter
                        mUpvoteDrawable.setColorFilter(new PorterDuffColorFilter(orangeColor, PorterDuff.Mode.SRC_IN));
                        mDownvoteDrawable.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));

                        // Update the ImageButton with the modified drawable
                        mUpvoteButton.setImageDrawable(mUpvoteDrawable);
                        mDownvoteButton.setImageDrawable(mDownvoteDrawable);

                        mDownvoteButton.setClickable(false);
                        mUpvoteButton.setClickable(false);

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "up", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }
            });

            mDownvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String accessToken = activity.getAccessToken();
                        int orangeColor = ContextCompat.getColor(activity, R.color.browser_express_orange_color);
                        int grayColor = ContextCompat.getColor(activity, R.color.onboarding_gray);

                        // Get the drawable from the ImageButton
                        Drawable mDownvoteDrawable = mDownvoteButton.getDrawable();
                        Drawable mUpvoteDrawable = mUpvoteButton.getDrawable();

                        // Apply the tint color using setColorFilter
                        mDownvoteDrawable.setColorFilter(new PorterDuffColorFilter(orangeColor, PorterDuff.Mode.SRC_IN));
                        mUpvoteDrawable.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));

                        // Update the ImageButton with the modified drawable
                        mDownvoteButton.setImageDrawable(mDownvoteDrawable);
                        mUpvoteButton.setImageDrawable(mUpvoteDrawable);

                        mDownvoteButton.setClickable(false);
                        mUpvoteButton.setClickable(false);

                        BrowserExpressAddVoteUtil.AddVoteWorkerTask workerTask =
                            new BrowserExpressAddVoteUtil.AddVoteWorkerTask(
                                    comment.getId(), "down", accessToken, addVoteCallback);
                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
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