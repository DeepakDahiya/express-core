package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.ui.PlayerView;

import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import android.content.Context;
import android.content.DialogInterface;
import org.chromium.chrome.R;
import org.chromium.base.Log;

public class MediaViewerFragment extends DialogFragment {
    private static final String ARG_MEDIA_URI = "media_uri";
    private static final String ARG_MEDIA_TYPE = "media_type";
    private static final String ARG_SHOW_KEYBOARD_ON_CLOSE = "show_keyboard_on_close";
    private static final String ARG_TITLE = "title";

    public interface OnViewerDismissedListener {
        void onViewerDismissed(boolean shouldShowKeyboard);
    }

    private Uri mMediaUri;
    private String mMediaType;
    private boolean mShowKeyboardOnClose;
    @Nullable private String mTitle;
    private OnViewerDismissedListener mDismissListener;

    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    /** Holding the right half of a video plays it at 2× until the finger lifts. */
    private HoldToFastForward mFastForward;
    private ImageView mImageView;
    private boolean mPlayWhenReady = true;
    private long mPlaybackPosition;
    private int mCurrentWindow;

    public static MediaViewerFragment newInstance(Uri mediaUri, String mediaType, boolean showKeyboardOnClose) {
        return newInstance(mediaUri, mediaType, showKeyboardOnClose, null);
    }

    /**
     * @param title when non-null the viewer wears a title bar with a back arrow instead of the
     *     floating close button — for when it reads as a page stacked on whatever is underneath,
     *     rather than as an overlay dismissed with an X.
     */
    public static MediaViewerFragment newInstance(
            Uri mediaUri, String mediaType, boolean showKeyboardOnClose, @Nullable String title) {
        MediaViewerFragment fragment = new MediaViewerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_MEDIA_URI, mediaUri);
        args.putString(ARG_MEDIA_TYPE, mediaType);
        args.putBoolean(ARG_SHOW_KEYBOARD_ON_CLOSE, showKeyboardOnClose);
        args.putString(ARG_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnViewerDismissedListener) {
            mDismissListener = (OnViewerDismissedListener) getParentFragment();
        } else {
            Log.w("MediaViewerFragment", "Parent fragment does not implement OnViewerDismissedListener");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (getArguments() != null) {
            mMediaUri = getArguments().getParcelable(ARG_MEDIA_URI);
            mMediaType = getArguments().getString(ARG_MEDIA_TYPE);
            mShowKeyboardOnClose = getArguments().getBoolean(ARG_SHOW_KEYBOARD_ON_CLOSE, false);
            mTitle = getArguments().getString(ARG_TITLE);
        }

        GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(true);
    }

    @Nullable
    @Override
    @SuppressLint("ClickableViewAccessibility") // The hold listener leaves taps to the view.
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media_viewer, container, false);
        mImageView = view.findViewById(R.id.fullscreen_image_view);
        mPlayerView = view.findViewById(R.id.fullscreen_player_view);
        mFastForward = new HoldToFastForward(
                () -> mPlayer, view.findViewById(R.id.video_fast_forward_badge));
        // Taps still reach the player view (its controls); only a hold is kept from it.
        mPlayerView.setOnTouchListener((v, event) -> mFastForward.onTouch(v, event));
        ImageButton closeButton = view.findViewById(R.id.close_button_fullscreen);
        View header = view.findViewById(R.id.media_viewer_header);

        if (mTitle != null) {
            closeButton.setVisibility(View.GONE);
            header.setVisibility(View.VISIBLE);
            TextView title = header.findViewById(R.id.be_toolbar_title);
            title.setText(mTitle);
            // The shared title bar is themed for the comments page; media stays dark, so it
            // reads white here in both themes.
            title.setTextColor(android.graphics.Color.WHITE);
            ((ImageView) header.findViewById(R.id.back_button))
                    .setImageTintList(android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.WHITE));
            // Dismissing is what going back means here: the viewer is a dialog stacked over the
            // screen that opened it, so this leaves the reader exactly where the device back
            // button would.
            header.findViewById(R.id.back_button).setOnClickListener(v -> dismiss());
        } else {
            closeButton.setOnClickListener(v -> dismiss());
        }

        // Glide animates a GIF for free here — the fullscreen view loads the URI directly rather
        // than going through ImageLoader's bitmap path.
        if (mMediaUri != null && ("image".equals(mMediaType) || "gif".equals(mMediaType))) {
            mImageView.setVisibility(View.VISIBLE);
            mPlayerView.setVisibility(View.GONE);
            Glide.with(getContext())
                 .load(mMediaUri)
                 .fitCenter()
                 .into(mImageView);
        } else {
             mImageView.setVisibility(View.GONE);
             mPlayerView.setVisibility(View.VISIBLE);
        }

        return view;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        GlobalVideoPlaybackManager.getInstance().setFullScreenViewerOpen(false);

        if (getView() != null) {
            getView().postDelayed(() -> {
                triggerVideoVisibilityCheck();
            }, 100);
        }
        
        if (mDismissListener != null) {
            mDismissListener.onViewerDismissed(mShowKeyboardOnClose);
        }
    }

    private void triggerVideoVisibilityCheck() {
        if (getParentFragment() instanceof BrowserExpressCommentsBottomSheetFragment) {
            ((BrowserExpressCommentsBottomSheetFragment) getParentFragment()).resumeVisibleVideos();
        }
    }

    private void initializePlayer() {
        if (mPlayer == null && getContext() != null && "video".equals(mMediaType)) {
            // The inline card usually streamed this video moments ago; reading through the
            // shared cache lets fullscreen start from the bytes already on disk.
            mPlayer =
                    new ExoPlayer.Builder(getContext())
                            .setMediaSourceFactory(
                                    CommentMediaCache.mediaSourceFactory(getContext()))
                            .build();
            mPlayerView.setPlayer(mPlayer);

            MediaItem mediaItem = MediaItem.fromUri(mMediaUri);
            mPlayer.setMediaItem(mediaItem);
            mPlayer.setPlayWhenReady(mPlayWhenReady);
            mPlayer.seekTo(mCurrentWindow, mPlaybackPosition);
            mPlayer.prepare();
        }
    }

    private void releasePlayer() {
        if (mFastForward != null) mFastForward.cancel();
        if (mPlayer != null) {
            mPlayWhenReady = mPlayer.getPlayWhenReady();
            mPlaybackPosition = mPlayer.getCurrentPosition();
            mCurrentWindow = mPlayer.getCurrentMediaItemIndex();
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPlayer == null) {
            initializePlayer();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        releasePlayer();
    }
    
    @Override
    public void onStop() {
        super.onStop();
        releasePlayer();
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releasePlayer();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(dialogInterface -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }
}