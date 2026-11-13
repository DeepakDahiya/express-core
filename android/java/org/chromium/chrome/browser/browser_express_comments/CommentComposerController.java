/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The comment box's states (Figma "Express/Comment composer · Attachment flow", 500:2215): empty,
 * the round action records a voice note; with text or files it sends. Several files collapse to
 * the first one's thumbnail with a pencil and a count on the send button; a lone voice note
 * previews above the box, and a lone photo, GIF or video gets the large preview of "Universal
 * reply composer / Video" (660:8947), each with a cross to drop it.
 *
 * <p>Holds the files picked for the next comment. The sheet hands it picks and recordings and
 * gets the comment back to send.
 */
final class CommentComposerController {
    interface Host {
        /** The box is empty and the round action was tapped: record a voice note. */
        void onRecordRequested();

        /**
         * The box is empty and the round action was pressed: start recording now, so a held
         * finger loses none of its first words. False when that has to wait (no microphone
         * permission yet), and the press goes on to end as a tap.
         */
        boolean onRecordPressed();

        /** The finger holding the round action to record let go: stop recording. */
        void onRecordHoldReleased();

        /** Text only: the comments page's own send takes it from here. */
        void onSendText();

        /** Text and files, or files alone, to send as one comment. */
        void onSendDraft(String content, List<CommentUploadManager.Item> items);

        /** What is typed or picked for the next comment changed. */
        void onDraftChanged();
    }

    private static final long VOICE_TICK_MS = 200;

    private final Context mContext;
    private final Host mHost;
    private final List<CommentUploadManager.Item> mItems = new ArrayList<>();

    private final EditText mInput;
    private final ImageView mAvatar;
    private final View mMedia;
    private final ImageView mMediaThumb;
    private final ImageButton mAction;
    private final TextView mCount;

    private final View mVoicePreview;
    private final ImageButton mVoicePlay;
    private final SeekBar mVoiceSeek;
    private final TextView mVoiceTimer;

    /** The large preview of a lone photo, GIF or video (ids say video, its first use). */
    private final View mVideoPreview;
    private final ImageView mVideoThumb;
    private final View mVideoPlay;
    private final ImageButton mVideoRemove;
    private final TextView mVideoName;
    private final TextView mVideoMeta;
    private final TextView mVideoStatus;

    /** The hint the page set, put back when the files go. */
    private @Nullable CharSequence mPageHint;
    private boolean mShowingCaptionHint;

    /** Whether the press on the round action under way started a recording. */
    private boolean mPressRecording;

    private @Nullable MediaPlayer mVoicePlayer;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVoiceTick = new Runnable() {
        @Override
        public void run() {
            if (mVoicePlayer == null || !mVoicePlayer.isPlaying()) return;
            showVoicePosition(mVoicePlayer.getCurrentPosition(), mVoicePlayer.getDuration());
            mHandler.postDelayed(this, VOICE_TICK_MS);
        }
    };

    // The touch listener only adds holding to record; a click (accessibility's too) still records
    // or sends through onActionTapped.
    @SuppressLint("ClickableViewAccessibility")
    CommentComposerController(View root, Host host) {
        mContext = root.getContext();
        mHost = host;
        mInput = root.findViewById(R.id.comment_content_input);
        mAvatar = root.findViewById(R.id.avatar_image);
        mMedia = root.findViewById(R.id.composer_media);
        mMediaThumb = root.findViewById(R.id.composer_media_thumb);
        mAction = root.findViewById(R.id.composer_action);
        mCount = root.findViewById(R.id.composer_count);
        mVoicePreview = root.findViewById(R.id.composer_voice_preview);
        mVoicePlay = root.findViewById(R.id.composer_voice_play);
        mVoiceSeek = root.findViewById(R.id.composer_voice_seek);
        mVoiceTimer = root.findViewById(R.id.composer_voice_timer);
        mVideoPreview = root.findViewById(R.id.composer_video_preview);
        mVideoThumb = root.findViewById(R.id.composer_video_thumb);
        mVideoPlay = root.findViewById(R.id.composer_video_play);
        mVideoRemove = root.findViewById(R.id.composer_video_remove);
        mVideoName = root.findViewById(R.id.composer_video_name);
        mVideoMeta = root.findViewById(R.id.composer_video_meta);
        mVideoStatus = root.findViewById(R.id.composer_video_status);

        clipToBackground(mAvatar, true);
        clipToBackground(mMedia, false);
        clipToBackground(root.findViewById(R.id.composer_video_frame), false);

        mAction.setOnClickListener(v -> onActionTapped());
        mAction.setOnTouchListener((v, event) -> onActionTouched(event));
        mMedia.setOnClickListener(v -> showEditMenu());
        root.findViewById(R.id.composer_voice_remove).setOnClickListener(v -> removeAll());
        mVideoRemove.setOnClickListener(v -> removeAll());
        mVoicePlay.setOnClickListener(v -> toggleVoicePlayback());
        mVoiceSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && mVoicePlayer != null) mVoicePlayer.seekTo(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                renderAction();
            }
        });
        render();
    }

    /** Whether a file is picked for the next comment. */
    boolean hasFiles() {
        return !mItems.isEmpty();
    }

    /** Adds picked files, as many as the comment still has room for. */
    void addItems(List<CommentUploadManager.Item> items) {
        boolean dropped = false;
        for (CommentUploadManager.Item item : items) {
            if (item.isTooLarge()) {
                Toast.makeText(mContext, R.string.browser_express_attach_too_large,
                             Toast.LENGTH_SHORT)
                        .show();
                continue;
            }
            if (mItems.size() >= CommentUploadManager.MAX_FILES) {
                dropped = true;
                break;
            }
            mItems.add(item);
        }
        if (dropped) {
            Toast.makeText(mContext,
                         mContext.getResources().getQuantityString(
                                 R.plurals.browser_express_attach_too_many,
                                 CommentUploadManager.MAX_FILES, CommentUploadManager.MAX_FILES),
                         Toast.LENGTH_SHORT)
                    .show();
        }
        render();
    }

    /** Puts a comment whose upload was cancelled back in the box, files and all. */
    void restore(String content, List<CommentUploadManager.Item> items) {
        mItems.clear();
        mItems.addAll(items);
        mInput.setText(content);
        mInput.setSelection(mInput.getText().length());
        render();
    }

    /** Stops the voice-note preview, e.g. when the sheet goes away. */
    void release() {
        stopVoicePlayback();
    }

    /**
     * Holding the mic records, as in WhatsApp: pressing it starts recording at once. A tap leaves
     * the recording running, ended by the recording bar's send or discard; a finger held past the
     * long-press time stops it on letting go, and the take previews above the box.
     */
    private boolean onActionTouched(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // False, the press is left to end as a click: send, or ask for the microphone.
                mPressRecording = isEmpty() && mHost.onRecordPressed();
                return mPressRecording;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mPressRecording) return false;
                mPressRecording = false;
                if (event.getEventTime() - event.getDownTime()
                        >= ViewConfiguration.getLongPressTimeout()) {
                    mHost.onRecordHoldReleased();
                }
                return true;
            default:
                return mPressRecording;
        }
    }

    private void onActionTapped() {
        String text = mInput.getText() != null ? mInput.getText().toString().trim() : "";
        if (mItems.isEmpty()) {
            if (text.isEmpty()) {
                mHost.onRecordRequested();
            } else {
                mHost.onSendText();
            }
            return;
        }
        List<CommentUploadManager.Item> items = new ArrayList<>(mItems);
        mItems.clear();
        stopVoicePlayback();
        mInput.setText("");
        render();
        mHost.onSendDraft(text, items);
    }

    private void showEditMenu() {
        PopupMenu menu = new PopupMenu(mContext, mMedia);
        for (int i = 0; i < mItems.size(); i++) {
            menu.getMenu().add(0, i, i,
                    mContext.getString(R.string.browser_express_composer_remove_file,
                            labelOf(mItems.get(i))));
        }
        final int removeAll = mItems.size();
        menu.getMenu().add(0, removeAll, removeAll, R.string.browser_express_composer_remove_all);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == removeAll) {
                removeAll();
            } else if (item.getItemId() < mItems.size()) {
                mItems.remove(item.getItemId());
                render();
            }
            return true;
        });
        menu.show();
    }

    private void removeAll() {
        mItems.clear();
        stopVoicePlayback();
        render();
    }

    private String labelOf(CommentUploadManager.Item item) {
        if (item.name != null && !item.name.isEmpty()) return item.name;
        switch (item.kind) {
            case CommentAttachment.KIND_GIF:
                return mContext.getString(R.string.browser_express_composer_gif);
            case CommentAttachment.KIND_VIDEO:
                return mContext.getString(R.string.browser_express_composer_video);
            case CommentAttachment.KIND_AUDIO:
                return mContext.getString(R.string.browser_express_composer_voice);
            case CommentAttachment.KIND_DOCUMENT:
                return mContext.getString(R.string.browser_express_composer_document);
            default:
                return mContext.getString(R.string.browser_express_composer_photo);
        }
    }

    private void render() {
        CommentUploadManager.Item only = mItems.size() == 1 ? mItems.get(0) : null;
        CommentUploadManager.Item voice = only != null && only.isAudio() ? only : null;
        CommentUploadManager.Item visual = only != null && isVisual(only) ? only : null;
        boolean compact = !mItems.isEmpty() && voice == null && visual == null;

        if (voice == null) stopVoicePlayback();
        mVoicePreview.setVisibility(voice != null ? View.VISIBLE : View.GONE);
        if (voice != null) bindVoice(voice);

        mVideoPreview.setVisibility(visual != null ? View.VISIBLE : View.GONE);
        if (visual != null) bindPreview(visual);

        mMedia.setVisibility(compact ? View.VISIBLE : View.GONE);
        mAvatar.setVisibility(compact ? View.INVISIBLE : View.VISIBLE);
        mCount.setVisibility(compact ? View.VISIBLE : View.GONE);
        if (compact) {
            mCount.setText(String.valueOf(mItems.size()));
            bindThumb(mMediaThumb, firstVisual());
            mMedia.setContentDescription(mContext.getResources().getQuantityString(
                    R.plurals.browser_express_composer_file_count, mItems.size(),
                    mItems.size()));
        }

        // "Add a caption…" while files are picked; otherwise whatever the page asked for.
        if (!mItems.isEmpty() && !mShowingCaptionHint) {
            mPageHint = mInput.getHint();
            mInput.setHint(R.string.browser_express_comment_caption_hint);
            mShowingCaptionHint = true;
        } else if (mItems.isEmpty() && mShowingCaptionHint) {
            mInput.setHint(mPageHint);
            mShowingCaptionHint = false;
        }
        renderAction();
    }

    /** Nothing typed or picked: the round action is the mic. */
    private boolean isEmpty() {
        return mItems.isEmpty()
                && (mInput.getText() == null || mInput.getText().toString().trim().isEmpty());
    }

    private void renderAction() {
        boolean empty = isEmpty();
        mAction.setImageResource(empty ? R.drawable.ic_browser_express_composer_mic
                                       : R.drawable.ic_browser_express_composer_send);
        mAction.setContentDescription(mContext.getString(empty
                        ? R.string.browser_express_composer_record
                        : R.string.browser_express_comment_send));
        mHost.onDraftChanged();
    }

    @Nullable
    private CommentUploadManager.Item firstVisual() {
        for (CommentUploadManager.Item item : mItems) {
            if (!item.isAudio() && !CommentAttachment.KIND_DOCUMENT.equals(item.kind)) {
                return item;
            }
        }
        return null;
    }

    private void bindThumb(ImageView view, @Nullable CommentUploadManager.Item item) {
        if (item == null) {
            Glide.with(view).clear(view);
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageResource(R.drawable.ic_browser_express_attach_document);
            return;
        }
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Glide.with(view).load(item.uri).centerCrop().into(view);
    }

    /** A photo, GIF or video: what gets the large preview when it is the only file. */
    private static boolean isVisual(CommentUploadManager.Item item) {
        return item.isVideo() || CommentAttachment.KIND_IMAGE.equals(item.kind)
                || CommentAttachment.KIND_GIF.equals(item.kind);
    }

    /**
     * The large preview: the still (a video's with the play mark), the file's name, "00:24 ·
     * 18.6 MB" for a video or "Photo · 2.1 MB", and when it uploads.
     */
    private void bindPreview(CommentUploadManager.Item item) {
        Glide.with(mVideoThumb).load(item.uri).centerCrop().into(mVideoThumb);
        mVideoName.setText(labelOf(item));
        boolean video = item.isVideo();
        boolean gif = CommentAttachment.KIND_GIF.equals(item.kind);
        mVideoPlay.setVisibility(video ? View.VISIBLE : View.GONE);
        String size = item.size > 0 ? Formatter.formatShortFileSize(mContext, item.size) : "";
        // A video leads with its length; a photo or GIF with what it is.
        String lead = video
                ? (item.durationMs > 0 ? clock(item.durationMs) : "")
                : mContext.getString(gif ? R.string.browser_express_composer_gif
                                         : R.string.browser_express_composer_photo);
        mVideoMeta.setText(!lead.isEmpty() && !size.isEmpty()
                        ? mContext.getString(
                                R.string.browser_express_composer_video_meta, lead, size)
                        : lead.isEmpty() ? size : lead);
        mVideoStatus.setText(video ? R.string.browser_express_composer_uploads_on_send
                                   : R.string.browser_express_composer_ready_to_post);
        mVideoRemove.setContentDescription(mContext.getString(video
                        ? R.string.browser_express_composer_remove_video
                        : gif ? R.string.browser_express_composer_remove_gif
                              : R.string.browser_express_composer_remove_photo));
    }

    private void bindVoice(CommentUploadManager.Item voice) {
        if (mVoicePlayer == null) {
            mVoicePlay.setImageResource(R.drawable.ic_browser_express_post_audio_play);
            mVoiceSeek.setMax(Math.max(voice.durationMs, 1));
            mVoiceSeek.setProgress(0);
            mVoiceTimer.setText(clock(voice.durationMs));
        }
    }

    private void toggleVoicePlayback() {
        CommentUploadManager.Item voice = mItems.size() == 1 ? mItems.get(0) : null;
        if (voice == null || !voice.isAudio()) return;
        if (mVoicePlayer != null && mVoicePlayer.isPlaying()) {
            mVoicePlayer.pause();
            mVoicePlay.setImageResource(R.drawable.ic_browser_express_post_audio_play);
            return;
        }
        if (mVoicePlayer == null) {
            mVoicePlayer = MediaPlayer.create(mContext, voice.uri);
            if (mVoicePlayer == null) return;
            mVoicePlayer.setOnCompletionListener(player -> {
                stopVoicePlayback();
                if (!mItems.isEmpty()) bindVoice(mItems.get(0));
            });
            mVoiceSeek.setMax(Math.max(mVoicePlayer.getDuration(), 1));
        }
        // Whatever else is playing would talk over the note.
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingAudio();
        mVoicePlayer.start();
        mVoicePlay.setImageResource(R.drawable.ic_browser_express_post_audio_pause);
        mHandler.removeCallbacks(mVoiceTick);
        mHandler.post(mVoiceTick);
    }

    private void showVoicePosition(int positionMs, int durationMs) {
        mVoiceSeek.setProgress(positionMs);
        mVoiceTimer.setText(clock(Math.max(durationMs - positionMs, 0)));
    }

    private void stopVoicePlayback() {
        mHandler.removeCallbacks(mVoiceTick);
        if (mVoicePlayer != null) {
            mVoicePlayer.release();
            mVoicePlayer = null;
        }
    }

    /** "01:40", as the design writes a length. */
    static String clock(long durationMs) {
        long seconds = Math.max(durationMs, 0) / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Clips a view to its background's outline: a circle for the avatar, else the rounded box. */
    private static void clipToBackground(View view, boolean circle) {
        if (circle) {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
        }
        view.setClipToOutline(true);
    }
}
