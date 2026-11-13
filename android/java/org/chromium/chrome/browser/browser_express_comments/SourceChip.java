/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.chrome.browser.ntp.PostListAdapter;

/**
 * The chip under the comments title naming what the comments are on (Figma "Source Post Chips",
 * 89:1322): the feed post or web page on the comments page, the comment being answered on a
 * replies page.
 *
 * <p>The attachment picks the design's state. A photo or a video puts a 40dp thumbnail beside the
 * author line, a video's with a play mark, and tapping the thumbnail opens the media full screen.
 * A voice note puts a compact player under the author line. The text line shows whenever there is
 * text.
 *
 * <p>The voice note takes the comment list's one audio slot ({@link GlobalVideoPlaybackManager}),
 * so starting it quiets a voice note or video that was playing and they quiet it in turn. Its
 * player is let go when the chip leaves the window.
 */
public final class SourceChip {
    private static final String KEY_IMAGE_URL = "image_url";
    private static final String KEY_VIDEO_URL = "video_url";
    private static final String KEY_AUDIO_URL = "audio_url";
    private static final String KEY_AUDIO_DURATION_MS = "audio_duration_ms";
    private static final String KEY_CREATED_AT_MS = "created_at_ms";
    private static final String KEY_HASHTAG = "hashtag";
    private static final String KEY_COMMENT_COUNT = "comment_count";
    private static final String KEY_EMOTE_COUNT = "emote_count";

    private static final int SEEK_MAX = 1000;
    private static final long AUDIO_TICK_MS = 100;

    /** What the chip shows; any of it may be missing. */
    static final class Source {
        @Nullable String author;
        @Nullable String avatarUrl;
        /** Off for a web page, which has a host where a person would be. */
        boolean showAvatar = true;
        long createdAtMs;
        @Nullable String text;
        /** A photo, or a video's still. */
        @Nullable String imageUrl;
        boolean imageIsGif;
        /** Puts the play mark on the thumbnail. */
        boolean isVideo;
        /** The video the thumbnail opens; null when it opens nothing (a YouTube page's). */
        @Nullable String videoUrl;
        @Nullable String audioUrl;
        long audioDurationMs;
        /** For "Add your take": a feed post's hashtag (no "#") and totals, -1 if unknown. */
        @Nullable String hashtag;
        int commentCount = -1;
        int emoteCount = -1;

        /** The comment a replies page answers. */
        static Source forComment(Comment comment) {
            Source source = new Source();
            source.author = comment.getUser() != null
                    ? comment.getUser().getUsername()
                    : comment.getYoutubeAuthorName();
            source.avatarUrl = CommentListAdapter.avatarUrlFor(comment);
            source.createdAtMs = comment.getCreatedAtMs();
            source.text = comment.getContent();
            if (TextUtils.isEmpty(source.text) && comment.hasDocumentMedia()) {
                source.text = comment.getMediaDocumentName();
            }
            if (comment.hasVideoMedia()) {
                source.isVideo = true;
                source.videoUrl = comment.getMediaVideoUrl();
            } else if (comment.hasImageMedia()) {
                source.imageUrl = comment.getDisplayImageUrl();
                source.imageIsGif = comment.hasGifMedia();
            }
            if (comment.hasAudioMedia()) {
                source.audioUrl = comment.getMediaAudioUrl();
                source.audioDurationMs = comment.getMediaDurationMs();
            }
            return source;
        }

        /**
         * A feed post.
         *
         * @param media what {@link #postMedia} packed for it, if anything.
         * @param videoUrl the post's video when {@code media} has none (a push notification
         *     names it on its own).
         */
        static Source forPost(@Nullable String author, @Nullable String avatarUrl,
                @Nullable String text, @Nullable Bundle media, @Nullable String videoUrl) {
            Source source = new Source();
            source.author = author;
            source.avatarUrl = avatarUrl;
            source.text = text;
            if (media != null) {
                source.createdAtMs = media.getLong(KEY_CREATED_AT_MS);
                source.imageUrl = urlOrNull(media.getString(KEY_IMAGE_URL));
                source.videoUrl = urlOrNull(media.getString(KEY_VIDEO_URL));
                source.audioUrl = urlOrNull(media.getString(KEY_AUDIO_URL));
                source.audioDurationMs = media.getLong(KEY_AUDIO_DURATION_MS);
                source.hashtag = media.getString(KEY_HASHTAG);
                source.commentCount = media.getInt(KEY_COMMENT_COUNT, -1);
                source.emoteCount = media.getInt(KEY_EMOTE_COUNT, -1);
            }
            if (source.videoUrl == null) source.videoUrl = urlOrNull(videoUrl);
            source.isVideo = source.videoUrl != null;
            return source;
        }

        /**
         * A web page: its host where the author goes, its title as the text, and the video's
         * thumbnail when the page is one (YouTube).
         */
        static Source forPage(
                @Nullable String host, @Nullable String title, @Nullable String videoThumbUrl) {
            Source source = new Source();
            source.showAvatar = false;
            source.author = host;
            source.text = title;
            source.imageUrl = urlOrNull(videoThumbUrl);
            source.isVideo = source.imageUrl != null;
            return source;
        }

        boolean isEmpty() {
            return TextUtils.isEmpty(author) && TextUtils.isEmpty(text) && imageUrl == null
                    && !isVideo && audioUrl == null;
        }
    }

    /**
     * Packs a feed post's attachment and age for its comments sheet to show in the chip, with its
     * hashtag (no "#"), comment count and emoji total for the "Add your take" card. Any of it may
     * be missing; a video's {@code imageUrl} is its poster.
     */
    public static Bundle postMedia(@Nullable String imageUrl, @Nullable String videoUrl,
            @Nullable String audioUrl, long audioDurationMs, long createdAtMs,
            @Nullable String hashtag, int commentCount, int emoteCount) {
        Bundle media = new Bundle();
        media.putString(KEY_IMAGE_URL, imageUrl);
        media.putString(KEY_VIDEO_URL, videoUrl);
        media.putString(KEY_AUDIO_URL, audioUrl);
        media.putLong(KEY_AUDIO_DURATION_MS, audioDurationMs);
        media.putLong(KEY_CREATED_AT_MS, createdAtMs);
        media.putString(KEY_HASHTAG, hashtag);
        media.putInt(KEY_COMMENT_COUNT, commentCount);
        media.putInt(KEY_EMOTE_COUNT, emoteCount);
        return media;
    }

    /** The chip in {@code root}'s comments header, made on first use; null when there is none. */
    @Nullable
    static SourceChip in(View root) {
        View chip = root.findViewById(R.id.comments_source_chip);
        if (chip == null) return null;
        if (chip.getTag() instanceof SourceChip) return (SourceChip) chip.getTag();
        SourceChip sourceChip = new SourceChip(chip);
        chip.setTag(sourceChip);
        return sourceChip;
    }

    private final Context mContext;
    private final View mChip;
    private final View mThumbFrame;
    private final ImageView mThumb;
    private final View mPlayMark;
    private final ImageView mAvatar;
    private final TextView mAuthor;
    private final TextView mTime;
    private final TextView mText;
    private final View mAudio;
    private final SeekBar mAudioSeek;
    private final TextView mAudioTime;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAudioTick = this::updateAudioProgress;
    /** Run by the playback manager when a comment's voice note or video takes the audio slot. */
    private final Runnable mAudioSlotLost = this::stopAudio;

    @Nullable private String mAudioUrl;
    private long mAudioDurationMs;
    @Nullable private ExoPlayer mAudioPlayer;
    private boolean mUserSeeking;

    @Nullable private String mFrameUrl;
    @Nullable private CommentVideoFrames.FrameListener mFrameListener;

    private SourceChip(View chip) {
        mContext = chip.getContext();
        mChip = chip;
        mThumbFrame = chip.findViewById(R.id.comments_source_thumb_frame);
        mThumb = chip.findViewById(R.id.comments_source_thumb);
        mPlayMark = chip.findViewById(R.id.comments_source_play);
        mAvatar = chip.findViewById(R.id.comments_source_avatar);
        mAuthor = chip.findViewById(R.id.comments_source_author);
        mTime = chip.findViewById(R.id.comments_source_time);
        mText = chip.findViewById(R.id.comments_source_text);
        mAudio = chip.findViewById(R.id.comments_source_audio);
        mAudioSeek = chip.findViewById(R.id.comments_source_audio_seek);
        mAudioTime = chip.findViewById(R.id.comments_source_audio_time);

        // The thumbnail's 3dp corners.
        mThumbFrame.setClipToOutline(true);
        chip.findViewById(R.id.comments_source_audio_play).setOnClickListener(v -> playAudio());
        chip.findViewById(R.id.comments_source_audio_pause).setOnClickListener(v -> pauseAudio());
        mAudioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    mAudioTime.setText(PostListAdapter.formatDuration(
                            progress * audioDurationMs() / SEEK_MAX));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mUserSeeking = false;
                long duration = audioDurationMs();
                ExoPlayer player = ensureAudioPlayer();
                if (player != null && duration > 0) {
                    player.seekTo(bar.getProgress() * duration / SEEK_MAX);
                }
                updateAudioProgress();
            }
        });
        chip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                cancelFrame();
                releaseAudio();
            }
        });
    }

    /**
     * Shows {@code source}, or hides the chip when it has nothing to show.
     *
     * @param viewerHost where a tapped thumbnail opens its photo or video full screen; null
     *     leaves the thumbnail inert.
     */
    void bind(Source source, @Nullable FragmentManager viewerHost) {
        if (source.isEmpty()) {
            cancelFrame();
            releaseAudio();
            mChip.setVisibility(View.GONE);
            return;
        }
        bindAuthor(source);
        String text = source.text != null ? source.text.replace('\n', ' ').trim() : "";
        mText.setText(text);
        mText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        bindThumbnail(source, viewerHost);
        bindAudio(source);
        mChip.setVisibility(View.VISIBLE);
    }

    private void bindAuthor(Source source) {
        mAuthor.setText(source.author != null ? source.author : "");
        mAvatar.setVisibility(source.showAvatar ? View.VISIBLE : View.GONE);
        if (source.showAvatar) {
            ProfileAvatar.load(mAvatar, source.avatarUrl, null);
        }
        if (source.createdAtMs > 0) {
            mTime.setText(PostListAdapter.formatAge(
                    mContext, source.createdAtMs, System.currentTimeMillis()));
            mTime.setVisibility(View.VISIBLE);
        } else {
            mTime.setVisibility(View.GONE);
        }
    }

    private void bindThumbnail(Source source, @Nullable FragmentManager viewerHost) {
        cancelFrame();
        Glide.with(mThumb).clear(mThumb);
        mThumb.setImageDrawable(null);
        boolean visual = source.imageUrl != null || source.isVideo;
        mThumbFrame.setVisibility(visual ? View.VISIBLE : View.GONE);
        mPlayMark.setVisibility(source.isVideo ? View.VISIBLE : View.GONE);
        if (source.imageUrl != null) {
            // A still even for a GIF: 40dp is too small to watch it move.
            Glide.with(mThumb)
                    .asBitmap()
                    .load(source.imageUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(mThumb);
        } else if (source.videoUrl != null) {
            requestFrame(source.videoUrl);
        }

        String mediaUrl = source.isVideo ? source.videoUrl : source.imageUrl;
        if (visual && viewerHost != null && mediaUrl != null) {
            String mediaType = source.isVideo ? "video" : source.imageIsGif ? "gif" : "image";
            mThumbFrame.setOnClickListener(
                    v -> openViewer(viewerHost, Uri.parse(mediaUrl), mediaType));
            mThumbFrame.setContentDescription(mContext.getString(source.isVideo
                            ? R.string.browser_express_comments_source_open_video
                            : R.string.browser_express_comments_source_open_photo));
        } else {
            mThumbFrame.setOnClickListener(null);
            mThumbFrame.setClickable(false);
            mThumbFrame.setContentDescription(null);
        }
    }

    /** A video with no still of its own shows its opening frame. */
    private void requestFrame(String videoUrl) {
        mFrameUrl = videoUrl;
        mFrameListener = (url, frame) -> {
            if (url.equals(mFrameUrl)) mThumb.setImageBitmap(frame);
        };
        Bitmap cached = CommentVideoFrames.requestFrame(mContext, videoUrl, mFrameListener);
        if (cached != null) mThumb.setImageBitmap(cached);
    }

    private void cancelFrame() {
        if (mFrameUrl != null && mFrameListener != null) {
            CommentVideoFrames.cancel(mFrameUrl, mFrameListener);
        }
        mFrameUrl = null;
        mFrameListener = null;
    }

    /** Opens the media full screen the way a comment card's media does. */
    private static void openViewer(FragmentManager host, Uri mediaUri, String mediaType) {
        if (host.isStateSaved()) return;
        GlobalVideoPlaybackManager playback = GlobalVideoPlaybackManager.getInstance();
        CommentListAdapter.CommentHolder playing = playback.getCurrentlyPlayingHolder();
        if (playing != null) playback.setHolderToResume(playing);
        playback.pauseCurrentlyPlayingVideo();
        playback.pauseCurrentlyPlayingAudio();
        MediaViewerFragment.newInstance(mediaUri, mediaType, false)
                .show(host, MediaViewerFragment.class.getSimpleName());
    }

    // ---------------------------------------------------------------------------------------
    // Voice note
    // ---------------------------------------------------------------------------------------

    private void bindAudio(Source source) {
        if (!TextUtils.equals(source.audioUrl, mAudioUrl)) releaseAudio();
        mAudioUrl = source.audioUrl;
        mAudio.setVisibility(mAudioUrl != null ? View.VISIBLE : View.GONE);
        if (mAudioUrl == null || mAudioPlayer != null) return;
        // The length reads before the clip has been fetched; the player's own takes over later.
        mAudioDurationMs = Math.max(0, source.audioDurationMs);
        showAudioAtStart();
    }

    private void playAudio() {
        ExoPlayer player = ensureAudioPlayer();
        if (player == null || player.getPlayWhenReady()) return;
        GlobalVideoPlaybackManager.getInstance().playOtherAudio(mAudioSlotLost);
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        player.play();
        updateAudioProgress();
    }

    private void pauseAudio() {
        GlobalVideoPlaybackManager.getInstance().onOtherAudioStopped(mAudioSlotLost);
        stopAudio();
    }

    /** Pauses where it is; the audio slot is already free or someone else's. */
    private void stopAudio() {
        if (mAudioPlayer != null) mAudioPlayer.pause();
        mHandler.removeCallbacks(mAudioTick);
    }

    @Nullable
    private ExoPlayer ensureAudioPlayer() {
        if (mAudioPlayer != null) return mAudioPlayer;
        if (mAudioUrl == null) return null;
        mAudioPlayer = new ExoPlayer.Builder(mContext)
                               .setMediaSourceFactory(CommentMediaCache.mediaSourceFactory(mContext))
                               .build();
        mAudioPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    // Rewound and stopped, ready to play again.
                    pauseAudio();
                    if (mAudioPlayer != null) mAudioPlayer.seekTo(0);
                    showAudioAtStart();
                } else {
                    // Ready: the player now knows the real length.
                    updateAudioProgress();
                }
            }
        });
        mAudioPlayer.setMediaItem(MediaItem.fromUri(mAudioUrl));
        mAudioPlayer.prepare();
        return mAudioPlayer;
    }

    /** Moves the track and the time: elapsed while under way, else the length. */
    private void updateAudioProgress() {
        mHandler.removeCallbacks(mAudioTick);
        ExoPlayer player = mAudioPlayer;
        if (player == null) return;
        long duration = audioDurationMs();
        long position = Math.max(0, player.getCurrentPosition());
        if (!mUserSeeking) {
            mAudioSeek.setProgress(
                    duration > 0 ? (int) Math.min(SEEK_MAX, position * SEEK_MAX / duration) : 0);
            mAudioTime.setText(PostListAdapter.formatDuration(position > 0 ? position : duration));
        }
        if (player.getPlayWhenReady()) mHandler.postDelayed(mAudioTick, AUDIO_TICK_MS);
    }

    private void showAudioAtStart() {
        mAudioSeek.setProgress(0);
        mAudioTime.setText(PostListAdapter.formatDuration(audioDurationMs()));
    }

    private long audioDurationMs() {
        if (mAudioPlayer != null && mAudioPlayer.getDuration() > 0) {
            mAudioDurationMs = mAudioPlayer.getDuration();
        }
        return mAudioDurationMs;
    }

    private void releaseAudio() {
        mHandler.removeCallbacks(mAudioTick);
        GlobalVideoPlaybackManager.getInstance().onOtherAudioStopped(mAudioSlotLost);
        if (mAudioPlayer != null) {
            mAudioPlayer.release();
            mAudioPlayer = null;
            showAudioAtStart();
        }
    }

    private static @Nullable String urlOrNull(@Nullable String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
