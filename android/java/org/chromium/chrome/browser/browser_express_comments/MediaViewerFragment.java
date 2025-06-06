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
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.PlayerView;

import org.chromium.chrome.R;

public class MediaViewerFragment extends DialogFragment {
    private static final String ARG_MEDIA_URI = "media_uri";
    private static final String ARG_MEDIA_TYPE = "media_type";

    private Uri mMediaUri;
    private String mMediaType;

    // ExoPlayer related variables
    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private ImageView mImageView;

    public static MediaViewerFragment newInstance(Uri mediaUri, String mediaType) {
        MediaViewerFragment fragment = new MediaViewerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_MEDIA_URI, mediaUri);
        args.putString(ARG_MEDIA_TYPE, mediaType);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (getArguments() != null) {
            mMediaUri = getArguments().getParcelable(ARG_MEDIA_URI);
            mMediaType = getArguments().getString(ARG_MEDIA_TYPE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media_viewer, container, false);
        mImageView = view.findViewById(R.id.fullscreen_image_view);
        mPlayerView = view.findViewById(R.id.fullscreen_player_view); // Updated ID
        ImageButton closeButton = view.findViewById(R.id.close_button_fullscreen);

        closeButton.setOnClickListener(v -> dismiss());

        return view;
    }

    private void initializePlayer() {
        if (mPlayer == null) {
            mPlayer = new ExoPlayer.Builder(getContext()).build();
            mPlayerView.setPlayer(mPlayer);

            // Create a MediaItem and set it to the player
            MediaItem mediaItem = MediaItem.fromUri(mMediaUri);
            mPlayer.setMediaItem(mediaItem);

            // Prepare the player
            mPlayer.setPlayWhenReady(true); // Autoplay when ready
            mPlayer.prepare();
        }
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mMediaUri != null && mMediaType != null) {
            if ("image".equals(mMediaType)) {
                mImageView.setVisibility(View.VISIBLE);
                mPlayerView.setVisibility(View.GONE);
                Glide.with(this)
                     .load(mMediaUri)
                     .fitCenter()
                     .into(mImageView);
            } else if ("video".equals(mMediaType)) {
                mImageView.setVisibility(View.GONE);
                mPlayerView.setVisibility(View.VISIBLE);
                initializePlayer();
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // Release the player when the view is not visible
        releasePlayer();
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // A final safety check to release the player
        releasePlayer();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // Make the dialog fill the screen
        dialog.setOnShowListener(dialogInterface -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }
}