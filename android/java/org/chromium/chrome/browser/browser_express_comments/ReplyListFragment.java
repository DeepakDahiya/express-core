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
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AppCompatActivity;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.ui.base.ViewUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import org.chromium.chrome.browser.crypto_wallet.util.AndroidUtils;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

public class ReplyListFragment extends Fragment {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String COMMENT_ID = "comment_id";
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mComments;
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;

    private RecyclerView mTopCommentRecycler;
    private CommentListAdapter mTopCommentAdapter;
    private List<Comment> mTopComments;

    private String mCommentId;

    private ShimmerFrameLayout mShimmerLoading;
    private ViewGroup mShimmerItems;

    private ImageButton mSendButton;
    private ImageButton mCancelReplyButton;
    private EditText mMessageEditText;
    private TextView mReplyToText;
    private TextView mCommentsText;

    private ImageView mAvatarImage;
    private ImageView mBackButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_reply_list, container, false);

        if (getArguments() != null) {
            mCommentId = getArguments().getString(COMMENT_ID);
        }

        mAvatarImage = (ImageView) view.findViewById(R.id.avatar_image);
        mSendButton = view.findViewById(R.id.button_send);
        mMessageEditText = view.findViewById(R.id.comment_content);
        mReplyToText = view.findViewById(R.id.reply_to);
        mCancelReplyButton = view.findViewById(R.id.cancel_btn);
        mBackButton = view.findViewById(R.id.back_button);

        mBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                parentFragment.openComments();
            }
        });

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        
        // Toolbar toolbar = view.findViewById(R.id.toolbar);

        // AppCompatActivity acActivity = (AppCompatActivity) getActivity();
        // if (acActivity != null) {
        //     acActivity.setSupportActionBar(toolbar);
        //     acActivity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        //     acActivity.getSupportActionBar().setDisplayShowHomeEnabled(false);
        //     acActivity.getSupportActionBar().setTitle("");
        // }

        // toolbar.setNavigationOnClickListener(new View.OnClickListener() {
        //     @Override
        //     public void onClick(View v) {
        //         parentFragment.openComments();
        //     }
        // });

        mShimmerLoading = view.findViewById(R.id.skeleton_shimmer);
        mShimmerItems = view.findViewById(R.id.shimmer_items);
        int shimmerSkeletonRows =
                AndroidUtils.getSkeletonRowCount(ViewUtils.dpToPx(requireContext(), 50));
        for (int i = 0; i < shimmerSkeletonRows; i++) {
            inflater.inflate(R.layout.shimmer_skeleton_item, mShimmerItems, true);
        }

        mShimmerLoading.showShimmer(true);
        AndroidUtils.show(mShimmerItems);

        mComments = new ArrayList<Comment>();

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_replies);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        // mCancelReplyButton = null;
        // mMessageEditText = null;
        // mReplyToText = null;

        // if(parentFragment != null){
        //     mMessageEditText = parentFragment.getMessageEditText();
        //     mReplyToText = parentFragment.getReplyToText();
        //     mCancelReplyButton = parentFragment.getCancelReplyButton();
        // }

        boolean isReplyAdapter = true;
        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mReplyToText, mCancelReplyButton, mMessageEditText, mCommentRecycler, null, isReplyAdapter, false);
        mCommentRecycler.setAdapter(mCommentAdapter);

        mTopComments = new ArrayList<Comment>();
        // User u = new User("123", "Test Username");
        // Comment parentComment = new Comment(
        //                         "123", 
        //                         "Test Comment",
        //                         5,
        //                         2,
        //                         0,
        //                         null,
        //                         null,
        //                         u, 
        //                         null);
        // mTopComments.add(parentComment);
        mTopCommentRecycler = (RecyclerView) view.findViewById(R.id.top_comment_recycler);
        mTopCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mTopCommentAdapter = new CommentListAdapter(requireContext(), mTopComments, mReplyToText, mCancelReplyButton, mMessageEditText, mTopCommentRecycler, null, isReplyAdapter, true);
        mTopCommentRecycler.setAdapter(mTopCommentAdapter);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();

            if(accessToken != null){
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
                ImageLoader.downloadImage("https://api.multiavatar.com/" + decodedAccessTokenObj.getString("_id") + ".png?apikey=ewsXMRIAbcdY5F", Glide.with(activity), false, 5, mAvatarImage, null);
            }

            mSendButton.setOnClickListener((new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (getActivity() != null) {
                        try {
                            mSendButton.setClickable(false);
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            String accessToken = activity.getAccessToken();
                            mUrl = activity.getActivityTab().getUrl().getSpec();
                            if (accessToken == null) {
                                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                                activity.showGenerateUsernameBottomSheet();
                                parentFragment.dismissBottomsheet();
                            } else {
                                String content = mMessageEditText.getText().toString().trim();
                                if(content.length() > 0){
                                    BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                        new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                content, "comment", mUrl, mCommentId, accessToken, addCommentCallback);
                                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                    mMessageEditText.setText(R.string.browser_express_empty_text);
                                }
                            }
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e("Express Browser Access Token", e.getMessage());
                        }finally{
                            mSendButton.setClickable(true);
                        }
                    }
                }
            }));

            // Getting replies
            BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                        null, mCommentId, null, mPage, mPerPage, accessToken, getCommentsCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            Log.e("Express Browser Access Token", ex.getMessage());
        }

        return view;
    }

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments, Comment parentComment) {
                    int len = mComments.size();
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());

                    if(parentComment != null){
                        Log.e("SETTING PARENT COMMENT", parentComment.toString());
                        mTopComments.add(parentComment);
                        mTopCommentAdapter.notifyItemRangeInserted(0, 1);
                    }

                    mShimmerLoading.setVisibility(View.GONE);
                    AndroidUtils.gone(mShimmerItems);
                    mShimmerLoading.hideShimmer();
                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    private BrowserExpressAddCommentUtil.AddCommentCallback addCommentCallback=
            new BrowserExpressAddCommentUtil.AddCommentCallback() {
                @Override
                public void addCommentSuccessful(Comment comment) {
                    mComments.add(0, comment);
                    mCommentAdapter.notifyItemRangeInserted(0, 1);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(0, 0);
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    private JSONObject getDecodedToken(String accessToken){
        try{
            String[] split_string = accessToken.split("\\.");
            String base64EncodedHeader = split_string[0];
            String base64EncodedBody = split_string[1];
            String base64EncodedSignature = split_string[2];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
        
    }
}
