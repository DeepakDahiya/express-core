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
import org.chromium.ui.base.ViewUtils;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import org.chromium.chrome.browser.crypto_wallet.util.AndroidUtils;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;

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

    private ShimmerFrameLayout mShimmerLoading;
    private ViewGroup mShimmerItems;

    private Button mLolButton;
    private Button mHeartButton;
    private Button mCryButton;
    private Button mFireButton;
    private Button mLoveButton;
    private Button mClapButton;

    private ImageButton mSendButton;
    private EditText mMessageEditText;
    private TextView mCommentsText;

    private ImageView mAvatarImage;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_comment_list, container, false);

        if (getArguments() != null) {
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
        }

        mAvatarImage = (ImageView) view.findViewById(R.id.avatar_image);
        mSendButton = view.findViewById(R.id.button_send);
        mMessageEditText = (EditText) view.findViewById(R.id.comment_content_input);

        mLolButton = view.findViewById(R.id.lol_button);
        mHeartButton = view.findViewById(R.id.heart_button);
        mCryButton = view.findViewById(R.id.cry_button);
        mFireButton = view.findViewById(R.id.fire_button);
        mLoveButton = view.findViewById(R.id.love_button);
        mClapButton = view.findViewById(R.id.clap_button);

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

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        
        boolean isReplyAdapter = false;
        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mMessageEditText, mCommentRecycler, parentFragment, isReplyAdapter, false);
        mCommentRecycler.setAdapter(mCommentAdapter);

        this.setOnClickForEmoji(mLolButton, mMessageEditText);
        this.setOnClickForEmoji(mHeartButton, mMessageEditText);
        this.setOnClickForEmoji(mCryButton, mMessageEditText);
        this.setOnClickForEmoji(mFireButton, mMessageEditText);
        this.setOnClickForEmoji(mLoveButton, mMessageEditText);
        this.setOnClickForEmoji(mClapButton, mMessageEditText);

        // mHeartButton.setOnClickListener((new View.OnClickListener() {
        //         @Override
        //         public void onClick(View v) {
        //             String content = mMessageEditText.getText().toString().trim();
        //             if(content.length() > 0){
        //                 String finalContent = content + mHeartButton.getText().toString();
        //                 mMessageEditText.setText(finalContent);
        //             }else{
        //                 mMessageEditText.setText(mHeartButton.getText().toString());
        //             }
        //         }
        //     }));

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();
            mCommentsText = activity.getCommentCountText();
            if(accessToken != null){
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
                ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), false, 5, mAvatarImage, null);
            }
            
            if(mCommentsFor.equals("post")){
                BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                    new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                            null, null, mPostId, mPage, mPerPage, accessToken, getCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }else{
                mUrl = activity.getActivityTab().getUrl().getSpec();
                BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                    new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                            mUrl, null, null, mPage, mPerPage, accessToken, getCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }

            mSendButton.setOnClickListener((new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (getActivity() != null) {
                        try {
                            mSendButton.setClickable(false);
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            String accessToken = activity.getAccessToken();
                            if (accessToken == null) {
                                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                                activity.showGenerateUsernameBottomSheet();
                                parentFragment.dismissBottomsheet();
                            } else {
                                String content = mMessageEditText.getText().toString().trim();
                                if(content.length() > 0){
                                    String pType = "page";
                                    String pId = null;
                                    if(mCommentsFor.equals("post")){
                                        pType = "post";
                                        pId = mPostId;
                                    }
                                    BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                        new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                content, pType, mUrl, pId, accessToken, addCommentCallback);
                                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                    mMessageEditText.setText(R.string.browser_express_empty_text);
                                }
                            }
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            // Log.e("Express Browser Access Token", e.getMessage());
                        }finally{
                            mSendButton.setClickable(true);
                        }
                    }
                }
            }));

        } catch (BraveActivity.BraveActivityNotFoundException e) {
            // Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            // Log.e("Express Browser Access Token", ex.getMessage());
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

    private void setOnClickForEmoji(Button emojiButton, EditText editText){ {
        emojiButton.setOnClickListener((new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String content = editText.getText().toString().trim();
                    if(content.length() > 0){
                        String finalContent = content + emojiButton.getText().toString();
                        editText.setText(finalContent);
                    }else{
                        editText.setText(emojiButton.getText().toString());
                    }
                }
            }));
    }

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments, Comment parentComment) {
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

    private BrowserExpressAddCommentUtil.AddCommentCallback addCommentCallback=
            new BrowserExpressAddCommentUtil.AddCommentCallback() {
                @Override
                public void addCommentSuccessful(Comment comment) {
                    mComments.add(0, comment);
                    mCommentAdapter.notifyItemRangeInserted(0, 1);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(0, 0);
                    try{
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        // Updating comment count for bottom toolbar
                        mCommentsText = activity.getCommentCountText();

                        String currentText = mCommentsText.getText().toString();
                        int commentCount = 0;
                        try {
                            String[] parts = currentText.split(" ");
                            if (parts.length > 0) {
                                commentCount = Integer.parseInt(parts[0]);
                            }
                        } catch (NumberFormatException e) {
                        }

                        // Increment the comment count
                        commentCount++;

                        mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        // Log.e("Express Browser Access Token", e.getMessage());
                    }
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
            // Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            // Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
        
    }
}
