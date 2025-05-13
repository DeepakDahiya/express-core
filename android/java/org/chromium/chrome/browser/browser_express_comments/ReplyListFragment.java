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
import android.content.Intent;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import android.widget.LinearLayout;

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
    private EditText mMessageEditText;
    private TextView mCommentsText;

    private ImageView mBackButton;

    private Button mLolButton;
    private Button mHeartButton;
    private Button mCryButton;
    private Button mFireButton;
    private Button mLoveButton;
    private Button mClapButton;

    private LinearLayout mParentCommentLayout;
    private ImageView mArrow1;
    private ImageView mArrow2;

    private BottomSheetInputCallback inputCallback;
    
    private RecyclerView.OnScrollListener videoScrollListener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Fragment parentFragment = getParentFragment();
        if (parentFragment instanceof BottomSheetInputCallback) {
            inputCallback = (BottomSheetInputCallback) parentFragment;
        } else {
            Log.e("Reply List Fragment", "Parent fragment must implement BottomSheetInputCallback");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_reply_list, container, false);

        if (getArguments() != null) {
            mCommentId = getArguments().getString(COMMENT_ID);
        }

        mMessageEditText = inputCallback.getInputEditText();
        mSendButton = inputCallback.getSendButton();

        mLolButton = view.findViewById(R.id.lol_button);
        mHeartButton = view.findViewById(R.id.heart_button);
        mCryButton = view.findViewById(R.id.cry_button);
        mFireButton = view.findViewById(R.id.fire_button);
        mLoveButton = view.findViewById(R.id.love_button);
        mClapButton = view.findViewById(R.id.clap_button);
        mParentCommentLayout = view.findViewById(R.id.parent_comment_container);
        mArrow1 = view.findViewById(R.id.comment_arrow1);
        mArrow2 = view.findViewById(R.id.comment_arrow2);

        mArrow2.setVisibility(View.VISIBLE);
        
        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();

        mBackButton = view.findViewById(R.id.back_button);
        mBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                parentFragment.openComments();
            }
        });
        
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

        boolean isReplyAdapter = true;
        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mMessageEditText, mCommentRecycler, parentFragment, isReplyAdapter, false, false);
        mCommentRecycler.setAdapter(mCommentAdapter);

        mTopComments = new ArrayList<Comment>();
        mTopCommentRecycler = (RecyclerView) view.findViewById(R.id.top_comment_recycler);
        mTopCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mTopCommentAdapter = new CommentListAdapter(requireContext(), mTopComments, mMessageEditText, mTopCommentRecycler, parentFragment, isReplyAdapter, true, false);
        mTopCommentRecycler.setAdapter(mTopCommentAdapter);

        this.setOnClickForEmoji(inputCallback.getEmojiButton("lol"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("heart"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("cry"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("fire"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("love"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("clap"), mMessageEditText);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();

            mSendButton.setOnClickListener((new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (getActivity() != null) {
                        try {
                            mSendButton.setClickable(false);
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            String accessToken = activity.getAccessToken();
                            mUrl = activity.getActivityTab().getUrl().getSpec();
                            // if (accessToken == null) {
                            //     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                            //     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                            //     activity.showGenerateUsernameBottomSheet();
                            //     parentFragment.dismissBottomsheet();
                            //     return;
                            // }
                            String content = mMessageEditText.getText().toString().trim();
                            if(content.length() > 0){
                                BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                    new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                            content, "comment", mUrl, mCommentId, accessToken, addCommentCallback);
                                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                mMessageEditText.setText(R.string.browser_express_empty_text);
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
        
        setupVideoScrollListener(mCommentRecycler);
        return view;
    }

     private void setupVideoScrollListener(RecyclerView recyclerView) {
        if (videoScrollListener != null) {
            recyclerView.removeOnScrollListener(videoScrollListener);
        }
        videoScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                checkAndPauseInvisibleVideos(recyclerView);
            }
        };
        recyclerView.addOnScrollListener(videoScrollListener);
    }

     private void checkAndPauseInvisibleVideos(RecyclerView recyclerView) {
        CommentListAdapter.CommentHolder currentPlayingHolder = CommentListAdapter.VideoPlaybackManager.getCurrentlyPlayingHolder();
        if (currentPlayingHolder != null && currentPlayingHolder.player != null && currentPlayingHolder.player.isPlaying()) {
            
            LinearLayoutManager layoutManager = null;
            if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                 layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            }
            if (layoutManager == null) return;

            int holderPosition = currentPlayingHolder.getBindingAdapterPosition();
            if (holderPosition == RecyclerView.NO_POSITION) {
                CommentListAdapter.VideoPlaybackManager.pauseCurrentlyPlayingVideo();
                return;
            }

            int firstVisible = layoutManager.findFirstVisibleItemPosition();
            int lastVisible = layoutManager.findLastVisibleItemPosition();

            if (holderPosition < firstVisible || holderPosition > lastVisible) {
                Log.d("VideoScroll", "Pausing video (holder fully out of view): " + holderPosition);
                CommentListAdapter.VideoPlaybackManager.pauseCurrentlyPlayingVideo();
            } else {
                // Holder is in visible range, check how much of the video view itself is visible
                if (currentPlayingHolder.commentVideo != null && !isViewMostlyVisible(currentPlayingHolder.commentVideo, recyclerView)) {
                    Log.d("VideoScroll", "Pausing video (partially out of view): " + holderPosition);
                    CommentListAdapter.VideoPlaybackManager.pauseCurrentlyPlayingVideo();
                }
            }
        }
    }

    private boolean isViewMostlyVisible(View view, RecyclerView recyclerView) {
        if (view == null || !view.isShown() || view.getHeight() == 0 || view.getWidth() == 0) {
            return false;
        }

        Rect viewRect = new Rect();
        if (!view.getGlobalVisibleRect(viewRect)) { // if not visible on screen at all
            return false;
        }

        Rect recyclerRect = new Rect();
        recyclerView.getGlobalVisibleRect(recyclerRect); // Visible part of RecyclerView on screen

        if (!Rect.intersects(viewRect, recyclerRect)) { // No intersection
            return false;
        }

        // Calculate the height of the intersection
        int visibleHeight = Math.min(viewRect.bottom, recyclerRect.bottom) - Math.max(viewRect.top, recyclerRect.top);
        
        float visibilityThreshold = 0.5f; // 50% of video height must be visible
        return visibleHeight >= view.getHeight() * visibilityThreshold;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mCommentRecycler != null && videoScrollListener != null) {
            mCommentRecycler.removeOnScrollListener(videoScrollListener);
            videoScrollListener = null;
        }
        // Individual players are released by CommentHolder's onViewRecycled/onViewDetachedFromWindow.
        // Global pause for fragment destruction is handled by BrowserExpressCommentsBottomSheetFragment's lifecycle.
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
    }

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments, Comment parentComment, Comment grandParentComment) {
                    int len = mComments.size();
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());

                    if(parentComment != null){
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
                public void addCommentSuccessful(Comment comment, String newAccessToken, String newRefreshToken) {
                    mComments.add(0, comment);
                    mCommentAdapter.notifyItemRangeInserted(0, 1);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(0, 0);

                    if(newRefreshToken != null && !newRefreshToken.isEmpty()){
                        try {
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            activity.setAccessToken(newAccessToken);

                            mMessageEditText.clearFocus();
                            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(mMessageEditText.getWindowToken(), 0);

                            JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                            Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            intent.setAction(Intent.ACTION_VIEW);
                            Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " created. You can edit this in Profile.", Toast.LENGTH_SHORT).show();
                            startActivity(intent);
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                        } catch (JSONException e) {
                        }
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
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
        
    }
}
