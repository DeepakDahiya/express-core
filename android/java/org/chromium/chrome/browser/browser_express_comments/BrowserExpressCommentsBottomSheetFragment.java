/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
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
import org.chromium.chrome.browser.ntp.PostVideoViewerFragment;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.Intent;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.content.ContentResolver;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import android.view.HapticFeedbackConstants;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

public class BrowserExpressCommentsBottomSheetFragment extends BottomSheetDialogFragment
        implements BottomSheetInputCallback, CommentActionHost,
                BrowserExpressReplyWithAttachmentBottomSheetFragment.OnCommentPostedListener,
                CommentEmojiSheet.Host, CommentAttachmentSheet.Host, GifPickerSheet.Host,
                CommentComposerController.Host, CommentUploadManager.Listener,
                TakeComposer.Host {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String POST_USERNAME = "post_username";
    public static final String POST_CONTENT = "post_content";
    public static final String POST_AVATAR_URL = "post_avatar_url";
    public static final String OPEN_KEYBOARD = "open_keyboard";

    /**
     * URL of a post video the sheet should open full screen on top of the comments. Set when the
     * reader tapped the video itself rather than the post around it: the comments are still what
     * they land on, the video just plays over them until they come back.
     */
    public static final String POST_VIDEO_URL = "post_video_url";
    /**
     * PostVideoViewerFragment arguments: a feed post's video, played full screen over its
     * comments. Takes precedence over {@link #POST_VIDEO_URL}.
     */
    public static final String POST_VIDEO_VIEWER = "post_video_viewer";
    /** Comment to scroll to (and flash) once the list is up — set by a push notification tap. */
    public static final String TARGET_COMMENT_ID = "target_comment_id";
    /** A feed post's attachment and age for the source chip, from {@link SourceChip#postMedia}. */
    public static final String POST_SOURCE_MEDIA = "post_source_media";

    private String mCommentsFor;
    private String mPostId;
    private String mVideoId;
    private String mPostAvatarString;
    private String mPostVideoUrl;
    private @Nullable Bundle mPostVideoViewerArgs;
    private @Nullable Bundle mPostSourceMedia;
    private String mPostUsernameString;
    private String mPostContentString;

    private String mTempPostId;
    private String mTempPostAvatarString;
    private String mTempPostUsernameString;
    private String mTempPostContentString;
    private String mTempType;

    private View mSheetHandle;

    private LinearLayout mPostInfoContainer;
    private ImageView mPostAvatar;
    private TextView mPostUsername;
    private TextView mPostContent;

    private Boolean mOpenKeyboard = false;

    private ImageButton mAttachButton;
    private FrameLayout mAttachmentPreviewContainer;
    private ImageView mAttachmentPreviewImage;
    private ImageButton mRemoveAttachmentButton;
    private Uri mSelectedMediaUri;
    private String mSelectedMediaType;

    private View mSendButton;
    private EditText mMessageEditText;
    private View mEmojiButton;

    private ImageView mAvatarImage;

    private String mLastOpenedRepliesForCommentId;
    private String mLastOpenedRepliesToRepliesForCommentId;

    // L1 (top-level) YouTube comment that the currently-open L2 replies belong to.
    // Cached on openReplies so that openRepliesToReply (L3) can pass it through as the
    // grand-ancestor needed by yt_interact when registering an unregistered L2 parent.
    private String mLastOpenedL1ParentJson;

    /** The attachment sheet's pickers; each result opens the attachment composer. */
    private ActivityResultLauncher<PickVisualMediaRequest> mMediaPickerLauncher;
    private ActivityResultLauncher<Uri> mCameraLauncher;
    private ActivityResultLauncher<String> mCameraPermissionLauncher;
    private ActivityResultLauncher<String[]> mDocumentPickerLauncher;
    /** Where the camera is writing the photo it is taking. */
    private @Nullable Uri mPendingCameraUri;
    private static final String STATE_CAMERA_URI = "state_camera_uri";

    // --- Voice notes -------------------------------------------------------------------------
    private static final long RECORDING_TICK_MS = 80;

    private LinearLayout mInputBarLayout;
    private LinearLayout mRecordingBarLayout;
    private TextView mRecordingTimer;
    private AudioWaveformView mRecordingWaveform;

    private AudioCommentRecorder mRecorder;
    private final Handler mRecordingHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRecordingTick = new Runnable() {
        @Override
        public void run() {
            if (mRecorder == null || !mRecorder.isRecording()) return;
            long elapsed = mRecorder.getElapsedMs();
            mRecordingTimer.setText(AudioTime.format(elapsed));
            mRecordingWaveform.pushAmplitude(mRecorder.getAmplitude());
            if (elapsed >= AudioCommentRecorder.MAX_DURATION_MS) {
                // Hitting the ceiling posts what was captured rather than dropping it.
                finishRecording(false);
                return;
            }
            mRecordingHandler.postDelayed(this, RECORDING_TICK_MS);
        }
    };

    private ActivityResultLauncher<String> mMicPermissionLauncher;

    private android.content.BroadcastReceiver mUploadReceiver;

    // --- Sending (Figma "Comments · Complete Attachment & Upload Flow", 498:2056) -------------
    /** How long "Comment posted" and the like stay up. */
    private static final long TOAST_MS = 2500;
    /** How long a just-posted comment keeps its accent outline. */
    private static final long POSTED_HIGHLIGHT_MS = 3000;
    private static final long COMPOSER_SLIDE_MS = 180;

    private CommentComposerController mComposer;
    /**
     * The comment box. It floats over the bottom of the page area: every page stops above it
     * except a full-page video, which runs under it while the box slides away until the reader
     * uses it.
     */
    private View mComposerBar;
    /** A full-page video is showing (CommentReelFragment). */
    private boolean mComposerOverReel;
    private boolean mComposerTucked;
    /**
     * "Add your take": on the comments themselves the comment box opens it; on a replies page
     * the box is for replying and works as it always has.
     */
    private @Nullable TakeComposer mTake;
    /**
     * The topic's totals and hashtag as the comments answer last gave them (-1 / null until it
     * does); the take card prefers them to the feed's, which may be older.
     */
    /**
     * For "Comments Opened" / "Comments Closed": when the sheet opened (0 once reported), and
     * how many reply pages were opened and comments sent while it was up.
     */
    private long mOpenedAtMs;
    private int mRepliesOpened;
    private int mCommentsSent;
    private int mTopicComments = -1;
    private int mTopicEmotes = -1;
    private @Nullable String mTopicHashtag;
    private @Nullable TakeBanner mBanner;
    /** A replies page is showing or on its way; set before its fragment is committed. */
    private boolean mRepliesShowing;
    /** Picked files still being read; the box stays out while they are on their way into it. */
    private int mPendingPicks;
    private boolean mImeVisible;
    private @Nullable FragmentManager.FragmentLifecycleCallbacks mPageInsetCallbacks;
    /** The comments this sheet is sending, by their temporary id. */
    private final java.util.Map<String, Comment> mOutgoing = new java.util.HashMap<>();
    /** What each was sent with, to put back in the box if its upload is cancelled. */
    private final java.util.Map<String, android.util.Pair<String, List<CommentUploadManager.Item>>>
            mSentDrafts = new java.util.HashMap<>();
    private final Handler mToastHandler = new Handler(Looper.getMainLooper());
    private TextView mInlineStatus;

    /**
     * Whether this surface shows the full-screen reel pager instead of the flat scrolling list.
     *
     * <p>Everything except YouTube watch pages does. There, the sheet stays at 60% so the video
     * behind it remains visible, which a full-screen pager would defeat.
     */
    private boolean useReelPresentation() {
        return !"youtube".equals(mCommentsFor);
    }

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
        GlobalVideoPlaybackManager.getInstance().resumePlaybackIfPossible();
        registerUploadReceiver();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.AppSetDefaultBottomSheetDialogTheme);

        if (getArguments() != null) {
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
            mVideoId = getArguments().getString(CommentListFragment.VIDEO_ID);
            mPostUsernameString = getArguments().getString(POST_USERNAME);
            mPostContentString = getArguments().getString(POST_CONTENT);
            mPostAvatarString = getArguments().getString(POST_AVATAR_URL);
            mPostVideoUrl = getArguments().getString(POST_VIDEO_URL);
            mPostVideoViewerArgs = getArguments().getBundle(POST_VIDEO_VIEWER);
            mPostSourceMedia = getArguments().getBundle(POST_SOURCE_MEDIA);
            setTargetCommentId(getArguments().getString(TARGET_COMMENT_ID));

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

        }

        if (savedInstanceState != null) {
            mPendingCameraUri = savedInstanceState.getParcelable(STATE_CAMERA_URI);
        } else {
            mOpenedAtMs = SystemClock.elapsedRealtime();
            PostHogUtil.capture(
                    PostHogEventKeys.COMMENTS_OPENED,
                    "comments_for",
                    mCommentsFor,
                    "post_id",
                    mPostId,
                    "with_video",
                    mPostVideoViewerArgs != null,
                    "from_notification",
                    getArguments() != null && getArguments().getString(TARGET_COMMENT_ID) != null);
        }
        registerAttachmentLaunchers();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // The camera app may outlive this sheet's process; the photo's destination has to too.
        outState.putParcelable(STATE_CAMERA_URI, mPendingCameraUri);
    }

    private void registerAttachmentLaunchers() {
        mMediaPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(
                        CommentUploadManager.MAX_FILES),
                uris -> addPicked(uris, CommentAttachment.KIND_IMAGE, 0));
        mCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), taken -> {
                    Uri photo = mPendingCameraUri;
                    mPendingCameraUri = null;
                    if (Boolean.TRUE.equals(taken) && photo != null) {
                        addPicked(java.util.Collections.singletonList(photo),
                                CommentAttachment.KIND_IMAGE, 0);
                    }
                });
        mCameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        launchCamera();
                    } else if (getContext() != null) {
                        Toast.makeText(getContext(),
                                     R.string.browser_express_attach_camera_permission,
                                     Toast.LENGTH_LONG)
                                .show();
                    }
                });
        mDocumentPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::onDocumentsPicked);
        mMicPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        // A tap started this, not a held finger, so recording can begin now.
                        startRecording();
                    } else if (getContext() != null) {
                        Toast.makeText(getContext(), R.string.be_mic_permission_required,
                                     Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    /**
     * Adds picked or recorded files to the comment box. Reading their details (type, size, a
     * video's length) touches the files, so it happens off the UI thread.
     *
     * @param kindHint what the picker was asked for; the files' own types refine it.
     * @param durationMs a recording's length when known, else 0.
     */
    private void addPicked(@Nullable List<Uri> uris, String kindHint, int durationMs) {
        if (uris == null || uris.isEmpty() || getContext() == null) return;
        Context appContext = getContext().getApplicationContext();
        List<Uri> picked = new java.util.ArrayList<>(uris);
        mPendingPicks++;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            List<CommentUploadManager.Item> items = new java.util.ArrayList<>();
            boolean failed = false;
            for (Uri uri : picked) {
                CommentUploadManager.Item item =
                        CommentDraftItems.describe(appContext, uri, kindHint, durationMs);
                if (item == null) {
                    failed = true;
                } else {
                    items.add(item);
                }
            }
            final boolean anyFailed = failed;
            org.chromium.base.ThreadUtils.postOnUiThread(() -> {
                mPendingPicks--;
                if (mComposer == null || getContext() == null) return;
                if (anyFailed) {
                    Toast.makeText(getContext(), R.string.browser_express_attach_failed,
                                 Toast.LENGTH_SHORT)
                            .show();
                }
                if (!items.isEmpty()) {
                    mComposer.addItems(items);
                    // Back from the picker: the take sheet carries on with the keyboard.
                    if (mTake != null && mTake.isOpen()) showKeyboardWithFocus();
                } else {
                    updateComposerTuck(true);
                }
            });
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        // A recording in flight cannot survive the sheet going away — the finger holding the mic
        // is gone, so there would be nothing left to stop it.
        if (mRecorder != null && mRecorder.isRecording()) {
            finishRecording(true);
        }
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingAudio();
        if (mUploadReceiver != null && getContext() != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(mUploadReceiver);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_browser_express_comments_bottom_sheet, container, false);
        Log.e("YouTubeComments", "[BottomSheet] onCreateView mCommentsFor=" + mCommentsFor + " mVideoId=" + mVideoId);
        if (useReelPresentation()) {
            loadFragment(CommentReelFragment.newInstance(mPostId, mCommentsFor, mOpenKeyboard));
        } else {
            loadFragment(CommentListFragment.newInstance(mPostId, mCommentsFor, mOpenKeyboard, mVideoId));
        }

        mMessageEditText = view.findViewById(R.id.comment_content_input);
        mSendButton = view.findViewById(R.id.button_send);
        mAvatarImage = view.findViewById(R.id.avatar_image);

        mSheetHandle = view.findViewById(R.id.sheet_title);

        mPostInfoContainer = view.findViewById(R.id.post_info_container);
        mPostAvatar = view.findViewById(R.id.post_avatar);
        mPostUsername = view.findViewById(R.id.post_username);
        mPostContent = view.findViewById(R.id.post_content);

        mAttachButton = view.findViewById(R.id.button_attach);
        mAttachmentPreviewContainer = view.findViewById(R.id.attachment_preview_container);
        mAttachmentPreviewImage = view.findViewById(R.id.attachment_preview_image);
        mRemoveAttachmentButton = view.findViewById(R.id.button_remove_attachment);
        mEmojiButton = view.findViewById(R.id.button_emoji);

        mInputBarLayout = view.findViewById(R.id.input_bar_layout);
        mRecordingBarLayout = view.findViewById(R.id.recording_bar_layout);
        mRecordingTimer = view.findViewById(R.id.recording_timer);
        mRecordingWaveform = view.findViewById(R.id.recording_waveform);

        setupAttachmentListeners();
        setupVoiceNoteRecording(view);
        setupComposer(view);
        setupComposerOverlay(view);

        return view;
    }

    private void setupComposerOverlay(View view) {
        mComposerBar = view.findViewById(R.id.sheet_button);
        mTake = new TakeComposer(view, this);
        mBanner = new TakeBanner(view.findViewById(R.id.take_banner));
        getChildFragmentManager().addOnBackStackChangedListener(() -> {
            mRepliesShowing = getChildFragmentManager().getBackStackEntryCount() > 1;
        });
        mComposerBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // The take sheet covers the pages, which keep their inset until it closes.
            if (b - t != ob - ot && (mTake == null || !mTake.isOpen())) {
                applyComposerInset(
                        getChildFragmentManager().findFragmentById(R.id.bottom_sheet_container));
            }
        });
        mPageInsetCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                    @NonNull View v, @Nullable Bundle savedInstanceState) {
                if (f.getId() != R.id.bottom_sheet_container) return;
                applyComposerInset(f);
                // A reel says whether a video is showing once its pages are packed.
                if (!(f instanceof CommentReelFragment)) setComposerOverReel(false, true);
            }
        };
        getChildFragmentManager().registerFragmentLifecycleCallbacks(mPageInsetCallbacks, false);

        mMessageEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && needsTakeSheet()) openTakeComposer(false, "text_box");
            updateComposerTuck(true);
        });
        // Closing the keyboard with nothing typed puts the box away again over a video. The
        // sheet resizes with the keyboard, so its body lays out whenever that happens.
        view.findViewById(R.id.comment_sheet_body).addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(v);
                    boolean ime = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
                    if (ime == mImeVisible) return;
                    mImeVisible = ime;
                    if (!ime && mComposerOverReel && !hasDraft() && mMessageEditText != null
                            && mMessageEditText.hasFocus()) {
                        mMessageEditText.clearFocus();
                    }
                });
    }

    /** Keeps a page clear of the comment box floating over its bottom. */
    private void applyComposerInset(@Nullable Fragment page) {
        if (page == null || mComposerBar == null) return;
        int inset = mComposerBar.getHeight();
        if (page instanceof CommentReelFragment) {
            // Its reels run under the box; it insets its other pages itself.
            ((CommentReelFragment) page).setComposerInset(inset);
            return;
        }
        View view = page.getView();
        if (view != null && view.getPaddingBottom() != inset) {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    inset);
        }
    }

    /**
     * Called by the comments pager as pages change: over a full-page video the comment box slides
     * away for an unobstructed view, unless the reader is writing, recording or has files in it.
     */
    public void setComposerOverReel(boolean overReel, boolean animate) {
        mComposerOverReel = overReel;
        updateComposerTuck(animate);
    }

    /** Something typed or picked for the next comment. */
    private boolean hasDraft() {
        boolean typed = mMessageEditText != null && mMessageEditText.getText() != null
                && mMessageEditText.getText().toString().trim().length() > 0;
        return typed || (mComposer != null && mComposer.hasFiles()) || mPendingPicks > 0;
    }

    private boolean isComposerInUse() {
        return hasDraft()
                || (mTake != null && mTake.isOpen())
                || (mMessageEditText != null && mMessageEditText.hasFocus())
                || (mRecorder != null && mRecorder.isRecording());
    }

    private void updateComposerTuck(boolean animate) {
        setComposerTucked(mComposerOverReel && !isComposerInUse(), animate);
    }

    private void setComposerTucked(boolean tucked, boolean animate) {
        if (mComposerBar == null || tucked == mComposerTucked) return;
        mComposerTucked = tucked;
        View bar = mComposerBar;
        bar.animate().cancel();
        if (tucked) {
            float away = bar.getHeight();
            if (!animate) {
                bar.setTranslationY(away);
                bar.setAlpha(0f);
                bar.setVisibility(View.INVISIBLE);
                return;
            }
            // Invisible once out of sight, so taps along the bottom reach the video.
            bar.animate().translationY(away).alpha(0f).setDuration(COMPOSER_SLIDE_MS)
                    .withEndAction(() -> bar.setVisibility(View.INVISIBLE));
        } else {
            bar.setVisibility(View.VISIBLE);
            if (!animate) {
                bar.setTranslationY(0f);
                bar.setAlpha(1f);
                return;
            }
            bar.animate().translationY(0f).alpha(1f).setDuration(COMPOSER_SLIDE_MS);
        }
    }

    private void setupComposer(View view) {
        mComposer = new CommentComposerController(view, this);
        mInlineStatus = view.findViewById(R.id.comment_inline_status);
        try {
            String token = BraveActivity.getBraveActivity().getAccessToken();
            if (token != null && !token.isEmpty()) {
                ProfileAvatar.load(mAvatarImage, ProfileAvatar.selfUrl(token), null);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("CommentsSheet", "No activity for the reader's avatar", e);
        }
        CommentUploadManager.getInstance().addListener(this);
    }

    @Override
    public void onDestroyView() {
        if (mPageInsetCallbacks != null) {
            getChildFragmentManager().unregisterFragmentLifecycleCallbacks(mPageInsetCallbacks);
            mPageInsetCallbacks = null;
        }
        if (mComposerBar != null) mComposerBar.animate().cancel();
        mComposerBar = null;
        CommentUploadManager.getInstance().removeListener(this);
        mToastHandler.removeCallbacksAndMessages(null);
        if (mComposer != null) mComposer.release();
        mComposer = null;
        // A take sheet open as the view goes must not keep every video in the app paused.
        GlobalVideoPlaybackManager.getInstance().setTakeComposerOpen(false);
        super.onDestroyView();
    }

    /**
     * Forces the dialog's sheet container to fill the window instead of wrapping its content.
     *
     * <p>{@code design_bottom_sheet} is declared {@code wrap_content}, which measures every
     * match_parent view under it — the reel pager included — against the height of whatever
     * happens to be inside rather than the height of the screen. Full pages hide that, because
     * their content overflows and gets clamped back to the screen; a page with one comment or
     * none does not, and the sheet opens part-way up.
     *
     * <p>Safe to call more than once, and does nothing until the container has been attached to
     * the dialog window — which is why {@link #onStart()} calls it again.
     */
    private void setSheetContainerFullHeight(BottomSheetDialog dialog) {
        View container = dialog.findViewById(R.id.design_bottom_sheet);
        if (container == null) return;
        ViewGroup.LayoutParams params = container.getLayoutParams();
        if (params == null || params.height == ViewGroup.LayoutParams.MATCH_PARENT) return;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        container.setLayoutParams(params);
    }

    @Override
    public void onStart() {
        super.onStart();
        // The container is guaranteed to be in the window by now: super.onStart() shows the
        // dialog, which cannot happen before its content view is set. onViewCreated may have
        // run too early to find it.
        if (useReelPresentation() && getDialog() instanceof BottomSheetDialog) {
            setSheetContainerFullHeight((BottomSheetDialog) getDialog());
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        BottomSheetBehavior behavior = dialog.getBehavior();

        if (!useReelPresentation()) {
            // YouTube keeps the partly-open sheet so the video stays visible behind it.
            int peekHeight = (int) (screenHeight * 0.6);
            behavior.setMaxHeight(screenHeight);
            behavior.setPeekHeight(peekHeight);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            // The dialog's own sheet container is declared wrap_content, so with little to show
            // — no comments, or a single short one — the whole surface measures down to that
            // content and opens part-way up the screen. Every view below it is match_parent, so
            // the reel pager collapses with it, and since the pager's height is what
            // CommentSlidePacker packs against, a lone portrait video ends up sized to its row
            // instead of the full-bleed page it should get. Pinning the container to full height
            // makes the measurement exact and independent of how many comments arrived.
            setSheetContainerFullHeight(dialog);
            behavior.setMaxHeight(screenHeight);
            behavior.setPeekHeight(screenHeight);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            // Vertical drags belong to the reel pager. Leaving the sheet draggable would let it
            // swallow the swipe and slide the whole surface down instead of turning the page.
            behavior.setDraggable(false);
            if (mSheetHandle != null) mSheetHandle.setVisibility(View.GONE);
        }

        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Opened by a tap on a post's video: the comments are what the reader lands on, with the
        // video stacked over them as its own page. Only on a fresh open — after a recreate the
        // viewer restores itself, and re-showing it would stack a second copy.
        // The viewer marks itself open once created; marking it here too covers the frame before
        // that, in case the comments land first and their first page is a reel.
        if (savedInstanceState == null && mPostVideoViewerArgs != null) {
            GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(true);
            PostVideoViewerFragment.fromArguments(mPostVideoViewerArgs)
                    .show(getChildFragmentManager(), PostVideoViewerFragment.TAG);
        } else if (savedInstanceState == null
                && mPostVideoUrl != null
                && !mPostVideoUrl.isEmpty()) {
            GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(true);
            MediaViewerFragment.newInstance(
                    Uri.parse(mPostVideoUrl), "video", false, getString(R.string.video))
                    .show(getChildFragmentManager(), MediaViewerFragment.class.getSimpleName());
        }

        // Keeps the input bar and a reel page's overlay buttons above the navigation bar on
        // edge-to-edge devices; see EdgeToEdgeUtil.
        EdgeToEdgeUtil.padBottomBySystemBars(view);

        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                Log.e("ROOT_VIEW_KEY", "keyCode: " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (closeTakeOnBack()) return true;
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 2) {
                        hideKeyboard();
                        openComments(false);
                        return true;
                    }
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        hideKeyboard();
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
                    if (closeTakeOnBack()) return true;
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 2) {
                        hideKeyboard();
                        openComments(false);
                        return true;
                    }
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        hideKeyboard();
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
                    if (closeTakeOnBack()) return;
                    FragmentManager fragmentManager = getChildFragmentManager();
                    Log.e("BACK BUTTON PRESSED 2", fragmentManager.getBackStackEntryCount() + "");
                    if (fragmentManager.getBackStackEntryCount() > 2) {
                        hideKeyboard();
                        openComments(false);
                    } else if (fragmentManager.getBackStackEntryCount() > 1) {
                        hideKeyboard();
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
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }

    /**
     * Lets the videos on the showing page play again once a full-screen viewer stacked over the
     * comments has closed.
     */
    public void resumeVisibleVideos() {
        Fragment active = getChildFragmentManager().findFragmentById(R.id.bottom_sheet_container);
        if (active instanceof CommentReelFragment) {
            ((CommentReelFragment) active).triggerVideoVisibilityCheck();
        } else if (active instanceof CommentListFragment) {
            ((CommentListFragment) active).triggerVideoVisibilityCheck();
        } else if (active instanceof ReplyListFragment) {
            ((ReplyListFragment) active).triggerVideoVisibilityCheck();
        } else if (active instanceof ReplyListFragment2) {
            ((ReplyListFragment2) active).triggerVideoVisibilityCheck();
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        FragmentActivity host = getActivity();
        if (mOpenedAtMs > 0 && (host == null || !host.isChangingConfigurations())) {
            PostHogUtil.capture(
                    PostHogEventKeys.COMMENTS_CLOSED,
                    "comments_for",
                    mCommentsFor,
                    "post_id",
                    mPostId,
                    "duration_ms",
                    SystemClock.elapsedRealtime() - mOpenedAtMs,
                    "replies_opened",
                    mRepliesOpened,
                    "comments_sent",
                    mCommentsSent);
            mOpenedAtMs = 0;
        }
        // Nothing of the sheet is left to cover, and the flags are process-wide.
        GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(false);
        GlobalVideoPlaybackManager.getInstance().setTakeComposerOpen(false);
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();
        try {
            BraveActivity.getBraveActivity().refreshPageReactionsIfChanged();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("CommentsSheet", "refreshPageReactionsIfChanged " + e);
        }
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

    public void openReplies(String commentId, String parentCommentJson) {
        mRepliesShowing = true;
        mRepliesOpened++;
        mLastOpenedRepliesForCommentId = commentId;
        mLastOpenedRepliesToRepliesForCommentId = null;
        mLastOpenedL1ParentJson = parentCommentJson;
        ReplyListFragment replyFragment = new ReplyListFragment();
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        if (mCommentsFor != null) args.putString(ReplyListFragment.COMMENTS_FOR, mCommentsFor);
        if (mVideoId != null) {
            args.putString(CommentListFragment.VIDEO_ID, mVideoId);
            args.putString(ReplyListFragment.PAGE_URL,
                    "https://www.youtube.com/watch?v=" + mVideoId);
        }
        if (parentCommentJson != null) args.putString(ReplyListFragment.PARENT_COMMENT_JSON, parentCommentJson);
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
    }

    public void openRepliesToReply(String commentId) {
        openRepliesToReply(commentId, null);
    }

    public void openRepliesToReply(String commentId, String parentCommentJson) {
        mRepliesShowing = true;
        mRepliesOpened++;
        mLastOpenedRepliesToRepliesForCommentId = commentId;
        ReplyListFragment2 replyFragment = new ReplyListFragment2();
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        if (mCommentsFor != null) args.putString(ReplyListFragment2.COMMENTS_FOR, mCommentsFor);
        if (mVideoId != null) {
            args.putString(ReplyListFragment2.PAGE_URL,
                    "https://www.youtube.com/watch?v=" + mVideoId);
        }
        if (parentCommentJson != null) args.putString(ReplyListFragment2.PARENT_COMMENT_JSON, parentCommentJson);
        if (mLastOpenedL1ParentJson != null) {
            args.putString(ReplyListFragment2.L1_ANCESTOR_JSON, mLastOpenedL1ParentJson);
        }
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
    }

    public void dismissBottomsheet() {
        dismiss();
    }

    public void openComments() {
        openComments(true);
    }

    public void openComments(Boolean clearLastOpenedReplies) {
        if(clearLastOpenedReplies) {
            mLastOpenedRepliesToRepliesForCommentId = null;
        }
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        FragmentManager fragmentManager = getChildFragmentManager();
        fragmentManager.popBackStack();
    }

    @Override
    public EditText getInputEditText() {
        return mMessageEditText;
    }

    @Override
    public View getSendButton() {
        return mSendButton;
    }

    @Override
    public void setInputEnabled(boolean enabled) {
        if (mMessageEditText != null) mMessageEditText.setEnabled(enabled);
        if (mSendButton != null) mSendButton.setEnabled(enabled);
    }

    @Nullable
    public String getLastOpenedRepliesToRepliesForCommentId() {
        return mLastOpenedRepliesToRepliesForCommentId;
    }

    public void clearLastOpenedRepliesToRepliesForCommentId() {
        mLastOpenedRepliesToRepliesForCommentId = null;
    }

    /**
     * Deep-link target from a push notification. Reuses the "last opened replies" hook:
     * CommentListFragment.onResume() scrolls to (and flashes) this id, deferring until
     * the first page has loaded if needed.
     */
    public void setTargetCommentId(@Nullable String commentId) {
        if (commentId == null || commentId.isEmpty()) return;
        mLastOpenedRepliesForCommentId = commentId;
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
            ProfileAvatar.load(mAvatarImage, avatarUrl, null);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Voice notes: "Voice note" in the attachment sheet starts recording; the recording bar's
    // send posts it and its cross throws it away.
    // -------------------------------------------------------------------------------------------

    private void setupVoiceNoteRecording(View root) {
        root.findViewById(R.id.button_recording_discard)
                .setOnClickListener(v -> finishRecording(true));
        root.findViewById(R.id.button_recording_send)
                .setOnClickListener(v -> finishRecording(false));
    }

    private void beginVoiceNote() {
        if (!hasMicPermission()) {
            mMicPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private boolean hasMicPermission() {
        return getContext() != null
                && androidx.core.content.ContextCompat.checkSelfPermission(
                           getContext(), android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void startRecording() {
        if (getContext() == null) return;

        // Anything already playing has to stop: the mic and a voice note through the speaker
        // would end up recording each other.
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();
        hideKeyboard();

        mRecorder = new AudioCommentRecorder(getContext());
        if (!mRecorder.start()) {
            mRecorder = null;
            Toast.makeText(getContext(), R.string.be_mic_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        mRecordingBarLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mInputBarLayout.setVisibility(View.GONE);
        mRecordingBarLayout.setVisibility(View.VISIBLE);
        mRecordingTimer.setText(AudioTime.format(0));
        mRecordingWaveform.startLiveMode();
        mRecordingHandler.post(mRecordingTick);
    }

    /** @param cancelled true to discard the take rather than post it. */
    private void finishRecording(boolean cancelled) {
        mRecordingHandler.removeCallbacks(mRecordingTick);
        if (mRecorder == null || !mRecorder.isRecording()) {
            restoreInputBar();
            return;
        }

        if (cancelled) {
            mRecorder.cancel();
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.be_recording_cancelled, Toast.LENGTH_SHORT)
                        .show();
            }
        } else {
            AudioCommentRecorder.Result result = mRecorder.stop();
            if (result == null) {
                // Too short to be a message, or the encoder captured nothing.
                if (getContext() != null) {
                    Toast.makeText(getContext(), R.string.be_recording_too_short,
                                 Toast.LENGTH_SHORT)
                            .show();
                }
            } else {
                // The take previews above the box and goes with the next send (Figma "05 · Voice
                // note"), so the reader can listen to it and add a note first.
                addPicked(java.util.Collections.singletonList(result.getUri()),
                        CommentAttachment.KIND_AUDIO, result.getDurationMs());
            }
        }

        mRecorder = null;
        restoreInputBar();
    }

    private void restoreInputBar() {
        if (mRecordingBarLayout != null) mRecordingBarLayout.setVisibility(View.GONE);
        if (mInputBarLayout != null) mInputBarLayout.setVisibility(View.VISIBLE);
        updateComposerTuck(true);
    }

    /**
     * Posts a text comment from the comment box. Used by the reel page; the other pages still
     * post text on their own.
     */
    public void postTextComment(String content) {
        if (content == null || content.trim().isEmpty()) return;
        sendComment(content, java.util.Collections.emptyList());
    }

    // -------------------------------------------------------------------------------------------
    // Comment box (CommentComposerController.Host)
    // -------------------------------------------------------------------------------------------

    @Override
    public void onRecordRequested() {
        if (needsTakeSheet()) {
            openTakeComposer(false, "mic");
            return;
        }
        beginVoiceNote();
    }

    @Override
    public boolean onRecordPressed() {
        // Without the permission the press ends as a tap, and the tap asks for it; on the
        // comments the tap opens "Add your take" first.
        if (!hasMicPermission() || needsTakeSheet()) return false;
        startRecording();
        return true;
    }

    @Override
    public void onRecordHoldReleased() {
        finishRecording(false);
    }

    @Override
    public void onSendText() {
        // The page showing binds its own posting to the send button (see getSendButton).
        if (mSendButton != null) mSendButton.performClick();
        // Sent: the take sheet closes at once while the take posts.
        if (mTake != null && mTake.isOpen() && !hasDraft()) mTake.close();
    }

    @Override
    public void onSendDraft(String content, List<CommentUploadManager.Item> items) {
        sendComment(content, items);
    }

    @Override
    public void onDraftChanged() {
        updateComposerTuck(true);
    }

    /**
     * Sends a comment: it shows at once as an outgoing card, disabled while its files upload,
     * and settles when the server has it (CommentUploadManager).
     */
    private void sendComment(String content, List<CommentUploadManager.Item> items) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();

            // A reply being written goes to its comment; anything else to the post or the page.
            String parentType;
            String parentId = null;
            String pageUrl = null;
            if ("comment".equals(mTempType) && mTempPostId != null) {
                parentType = "comment";
                parentId = mTempPostId;
            } else if ("post".equals(mCommentsFor) && mPostId != null && !mPostId.isEmpty()) {
                parentType = "post";
                parentId = mPostId;
            } else {
                parentType = "page";
                if (activity.getActivityTab() != null) {
                    pageUrl = activity.getActivityTab().getUrl().getSpec();
                }
            }

            String tempId = "temp_" + java.util.UUID.randomUUID().toString();
            Comment optimistic = new Comment(tempId, content, 0, 0, 0, null,
                    "post".equals(parentType) ? parentId : null,
                    "comment".equals(parentType) ? parentId : null,
                    currentUser(activity, accessToken), null, null, null, null, null, null);
            optimistic.setOutgoing(true);
            optimistic.setUploadStatus(Comment.UploadStatus.POSTING);
            if (!items.isEmpty()) {
                List<CommentAttachment> attachments = new java.util.ArrayList<>();
                for (CommentUploadManager.Item item : items) attachments.add(item.toAttachment());
                optimistic.setAttachments(attachments);
                if (items.size() == 1 && items.get(0).isAudio()) {
                    // A lone voice note keeps its player row on the card.
                    optimistic.setMediaAudioUrl(items.get(0).uri.toString());
                    optimistic.setMediaDurationMs(items.get(0).durationMs);
                }
            }
            mOutgoing.put(tempId, optimistic);
            mSentDrafts.put(tempId, android.util.Pair.create(content, items));
            addToVisiblePage(optimistic);
            hideKeyboard();

            reportCommented(content, parentType, parentId, pageUrl, items);
            CommentUploadManager.Job job = CommentUploadManager.getInstance().send(
                    tempId, content, parentType, parentId, pageUrl, items, accessToken);
            // Text alone posts straight away; files show their upload in the banner.
            if (job.hasFiles() && mBanner != null) mBanner.showUploading(job.tempId, isReelJob(job), 0f);
            // Sent, however it was (text, files, a voice note): the take sheet closes at once.
            if (mTake != null) mTake.close();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("CommentsSheet", "Cannot post a comment without BraveActivity", e);
        }
    }

    private void reportCommented(String content, String parentType, @Nullable String parentId,
            @Nullable String pageUrl, List<CommentUploadManager.Item> items) {
        mCommentsSent++;
        JSONObject payload = new JSONObject();
        try {
            payload.put("content", content);
            payload.put("type", parentType);
            payload.put("url", pageUrl);
            payload.put("post_id", "post".equals(parentType) ? parentId : null);
            payload.put("attachments", items.size());
            payload.put("attachment_kinds", CommentUploadManager.kindsOf(items));
            payload.put("from_take_sheet", mTake != null && mTake.isOpen());
        } catch (JSONException e) {
            // Analytics only.
        }
        PostHogUtil.capture(PostHogEventKeys.COMMENTED, payload);
    }

    // -------------------------------------------------------------------------------------------
    // Upload progress (CommentUploadManager.Listener)
    // -------------------------------------------------------------------------------------------

    @Override
    public void onJobUpdated(CommentUploadManager.Job job) {
        Comment optimistic = mOutgoing.get(job.tempId);
        if (optimistic == null) return; // Sent from another sheet.
        switch (job.getState()) {
            case UPLOADING: {
                boolean wasFailed = optimistic.getUploadStatus() == Comment.UploadStatus.FAILED;
                optimistic.setUploadStatus(Comment.UploadStatus.POSTING);
                optimistic.setUploadProgress(job.getProgress());
                if (wasFailed) {
                    // Retry and Cancel swap places, which can change the card's height.
                    repackAround(job.tempId);
                } else {
                    refreshUploadState(job.tempId);
                }
                if (job.hasFiles() && mBanner != null) {
                    mBanner.showUploading(job.tempId, isReelJob(job), job.getProgress());
                }
                break;
            }
            case FAILED:
                optimistic.setUploadStatus(Comment.UploadStatus.FAILED);
                optimistic.setUploadError(job.getError());
                markFailedOnVisiblePage(job.tempId);
                if (mBanner != null) {
                    mBanner.showFailed(job.getError(),
                            () -> CommentUploadManager.getInstance().retry(job.tempId));
                }
                break;
            case CANCELLED: {
                mOutgoing.remove(job.tempId);
                removeFromVisiblePage(job.tempId);
                android.util.Pair<String, List<CommentUploadManager.Item>> draft =
                        mSentDrafts.remove(job.tempId);
                CommentUploadManager.getInstance().dismiss(job.tempId);
                // The comment goes back in the box, files and all (Figma "14 · Upload cancelled").
                if (draft != null && mComposer != null) mComposer.restore(draft.first, draft.second);
                if (mBanner != null) mBanner.hide();
                showInlineStatus(R.string.browser_express_comment_upload_cancelled_inline);
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onJobPosted(CommentUploadManager.Job job, Comment comment) {
        Comment optimistic = mOutgoing.remove(job.tempId);
        mSentDrafts.remove(job.tempId);
        if (optimistic == null) return;
        // The card stays the reader's outgoing one, outlined in the accent for a moment
        // (Figma "08 · Posted").
        comment.setOutgoing(true);
        comment.setJustPosted(true);
        // The card keeps drawing the reader's own files: they are on the device already, and a
        // video's still would otherwise mean fetching the uploaded video again.
        if (!optimistic.getAttachments().isEmpty()) {
            comment.setAttachments(optimistic.getAttachments());
        }
        onCommentPostSucceeded(job.tempId, comment);
        if (mBanner != null) {
            String postedId = comment.getId();
            CharSequence sub = "comment".equals(job.parentType)
                    ? getString(R.string.browser_express_take_posted_reply)
                    : getString(R.string.browser_express_take_posted_to, takeTopicName());
            mBanner.showPosted(postedId, isReelJob(job), sub, () -> repackAround(postedId));
        }
        mToastHandler.postDelayed(() -> {
            comment.setJustPosted(false);
            refreshUploadState(comment.getId());
        }, POSTED_HIGHLIGHT_MS);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (!"comment".equals(job.parentType)) activity.incrementPageCommentCount();
            // A first comment is the moment replies become worth hearing about.
            org.chromium.chrome.browser.browser_express_push.BrowserExpressPushTokenManager
                    .maybePromptForPermissionAfterComment(activity);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("CommentsSheet", "No activity after posting", e);
        }
    }

    @Override
    public void retryUpload(String commentId) {
        CommentUploadManager.getInstance().retry(commentId);
    }

    @Override
    public void cancelUpload(String commentId) {
        CommentUploadManager.getInstance().cancel(commentId);
    }

    /**
     * "Karma earned": on a reel the reader's picture slides out from behind the "Add your take"
     * pill; anywhere else it is the comment bar's avatar that shimmers. Only an account earns
     * Karma.
     */
    @Override
    public void onReactionAdded(View row, boolean overReel) {
        String token;
        try {
            token = BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (ProfileAvatar.selfId(token) == null || mAvatarImage == null) return;
        View page = overReel ? KarmaEarned.reelPageOf(row) : null;
        if (page != null) {
            Drawable picture = mAvatarImage.getDrawable();
            Drawable.ConstantState state = picture != null ? picture.getConstantState() : null;
            KarmaEarned.playOnReel(
                    page, state != null ? state.newDrawable(getResources()) : null, token);
            return;
        }
        // The avatar's slot stays put when picked files cover the picture itself.
        View slot = (View) mAvatarImage.getParent();
        View root = getView();
        if (root == null || slot == null || !slot.isShown()) return;
        KarmaEarned.playOnAvatar(root, slot, mAvatarImage.getVisibility() == View.VISIBLE, token);
    }

    // -------------------------------------------------------------------------------------------
    // "Add your take" (TakeComposer.Host)
    // -------------------------------------------------------------------------------------------

    /** A take of one portrait video, which the banner calls a reel. */
    private static boolean isReelJob(CommentUploadManager.Job job) {
        CommentUploadManager.Item video = job.singleVideo();
        return video != null && video.height > video.width && video.width > 0;
    }

    /** What the comments are about, in a line: the post's caption or the page's title. */
    private String topicTitle() {
        SourceChip.Source source = currentSource();
        if (source.text != null && !source.text.trim().isEmpty()) return source.text.trim();
        return source.author != null ? source.author : getString(R.string.comments);
    }

    /** On the comments themselves (not a replies page) the box only opens "Add your take". */
    private boolean needsTakeSheet() {
        return mTake != null && !mTake.isOpen() && !mRepliesShowing;
    }

    @Override
    public void openTakeComposer() {
        if (mRepliesShowing) return;
        openTakeComposer(false, "take_button");
    }

    @Override
    public CharSequence takeEntryLabel() {
        return getString(R.string.browser_express_take_open_on, takeTopicName());
    }

    /** A comments page heard the post's or page's totals; -1 / null for what it left out. */
    void onTopicTotals(int commentCount, int emoteCount, @Nullable String hashtag) {
        if (commentCount >= 0) mTopicComments = commentCount;
        if (emoteCount >= 0) mTopicEmotes = emoteCount;
        if (hashtag != null) mTopicHashtag = hashtag;
    }

    @Override
    public @Nullable String topicHashtag() {
        String hashtag = mTopicHashtag != null ? mTopicHashtag : currentSource().hashtag;
        return hashtag != null ? "#" + hashtag : null;
    }

    @Override
    public @Nullable String sharePostId() {
        return "post".equals(mCommentsFor) ? mPostId : null;
    }

    /** The take card's title: the post's hashtag, else its caption or the page's title. */
    private String takeTopicName() {
        String hashtag = topicHashtag();
        return hashtag != null ? hashtag : topicTitle();
    }

    /** @param openedFrom what opened it, for analytics: text_box, mic, attach or take_button. */
    private void openTakeComposer(boolean picker, String openedFrom) {
        if (mTake == null || getContext() == null) return;
        PostHogUtil.capture(
                PostHogEventKeys.TAKE_COMPOSER_OPENED,
                "source",
                openedFrom,
                "comments_for",
                mCommentsFor,
                "post_id",
                mPostId);
        Fragment page = getChildFragmentManager().findFragmentById(R.id.bottom_sheet_container);
        List<Comment> comments = page instanceof CommentReelFragment
                ? ((CommentReelFragment) page).getLoadedComments()
                : page instanceof CommentListFragment
                ? ((CommentListFragment) page).getLoadedComments()
                : java.util.Collections.emptyList();
        SourceChip.Source source = currentSource();
        mTake.open(TakeComposer.topicFrom(takeTopicName(),
                           mTopicComments >= 0 ? mTopicComments : source.commentCount,
                           mTopicEmotes >= 0 ? mTopicEmotes : source.emoteCount, comments),
                picker);
    }

    /** Back first puts the picker away, then closes the take sheet (asking if need be). */
    private boolean closeTakeOnBack() {
        if (mTake == null || !mTake.isOpen()) return false;
        if (mTake.isPickerShown()) {
            mTake.hidePicker(true);
        } else {
            mTake.requestClose();
        }
        return true;
    }

    @Override
    public boolean hasTakeDraft() {
        return hasDraft();
    }

    @Override
    public void discardTakeDraft() {
        PostHogUtil.capture(PostHogEventKeys.TAKE_DISCARDED, "post_id", mPostId);
        if (mComposer != null) mComposer.restore("", java.util.Collections.emptyList());
    }

    @Override
    public void showComposerNow() {
        setComposerTucked(false, false);
    }

    @Override
    public void focusTakeInput() {
        showKeyboardWithFocus();
    }

    @Override
    public void hideTakeKeyboard() {
        hideKeyboard();
    }

    @Override
    public void onTakeAttachmentChosen(@CommentAttachmentSheet.Kind int kind) {
        onAttachmentKindChosen(kind);
    }

    @Override
    public boolean isTakeOverReel() {
        return mComposerOverReel;
    }

    @Override
    public @Nullable View takePages() {
        return getView() != null ? getView().findViewById(R.id.bottom_sheet_container) : null;
    }

    @Override
    public void onTakeClosed() {
        applyComposerInset(getChildFragmentManager().findFragmentById(R.id.bottom_sheet_container));
        updateComposerTuck(true);
        // The reel paused behind the sheet plays on.
        resumeVisibleVideos();
    }

    // -------------------------------------------------------------------------------------------
    // The inline status over the comment box (Figma "Inline upload status")
    // -------------------------------------------------------------------------------------------

    private final Runnable mHideInlineStatus = () -> {
        if (mInlineStatus != null) mInlineStatus.setVisibility(View.GONE);
    };

    private void showInlineStatus(int messageId) {
        if (mInlineStatus == null) return;
        mInlineStatus.setText(messageId);
        mInlineStatus.setVisibility(View.VISIBLE);
        mToastHandler.removeCallbacks(mHideInlineStatus);
        mToastHandler.postDelayed(mHideInlineStatus, TOAST_MS * 2);
    }

    /** The signed-in reader, for an optimistic comment's author line; null for a guest. */
    @Nullable
    private User currentUser(BraveActivity activity, @Nullable String accessToken) {
        JSONObject decoded = decodeToken(accessToken);
        if (decoded == null) return null;
        return new User(decoded.optString("_id", ""), decoded.optString("username", ""),
                ProfileAvatar.selfUrl(accessToken));
    }

    private JSONObject decodeToken(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return null;
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return null;
            byte[] data = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(data, "UTF-8"));
        } catch (JSONException | UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e("CommentsSheet", "Could not decode access token", e);
            return null;
        }
    }

    private void setupAttachmentListeners() {
        mAttachButton.setOnClickListener(v -> {
            mAttachButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (needsTakeSheet()) {
                openTakeComposer(true, "attach");
                return;
            }
            if (mTake != null && mTake.isOpen()) {
                mTake.showPicker();
                return;
            }
            hideKeyboard();
            new CommentAttachmentSheet().show(getChildFragmentManager(), CommentAttachmentSheet.TAG);
        });
        mEmojiButton.setOnClickListener(v -> {
            hideKeyboard();
            new CommentEmojiSheet().show(getChildFragmentManager(), CommentEmojiSheet.TAG);
        });
        mRemoveAttachmentButton.setOnClickListener(v -> removeAttachment());
    }

    @Override
    public void onAttachmentKindChosen(@CommentAttachmentSheet.Kind int kind) {
        PostHogUtil.capture(
                PostHogEventKeys.COMMENT_ATTACHMENT_CHOSEN,
                "kind",
                CommentAttachmentSheet.kindName(kind),
                "in_take_sheet",
                mTake != null && mTake.isOpen());
        switch (kind) {
            case CommentAttachmentSheet.Kind.PHOTO:
                pickVisualMedia(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
                break;
            case CommentAttachmentSheet.Kind.VIDEO:
                pickVisualMedia(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE);
                break;
            case CommentAttachmentSheet.Kind.CAMERA:
                takePhoto();
                break;
            case CommentAttachmentSheet.Kind.VOICE:
                beginVoiceNote();
                break;
            case CommentAttachmentSheet.Kind.GIF:
                new GifPickerSheet().show(getChildFragmentManager(), GifPickerSheet.TAG);
                break;
            case CommentAttachmentSheet.Kind.DOCUMENT:
                try {
                    mDocumentPickerLauncher.launch(CommentDocuments.PICKER_MIME_TYPES);
                } catch (android.content.ActivityNotFoundException e) {
                    Log.e("CommentsSheet", "No document picker available", e);
                }
                break;
            default:
                break;
        }
    }

    private void pickVisualMedia(ActivityResultContracts.PickVisualMedia.VisualMediaType type) {
        mMediaPickerLauncher.launch(new PickVisualMediaRequest.Builder().setMediaType(type).build());
    }

    /**
     * The camera needs the runtime permission here only because the app declares CAMERA in its
     * manifest (for the web): with it declared, the capture intent refuses to start without it.
     */
    private void takePhoto() {
        if (getContext() == null) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        if (getContext() == null) return;
        java.io.File dir = new java.io.File(
                getContext().getCacheDir(), GifPickerSheet.MEDIA_CACHE_DIR);
        java.io.File photo = new java.io.File(dir, "camera_" + System.currentTimeMillis() + ".jpg");
        Uri uri = null;
        if (dir.exists() || dir.mkdirs()) {
            uri = org.chromium.base.FileProviderUtils.getContentUriFromFile(photo);
        }
        if (uri == null) {
            Toast.makeText(getContext(), R.string.browser_express_attach_camera_unavailable,
                         Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        mPendingCameraUri = uri;
        try {
            mCameraLauncher.launch(uri);
        } catch (android.content.ActivityNotFoundException e) {
            mPendingCameraUri = null;
            Toast.makeText(getContext(), R.string.browser_express_attach_camera_unavailable,
                         Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void onDocumentsPicked(@Nullable List<Uri> uris) {
        if (uris == null || uris.isEmpty() || getContext() == null) return;
        List<Uri> accepted = new java.util.ArrayList<>();
        for (Uri uri : uris) {
            String mimeType = getContext().getContentResolver().getType(uri);
            if (!CommentDocuments.isSupported(mimeType)) {
                Toast.makeText(getContext(), R.string.browser_express_attach_document_unsupported,
                             Toast.LENGTH_SHORT)
                        .show();
                continue;
            }
            if (CommentDocuments.size(getContext(), uri) > CommentDocuments.MAX_BYTES) {
                Toast.makeText(getContext(), R.string.browser_express_attach_document_too_large,
                             Toast.LENGTH_SHORT)
                        .show();
                continue;
            }
            accepted.add(uri);
        }
        addPicked(accepted, CommentAttachment.KIND_DOCUMENT, 0);
    }

    @Override
    public void onGifPicked(Uri gifUri) {
        addPicked(java.util.Collections.singletonList(gifUri), CommentAttachment.KIND_GIF, 0);
    }

    @Override
    public void onEmojiPicked(String emoji) {
        if (mMessageEditText == null) return;
        android.text.Editable text = mMessageEditText.getText();
        int start = mMessageEditText.getSelectionStart();
        int end = mMessageEditText.getSelectionEnd();
        if (start < 0 || end < 0) {
            // No cursor yet: the emoji goes at the end of whatever is typed.
            start = text.length();
            end = start;
        }
        text.replace(Math.min(start, end), Math.max(start, end), emoji);
        showKeyboardWithFocus();
    }

    /**
     * Fills the source chip under the comments title (Figma "Source Post Chips") with what the
     * comments are on: the feed post with its photo, video or voice note, or the page (a YouTube
     * video shows its thumbnail).
     */
    public void bindSourceChip(View root) {
        SourceChip chip = SourceChip.in(root);
        if (chip == null || getContext() == null) return;
        chip.bind(currentSource(), getChildFragmentManager());
    }

    /** What the comments are about: the feed post, or the page (and a YouTube video's still). */
    private SourceChip.Source currentSource() {
        SourceChip.Source source;
        if ("post".equals(mCommentsFor)) {
            source = SourceChip.Source.forPost(mPostUsernameString, mPostAvatarString,
                    mPostContentString, mPostSourceMedia, mPostVideoUrl);
        } else {
            String host = null;
            String title = null;
            try {
                org.chromium.chrome.browser.tab.Tab tab =
                        BraveActivity.getBraveActivity().getActivityTab();
                if (tab != null) {
                    title = tab.getTitle();
                    host = tab.getUrl().getHost();
                    if (host != null && host.startsWith("www.")) {
                        host = host.substring(4);
                    }
                }
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e("CommentsSheet", "No activity for the source chip", e);
            }
            String thumbUrl = mVideoId != null && !mVideoId.isEmpty()
                    ? "https://i.ytimg.com/vi/" + mVideoId + "/mqdefault.jpg"
                    : null;
            source = SourceChip.Source.forPage(host, title, thumbUrl);
        }
        return source;
    }

    /** Whether the comments are on a feed post (rather than a web page or video). */
    public boolean isForPost() {
        return "post".equals(mCommentsFor);
    }

    private void removeAttachment() {
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

    public void showKeyboardWithFocus() {
        if (mMessageEditText != null && getContext() != null && isAdded()) {
            // Over a video the box may be put away, and only a showing box can take focus.
            setComposerTucked(false, true);
            mMessageEditText.post(() -> {
                if (getContext() != null && isAdded() && mMessageEditText != null) {
                    mMessageEditText.requestFocus();
                    // Back out of sight if the box could not take focus after all.
                    updateComposerTuck(true);
                    
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

    public void hideKeyboard() {
        if (getContext() == null || getView() == null) return;

        if (mMessageEditText != null && getContext() != null) {
            mMessageEditText.post(() -> {
                if (getContext() != null && mMessageEditText != null) {
                    mMessageEditText.clearFocus();
                    
                    if (mMessageEditText.getText() != null) {
                        mMessageEditText.setSelection(mMessageEditText.getText().length());
                    }
                }
            });
        }

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
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
        String pageUrl = null;
        if ("post".equals(type) && (postId == null || postId.isEmpty())) {
            // The composer only knows posts and replies. With no post behind the comments they
            // are on a web page, which the API addresses by URL.
            type = "page";
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                if (activity.getActivityTab() != null) {
                    pageUrl = activity.getActivityTab().getUrl().getSpec();
                }
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e("CommentsSheet", "No activity to resolve the page URL", e);
            }
        }
        enqueueCommentUpload(
                postId, type, pageUrl, optimisticComment, mediaUri, mediaType, 0, accessToken);
    }

    /**
     * Hands an optimistic comment to the upload service and shows it straight away.
     *
     * @param pageUrl page the comment belongs to, for "page" comments. The attachment path has
     *     never sent this, which is why it is nullable rather than required.
     * @param mediaDurationMs length of a voice note; 0 for everything else.
     */
    private void enqueueCommentUpload(String postId, String type, String pageUrl,
            Comment optimisticComment, Uri mediaUri, String mediaType, int mediaDurationMs,
            String accessToken) {
        if (getContext() == null) return;

        Intent uploadIntent = new Intent(getContext(), UploadService.class);
        uploadIntent.setAction(UploadService.ACTION_UPLOAD_COMMENT);

        // Only content:// URIs can be granted. Picker attachments are content URIs; a recorded
        // voice note is a file:// URI in our own cache, and granting on that throws.
        if (mediaUri != null && ContentResolver.SCHEME_CONTENT.equals(mediaUri.getScheme())) {
            uploadIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().grantUriPermission(getContext().getPackageName(), mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        uploadIntent.putExtra(UploadService.EXTRA_TEMP_ID, optimisticComment.getId());
        uploadIntent.putExtra(UploadService.EXTRA_COMMENT_CONTENT, optimisticComment.getContent());
        uploadIntent.putExtra(UploadService.EXTRA_POST_ID, postId);
        if (pageUrl != null) uploadIntent.putExtra(UploadService.EXTRA_URL, pageUrl);
        uploadIntent.putExtra(UploadService.EXTRA_MEDIA_URI, mediaUri);
        uploadIntent.putExtra(UploadService.EXTRA_MEDIA_TYPE, mediaType);
        if (mediaDurationMs > 0) {
            uploadIntent.putExtra(UploadService.EXTRA_MEDIA_DURATION_MS, mediaDurationMs);
        }
        uploadIntent.putExtra(UploadService.EXTRA_ACCESS_TOKEN, accessToken);
        uploadIntent.putExtra(UploadService.EXTRA_COMMENT_TYPE, type);

        UploadService.enqueueWork(getContext(), uploadIntent);
        addToVisiblePage(optimisticComment);
    }

    /** Shows an optimistic comment on whichever comments page is up. */
    private void addToVisiblePage(Comment optimisticComment) {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == null || !fragment.isAdded() || fragment.getView() == null) continue;
            if (fragment instanceof CommentReelFragment) {
                ((CommentReelFragment) fragment).addNewComment(optimisticComment);
            } else if (fragment instanceof CommentListFragment) {
                ((CommentListFragment) fragment).addNewComment(optimisticComment);
            } else if (fragment instanceof ReplyListFragment) {
                ((ReplyListFragment) fragment).addNewComment(optimisticComment);
            } else if (fragment instanceof ReplyListFragment2) {
                ((ReplyListFragment2) fragment).addNewComment(optimisticComment);
            }
        }
    }

    /** Redraws a sending comment's progress or highlight wherever it shows. */
    private void refreshUploadState(String commentId) {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == null || !fragment.isAdded() || fragment.getView() == null) continue;
            if (fragment instanceof CommentReelFragment) {
                ((CommentReelFragment) fragment).refreshUploadState(commentId);
            } else if (fragment instanceof CommentListFragment) {
                ((CommentListFragment) fragment).refreshUploadState(commentId);
            } else if (fragment instanceof ReplyListFragment) {
                ((ReplyListFragment) fragment).refreshUploadState(commentId);
            } else if (fragment instanceof ReplyListFragment2) {
                ((ReplyListFragment2) fragment).refreshUploadState(commentId);
            }
        }
    }

    /**
     * Redraws a sending comment whose card changed height. The reel pages re-pack around it
     * (which also brings it into view); the flat lists only need the row rebound.
     */
    private void repackAround(String commentId) {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == null || !fragment.isAdded() || fragment.getView() == null) continue;
            if (fragment instanceof CommentReelFragment) {
                ((CommentReelFragment) fragment).repackAround(commentId);
            }
        }
        refreshUploadState(commentId);
    }

    private void markFailedOnVisiblePage(String commentId) {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == null || !fragment.isAdded() || fragment.getView() == null) continue;
            if (fragment instanceof CommentReelFragment) {
                ((CommentReelFragment) fragment).markCommentAsFailed(commentId);
            } else if (fragment instanceof CommentListFragment) {
                ((CommentListFragment) fragment).markCommentAsFailed(commentId);
            } else if (fragment instanceof ReplyListFragment) {
                ((ReplyListFragment) fragment).markCommentAsFailed(commentId);
            } else if (fragment instanceof ReplyListFragment2) {
                ((ReplyListFragment2) fragment).markCommentAsFailed(commentId);
            }
        }
    }

    private void removeFromVisiblePage(String commentId) {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == null || !fragment.isAdded() || fragment.getView() == null) continue;
            if (fragment instanceof CommentReelFragment) {
                ((CommentReelFragment) fragment).removeComment(commentId);
            } else if (fragment instanceof CommentListFragment) {
                ((CommentListFragment) fragment).removeComment(commentId);
            } else if (fragment instanceof ReplyListFragment) {
                ((ReplyListFragment) fragment).removeComment(commentId);
            } else if (fragment instanceof ReplyListFragment2) {
                ((ReplyListFragment2) fragment).removeComment(commentId);
            }
        }
    }

    @Override
    public void onCommentPostSucceeded(String tempId, Comment realComment) {
        FragmentManager fm = getChildFragmentManager();
        List<Fragment> fragments = fm.getFragments();

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isAdded() && fragment.getView() != null) {
                if (fragment instanceof CommentReelFragment) {
                    ((CommentReelFragment) fragment).updateTemporaryComment(tempId, realComment);
                } else if (fragment instanceof CommentListFragment) {
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
                if (fragment instanceof CommentReelFragment) {
                    ((CommentReelFragment) fragment).markCommentAsFailed(tempId);
                } else if (fragment instanceof CommentListFragment) {
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
