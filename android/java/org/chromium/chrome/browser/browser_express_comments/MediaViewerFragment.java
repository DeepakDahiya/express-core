package org.chromium.chrome.browser.browser_express_comments;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
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

    public interface OnViewerDismissedListener {
        void onViewerDismissed(boolean shouldShowKeyboard);
    }

    private Uri mMediaUri;
    private String mMediaType;
    private boolean mShowKeyboardOnClose;
    private OnViewerDismissedListener mDismissListener;

    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private ImageView mImageView;
    private boolean mPlayWhenReady = true;
    private long mPlaybackPosition;
    private int mCurrentWindow;

    public static MediaViewerFragment newInstance(Uri mediaUri, String mediaType, boolean showKeyboardOnClose) {
        MediaViewerFragment fragment = new MediaViewerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_MEDIA_URI, mediaUri);
        args.putString(ARG_MEDIA_TYPE, mediaType);
        args.putBoolean(ARG_SHOW_KEYBOARD_ON_CLOSE, showKeyboardOnClose);
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
        }

        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media_viewer, container, false);
        mImageView = view.findViewById(R.id.fullscreen_image_view);
        mPlayerView = view.findViewById(R.id.fullscreen_player_view);
        ImageButton closeButton = view.findViewById(R.id.close_button_fullscreen);

        closeButton.setOnClickListener(v -> dismiss());

        if (mMediaUri != null && "image".equals(mMediaType)) {
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
            BrowserExpressCommentsBottomSheetFragment parentFragment = 
                (BrowserExpressCommentsBottomSheetFragment) getParentFragment();
            
            if (parentFragment.getChildFragmentManager() != null) {
                androidx.fragment.app.Fragment activeFragment = 
                    parentFragment.getChildFragmentManager().findFragmentById(R.id.bottom_sheet_container);
                
                if (activeFragment instanceof CommentListFragment) {
                    ((CommentListFragment) activeFragment).triggerVideoVisibilityCheck();
                } else if (activeFragment instanceof ReplyListFragment) {
                    ((ReplyListFragment) activeFragment).triggerVideoVisibilityCheck();
                } else if (activeFragment instanceof ReplyListFragment2) {
                    ((ReplyListFragment2) activeFragment).triggerVideoVisibilityCheck();
                }
            }
        }
    }

    private void initializePlayer() {
        if (mPlayer == null && getContext() != null && "video".equals(mMediaType)) {
            mPlayer = new ExoPlayer.Builder(getContext()).build();
            mPlayerView.setPlayer(mPlayer);

            MediaItem mediaItem = MediaItem.fromUri(mMediaUri);
            mPlayer.setMediaItem(mediaItem);
            mPlayer.setPlayWhenReady(mPlayWhenReady);
            mPlayer.seekTo(mCurrentWindow, mPlaybackPosition);
            mPlayer.prepare();
        }
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayWhenReady = mPlayer.getPlayWhenReady();
            mPlaybackPosition = mPlayer.getCurrentPosition();
            mCurrentWindow = mPlayer.getCurrentWindowIndex();
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