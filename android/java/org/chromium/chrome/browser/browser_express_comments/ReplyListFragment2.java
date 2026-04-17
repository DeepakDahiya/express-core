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
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import android.content.Intent;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import android.graphics.Rect;
import androidx.core.widget.NestedScrollView;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;

public class ReplyListFragment2 extends Fragment {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String COMMENT_ID = "comment_id";
    public static final String PAGE_URL = "page_url";
    public static final String PARENT_COMMENT_JSON = "parent_comment_json";
    public static final String L1_ANCESTOR_JSON = "l1_ancestor_json";
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mCombinedList;
    private static final int mPage = 1;
    private static final int mPerPage = 100;
    private String mUrl;

    private String mCommentId;
    private String mCommentsFor;
    private String mPageUrl;
    private Comment mParentReply;
    private Comment mL1Ancestor;

    private ShimmerFrameLayout mShimmerLoading;
    private ViewGroup mShimmerItems;

    private Button mSendButton;
    private EditText mMessageEditText;

    private ImageView mBackButton;

    private LinearLayout mEmptyContainer;

    private BottomSheetInputCallback inputCallback;

    private LinearLayoutManager mLayoutManager;

    private android.content.BroadcastReceiver mUploadReceiver;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVideoCheckRunnable = this::checkAndPlayMostVisibleVideo;

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
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
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
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPageUrl = getArguments().getString(PAGE_URL);
            String parentJson = getArguments().getString(PARENT_COMMENT_JSON);
            if (parentJson != null) {
                mParentReply = new com.google.gson.Gson().fromJson(parentJson, Comment.class);
            }
            String l1Json = getArguments().getString(L1_ANCESTOR_JSON);
            if (l1Json != null) {
                mL1Ancestor = new com.google.gson.Gson().fromJson(l1Json, Comment.class);
            }
        }

        mMessageEditText = inputCallback.getInputEditText();
        mSendButton = inputCallback.getSendButton();

        mEmptyContainer = view.findViewById(R.id.empty_container);

        BrowserExpressCommentsBottomSheetFragment parentFragment = (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
        
        mShimmerLoading = view.findViewById(R.id.skeleton_shimmer);
        mShimmerItems = view.findViewById(R.id.shimmer_items);
        int shimmerSkeletonRows =
                AndroidUtils.getSkeletonRowCount(ViewUtils.dpToPx(requireContext(), 50));
        for (int i = 0; i < shimmerSkeletonRows; i++) {
            inflater.inflate(R.layout.shimmer_skeleton_item, mShimmerItems, true);
        }

        mShimmerLoading.showShimmer(true);
        AndroidUtils.show(mShimmerItems);

        mCombinedList = new ArrayList<Comment>();

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
        mLayoutManager = new LinearLayoutManager(requireContext());
        mCommentRecycler.setLayoutManager(mLayoutManager);

        mCommentAdapter = new CommentListAdapter(requireContext(), mCombinedList, mMessageEditText, parentFragment, true, false);
        mCommentRecycler.setAdapter(mCommentAdapter);

        mBackButton = view.findViewById(R.id.back_button);
        mBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                parentFragment.hideKeyboard();
                parentFragment.openComments(false);
            }
        });

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

            mSendButton.setOnClickListener(new View.OnClickListener() {
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
                                String pInfo = activity.getCurrentAppVersion();
                                JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                                JSONObject payload = new JSONObject();
                                try {
                                    payload.put("app_version", pInfo);
                                    payload.put("content", content);
                                    payload.put("type", "comment");
                                    payload.put("url", mUrl);
                                    payload.put("comment_id", mCommentId);
                                    if (mediaUri != null) {
                                        payload.put("media_type", mediaType);
                                    }

                                    PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                                        new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.COMMENTED, decodedAccessTokenObj.getString("_id"), payload);
                                    postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                } catch (JSONException e) {
                                }

                                final String finalContent = content;
                                final String finalAccessToken = accessToken;
                                final Uri finalMediaUri = mediaUri;
                                final String finalMediaType = mediaType;

                                // For YouTube pages where the L2 parent reply has not been
                                // registered in our DB (its _id is the YouTube comment id, not
                                // a Mongo ObjectId), use yt_interact to register the [L1, L2]
                                // ancestor chain and create the L3 reply atomically.
                                if ("youtube".equals(mCommentsFor) && mParentReply != null
                                        && mParentReply.isYouTubeOnly() && mL1Ancestor != null
                                        && mL1Ancestor.getYoutubeId() != null) {
                                    java.util.List<Comment> ancestors = new java.util.ArrayList<>();
                                    ancestors.add(mL1Ancestor);
                                    ancestors.add(mParentReply);
                                    new YouTubeInteractUtil.Task(mPageUrl, ancestors, "reply", finalContent, finalAccessToken,
                                            new YouTubeInteractUtil.Callback() {
                                                @Override
                                                public void onSuccess(java.util.Map<String, String> resolvedIds, String targetId,
                                                        int upvoteCount, int downvoteCount, int commentCount, Vote didVote) {
                                                    if (resolvedIds != null) {
                                                        if (mL1Ancestor.getYoutubeId() != null
                                                                && resolvedIds.containsKey(mL1Ancestor.getYoutubeId())) {
                                                            mL1Ancestor.setId(resolvedIds.get(mL1Ancestor.getYoutubeId()));
                                                        }
                                                        if (mParentReply.getYoutubeId() != null
                                                                && resolvedIds.containsKey(mParentReply.getYoutubeId())) {
                                                            mParentReply.setId(resolvedIds.get(mParentReply.getYoutubeId()));
                                                            mCommentId = mParentReply.getId();
                                                        }
                                                    }
                                                    org.chromium.ui.widget.Toast.makeText(
                                                            getContext(), "Reply posted!", android.widget.Toast.LENGTH_SHORT).show();
                                                }
                                                @Override
                                                public void onFailure(String error) {
                                                    org.chromium.ui.widget.Toast.makeText(
                                                            getContext(), "Reply failed: " + error, android.widget.Toast.LENGTH_SHORT).show();
                                                }
                                            }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                } else {
                                    String parentIdForCreate = (mParentReply != null && !mParentReply.isYouTubeOnly())
                                            ? mParentReply.getId()
                                            : mCommentId;
                                    BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                        new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                finalContent, "comment", mUrl, parentIdForCreate, finalMediaUri, finalMediaType, finalAccessToken, addCommentCallback);
                                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                }
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
            });

            // Getting replies. L3 replies are always native (YouTube has no nested replies),
            // so we only need to fetch from our backend via /v1/comment with the parent's
            // DB _id. If the L2 parent is YT-only (unregistered), it has no DB record and
            // therefore no replies — skip the call to avoid a Mongo ObjectId cast failure.
            boolean parentIsRegistered = mParentReply == null
                    || !mParentReply.isYouTubeOnly();
            if (parentIsRegistered) {
                String parentDbId = (mParentReply != null && !mParentReply.isYouTubeOnly())
                        ? mParentReply.getId()
                        : mCommentId;
                Log.e("YouTubeComments", "[L3] Fetching replies via /v1/comment commentParent=" + parentDbId);
                BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                    new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                            null, parentDbId, null, mPage, mPerPage, accessToken, getCommentsCallback);
                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                Log.e("YouTubeComments", "[L3] Parent is YT-only (unregistered) — no backend replies to fetch");
                getCommentsCallback.getCommentsSuccessful(new ArrayList<>(), mParentReply, null);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            Log.e("Express Browser Access Token", ex.getMessage());
        }

        setupScrollListener();
        return view;
    }

    private void setupScrollListener() {
        if (mCommentRecycler != null) {
            mCommentRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        scheduleVideoCheck();
                    }
                }
            });
        }
    }
    
    private void scheduleVideoCheck() {
        mHandler.removeCallbacks(mVideoCheckRunnable);
        mHandler.postDelayed(mVideoCheckRunnable, 150);
    }

    private void checkAndPlayMostVisibleVideo() {
        if (mCommentRecycler == null || mLayoutManager == null) return;

        int firstVisible = mLayoutManager.findFirstVisibleItemPosition();
        int lastVisible = mLayoutManager.findLastVisibleItemPosition();

        if (firstVisible == RecyclerView.NO_POSITION) return;
        
        CommentListAdapter.CommentHolder bestHolder = null;
        float bestVisibilityPercentage = 0f;
        
        for (int i = firstVisible; i <= lastVisible; i++) {
            RecyclerView.ViewHolder vh = mCommentRecycler.findViewHolderForAdapterPosition(i);
            if (vh instanceof CommentListAdapter.CommentHolder) {
                CommentListAdapter.CommentHolder holder = (CommentListAdapter.CommentHolder) vh;
                if (holder.hasVideo()) {
                    float visibility = getVisibilityPercentage(holder.commentVideo);
                    if (visibility > bestVisibilityPercentage && visibility > 0.6f) { // 60% visibility threshold
                        bestVisibilityPercentage = visibility;
                        bestHolder = holder;
                    }
                }
            }
        }
        
        GlobalVideoPlaybackManager.getInstance().playVideo(bestHolder);
    }

    private float getVisibilityPercentage(View view) {
        if (view == null) return 0f;
        
        Rect rect = new Rect();
        boolean isVisible = view.getGlobalVisibleRect(rect);
        
        if (!isVisible) return 0f;
        
        int visibleArea = rect.width() * rect.height();
        int totalArea = view.getWidth() * view.getHeight();
        
        return totalArea > 0 ? (float) visibleArea / totalArea : 0f;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacks(mVideoCheckRunnable);
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();

        mHandler.removeCallbacksAndMessages(null);

        mCommentAdapter = null;
        mCommentRecycler = null;
    }

    private void setOnClickForEmoji(Button emojiButton, EditText editText){ {
        emojiButton.setOnClickListener(new View.OnClickListener() {
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
            });
        }
    }

    private final BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments, Comment parentComment, Comment grandParentComment) {
                    mCombinedList.clear();
                    Comment effectiveParent = parentComment != null ? parentComment : mParentReply;
                    if (effectiveParent != null) {
                        mCombinedList.add(effectiveParent);
                        String displayName = effectiveParent.getUser() != null
                                ? effectiveParent.getUser().getUsername()
                                : effectiveParent.getYoutubeAuthorName();
                        String displayAvatar = effectiveParent.getUser() != null
                                ? effectiveParent.getUser().getAvatar()
                                : effectiveParent.getYoutubeAvatarUrl();
                        inputCallback.setPostStuff(effectiveParent.getId(), displayName, effectiveParent.getContent(), displayAvatar, "comment");
                    }
                    mCombinedList.addAll(comments); // Add all replies

                    mCommentAdapter.notifyItemRangeInserted(0, comments.size());

                    mShimmerLoading.setVisibility(View.GONE);
                    AndroidUtils.gone(mShimmerItems);
                    mShimmerLoading.hideShimmer();
                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    private final BrowserExpressAddCommentUtil.AddCommentCallback addCommentCallback=
            new BrowserExpressAddCommentUtil.AddCommentCallback() {
                @Override
                public void addCommentSuccessful(Comment comment, String newAccessToken, String newRefreshToken) {
                    mCombinedList.add(1, comment);
                    mCommentAdapter.notifyItemInserted(1);
                    scrollNewReplyIntoView();
                    
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
        if (mCombinedList != null && mCommentAdapter != null && mCommentRecycler != null) {
            mCombinedList.add(1, newComment);
            mCommentAdapter.notifyItemInserted(1);
            scrollNewReplyIntoView();

            try{
                BraveActivity activity = BraveActivity.getBraveActivity();
                mMessageEditText.clearFocus();
                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(mMessageEditText.getWindowToken(), 0);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                // Log.e("Express Browser Access Token", e.getMessage());
            }
        }
    }

    /**
     * Defers the scroll to the next frame so the RecyclerView lays out the just-inserted
     * reply before we scroll to it. Targets position 1 (the new reply) so it lands as the
     * topmost visible row, with the pinned parent staying just above.
     */
    private void scrollNewReplyIntoView() {
        if (mCommentRecycler == null || mLayoutManager == null) return;
        mCommentRecycler.post(() -> {
            if (mLayoutManager != null) {
                mLayoutManager.scrollToPositionWithOffset(1, 0);
            }
        });
    }

    public void updateTemporaryComment(String tempId, Comment realComment) {
        if (mCombinedList == null || mCommentAdapter == null) return;
        for (int i = 0; i < mCombinedList.size(); i++) {
            if (mCombinedList.get(i).getId().equals(tempId)) {
                mCombinedList.set(i, realComment);
                mCommentAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    public void markCommentAsFailed(String tempId) {
        if (mCombinedList == null || mCommentAdapter == null) return;
        for (int i = 0; i < mCombinedList.size(); i++) {
            if (mCombinedList.get(i).getId().equals(tempId)) {
                mCombinedList.get(i).setUploadStatus(Comment.UploadStatus.FAILED);
                mCommentAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    public void triggerVideoVisibilityCheck() {
        scheduleVideoCheck();
    }

    private JSONObject getDecodedToken(String accessToken){
        if (accessToken == null || accessToken.isEmpty()) return null;
        try{
            String[] split_string = accessToken.split("\\.");
            if (split_string.length < 2) {
                Log.e("TokenDecoder", "Invalid JWT format");
                return null;
            }
            String base64EncodedBody = split_string[1];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e("TokenDecoder", "JSON parsing error: " + e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("TokenDecoder", "Encoding error: " + e.getMessage());
            return null;
        }catch(Exception e){
            Log.e("TokenDecoder", "Unexpected error: " + e.getMessage());
            return null;
        }
        
    }
}
