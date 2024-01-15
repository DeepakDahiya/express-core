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
import androidx.core.content.ContextCompat;

public class CommentListAdapter extends RecyclerView.Adapter {
    private Context mContext;
    private List<Comment> mMessageList;

    public CommentListAdapter(Context context, List<Comment> messageList) {
        mContext = context;
        mMessageList = messageList;
    }

    @Override
    public int getItemCount() {
        return mMessageList.size();
    }

    // Inflates the appropriate layout according to the ViewType.
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.browser_express_comment, parent, false);
        return new CommentHolder(view);
    }

    // Passes the message object to a ViewHolder so that the contents can be bound to UI.
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Comment message = (Comment) mMessageList.get(position);

        ((CommentHolder) holder).bind(message);
    }

    private class CommentHolder extends RecyclerView.ViewHolder {
        TextView usernameText;
        TextView contentText;
        private ImageButton mUpvoteButton;
        private ImageButton mDownvoteButton;

        CommentHolder(View itemView) {
            super(itemView);

            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
            mUpvoteButton = (ImageButton) itemView.findViewById(R.id.btn_upvote);
            mDownvoteButton = (ImageButton) itemView.findViewById(R.id.btn_downvote);
        }

        void bind(Comment message) {
            // messageText.setText(message.getMessage());

            // Format the stored timestamp into a readable String using method.
            usernameText.setText(message.getUser().getUsername().toString());
            contentText.setText(message.getContent().toString());
            mUpvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        int tintColor = ContextCompat.getColor(activity, R.color.browser_express_orange_color);

                        // Get the drawable from the ImageButton
                        Drawable drawable = mUpvoteButton.getDrawable();

                        // Apply the tint color using setColorFilter
                        drawable.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));

                        // Update the ImageButton with the modified drawable
                        mUpvoteButton.setImageDrawable(drawable);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                    
                }
            });

            mDownvoteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        int tintColor = ContextCompat.getColor(activity, R.color.browser_express_orange_color);

                        // Get the drawable from the ImageButton
                        Drawable drawable = mDownvoteButton.getDrawable();

                        // Apply the tint color using setColorFilter
                        drawable.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));

                        // Update the ImageButton with the modified drawable
                        mDownvoteButton.setImageDrawable(drawable);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }
            });
        }
    }
}