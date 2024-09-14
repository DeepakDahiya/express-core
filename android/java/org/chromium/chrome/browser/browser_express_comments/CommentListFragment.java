package org.chromium.chrome.browser.browser_express_comments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.CheckBox;
import org.chromium.ui.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import android.util.DisplayMetrics;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ProgressBar;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.EditText;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

public class CommentListFragment extends Fragment {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mComments;
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private ProgressBar mCommentProgress;

    private ImageButton mCancelReplyButton;
    private EditText mMessageEditText;
    private TextView mReplyToText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_comment_list, container, false);

        if (getArguments() != null) {
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
        }

        mCommentProgress = view.findViewById(R.id.comment_progress); 
        mCommentProgress.setVisibility(View.VISIBLE);

        mComments = new ArrayList<Comment>();

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getActivity();
        
        mCancelReplyButton = null;
        mMessageEditText = null;
        mReplyToText = null;

        if(parentFragment != null){
            mMessageEditText = parentFragment.getMessageEditText();
            mReplyToText = parentFragment.getReplyToText();
            mCancelReplyButton = parentFragment.getCancelReplyButton();
        }

        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mReplyToText, mCancelReplyButton, mMessageEditText, mCommentRecycler);
        mCommentRecycler.setAdapter(mCommentAdapter);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();
            
            if(mCommentsFor.equals("post")){
                mUrl = activity.getActivityTab().getUrl().getSpec();

                BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                    new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                            null, null, mPostId, mPage, mPerPage, accessToken, getCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }else{
                String commentsString = activity.getFirstComments();
                if(commentsString != null){
                    try{
                        JSONArray commentsArray = new JSONArray(commentsString);
                        List<Comment> comments = new ArrayList<Comment>();
                        for (int i = 0; i < commentsArray.length(); i++) {
                            JSONObject comment = commentsArray.getJSONObject(i);
                            JSONObject user = comment.getJSONObject("user");
                            JSONObject didVote = comment.optJSONObject("didVote");
                            Vote v = null;
                            if(didVote != null){
                                v = new Vote(didVote.getString("_id"), didVote.getString("type"));
                            }
                            User u = new User(user.getString("_id"), user.getString("username"));
                            String pageParent = null;
                            String commentParent = null;
                            if(comment.has("pageParent")){
                                pageParent = comment.getString("pageParent");
                            }

                            if(comment.has("commentParent")){
                                commentParent = comment.getString("commentParent");
                            }
                            comments.add(new Comment(
                                comment.getString("_id"), 
                                comment.getString("content"),
                                comment.getInt("upvoteCount"),
                                comment.getInt("downvoteCount"),
                                comment.getInt("commentCount"),
                                pageParent,
                                commentParent,
                                u, 
                                v));
                        }

                        int len = mComments.size();
                        mComments.clear();
                        mCommentAdapter.notifyItemRangeRemoved(0, len);
                        mComments.addAll(comments);
                        mCommentAdapter.notifyItemRangeInserted(0, comments.size());
                        mCommentProgress.setVisibility(View.GONE);
                        mPage = 2;
                    } catch (JSONException e) {
                        Log.e("Comments_Bottom_Sheet", e.getMessage());
                    }
                }
                mUrl = activity.getActivityTab().getUrl().getSpec();

                BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                    new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                            mUrl, null, null, mPage, mPerPage, accessToken, getCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            Log.e("Express Browser Access Token", ex.getMessage());
        }

        return view;
    }

    public static CommentListFragment newInstance(String postId, String commentsFor) {
        CommentListFragment fragment = new CommentListFragment();
        Bundle args = new Bundle();
        args.putString(COMMENTS_FOR, commentsFor);
        args.putString(POST_ID, postId);
        fragment.setArguments(args);
        return fragment;
    }

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments) {
                    int len = mComments.size();
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());
                    mCommentProgress.setVisibility(View.GONE);
                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };
}
