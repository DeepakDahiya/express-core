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
import android.content.Intent;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.base.ContextUtils;
import android.graphics.Rect;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;

public class CommentListFragment extends Fragment {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String OPEN_KEYBOARD = "open_keyboard";
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mComments;
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private Boolean mOpenKeyboard = false;

    private LinearLayoutManager mLayoutManager;

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

    private BottomSheetInputCallback inputCallback;

    private RecyclerView.OnScrollListener videoScrollListener;

    private boolean mShouldScrollToLastParent = false;
    private String mTargetScrollCommentId = null;

    private static final String KEY_SCROLL_POSITION = "comment_list_scroll_position";
    private int mSavedScrollPosition = RecyclerView.NO_POSITION; // Or 0 as default

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mSavedScrollPosition = savedInstanceState.getInt(KEY_SCROLL_POSITION, RecyclerView.NO_POSITION);
        }
    }

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
        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        if (parentFragment != null) {
            String targetId = parentFragment.getLastOpenedRepliesForCommentId();
            if (targetId != null && mComments != null && !mComments.isEmpty()) {
                scrollToCommentId(targetId);
                parentFragment.clearLastOpenedRepliesForCommentId();
            } else if (targetId != null) {
                mShouldScrollToLastParent = true;
                mTargetScrollCommentId = targetId;
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mLayoutManager != null && mCommentRecycler != null) {
            int currentPosition = mLayoutManager.findFirstVisibleItemPosition();
            if (currentPosition != RecyclerView.NO_POSITION) {
                outState.putInt(KEY_SCROLL_POSITION, currentPosition);
                Log.d("ScrollSave", "Saving scroll position: " + currentPosition);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_comment_list, container, false);

        if (getArguments() != null) {
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
            mOpenKeyboard = getArguments().getBoolean(OPEN_KEYBOARD);
        }

        mMessageEditText = inputCallback.getInputEditText();
        mSendButton = inputCallback.getSendButton();

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
        mLayoutManager = new LinearLayoutManager(requireContext());
        mCommentRecycler.setLayoutManager(mLayoutManager);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        
        boolean isReplyAdapter = false;
        mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mMessageEditText, mCommentRecycler, parentFragment, isReplyAdapter, false, false);
        mCommentRecycler.setAdapter(mCommentAdapter);

        this.setOnClickForEmoji(inputCallback.getEmojiButton("lol"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("heart"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("cry"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("fire"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("love"), mMessageEditText);
        this.setOnClickForEmoji(inputCallback.getEmojiButton("clap"), mMessageEditText);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();
            mCommentsText = activity.getCommentCountText();
            if(accessToken != null){
                Context context = ContextUtils.getApplicationContext();
                SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                String avatar = prefs.getString("avatar_url", null);
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
                if (avatar != null) {
                    inputCallback.updateAvatar(avatar, activity);
                }else{
                    inputCallback.updateAvatar("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", activity);
                }
            }

            if(mOpenKeyboard){
                mMessageEditText.requestFocus();
                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(mMessageEditText, InputMethodManager.SHOW_IMPLICIT);
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
                            // if (accessToken == null) {
                            //     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                            //     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                            //     activity.showGenerateUsernameBottomSheet();
                            //     return;
                            // }
                            String content = mMessageEditText.getText().toString().trim();
                            Uri mediaUri = null;
                            if (inputCallback != null) {
                                mediaUri = inputCallback.getSelectedMediaUri();
                            }

                            if (content.length() > 0 || mediaUri != null) {
                                String pType = "page";
                                String pId = null;
                                if (mCommentsFor.equals("post")) {
                                    pType = "post";
                                    pId = mPostId;
                                }
                                BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                    new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                            content, pType, mUrl, pId, accessToken, addCommentCallback);
                                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                mMessageEditText.setText(R.string.browser_express_empty_text);
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
        } catch(Exception ex){
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
        if (mCommentRecycler != null && videoScrollListener != null) {
            mCommentRecycler.removeOnScrollListener(videoScrollListener);
            videoScrollListener = null;
        }

        if (mCommentAdapter != null) {
            CommentListAdapter.VideoPlaybackManager manager = mCommentAdapter.getVideoPlaybackManager();
            if (manager != null) {
                manager.releaseAllResources();
            }
        }
        mCommentAdapter = null;
        mCommentRecycler = null;

        if (mLayoutManager != null) {
            int currentPosition = mLayoutManager.findFirstVisibleItemPosition();
            if (currentPosition != RecyclerView.NO_POSITION) {
                mSavedScrollPosition = currentPosition; // Save to member variable
                Log.d("ScrollSave", "onDestroyView - Saving scroll position to member: " + mSavedScrollPosition);
            }
        }

        mLayoutManager = null;
    }

    public void pauseAllVideosInList() {
        if (mCommentAdapter != null) {
            CommentListAdapter.VideoPlaybackManager manager = mCommentAdapter.getVideoPlaybackManager();
            if (manager != null) {
                manager.pauseAllPlayers();
            }
        }
    }

    public void releaseVideoManagerResources() { // Renamed for clarity from previous suggestion
        if (mCommentAdapter != null) {
            CommentListAdapter.VideoPlaybackManager manager = mCommentAdapter.getVideoPlaybackManager();
            if (manager != null) {
                manager.releaseAllResources();
            }
        }
    }

    public static CommentListFragment newInstance(String postId, String commentsFor, Boolean openKeyboard) {
        CommentListFragment fragment = new CommentListFragment();
        Bundle args = new Bundle();
        args.putString(COMMENTS_FOR, commentsFor);
        args.putString(POST_ID, postId);
        args.putBoolean(OPEN_KEYBOARD, openKeyboard);
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
    }

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments, Comment parentComment, Comment grandParentComment) {
                    int len = mComments.size();
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len, comments.size());
                    mShimmerLoading.setVisibility(View.GONE);
                    AndroidUtils.gone(mShimmerItems);
                    mShimmerLoading.hideShimmer();

                    if (mSavedScrollPosition != RecyclerView.NO_POSITION) {
                        mLayoutManager.scrollToPositionWithOffset(mSavedScrollPosition, 0);
                        mSavedScrollPosition = RecyclerView.NO_POSITION;
                    }

                    Log.e("CommentListScroll", "mSavedScrollPosition: " + mSavedScrollPosition);

                    if (mShouldScrollToLastParent && mTargetScrollCommentId != null) {
                        Log.e("CommentListScroll", "Scrolling to last parent comment ID: " + mTargetScrollCommentId);
                        scrollToCommentId(mTargetScrollCommentId);
                        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
                        if (parentFragment != null) {
                            parentFragment.clearLastOpenedRepliesForCommentId();
                        }
                        mShouldScrollToLastParent = false; // Reset flag
                        mTargetScrollCommentId = null;
                    }
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
                        // Updating comment count for bottom toolbar
                        mCommentsText = activity.getCommentCountText();

                        mMessageEditText.clearFocus();
                        InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mMessageEditText.getWindowToken(), 0);

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
                        if(newRefreshToken != null && !newRefreshToken.isEmpty()){
                            activity.setAccessToken(newAccessToken);
                            JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                            Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            intent.setAction(Intent.ACTION_VIEW);
                            Toast.makeText(activity, "Username " + decodedAccessTokenObj.getString("username") + " created. You can edit this in Profile.", Toast.LENGTH_SHORT).show();
                            startActivity(intent);
                        }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        // Log.e("Express Browser Access Token", e.getMessage());
                    } catch (JSONException e) {
                    }
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    private void scrollToCommentId(String commentId) {
        Log.e("CommentListScroll", "Scrolling to comment ID: " + commentId);
        if (commentId == null || mComments == null || mComments.isEmpty() || mCommentRecycler == null || mLayoutManager == null) {
            return;
        }

        Log.e("CommentListScroll", "mComments size: " + mComments.size());

        int position = -1;
        for (int i = 0; i < mComments.size(); i++) {
            if (mComments.get(i).getId().equals(commentId)) {
                position = i;
                break;
            }
        }

        Log.e("CommentListScroll", "Found comment ID at position: " + position);

        if (position != -1) {
            final int finalPosition = position;
            mLayoutManager.scrollToPositionWithOffset(finalPosition, 0);
            View itemView = mLayoutManager.findViewByPosition(finalPosition);
            if (itemView != null) {
                itemView.setBackgroundColor(Color.YELLOW); // Example highlight
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    itemView.setBackgroundColor(Color.TRANSPARENT); // Or original color
                }, 1000);
            }
        } else {
            Log.w("CommentListScroll", "Comment ID not found in list: " + commentId);
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
            // Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            // Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
    }
}
