/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

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
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import android.view.HapticFeedbackConstants;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class BrowserExpressCommentsBottomSheetFragment extends BottomSheetDialogFragment implements BottomSheetInputCallback, BrowserExpressReplyWithAttachmentBottomSheetFragment.OnCommentPostedListener {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String POST_USERNAME = "post_username";
    public static final String POST_CONTENT = "post_content";
    public static final String POST_AVATAR_URL = "post_avatar_url";
    public static final String OPEN_KEYBOARD = "open_keyboard";
    private static final int MAX_IMAGE_DIMENSION = 1920;
    private static final int IMAGE_COMPRESSION_QUALITY = 80;

    private Boolean mIsCommentPage = false;

    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private String mPostAvatarString;
    private String mPostUsernameString;
    private String mPostContentString;

    private String mTempPostId;
    private String mTempPostAvatarString;
    private String mTempPostUsernameString;
    private String mTempPostContentString;
    private String mTempType;

    private LinearLayout mPostInfoContainer;
    private ImageView mPostAvatar;
    private TextView mPostUsername;
    private TextView mPostContent;

    private Boolean mOpenKeyboard = false;
    private ProgressBar mCommentProgress;

    private ImageButton mAttachButton;
    private FrameLayout mAttachmentPreviewContainer;
    private ImageView mAttachmentPreviewImage;
    private ImageButton mRemoveAttachmentButton;
    private Uri mSelectedMediaUri;
    private String mSelectedMediaType;

    private Button mSendButton;
    private EditText mMessageEditText;

    private LinearLayout mReactionContainer;
    private Button mLolButton;
    private Button mHeartButton;
    private Button mCryButton;
    private Button mFireButton;
    private Button mLoveButton;
    private Button mClapButton;

    private LinearLayout mAttachmentButtonContainer;

    private boolean isFromMenu;

    private ImageView mAvatarImage;

    private String mLastOpenedRepliesForCommentId = null;
    private String mLastOpenedRepliesToRepliesForCommentId = null;

    private ActivityResultLauncher<String[]> mMediaPickerLauncher;

    private android.content.BroadcastReceiver mUploadReceiver;

    public static BrowserExpressCommentsBottomSheetFragment newInstance(boolean isFromMenu) {
        final BrowserExpressCommentsBottomSheetFragment fragment =
                new BrowserExpressCommentsBottomSheetFragment();
        final Bundle args = new Bundle();
        args.putBoolean(IS_FROM_MENU, isFromMenu);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerUploadReceiver();
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

            mTempPostId = mPostId;
            mTempPostUsernameString = mPostUsernameString;
            mTempPostContentString = mPostContentString;
            mTempPostAvatarString = mPostAvatarString;
            mTempType = "post";

            String tempOpenKeyboard = getArguments().getString(OPEN_KEYBOARD);
            if (tempOpenKeyboard != null && tempOpenKeyboard.equals("true")) {
                mOpenKeyboard = true;
            } else {
                mOpenKeyboard = false;
            }

            mMediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        // This is the new callback, containing the logic from your old onActivityResult
                        try {
                            // Grant persistent read permissions for the service. This is a robust way to handle it.
                            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                            if (getContext() != null) {
                                getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                            }

                            if (mMessageEditText != null) {
                                mMessageEditText.clearFocus();
                            }
                            hideKeyboard();

                            BraveActivity activity = BraveActivity.getBraveActivity();
                            activity.showReplyWithAttachmentBottomSheet(this, mTempPostId, mTempPostUsernameString, mTempPostContentString, mTempPostAvatarString, mTempType, uri);
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e("CommentsSheet", "Failed to get BraveActivity to show reply sheet", e);
                        } catch (SecurityException e) {
                            Log.e("CommentsSheet", "Failed to take persistent URI permission", e);
                            Toast.makeText(getContext(), "Could not get access to the selected file.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            );
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        if (mUploadReceiver != null && getContext() != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mUploadReceiver);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_browser_express_comments_bottom_sheet, container, false);
        loadFragment(CommentListFragment.newInstance(mPostId, mCommentsFor, mOpenKeyboard));
        mIsCommentPage = true;

        mMessageEditText = view.findViewById(R.id.comment_content_input);
        mSendButton = view.findViewById(R.id.button_send);
        mAvatarImage = view.findViewById(R.id.avatar_image);

        mReactionContainer = view.findViewById(R.id.reaction_buttons_container);
        mLolButton = view.findViewById(R.id.lol_button);
        mHeartButton = view.findViewById(R.id.heart_button);
        mCryButton = view.findViewById(R.id.cry_button);
        mFireButton = view.findViewById(R.id.fire_button);
        mLoveButton = view.findViewById(R.id.love_button);
        mClapButton = view.findViewById(R.id.clap_button);

        mPostInfoContainer = view.findViewById(R.id.post_info_container);
        mPostAvatar = view.findViewById(R.id.post_avatar);
        mPostUsername = view.findViewById(R.id.post_username);
        mPostContent = view.findViewById(R.id.post_content);

        mAttachButton = view.findViewById(R.id.button_attach);
        mAttachmentPreviewContainer = view.findViewById(R.id.attachment_preview_container);
        mAttachmentPreviewImage = view.findViewById(R.id.attachment_preview_image);
        mRemoveAttachmentButton = view.findViewById(R.id.button_remove_attachment);

        mAttachmentButtonContainer = view.findViewById(R.id.attachment_button_container);

        setupAttachmentListeners();

        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        int defaultHeight = (int) (screenHeight * 0.8);

        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        BottomSheetBehavior behavior = dialog.getBehavior();

        behavior.setMaxHeight(defaultHeight);

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (mMessageEditText != null && mAttachmentButtonContainer != null) {
            mAttachmentButtonContainer.setVisibility(View.GONE);

            mMessageEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        mAttachmentButtonContainer.setVisibility(View.VISIBLE);
                        // mReactionContainer.setVisibility(View.GONE);
                    } else {
                        mAttachmentButtonContainer.setVisibility(View.GONE);
                        // mReactionContainer.setVisibility(View.VISIBLE);
                    }
                }
            });
        }

        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                Log.e("ROOT_VIEW_KEY", "keyCode: " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                        return true;
                    }
                }
                return false;
            }
        });

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                Log.e("BACK BUTTON PRESSED", "keyCode: " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                        return true;
                    }
                }
                return false;
            }
        });

        ((BottomSheetDialog) dialog).getOnBackPressedDispatcher().addCallback(this, 
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Log.e("BACK BUTTON PRESSED 2", "START");
                    FragmentManager fragmentManager = getChildFragmentManager();
                    Log.e("BACK BUTTON PRESSED 2", fragmentManager.getBackStackEntryCount() + "");
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                    } else {
                        this.remove();
                        dismissBottomsheet();
                    }
                }
            });
        try{
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null) {
                Log.e("BottomSheetFragment", "BraveActivity is null in onViewCreated.");
                return;
            }
            if(mAvatarImage != null && mPostAvatarString != null && !mPostAvatarString.isEmpty()) {
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
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();
    }

    private void loadFragment(Fragment fragment) {
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        FragmentManager fragmentManager = getChildFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();

        transaction.setCustomAnimations(
            R.anim.slide_in_right,  // enter
            R.anim.slide_out_left,  // exit
            R.anim.slide_in_left,   // popEnter
            R.anim.slide_out_right  // popExit
        );

        transaction.replace(R.id.bottom_sheet_container, fragment).addToBackStack(null).commit();
    }

    private void pauseAllVideoPlaybackInActiveLists() {
        Log.d("BottomSheetFragment", "Attempting to pause videos in active lists.");
        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();
        if (fragments.isEmpty()) {
            // This can happen if called very early or after all fragments are removed.
            // Check the fragment currently in the container
            Fragment currentFragmentInContainer = fm.findFragmentById(R.id.bottom_sheet_container);
            if (currentFragmentInContainer != null) {
                fragments = new ArrayList<>();
                fragments.add(currentFragmentInContainer);
            } else {
                 Log.d("BottomSheetFragment", "No child fragments found to pause videos.");
                return;
            }
        }

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded() && fragment.getView() != null) {
                if (fragment instanceof CommentListFragment) {
                    Log.d("BottomSheetFragment", "Pausing videos in CommentListFragment");
                    ((CommentListFragment) fragment).pauseAllVideosInList();
                } else if (fragment instanceof ReplyListFragment) {
                    Log.d("BottomSheetFragment", "Pausing videos in ReplyListFragment");
                    ((ReplyListFragment) fragment).pauseAllVideosInList();
                } else if (fragment instanceof ReplyListFragment2) {
                    Log.d("BottomSheetFragment", "Pausing videos in ReplyListFragment2");
                    ((ReplyListFragment2) fragment).pauseAllVideosInList();
                }
                // Add other list fragment types if you have more
            }
        }
    }

    private void releaseAllVideoPlaybackResourcesInActiveLists() {
        Log.d("BottomSheetFragment", "Attempting to release video resources in active lists.");
        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();
         if (fragments.isEmpty()) {
            Fragment currentFragmentInContainer = fm.findFragmentById(R.id.bottom_sheet_container);
            if (currentFragmentInContainer != null) {
                fragments = new ArrayList<>();
                fragments.add(currentFragmentInContainer);
            } else {
                return;
            }
        }

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded()) { // No need for getView() if just releasing data
                if (fragment instanceof CommentListFragment) {
                    ((CommentListFragment) fragment).releaseVideoManagerResources();
                } else if (fragment instanceof ReplyListFragment) {
                    ((ReplyListFragment) fragment).releaseVideoManagerResources();
                } else if (fragment instanceof ReplyListFragment2) {
                    ((ReplyListFragment2) fragment).releaseVideoManagerResources();
                }
            }
        }
    }

    public void openReplies(String commentId) {
        mLastOpenedRepliesForCommentId = commentId;
        mLastOpenedRepliesToRepliesForCommentId = null;
        ReplyListFragment replyFragment = new ReplyListFragment();
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
        mIsCommentPage = false;
    }

    public void openRepliesToReply(String commentId) {
        mLastOpenedRepliesToRepliesForCommentId = commentId;
        ReplyListFragment2 replyFragment = new ReplyListFragment2();
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
        mIsCommentPage = false;
    }

    public void dismissBottomsheet() {
        dismiss();
    }

    public void openComments() {
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        mLastOpenedRepliesToRepliesForCommentId = null;
        FragmentManager fragmentManager = getChildFragmentManager();
        fragmentManager.popBackStack();
        mIsCommentPage = true;
    }

    @Override
    public EditText getInputEditText() {
        return mMessageEditText;
    }

    @Override
    public Button getSendButton() {
        return mSendButton;
    }

    @Override
    public void setInputEnabled(boolean enabled) {
        if (mMessageEditText != null) mMessageEditText.setEnabled(enabled);
        if (mSendButton != null) mSendButton.setEnabled(enabled);
    }

    @Override
    public Button getEmojiButton(String type) {
        switch(type) {
            case "lol": return mLolButton;
            case "heart": return mHeartButton;
            case "cry": return mCryButton;
            case "fire": return mFireButton;
            case "love": return mLoveButton;
            case "clap": return mClapButton;
            default: return null;
        }
    }

    @Nullable
    public String getLastOpenedRepliesToRepliesForCommentId() {
        return mLastOpenedRepliesToRepliesForCommentId;
    }

    public void clearLastOpenedRepliesToRepliesForCommentId() {
        mLastOpenedRepliesToRepliesForCommentId = null;
    }

    @Nullable
    public String getLastOpenedRepliesForCommentId() {
        return mLastOpenedRepliesForCommentId;
    }

    public void clearLastOpenedRepliesForCommentId() {
        mLastOpenedRepliesForCommentId = null;
    }

    @Override
    public void updateAvatar(String avatarUrl, BraveActivity activity) {
        if (mAvatarImage != null && getContext() != null) {
            ImageLoader.downloadImage(
                avatarUrl,
                Glide.with(activity),
                false,
                5,
                mAvatarImage,
                null
            );
        }
    }

    private void setupAttachmentListeners() {
        mAttachButton.setOnClickListener(v -> {
            mAttachButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            openMediaPicker();
        });
        mRemoveAttachmentButton.setOnClickListener(v -> removeAttachment());
    }

    private void openMediaPicker() {
        mMediaPickerLauncher.launch(new String[]{"image/*", "video/*"});
    }

    private void removeAttachment() {
        mReactionContainer.setVisibility(View.VISIBLE);
        mPostInfoContainer.setVisibility(View.GONE);
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
                    mMessageEditText.clearFocus();
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

    private void hideKeyboard() {
        if (getContext() == null || getView() == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
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
                                                params.height = (aspectRatio == 0) ? (int) (availableWidth * 0.75f) : (int) (availableWidth / aspectRatio) ;
                                            } else { // Vertical or square image
                                                params.width = (int) (availableWidth / 1.1);
                                                params.height = (aspectRatio == 0) ? (int) ((availableWidth/1.1) * 1.33f) : (int) ((availableWidth / 1.1) / aspectRatio) ;
                                            }

                                            int maxPreviewHeight = (int) (250 * getResources().getDisplayMetrics().density);
                                            if (params.height > maxPreviewHeight) {
                                                params.height = maxPreviewHeight;
                                                if (aspectRatio != 0) {
                                                    params.width = (int) (maxPreviewHeight * aspectRatio);
                                                } else {
                                                    params.width = (int) (maxPreviewHeight * 0.75f); // Default if aspect ratio is bad
                                                }

                                                int maxWidthForOrientation = (aspectRatio > 1 || aspectRatio == 0) ? availableWidth : (int)(availableWidth / 1.1);
                                                if (params.width > maxWidthForOrientation ) {
                                                     params.width = maxWidthForOrientation;
                                                }
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

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();
                int videoThumbWidth = (int) ((getResources().getDisplayMetrics().widthPixels - (getResources().getDisplayMetrics().density * 40))/2);
                if (((View)mAttachmentPreviewContainer.getParent()).getWidth() > 0) {
                    videoThumbWidth = (((View)mAttachmentPreviewContainer.getParent()).getWidth() - (mAttachmentPreviewContainer.getPaddingLeft() + mAttachmentPreviewContainer.getPaddingRight())) / 2;
                }

                params.width = videoThumbWidth;
                params.height = (int) (videoThumbWidth * (9.0/16.0));
                mAttachmentPreviewImage.setLayoutParams(params);

                mAttachmentPreviewContainer.setVisibility(View.VISIBLE);
            }
        } else {
            Log.w("CommentBottomSheet", "Unsupported media type: " + mimeType);
             if(getContext() != null) {
                Toast.makeText(getContext(), R.string.unsupported_file_type, Toast.LENGTH_SHORT).show();
            }
            removeAttachment();
        }
    }

    private void registerUploadReceiver() {
        if (getContext() == null) return;

        mUploadReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                String tempId = intent.getStringExtra(UploadService.EXTRA_TEMP_ID);
                if (tempId == null) return;

                if (UploadService.BROADCAST_UPLOAD_COMPLETE.equals(action)) {
                    String realCommentJson = intent.getStringExtra(UploadService.EXTRA_REAL_COMMENT_JSON);
                    if (realCommentJson != null) {
                        Comment realComment = new com.google.gson.Gson().fromJson(realCommentJson, Comment.class);
                        // Forward the success call
                        onCommentPostSucceeded(tempId, realComment);
                    }
                } else if (UploadService.BROADCAST_UPLOAD_FAILED.equals(action)) {
                    String errorMessage = intent.getStringExtra("error_message");
                    // Forward the failure call
                    onCommentPostFailed(tempId, errorMessage);
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(UploadService.BROADCAST_UPLOAD_COMPLETE);
        filter.addAction(UploadService.BROADCAST_UPLOAD_FAILED);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext()).registerReceiver(mUploadReceiver, filter);
    }

    @Override
    public void onCommentPostRequested(String postId, String type, Comment optimisticComment, Uri mediaUri, String mediaType, String accessToken) {
        Intent uploadIntent = new Intent(getContext(), UploadService.class);
        uploadIntent.setAction(UploadService.ACTION_UPLOAD_COMMENT);

        if (mediaUri != null) {
            uploadIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().grantUriPermission(getContext().getPackageName(), mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        uploadIntent.putExtra(UploadService.EXTRA_TEMP_ID, optimisticComment.getId());
        uploadIntent.putExtra(UploadService.EXTRA_COMMENT_CONTENT, optimisticComment.getContent());
        uploadIntent.putExtra(UploadService.EXTRA_POST_ID, postId);
        uploadIntent.putExtra(UploadService.EXTRA_MEDIA_URI, mediaUri);
        uploadIntent.putExtra(UploadService.EXTRA_MEDIA_TYPE, mediaType);
        uploadIntent.putExtra(UploadService.EXTRA_ACCESS_TOKEN, accessToken);
        uploadIntent.putExtra(UploadService.EXTRA_COMMENT_TYPE, type);
        
        if (getContext() != null) {
            UploadService.enqueueWork(getContext(), uploadIntent);
        }

        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded() && fragment.getView() != null) {
                if (fragment instanceof CommentListFragment) {
                    Log.d("BottomSheetFragment", "Pausing videos in CommentListFragment");
                    ((CommentListFragment) fragment).addNewComment(optimisticComment);
                } else if (fragment instanceof ReplyListFragment) {
                    Log.d("BottomSheetFragment", "Pausing videos in ReplyListFragment");
                    ((ReplyListFragment) fragment).addNewComment(optimisticComment);
                } else if (fragment instanceof ReplyListFragment2) {
                    Log.d("BottomSheetFragment", "Pausing videos in ReplyListFragment2");
                    ((ReplyListFragment2) fragment).addNewComment(optimisticComment);
                }
            }
        }
    }

    @Override
    public void onCommentPostSucceeded(String tempId, Comment realComment) {
        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded() && fragment.getView() != null) {
                if (fragment instanceof CommentListFragment) {
                    Log.d("BottomSheetFragment", "onCommentPostSucceeded in CommentListFragment");
                    ((CommentListFragment) fragment).updateTemporaryComment(tempId, realComment);
                } else if (fragment instanceof ReplyListFragment) {
                    Log.d("BottomSheetFragment", "onCommentPostSucceeded in ReplyListFragment");
                    ((ReplyListFragment) fragment).updateTemporaryComment(tempId, realComment);
                } else if (fragment instanceof ReplyListFragment2) {
                    Log.d("BottomSheetFragment", "onCommentPostSucceeded in ReplyListFragment2");
                    ((ReplyListFragment2) fragment).updateTemporaryComment(tempId, realComment);
                }
            }
        }
    }

    @Override
    public void onCommentPostFailed(String tempId, String errorMessage) {
        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded() && fragment.getView() != null) {
                if (fragment instanceof CommentListFragment) {
                    Log.d("BottomSheetFragment", "onCommentPostFailed in CommentListFragment");
                    ((CommentListFragment) fragment).markCommentAsFailed(tempId);
                } else if (fragment instanceof ReplyListFragment) {
                    Log.d("BottomSheetFragment", "onCommentPostFailed in ReplyListFragment");
                    ((ReplyListFragment) fragment).markCommentAsFailed(tempId);
                } else if (fragment instanceof ReplyListFragment2) {
                    Log.d("BottomSheetFragment", "onCommentPostFailed in ReplyListFragment2");
                    ((ReplyListFragment2) fragment).markCommentAsFailed(tempId);
                }
            }
        }

        if (getContext() != null) {
            Toast.makeText(getContext(), "Failed to post: " + errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @Nullable
    public Uri getSelectedMediaUri() {
        return mSelectedMediaUri;
    }

    @Override
    public void setPostStuff(String postId, String postUsername, String postContent, String postAvatarUrl, String type) {
        mTempPostId = postId;
        mTempPostUsernameString = postUsername;
        mTempPostContentString = postContent;
        mTempPostAvatarString = postAvatarUrl;
        mTempType = type;
    }

    @Override
    public void resetPostStuff() {
        mTempPostId = mPostId;
        mTempPostUsernameString = mPostUsernameString;
        mTempPostContentString = mPostContentString;
        mTempPostAvatarString = mPostAvatarString;
        mTempType = "post";
    }

    @Override
    @Nullable
    public String getSelectedMediaType() {
        return mSelectedMediaType;
    }

    @Override
    public void clearSelectedMedia() {
        removeAttachment();
    }
}
