package org.chromium.chrome.browser.browser_express_comments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import android.graphics.Rect;
import androidx.core.widget.NestedScrollView;
import android.net.Uri;

public class ReplyListFragment2 extends Fragment {
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

    private LinearLayout mParentCommentLayout;
    private ImageView mArrow2;

    private ImageView mBackButton;

    private Button mLolButton;
    private Button mHeartButton;
    private Button mCryButton;
    private Button mFireButton;
    private Button mLoveButton;
    private Button mClapButton;

    private LinearLayout mEmptyContainer;

    private BottomSheetInputCallback inputCallback;

    private android.content.BroadcastReceiver mUploadReceiver;

    private NestedScrollView mNestedScrollView;
    private NestedScrollView.OnScrollChangeListener videoNestedScrollListener;
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

    @Override
    public void onResume() {
        super.onResume();
        registerUploadReceiver();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mUploadReceiver != null && getContext() != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mUploadReceiver);
        }
    }

    private void registerUploadReceiver() {
        if (getContext() == null) return;
        mUploadReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UploadService.BROADCAST_UPLOAD_COMPLETE.equals(action)) {
                    String tempId = intent.getStringExtra(UploadService.EXTRA_TEMP_ID);
                    String realCommentJson = intent.getStringExtra(UploadService.EXTRA_REAL_COMMENT_JSON);
                    Comment realComment = new com.google.gson.Gson().fromJson(realCommentJson, Comment.class);
                    updateTemporaryComment(tempId, realComment);
                } else if (UploadService.BROADCAST_UPLOAD_FAILED.equals(action)) {
                    String tempId = intent.getStringExtra(UploadService.EXTRA_TEMP_ID);
                    markCommentAsFailed(tempId);
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(UploadService.BROADCAST_UPLOAD_COMPLETE);
        filter.addAction(UploadService.BROADCAST_UPLOAD_FAILED);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext()).registerReceiver(mUploadReceiver, filter);
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
        mArrow2 = view.findViewById(R.id.comment_arrow2);

        mEmptyContainer = view.findViewById(R.id.empty_container);

        mNestedScrollView = view.findViewById(R.id.reply_list_nested_scroll_view);
        
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
        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mMessageEditText, mCommentRecycler, null, isReplyAdapter, false, true);
        mCommentRecycler.setAdapter(mCommentAdapter);

        mParentCommentLayout.setVisibility(View.VISIBLE);
        mArrow2.setVisibility(View.VISIBLE);

        mTopComments = new ArrayList<Comment>();
        mTopCommentRecycler = (RecyclerView) view.findViewById(R.id.top_comment_recycler);
        mTopCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mTopCommentAdapter = new CommentListAdapter(requireContext(), mTopComments, mMessageEditText, mTopCommentRecycler, null, isReplyAdapter, true, true);
        mTopCommentRecycler.setAdapter(mTopCommentAdapter);

        this.setOnClickForEmoji(inputCallback.getEmojiButton("lol"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("heart"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("cry"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("fire"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("love"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("clap"), mMessageEditText);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        mEmptyContainer.getLayoutParams().height = (int)(screenHeight * 0.7);

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
                            Uri mediaUri = null;
                            String mediaType = null;
                            if (inputCallback != null) {
                                mediaUri = inputCallback.getSelectedMediaUri();
                                mediaType = inputCallback.getSelectedMediaType();
                            }

                            if (content.length() > 0 || mediaUri != null) {
                                BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                    new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                            content, "comment", mUrl, mCommentId, mediaUri, mediaType, accessToken, addCommentCallback);
                                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                mMessageEditText.setText(R.string.browser_express_empty_text);
                                if (inputCallback != null) {
                                    inputCallback.clearSelectedMedia();
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

        setupVideoScrollListener(mCommentRecycler);
        setupVideoScrollListenerForNestedView(mNestedScrollView);
        return view;
    }

    private void setupVideoScrollListenerForNestedView(NestedScrollView nestedScrollView) {
        if (nestedScrollView == null) return; // Guard against null NestedScrollView

        // nestedScrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) null); // Do this in onDestroyView

        videoNestedScrollListener = new NestedScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(@NonNull NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (mTopCommentAdapter != null && mTopCommentRecycler != null) {
                    checkAndPauseInvisibleVideosInSpecificAdapter(mTopCommentAdapter, v);
                }
                // Check videos in mCommentRecycler (replies)
                if (mCommentAdapter != null && mCommentRecycler != null) {
                    checkAndPauseInvisibleVideosInSpecificAdapter(mCommentAdapter, v);
                }
            }
        };
        nestedScrollView.setOnScrollChangeListener(videoNestedScrollListener);
    }

    private void checkAndPauseInvisibleVideosInSpecificAdapter(CommentListAdapter adapter, View parentScrollableView) {
        if (adapter == null) return;
        CommentListAdapter.VideoPlaybackManager manager = adapter.getVideoPlaybackManager();
        CommentListAdapter.CommentHolder currentPlayingHolder = manager.getCurrentlyPlayingHolder();

        if (currentPlayingHolder != null && currentPlayingHolder.player != null && currentPlayingHolder.player.isPlaying()) {
            if (currentPlayingHolder.commentVideo != null && !isViewMostlyVisible(currentPlayingHolder.commentVideo, parentScrollableView)) {
                manager.pauseCurrentlyPlayingVideo();
            }
        }
    }

    private boolean isViewMostlyVisible(View childView, View parentScrollableView) {
        if (childView == null || !childView.isShown() || childView.getHeight() == 0 || childView.getWidth() == 0) {
            return false;
        }
        if (parentScrollableView == null) return false; // Added guard for parent

        Rect viewRect = new Rect();
        if (!childView.getGlobalVisibleRect(viewRect)) {
            return false;
        }

        Rect parentRect = new Rect();
        parentScrollableView.getGlobalVisibleRect(parentRect);

        if (!Rect.intersects(viewRect, parentRect)) {
            return false;
        }

        int visibleHeight = Math.min(viewRect.bottom, parentRect.bottom) - Math.max(viewRect.top, parentRect.top);
        float visibilityThreshold = 0.5f;
        return visibleHeight >= childView.getHeight() * visibilityThreshold;
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
        if (mCommentAdapter == null) return;
        CommentListAdapter.VideoPlaybackManager manager = mCommentAdapter.getVideoPlaybackManager();
        CommentListAdapter.CommentHolder currentPlayingHolder = manager.getCurrentlyPlayingHolder();

        if (currentPlayingHolder != null && currentPlayingHolder.player != null && currentPlayingHolder.player.isPlaying()) {
            
            LinearLayoutManager layoutManager = null;
            if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                 layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            }
            if (layoutManager == null) return;

            int holderPosition = currentPlayingHolder.getBindingAdapterPosition();
            if (holderPosition == RecyclerView.NO_POSITION) {
                manager.pauseCurrentlyPlayingVideo();
                return;
            }

            int firstVisible = layoutManager.findFirstVisibleItemPosition();
            int lastVisible = layoutManager.findLastVisibleItemPosition();

            if (holderPosition < firstVisible || holderPosition > lastVisible) {
                Log.d("VideoScroll", "Pausing video (holder fully out of view): " + holderPosition);
                manager.pauseCurrentlyPlayingVideo();
            } else {
                // Holder is in visible range, check how much of the video view itself is visible
                if (currentPlayingHolder.commentVideo != null && !isViewMostlyVisible(currentPlayingHolder.commentVideo, recyclerView)) {
                    Log.d("VideoScroll", "Pausing video (partially out of view): " + holderPosition);
                    manager.pauseCurrentlyPlayingVideo();
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
        if (mNestedScrollView != null) {
            mNestedScrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) null);
        }
        videoNestedScrollListener = null;
        mNestedScrollView = null;

        // Release resources for all adapters
        if (mCommentAdapter != null && mCommentAdapter.getVideoPlaybackManager() != null) {
            mCommentAdapter.getVideoPlaybackManager().releaseAllResources();
        }
        if (mTopCommentAdapter != null && mTopCommentAdapter.getVideoPlaybackManager() != null) {
            mTopCommentAdapter.getVideoPlaybackManager().releaseAllResources();
        }
        mCommentAdapter = null;
        mCommentRecycler = null;
        mTopCommentAdapter = null;
        mTopCommentRecycler = null;
    }

    public void pauseAllVideosInList() {
        if (mCommentAdapter != null && mCommentAdapter.getVideoPlaybackManager() != null) {
            mCommentAdapter.getVideoPlaybackManager().pauseAllPlayers();
        }
        if (mTopCommentAdapter != null && mTopCommentAdapter.getVideoPlaybackManager() != null) {
            mTopCommentAdapter.getVideoPlaybackManager().pauseAllPlayers();
        }
    }

    public void releaseVideoManagerResources() { // Renamed for clarity from previous suggestion
        if (mCommentAdapter != null && mCommentAdapter.getVideoPlaybackManager() != null) {
            mCommentAdapter.getVideoPlaybackManager().releaseAllResources();
        }
        if (mTopCommentAdapter != null && mTopCommentAdapter.getVideoPlaybackManager() != null) {
            mTopCommentAdapter.getVideoPlaybackManager().releaseAllResources();
        }
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
                    mCommentAdapter.notifyItemRangeInserted(len, comments.size());

                    if(parentComment != null){
                        mTopComments.add(parentComment);
                        mTopCommentAdapter.notifyItemRangeInserted(0, 1);
                        inputCallback.setPostStuff(parentComment.getId(), parentComment.getUser().getUsername(), parentComment.getContent(), parentComment.getUser().getAvatar(), "comment");
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

                    try{
                        BraveActivity activity = BraveActivity.getBraveActivity();

                        mMessageEditText.clearFocus();
                        InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mMessageEditText.getWindowToken(), 0);


                        if(newRefreshToken != null && !newRefreshToken.isEmpty()){
                            try {
                                activity.setAccessToken(newAccessToken);

                                JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                                Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                                intent.setAction(Intent.ACTION_VIEW);
                                Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " created. You can edit this in Profile.", Toast.LENGTH_SHORT).show();
                                startActivity(intent);
                            } catch (JSONException e) {
                            }
                        }

                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    public void addNewComment(Comment newComment) {
        if (mComments != null && mCommentAdapter != null && mCommentRecycler != null) {
            mComments.add(0, newComment);

            mCommentAdapter.notifyItemRangeInserted(0, 1);
            LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
            layoutManager.scrollToPositionWithOffset(0, 0);

            try{
                BraveActivity activity = BraveActivity.getBraveActivity();
                // Updating comment count for bottom toolbar
                mCommentsText = activity.getCommentCountText();

                mMessageEditText.clearFocus();
                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(mMessageEditText.getWindowToken(), 0);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                // Log.e("Express Browser Access Token", e.getMessage());
            }
        }
    }

    public void updateTemporaryComment(String tempId, Comment realComment) {
        if (mComments == null || mCommentAdapter == null) return;
        for (int i = 0; i < mComments.size(); i++) {
            if (mComments.get(i).getId().equals(tempId)) {
                mComments.set(i, realComment);
                mCommentAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    public void markCommentAsFailed(String tempId) {
        if (mComments == null || mCommentAdapter == null) return;
        for (int i = 0; i < mComments.size(); i++) {
            if (mComments.get(i).getId().equals(tempId)) {
                mComments.get(i).setUploadStatus(Comment.UploadStatus.FAILED);
                mCommentAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

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
