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
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.content.ContentResolver;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.bumptech.glide.request.transition.Transition;

import org.chromium.base.ContextUtils;
import androidx.fragment.app.DialogFragment;
import android.media.MediaMetadataRetriever;
import org.chromium.chrome.browser.ChromeTabbedActivity;

import android.view.HapticFeedbackConstants;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

public class BrowserExpressReplyWithAttachmentBottomSheetFragment extends DialogFragment implements MediaViewerFragment.OnViewerDismissedListener, ImageCropDialogFragment.OnImageCroppedListener {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String POST_USERNAME = "post_username";
    public static final String POST_CONTENT = "post_content";
    public static final String POST_AVATAR_URL = "post_avatar_url";
    public static final String ATTACHMENT_URI = "attachment_uri";
    private static final String STATE_MEDIA_URI = "state_media_uri";
    private static final String STATE_MEDIA_TYPE = "state_media_type";
    private static final String STATE_INITIAL_MEDIA_STAGED = "state_initial_media_staged";
    private static final String STATE_CROP_IS_INITIAL = "state_crop_is_initial";
    private static final String MIME_TYPE_GIF = "image/gif";

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

    private ImageButton mAttachButton;
    private FrameLayout mAttachmentPreviewContainer;
    private ImageView mAttachmentPreviewImage;
    private ImageButton mRemoveAttachmentButton;
    private ImageView mVideoPlayButton;
    /** A document attachment's card: its name and size instead of a picture. */
    private View mDocumentPreviewContainer;
    private TextView mDocumentPreviewName;
    private TextView mDocumentPreviewMeta;
    private Uri mSelectedMediaUri;
    private String mSelectedMediaType;

    private Uri mTempSelectedMediaUri;
    /** Guards against staging the launch attachment twice when the composer is recreated. */
    private boolean mInitialMediaStaged;
    /** True while cropping the image the composer was opened for; see {@link #onCropCancelled}. */
    private boolean mCropIsForInitialAttachment;

    private ImageView mAvatarImage;
    private EditText mMessageEditText;

    // private boolean isFromMenu;

    private ActivityResultLauncher<PickVisualMediaRequest> mMediaPickerLauncher;

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
            // isFromMenu = getArguments().getBoolean(IS_FROM_MENU);
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
            mPostUsernameString = getArguments().getString(POST_USERNAME);
            mPostContentString = getArguments().getString(POST_CONTENT);
            mPostAvatarString = getArguments().getString(POST_AVATAR_URL);
            mTempSelectedMediaUri = getArguments().getParcelable(ATTACHMENT_URI);

            mMediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        stageSelectedMedia(uri, false);
                    }
                }
            );
        }

        if (savedInstanceState != null) {
            mSelectedMediaUri = savedInstanceState.getParcelable(STATE_MEDIA_URI);
            mSelectedMediaType = savedInstanceState.getString(STATE_MEDIA_TYPE);
            mInitialMediaStaged = savedInstanceState.getBoolean(STATE_INITIAL_MEDIA_STAGED);
            mCropIsForInitialAttachment = savedInstanceState.getBoolean(STATE_CROP_IS_INITIAL);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // The attachment only lives in memory — cropping happens before the composer ever shows
        // it — so recreation has to carry it over, or the crop stage would run a second time.
        outState.putParcelable(STATE_MEDIA_URI, mSelectedMediaUri);
        outState.putString(STATE_MEDIA_TYPE, mSelectedMediaType);
        outState.putBoolean(STATE_INITIAL_MEDIA_STAGED, mInitialMediaStaged);
        outState.putBoolean(STATE_CROP_IS_INITIAL, mCropIsForInitialAttachment);
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
        mDocumentPreviewContainer = view.findViewById(R.id.document_preview_container);
        mDocumentPreviewName = view.findViewById(R.id.document_preview_name);
        mDocumentPreviewMeta = view.findViewById(R.id.document_preview_meta);
        view.findViewById(R.id.button_remove_document).setOnClickListener(v -> removeAttachment());

        setupAttachmentListeners();

        if (mSelectedMediaUri != null) {
            // Restored across recreation; this attachment already cleared the crop stage.
            if ("video".equals(mSelectedMediaType)) {
                showVideoPreview(mSelectedMediaUri);
            } else if ("document".equals(mSelectedMediaType)) {
                showDocumentPreview(mSelectedMediaUri);
            } else if ("gif".equals(mSelectedMediaType)) {
                showGifPreview(mSelectedMediaUri);
            } else {
                displayImagePreview();
            }
            showKeyboardWithFocus();
        } else if (mTempSelectedMediaUri != null && !mInitialMediaStaged) {
            mInitialMediaStaged = true;
            // Posted so the composer's own window is up before the full-screen crop covers it.
            // The keyboard is left to whatever staging lands on — raising it here would push it
            // up over the crop screen instead.
            view.post(() -> stageSelectedMedia(mTempSelectedMediaUri, true));
        } else {
            mAttachmentPreviewContainer.setVisibility(View.GONE);
            mRemoveAttachmentButton.setVisibility(View.GONE);
            showKeyboardWithFocus();
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
        // Keeps the Post button row above the navigation bar on edge-to-edge devices; see
        // EdgeToEdgeUtil.
        EdgeToEdgeUtil.padBottomBySystemBars(view);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        try{
            BraveActivity activity = BraveActivity.getBraveActivity();
            if(activity == null) {
                Log.e("BottomSheetFragment", "BraveActivity is null in onViewCreated.");
                return;
            }
            if(mPostAvatar != null && mPostAvatarString != null && !mPostAvatarString.isEmpty()) {
                ProfileAvatar.load(mPostAvatar, mPostAvatarString, null);
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
                updateAvatar(ProfileAvatar.selfUrl(accessToken), activity);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }

        mCancelButton.setOnClickListener(v -> dismissBottomsheet());

        mPostButton.setOnClickListener(v -> {
            if (getActivity() == null) return;
            String content = mMessageEditText.getText().toString().trim();
            if (content.isEmpty() && mSelectedMediaUri == null) return;

            // Images were already cropped on their way in (see stageSelectedMedia), so what the
            // composer previews is what gets posted.
            submitComment();
        });
    }

    /** Builds the optimistic comment, hands the upload off, and dismisses the composer. */
    private void submitComment() {
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
                            user = new User(decodedAccessTokenObj.getString("_id"), decodedAccessTokenObj.getString("username"), ProfileAvatar.selfUrl(accessToken));
                        } else {
                            user = new User(tempId, "guest user", ProfileAvatar.defaultFace(tempId));
                        }

                        Comment optimisticComment = new Comment(
                            tempId,
                            content,
                            0, 0, 0, null, 
                            tempPostParent, tempCommentParent,
                            user,
                            null,
                            "image".equals(mSelectedMediaType) || "gif".equals(mSelectedMediaType)
                                    ? mSelectedMediaUri.toString()
                                    : null,
                            "video".equals(mSelectedMediaType) ? mSelectedMediaUri.toString() : null,
                            null, null, null
                        );
                        if ("gif".equals(mSelectedMediaType)) {
                            optimisticComment.setMediaGifUrl(mSelectedMediaUri.toString());
                        } else if ("document".equals(mSelectedMediaType)) {
                            String name = CommentDocuments.displayName(activity, mSelectedMediaUri);
                            optimisticComment.setDocument(mSelectedMediaUri.toString(), name,
                                    activity.getContentResolver().getType(mSelectedMediaUri),
                                    CommentDocuments.size(activity, mSelectedMediaUri));
                        }
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

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        pauseAllVideoPlaybackInActiveLists();
    }

    private void pauseAllVideoPlaybackInActiveLists() {
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
    }

    public void dismissBottomsheet() {
        dismiss();
    }

    public void updateAvatar(String avatarUrl, BraveActivity activity) {
        ProfileAvatar.load(mAvatarImage, avatarUrl, null);
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
        mMediaPickerLauncher.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build());
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
        if (mDocumentPreviewContainer != null) {
            mDocumentPreviewContainer.setVisibility(View.GONE);
        }
    }

    private void hideDocumentPreview() {
        if (mDocumentPreviewContainer != null) mDocumentPreviewContainer.setVisibility(View.GONE);
    }

    /** Previews a document as the file card the comment will show. */
    private void showDocumentPreview(Uri documentUri) {
        if (getContext() == null) return;
        mSelectedMediaUri = documentUri;
        mSelectedMediaType = "document";
        mAttachmentPreviewContainer.setVisibility(View.GONE);
        String name = CommentDocuments.displayName(getContext(), documentUri);
        mDocumentPreviewName.setText(name != null
                        ? name
                        : getString(R.string.browser_express_attach_document));
        mDocumentPreviewMeta.setText(CommentDocuments.describe(
                getContext(), name, CommentDocuments.size(getContext(), documentUri)));
        mDocumentPreviewContainer.setVisibility(View.VISIBLE);
    }

    /**
     * The crop stage's Next: the image (cropped or untouched) becomes the composer's attachment,
     * which is where the user adds the text and hits Post.
     */
    @Override
    public void onImageCropped(Uri croppedImageUri) {
        mCropIsForInitialAttachment = false;
        if (croppedImageUri == null) return;
        processSelectedImage(croppedImageUri);
        showKeyboardWithFocus();
    }

    /**
     * The crop stage was backed out of, so nothing gets attached. If the composer was opened for
     * that image there is nothing left to compose and it closes with it; otherwise any attachment
     * that was already staged stays untouched.
     */
    @Override
    public void onCropCancelled() {
        if (mCropIsForInitialAttachment) {
            mCropIsForInitialAttachment = false;
            // Posted so the composer's own teardown doesn't run nested inside the crop dialog's
            // dismissal.
            if (getView() != null) {
                getView().post(this::dismissAllowingStateLoss);
            } else {
                dismissAllowingStateLoss();
            }
            return;
        }
        showKeyboardWithFocus();
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

    /**
     * Entry point for a newly chosen attachment. Images are sent to the crop stage first and only
     * come back here through {@link #onImageCropped}, so the composer previews — and posts — the
     * image the user confirmed. Videos and GIFs have no crop stage and attach directly.
     *
     * @param isInitialAttachment true when the composer was opened for this image; backing out of
     *     its crop then closes the composer rather than leaving it empty.
     */
    private void stageSelectedMedia(Uri originalUri, boolean isInitialAttachment) {
        if (originalUri == null || getContext() == null || !isAdded()) {
            removeAttachment();
            return;
        }

        ContentResolver contentResolver = getContext().getContentResolver();
        String mimeType = contentResolver.getType(originalUri);

        if (MIME_TYPE_GIF.equals(mimeType)) {
            // A GIF attaches exactly as it was picked. Both the crop stage and ImageProcessor go
            // through BitmapFactory, which keeps a single frame — running either would post a
            // still of the first frame instead of the animation the user chose.
            showGifPreview(originalUri);
            showKeyboardWithFocus();
        } else if (mimeType != null && mimeType.startsWith("image/")) {
            mCropIsForInitialAttachment = isInitialAttachment;
            hideKeyboard();
            // Child manager so the crop dialog resolves this fragment as its parent and
            // delivers the result through OnImageCroppedListener.
            ImageCropDialogFragment.newInstance(originalUri)
                    .show(getChildFragmentManager(),
                            ImageCropDialogFragment.class.getSimpleName());
        } else if (mimeType != null && mimeType.startsWith("video/")) {
            showVideoPreview(originalUri);
            showKeyboardWithFocus();
        } else if (CommentDocuments.isSupported(mimeType)) {
            showDocumentPreview(originalUri);
            showKeyboardWithFocus();
        } else {
            Log.w("CommentBottomSheet", "Unsupported media type: " + mimeType);
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.unsupported_file_type, Toast.LENGTH_SHORT).show();
            }
            removeAttachment();
        }
    }

    /**
     * Normalizes a confirmed image (downscale, re-encode, EXIF) and previews it. The URI is the
     * crop stage's output: a cache JPEG when the user edited, otherwise the picked image as-is.
     */
    private void processSelectedImage(Uri originalUri) {
        if (getContext() == null) {
            removeAttachment();
            return;
        }

        // Processing is async and nothing is attached until it lands, so Post is held until then
        // — otherwise a quick tap right out of the crop stage would post the text without it.
        if (mPostButton != null) mPostButton.setEnabled(false);

        ImageProcessor.processImage(getContext(), originalUri,
            new ImageProcessor.ProcessImageCallback() {
                @Override
                public void onImageProcessed(@Nullable Uri processedImageUri, @Nullable String finalMimeType) {
                    if (mPostButton != null) mPostButton.setEnabled(true);
                    if (!isAdded() || getContext() == null || mAttachmentPreviewImage == null || mAttachmentPreviewContainer == null) {
                        return; // Fragment not attached or views are null
                    }

                    if (processedImageUri != null) {
                        mSelectedMediaUri = processedImageUri;
                        mSelectedMediaType = "image";

                        displayImagePreview();
                    } else {
                        Log.e("CommentBottomSheet", "Image processing failed.");
                        mSelectedMediaUri = originalUri;
                        mSelectedMediaType = "image";
                        // The crop stage already delivered an upright image, so the
                        // unprocessed URI is still safe to attach as-is.
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
    }

    /**
     * Attaches a GIF and renders it playing in the composer. Nothing re-encodes it on the way to
     * the upload, so what the preview animates is what gets posted.
     */
    private void showGifPreview(Uri gifUri) {
        hideDocumentPreview();
        mSelectedMediaUri = gifUri;
        mSelectedMediaType = "gif";
        if (mAttachmentPreviewImage == null || mAttachmentPreviewContainer == null
                || getContext() == null) {
            return;
        }

        Glide.with(getContext())
                .load(mSelectedMediaUri)
                .placeholder(R.drawable.ic_image_placeholder_24dp)
                .error(R.drawable.ic_error_placeholder_24dp)
                .into(new DrawableImageViewTarget(mAttachmentPreviewImage) {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource,
                            @Nullable Transition<? super Drawable> transition) {
                        super.onResourceReady(resource, transition);
                        if (!isAdded() || mAttachmentPreviewImage == null) return;
                        sizeAttachmentPreview(resource.getIntrinsicWidth(),
                                resource.getIntrinsicHeight());
                        mAttachmentPreviewImage.post(() -> showKeyboardWithFocus());
                    }
                });

        mAttachmentPreviewContainer.setVisibility(View.VISIBLE);
        mRemoveAttachmentButton.setVisibility(View.VISIBLE);
        mVideoPlayButton.setVisibility(View.GONE);
    }

    /**
     * Fits the attachment preview to the media's own shape within the composer's width. Shared
     * by the still-image and GIF previews so both are laid out identically.
     */
    private void sizeAttachmentPreview(int mediaWidth, int mediaHeight) {
        if (!isAdded() || mAttachmentPreviewImage == null || mAttachmentPreviewContainer == null) {
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int parentActualWidth = ((View) mAttachmentPreviewContainer.getParent()).getWidth();
        int containerPaddingHorizontal = mAttachmentPreviewContainer.getPaddingLeft()
                + mAttachmentPreviewContainer.getPaddingRight();
        int availableWidth = parentActualWidth > 0
                ? parentActualWidth - containerPaddingHorizontal
                : screenWidth - (int) (getResources().getDisplayMetrics().density * 40);

        float aspectRatio = (mediaHeight <= 0 || mediaWidth <= 0)
                ? 1.0f
                : (float) mediaWidth / (float) mediaHeight;

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) mAttachmentPreviewImage.getLayoutParams();
        if (aspectRatio > 1) { // Horizontal media
            params.width = availableWidth;
            params.height = (int) (availableWidth / aspectRatio);
        } else { // Vertical or square media
            params.width = (int) (availableWidth / 1.05);
            params.height = (int) ((availableWidth / 1.05) / aspectRatio);
        }
        mAttachmentPreviewImage.setLayoutParams(params);
    }

    /** Attaches a video and renders its preview; videos never go through the crop stage. */
    private void showVideoPreview(Uri videoUri) {
        hideDocumentPreview();
        mSelectedMediaUri = videoUri;
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
    }

    /** Renders {@code mSelectedMediaUri} as the sized image preview with its remove control. */
    private void displayImagePreview() {
        hideDocumentPreview();
        if (!isAdded() || getContext() == null || mAttachmentPreviewImage == null
                || mAttachmentPreviewContainer == null) {
            return;
        }

        Glide.with(getContext())
                                    .asBitmap()
                                    .load(mSelectedMediaUri)
                                    .placeholder(R.drawable.ic_image_placeholder_24dp)
                                    .error(R.drawable.ic_error_placeholder_24dp)
                                    .into(new CustomTarget<Bitmap>() {
                                        @Override
                                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                                            if (!isAdded() || mAttachmentPreviewImage == null) return;

                                            sizeAttachmentPreview(resource.getWidth(), resource.getHeight());
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
