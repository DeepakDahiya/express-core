/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.app.Dialog;
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
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.EditText;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.FragmentActivity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.app.Activity;
import android.net.Uri;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;
import android.content.Intent;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.content.ContentResolver;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.chromium.base.ContextUtils;
import androidx.fragment.app.DialogFragment;
import android.media.MediaMetadataRetriever;
import org.chromium.chrome.browser.ChromeTabbedActivity;

import android.view.HapticFeedbackConstants;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class BrowserExpressReplyWithAttachmentBottomSheetFragment extends DialogFragment implements MediaViewerFragment.OnViewerDismissedListener {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String POST_USERNAME = "post_username";
    public static final String POST_CONTENT = "post_content";
    public static final String POST_AVATAR_URL = "post_avatar_url";
    public static final String ATTACHMENT_URI = "attachment_uri";
    private static final int MAX_IMAGE_DIMENSION = 1920;
    private static final int IMAGE_COMPRESSION_QUALITY = 80;
    private static final String BE_PROFILE_PREF = "BE_PROFILE_PREFS";

    private Boolean mIsCommentPage = false;

    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private String mPostAvatarString;
    private String mPostUsernameString;
    private String mPostContentString;

    private Button mCancelButton;
    private Button mPostButton;

    private ImageView mPostAvatar;
    private TextView mPostUsername;
    private TextView mPostContent;

    private ProgressBar mCommentProgress;

    private ImageButton mAttachButton;
    private FrameLayout mAttachmentPreviewContainer;
    private ImageView mAttachmentPreviewImage;
    private ImageButton mRemoveAttachmentButton;
    private ImageView mVideoPlayButton;
    private Uri mSelectedMediaUri;
    private String mSelectedMediaType;

    private Uri mTempSelectedMediaUri;

    private ImageView mAvatarImage;
    private EditText mMessageEditText;

    private boolean isFromMenu;

    private String mLastOpenedRepliesForCommentId = null;
    private String mLastOpenedRepliesToRepliesForCommentId = null;

    private ActivityResultLauncher<String[]> mMediaPickerLauncher;

    public static BrowserExpressReplyWithAttachmentBottomSheetFragment newInstance(boolean isFromMenu) {
        final BrowserExpressReplyWithAttachmentBottomSheetFragment fragment =
                new BrowserExpressReplyWithAttachmentBottomSheetFragment();
        final Bundle args = new Bundle();
        args.putBoolean(IS_FROM_MENU, isFromMenu);
        fragment.setArguments(args);
        return fragment;
    }

    public interface OnCommentPostedListener {
        void onCommentPostRequested(String postId, String type, Comment optimisticComment, Uri mediaUri, String mediaType, String accessToken);
        void onCommentPostSucceeded(String tempId, Comment realComment);
        void onCommentPostFailed(String tempId, String errorMessage);
    }

    private OnCommentPostedListener mCommentPostedListener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Prioritize the target fragment, which is the reliable way to get the listener.
        if (getTargetFragment() instanceof OnCommentPostedListener) {
            mCommentPostedListener = (OnCommentPostedListener) getTargetFragment();
        } 
        // Fallback to parent, though targetFragment is preferred.
        else if (getParentFragment() instanceof OnCommentPostedListener) {
            mCommentPostedListener = (OnCommentPostedListener) getParentFragment();
        } 
        // Final fallback to the hosting activity.
        else if (context instanceof OnCommentPostedListener) {
            mCommentPostedListener = (OnCommentPostedListener) context;
        } 
        else {
            Log.e("ReplyWithAttachment", "The host fragment or activity must implement OnCommentPostedListener");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.AppSetDefaultBottomSheetDialogTheme);

        if (getArguments() != null) {
            isFromMenu = getArguments().getBoolean(IS_FROM_MENU);
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
            mPostUsernameString = getArguments().getString(POST_USERNAME);
            mPostContentString = getArguments().getString(POST_CONTENT);
            mPostAvatarString = getArguments().getString(POST_AVATAR_URL);
            mTempSelectedMediaUri = getArguments().getParcelable(ATTACHMENT_URI);

            mMediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        // Grant persistent read permissions for the service if needed.
                        try {
                            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                            if (getContext() != null) {
                                getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                            }
                        } catch (SecurityException e) {
                            Log.e("ReplySheet", "Failed to take persistent URI permission", e);
                        }
                        
                        // The logic from your old onActivityResult goes here.
                        processSelectedMedia(uri);
                        showKeyboardWithFocus();
                    }
                }
            );
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseAllVideoPlaybackInActiveLists();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_reply_with_attachment, container, false);

        mMessageEditText = view.findViewById(R.id.comment_content_input);
        mAvatarImage = view.findViewById(R.id.avatar_image);

        mPostAvatar = view.findViewById(R.id.post_avatar);
        mPostUsername = view.findViewById(R.id.post_username);
        mPostContent = view.findViewById(R.id.post_content);

        mCancelButton = view.findViewById(R.id.cancel_button);
        mPostButton = view.findViewById(R.id.post_button);

        mAttachButton = view.findViewById(R.id.button_attach);
        mAttachmentPreviewContainer = view.findViewById(R.id.media_preview_container);
        mAttachmentPreviewImage = view.findViewById(R.id.attachment_preview_image);
        mRemoveAttachmentButton = view.findViewById(R.id.button_remove_attachment);
        mVideoPlayButton = view.findViewById(R.id.video_play_button);

        setupAttachmentListeners();

        showKeyboardWithFocus();

        if (mTempSelectedMediaUri != null) {
            processSelectedMedia(mTempSelectedMediaUri);
        } else {
            mAttachmentPreviewContainer.setVisibility(View.GONE);
            mRemoveAttachmentButton.setVisibility(View.GONE);
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        try{
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null) {
                Log.e("BottomSheetFragment", "BraveActivity is null in onViewCreated.");
                return;
            }
            if(mPostAvatar != null && mPostAvatarString != null && !mPostAvatarString.isEmpty()) {
                ImageLoader.downloadImage(mPostAvatarString, Glide.with(activity), false, 5, mPostAvatar, null);
            }

            if(mPostUsernameString != null && !mPostUsernameString.isEmpty()) {
                mPostUsername.setText(mPostUsernameString);
            }

            if(mPostContentString != null && !mPostContentString.isEmpty()) {
                if(mPostContentString.toString().length() > 75){
                    String contentString = mPostContentString.toString().subSequence(0, 75) + "...";
                    mPostContent.setText(contentString);
                }else{
                    mPostContent.setText(mPostContentString.toString());
                }
            } 

            
            String accessToken = activity.getAccessToken();
            if(accessToken != null){
                Context context = ContextUtils.getApplicationContext();
                SharedPreferences prefs = context.getSharedPreferences(BE_PROFILE_PREF, 0);
                String avatar = prefs.getString("avatar_url", null);
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
                if (avatar != null) {
                    updateAvatar(avatar, activity);
                }else{
                    updateAvatar("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", activity);
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        } catch (JSONException e) {
        }

        mCancelButton.setOnClickListener(v -> dismissBottomsheet());

        mPostButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    String tempId = "temp_" + java.util.UUID.randomUUID().toString();
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String accessToken = activity.getAccessToken();

                        mPostButton.setEnabled(false);
                        String content = mMessageEditText.getText().toString().trim();
                        if (content.isEmpty() && mSelectedMediaUri == null) {
                            mPostButton.setEnabled(true);
                            return;
                        }
                        
                        String tempPostParent = mCommentsFor.equals("post") ? mPostId : null;
                        String tempCommentParent = mCommentsFor.equals("comment") ? mPostId : null;

                        User user = null;
                        if(accessToken != null) {
                            JSONObject decodedAccessTokenObj = getDecodedToken(accessToken);
                            SharedPreferences prefs = activity.getSharedPreferences(BE_PROFILE_PREF, 0);
                            String avatar = prefs.getString("avatar_url", null);
                            user = new User(decodedAccessTokenObj.getString("_id"), decodedAccessTokenObj.getString("username"), avatar != null ? avatar :"https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile");
                        } else {
                            user = new User(tempId, "guest user", "https://api.dicebear.com/9.x/fun-emoji/png?seed=" + tempId + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile");
                        }

                        Comment optimisticComment = new Comment(
                            tempId,
                            content,
                            0, 0, 0, null, 
                            tempPostParent, tempCommentParent,
                            user,
                            null,
                            ("image".equals(mSelectedMediaType)) ? mSelectedMediaUri.toString() : null,
                            ("video".equals(mSelectedMediaType)) ? mSelectedMediaUri.toString() : null,
                            null, null, null
                        );
                        optimisticComment.setUploadStatus(Comment.UploadStatus.POSTING);

                        if (mCommentPostedListener != null) {
                            Log.e("ReplyWithAttachment", "Posting optimistic comment: " + optimisticComment.getId());
                            mCommentPostedListener.onCommentPostRequested(
                                mPostId,
                                mCommentsFor.equals("post") ? "post" : "comment",
                                optimisticComment,
                                mSelectedMediaUri,
                                mSelectedMediaType,
                                accessToken
                            );
                        }

                        dismiss();

                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        if (mCommentPostedListener != null) {
                            mCommentPostedListener.onCommentPostFailed(tempId, "Activity not found.");
                        }
                    } catch (JSONException e) {
                        if (mCommentPostedListener != null) {
                            mCommentPostedListener.onCommentPostFailed(tempId, "Activity not found.");
                        }
                    }
                }
            }
        }));
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        pauseAllVideoPlaybackInActiveLists();
    }

    private void pauseAllVideoPlaybackInActiveLists() {
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
    }

    private void releaseAllVideoPlaybackResourcesInActiveLists() {
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();
    }

    public void dismissBottomsheet() {
        dismiss();
    }

    public void updateAvatar(String avatarUrl, BraveActivity activity) {
        ImageLoader.downloadImage(
            avatarUrl,
            Glide.with(activity),
            false,
            5,
            mAvatarImage,
            null
        );
    }

    private void setupAttachmentListeners() {
        mAttachButton.setOnClickListener(v -> {
            mAttachButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            openMediaPicker();
        });
        mRemoveAttachmentButton.setOnClickListener(v -> removeAttachment());

        View.OnClickListener fullscreenListener = v -> showMediaFullscreen();
        mAttachmentPreviewContainer.setOnClickListener(fullscreenListener);
        mVideoPlayButton.setOnClickListener(fullscreenListener);
    }

    private void openMediaPicker() {
        mMediaPickerLauncher.launch(new String[]{"image/*", "video/*"});
    }

    private void removeAttachment() {
        mSelectedMediaUri = null;
        mSelectedMediaType = null;
        if (mAttachmentPreviewImage != null) {
            mAttachmentPreviewImage.setImageDrawable(null); // Or a placeholder
        }
        if (mAttachmentPreviewContainer != null) {
            mAttachmentPreviewContainer.setVisibility(View.GONE);
        }
    }

    private void showKeyboardWithFocus() {
        if (mMessageEditText != null && getContext() != null && isAdded()) {
            mMessageEditText.post(() -> {
                if (getContext() != null && isAdded() && mMessageEditText != null) {
                    mMessageEditText.requestFocus();
                    
                    if (mMessageEditText.getText() != null) {
                        mMessageEditText.setSelection(mMessageEditText.getText().length());
                    }
                    
                    mMessageEditText.postDelayed(() -> {
                        if (getContext() != null && isAdded() && mMessageEditText != null && mMessageEditText.hasFocus()) {
                            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                boolean keyboardShown = imm.showSoftInput(mMessageEditText, InputMethodManager.SHOW_FORCED);
                                if (!keyboardShown) {
                                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
                                }
                            }
                        }
                    }, 500); // Increased delay to 500ms
                }
            });
        }
    }

    private void processSelectedMedia(Uri originalUri) {
        if (getContext() == null) {
            removeAttachment();
            return;
        }

        ContentResolver contentResolver = getContext().getContentResolver();
        String mimeType = contentResolver.getType(originalUri);

        if (mimeType != null && mimeType.startsWith("image/")) {
            ImageProcessor.processImage(getContext(), originalUri,
                new ImageProcessor.ProcessImageCallback() {
                    @Override
                    public void onImageProcessed(@Nullable Uri processedImageUri, @Nullable String finalMimeType) {
                        if (!isAdded() || getContext() == null || mAttachmentPreviewImage == null || mAttachmentPreviewContainer == null) {
                            return; // Fragment not attached or views are null
                        }

                        if (processedImageUri != null) {
                            mSelectedMediaUri = processedImageUri;
                            mSelectedMediaType = "image";

                            Glide.with(getContext())
                                    .asBitmap()
                                    .load(mSelectedMediaUri)
                                    .placeholder(R.drawable.ic_image_placeholder_24dp)
                                    .error(R.drawable.ic_error_placeholder_24dp)
                                    .into(new CustomTarget<Bitmap>() {
                                        @Override
                                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                            if (!isAdded() || mAttachmentPreviewImage == null) return;

                                            int screenWidth = getResources().getDisplayMetrics().widthPixels;
                                            int parentActualWidth = ((View)mAttachmentPreviewContainer.getParent()).getWidth();
                                            int containerPaddingHorizontal = mAttachmentPreviewContainer.getPaddingLeft() + mAttachmentPreviewContainer.getPaddingRight();
                                            int availableWidth = parentActualWidth > 0 ? parentActualWidth - containerPaddingHorizontal
                                                                  : screenWidth - (int) (getResources().getDisplayMetrics().density * 40);


                                            int imageWidth = resource.getWidth();
                                            int imageHeight = resource.getHeight();
                                            float aspectRatio = (imageHeight == 0) ? 1.0f : (float) imageWidth / (float) imageHeight;


                                            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();

                                            if (aspectRatio > 1) { // Horizontal image
                                                params.width = availableWidth;
                                                params.height = (aspectRatio == 0) ? (int) (availableWidth * 0.9f) : (int) (availableWidth / aspectRatio) ;
                                            } else { // Vertical or square image
                                                params.width = (int) (availableWidth / 1.05);
                                                params.height = (aspectRatio == 0) ? (int) ((availableWidth/1.05) * 1.33f) : (int) ((availableWidth / 1.05) / aspectRatio) ;
                                            }

                                            mAttachmentPreviewImage.setLayoutParams(params);
                                            mAttachmentPreviewImage.setImageBitmap(resource);

                                            mAttachmentPreviewImage.post(() -> {
                                                showKeyboardWithFocus();
                                            });
                                        }

                                        @Override
                                        public void onLoadCleared(@Nullable Drawable placeholder) {
                                            if (mAttachmentPreviewImage != null) {
                                                mAttachmentPreviewImage.setImageDrawable(placeholder);
                                            }
                                        }

                                        @Override
                                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                             if (!isAdded() || mAttachmentPreviewImage == null || getContext() == null) return;
                                            mAttachmentPreviewImage.setImageDrawable(errorDrawable);
                                            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();
                                            params.width = (int) (100 * getResources().getDisplayMetrics().density);
                                            params.height = (int) (100 * getResources().getDisplayMetrics().density);
                                            mAttachmentPreviewImage.setLayoutParams(params);
                                        }
                                    });
                            mAttachmentPreviewContainer.setVisibility(View.VISIBLE);
                            mRemoveAttachmentButton.setVisibility(View.VISIBLE);
                            mVideoPlayButton.setVisibility(View.GONE);
                        } else {
                            Log.e("CommentBottomSheet", "Image processing failed.");
                            mSelectedMediaUri = originalUri;
                            mSelectedMediaType = "image";
                            if (mAttachmentPreviewImage != null && mAttachmentPreviewContainer != null) {
                                Glide.with(getContext())
                                        .load(originalUri)
                                        .placeholder(R.drawable.ic_image_placeholder_24dp)
                                        .error(R.drawable.ic_error_placeholder_24dp)
                                        .into(mAttachmentPreviewImage);
                                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();
                                params.width = (int) (100 * getResources().getDisplayMetrics().density);
                                params.height = (int) (100 * getResources().getDisplayMetrics().density);
                                mAttachmentPreviewImage.setLayoutParams(params);
                                mAttachmentPreviewContainer.setVisibility(View.VISIBLE);
                                mRemoveAttachmentButton.setVisibility(View.VISIBLE);
                                mVideoPlayButton.setVisibility(View.GONE);
                            }
                            if(getContext() != null) {
                                Toast.makeText(getContext(), R.string.image_processing_failed, Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                });
        } else if (mimeType != null && mimeType.startsWith("video/")) {
            mSelectedMediaUri = originalUri;
            mSelectedMediaType = "video";
            if (mAttachmentPreviewImage != null && mAttachmentPreviewContainer != null && getContext() != null) {
                Glide.with(getContext())
                        .load(mSelectedMediaUri)
                        .placeholder(R.drawable.ic_image_placeholder_24dp)
                        .error(R.drawable.ic_error_placeholder_24dp)
                        .into(mAttachmentPreviewImage);

                setVideoPreviewSize(mSelectedMediaUri);

                mAttachmentPreviewContainer.setVisibility(View.VISIBLE);
                mVideoPlayButton.setVisibility(View.VISIBLE);
                mRemoveAttachmentButton.setVisibility(View.VISIBLE);
            }
        } else {
            Log.w("CommentBottomSheet", "Unsupported media type: " + mimeType);
             if(getContext() != null) {
                Toast.makeText(getContext(), R.string.unsupported_file_type, Toast.LENGTH_SHORT).show();
            }
            removeAttachment();
        }
    }

    private void showMediaFullscreen() {
        if (mSelectedMediaUri == null || mSelectedMediaType == null || getParentFragmentManager() == null) {
            Log.w("ReplyWithAttachment", "Cannot open fullscreen viewer, media URI or type is null.");
            return;
        }

        MediaViewerFragment viewerFragment = MediaViewerFragment.newInstance(mSelectedMediaUri, mSelectedMediaType, true);
        hideKeyboard();
        viewerFragment.show(getParentFragmentManager(), MediaViewerFragment.class.getSimpleName());
    }

    @Override
    public void onViewerDismissed(boolean shouldShowKeyboard) {
        if (shouldShowKeyboard) {
            showKeyboardWithFocus();
        }
    }

    private void hideKeyboard() {
        if (getContext() == null || getView() == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
        }
    }

    private void setVideoPreviewSize(Uri videoUri) {
        if (getContext() == null || mAttachmentPreviewImage == null || mAttachmentPreviewContainer == null) {
            return;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int videoWidth = 0;
        int videoHeight = 0;
        try {
            retriever.setDataSource(getContext(), videoUri);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (widthStr != null && heightStr != null) {
                videoWidth = Integer.parseInt(widthStr);
                videoHeight = Integer.parseInt(heightStr);
            }
        } catch (Exception e) {
            Log.e("CommentBottomSheet", "Failed to retrieve video metadata", e);
            videoWidth = 16;
            videoHeight = 9;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                 Log.e("CommentBottomSheet", "Failed to release MediaMetadataRetriever", e);
            }
        }
        
        if (videoWidth <= 0 || videoHeight <= 0) {
            videoWidth = 16;
            videoHeight = 9;
        }

        View parentView = (View) mAttachmentPreviewContainer.getParent();
        int availableWidth = 0;
        if (parentView != null && parentView.getWidth() > 0) {
            availableWidth = parentView.getWidth() - mAttachmentPreviewContainer.getPaddingLeft() - mAttachmentPreviewContainer.getPaddingRight();
        } else {
             availableWidth = getResources().getDisplayMetrics().widthPixels - (int)(getResources().getDisplayMetrics().density * 40);
        }

        float aspectRatio = (float) videoHeight / (float) videoWidth;
        int calculatedHeight = (int) (availableWidth * aspectRatio);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();
        params.width = availableWidth;
        params.height = calculatedHeight;
        mAttachmentPreviewImage.setLayoutParams(params);
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
