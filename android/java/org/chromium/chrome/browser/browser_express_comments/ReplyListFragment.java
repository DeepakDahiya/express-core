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
    private ProgressBar mCommentProgress;

    private String mCommentId;

    private ImageButton mCancelReplyButton;
    private EditText mMessageEditText;
    private TextView mReplyToText;

    private ShimmerFrameLayout mShimmerLoading;
    private ViewGroup mShimmerItems;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_reply_list, container, false);

        if (getArguments() != null) {
            mCommentId = getArguments().getString(COMMENT_ID);
        }

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parentFragment.openComments();
            }
        });

        AppCompatActivity acActivity = (AppCompatActivity) getActivity();
        if (acActivity != null) {
            acActivity.setSupportActionBar(toolbar);
            acActivity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            acActivity.getSupportActionBar().setDisplayShowHomeEnabled(false);
            acActivity.getSupportActionBar().setTitle("");
        }

        // mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
        //     @Override
        //     public void onClick(View v) {
        //         requireActivity().onBackPressed();
        //     }
        // });

        // setSupportActionBar(toolbar);
        // ActionBar actionBar = getSupportActionBar();
        // assert actionBar != null;
        // actionBar.setDisplayHomeAsUpEnabled(true);
        // actionBar.setDisplayShowHomeEnabled(true);

        mShimmerLoading = view.findViewById(R.id.skeleton_shimmer);
        mShimmerItems = view.findViewById(R.id.shimmer_items);
        int shimmerSkeletonRows =
                AndroidUtils.getSkeletonRowCount(ViewUtils.dpToPx(requireContext(), 50));
        for (int i = 0; i < shimmerSkeletonRows; i++) {
            inflater.inflate(R.layout.shimmer_skeleton_item, mShimmerItems, true);
        }

        // mCommentProgress = view.findViewById(R.id.comment_progress); 
        // mCommentProgress.setVisibility(View.VISIBLE);

        mShimmerLoading.showShimmer(true);
        AndroidUtils.show(mShimmerItems);

        mComments = new ArrayList<Comment>();

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_replies);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        mCancelReplyButton = null;
        mMessageEditText = null;
        mReplyToText = null;

        if(parentFragment != null){
            mMessageEditText = parentFragment.getMessageEditText();
            mReplyToText = parentFragment.getReplyToText();
            mCancelReplyButton = parentFragment.getCancelReplyButton();
        }

        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mReplyToText, mCancelReplyButton, mMessageEditText, mCommentRecycler, null);
        mCommentRecycler.setAdapter(mCommentAdapter);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();

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
                public void getCommentsSuccessful(List<Comment> comments) {
                    int len = mComments.size();
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());
                    mShimmerLoading.setVisibility(View.GONE);
                    AndroidUtils.gone(mShimmerItems);
                    mShimmerLoading.hideShimmer();
                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };
}
