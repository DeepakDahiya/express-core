package org.chromium.chrome.browser.browser_express_comments;

import java.util.UUID;
import java.util.List;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;
import org.chromium.chrome.R;
import android.view.LayoutInflater;

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

        CommentHolder(View itemView) {
            super(itemView);

            usernameText = (TextView) itemView.findViewById(R.id.username);
            contentText = (TextView) itemView.findViewById(R.id.comment_content);
        }

        void bind(Comment message) {
            // messageText.setText(message.getMessage());

            // Format the stored timestamp into a readable String using method.
            usernameText.setText(message.getUser().getUsername().toString());
            contentText.setText(message.getContent().toString());
        }
    }
}