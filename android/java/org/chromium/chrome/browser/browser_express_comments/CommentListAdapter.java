package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.content.Intent;
import java.util.UUID;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.util.ArrayList;
import android.widget.TextView;
import android.view.View;
import org.chromium.base.Log;
import android.widget.ImageButton;
import android.widget.Button;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Context;
import org.chromium.chrome.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import android.view.LayoutInflater;
import org.chromium.chrome.browser.app.BraveActivity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import org.chromium.base.task.AsyncTask;
import java.util.Locale;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.widget.LinearLayout;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.view.inputmethod.InputMethodManager;
import android.view.HapticFeedbackConstants;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import android.os.Bundle;
import androidx.core.content.res.ResourcesCompat;
import org.chromium.ui.widget.Toast;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import androidx.cardview.widget.CardView;
import android.widget.ProgressBar;
import androidx.recyclerview.widget.LinearSnapHelper;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.util.Util;
import android.view.MotionEvent;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import java.util.Map;
import java.util.HashMap;
import androidx.annotation.NonNull; 
import androidx.media3.common.MediaMetadata;
import com.bumptech.glide.request.target.Target;
import android.util.TypedValue;
import android.media.MediaMetadataRetriever;
import android.widget.Space;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.view.OneShotPreDrawListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import java.util.concurrent.Executor;
import androidx.annotation.Nullable;
import android.graphics.Rect;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;
import android.content.pm.PackageInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import org.chromium.chrome.browser.browser_express_share.ShareLinks;
import org.chromium.chrome.browser.ntp.PostListAdapter;
import org.chromium.chrome.browser.util.TabUtils;

public class CommentListAdapter extends RecyclerView.Adapter<CommentListAdapter.CommentHolder> {
    /** Corner rounding of a media card, matching what the remote path asks ImageLoader for. */
    private static final int MEDIA_CORNER_RADIUS_DP = 5;
    /**
     * Widest video (width / height) a reel page centre-crops to fill the screen: 9:16, with
     * slack for encoder sizes like 1080x1800. A squarer one (2:3, 3:4, 4:5, ...) would lose a
     * third or more of its width to the crop and look stretched, so it is letterboxed instead.
     */
    private static final float REEL_CROP_MAX_RATIO = 0.62f;

    private final List<Comment> mCommentList;
    private final EditText mMessageEditText;
    private final CommentActionHost mParentFragment;
    private final boolean mIsReplyAdapter;
    private final boolean mIsReplyToReplyAdapter;
    private final String mPageUrl;
    private final boolean mIsPagerMode;
    private final boolean mIsReelMode;
    /**
     * On a replies page, the comment being answered. It sits in the page header (the source chip),
     * not in the list, but a YouTube reaction still has to name it as the replies' ancestor.
     */
    @Nullable private Comment mParentComment;

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, CommentActionHost parentFragment, boolean isReplyAdapter, boolean isReplyToReplyAdapter) {
        this(context, commentList, messageEditText, parentFragment, isReplyAdapter, isReplyToReplyAdapter, null);
    }

    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, CommentActionHost parentFragment, boolean isReplyAdapter, boolean isReplyToReplyAdapter, String pageUrl) {
        this(context, commentList, messageEditText, parentFragment, isReplyAdapter, isReplyToReplyAdapter, pageUrl, false, false);
    }

    /**
     * @param pagerMode rows live on a page of the reel pager: bodies clamp to
     *     {@link Comment#PAGER_CONTENT_WORD_LIMIT} words, rows pack tighter, and "see more"
     *     grows the body in place — the page scrolls to fit it (see
     *     {@link SlideScrollHost}) rather than re-packing.
     * @param reelMode the row is a full-screen TikTok-style reel instead of an ordinary list row
     *     — same view ids, same bind logic, different arrangement. Implies pager behaviour.
     *     Both are used only by {@link CommentSlideAdapter}.
     */
    public CommentListAdapter(Context context, List<Comment> commentList, EditText messageEditText, CommentActionHost parentFragment, boolean isReplyAdapter, boolean isReplyToReplyAdapter, String pageUrl, boolean pagerMode, boolean reelMode) {
        mCommentList = commentList;
        mMessageEditText = messageEditText;
        mParentFragment = parentFragment;
        mIsReplyAdapter = isReplyAdapter;
        mIsReplyToReplyAdapter = isReplyToReplyAdapter;
        mPageUrl = pageUrl;
        mIsPagerMode = pagerMode || reelMode;
        mIsReelMode = reelMode;
    }

    /**
     * Updates a comment after a successful upload.
     * Finds the temporary item, replaces it with the real item, and notifies the specific row.
     */
    public void updateCommentForSuccess(String tempId, Comment realComment) {
        if (mCommentList == null || tempId == null) return;
        
        for (int i = 0; i < mCommentList.size(); i++) {
            Comment c = mCommentList.get(i);
            // Check for ID match. Safe check for null IDs.
            if (c.getId() != null && c.getId().equals(tempId)) {
                // Replace the temporary comment with the real one from server
                mCommentList.set(i, realComment);
                // Trigger the bind method again for this position to remove progress bar
                notifyItemChanged(i);
                return;
            }
        }
    }

    /**
     * Updates a comment after a failed upload.
     * Finds the temporary item, marks it as failed, and notifies the specific row.
     */
    public void updateCommentForFailure(String tempId) {
        if (mCommentList == null || tempId == null) return;

        for (int i = 0; i < mCommentList.size(); i++) {
            Comment c = mCommentList.get(i);
            if (c.getId() != null && c.getId().equals(tempId)) {
                c.setUploadStatus(Comment.UploadStatus.FAILED);
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Replies pages: the comment the listed replies answer. */
    public void setParentComment(@Nullable Comment parent) {
        mParentComment = parent;
    }

    @Override
    public int getItemCount() {
        return mCommentList.size();
    }

    @NonNull
    @Override
    public CommentListAdapter.CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = mIsReelMode
                ? R.layout.browser_express_comment_reel_item
                : R.layout.browser_express_comment;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new CommentHolder(view, mMessageEditText, mParentFragment, mIsReplyAdapter, mIsReplyToReplyAdapter);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentListAdapter.CommentHolder holder, int position) {
        Comment comment = mCommentList.get(position);
        holder.bind(comment, position);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentListAdapter.CommentHolder holder, int position,
            @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_UPLOAD_STATE)) {
            // Upload progress and the like: only the outgoing rows change, so the media and
            // its player are left alone.
            holder.bindAttachmentRows(mCommentList.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    /** Rebind payload for a sending comment whose upload moved on. */
    static final Object PAYLOAD_UPLOAD_STATE = new Object();

    /**
     * Redraws a sending comment's upload state (progress, failure, the posted highlight) without
     * rebinding the rest of the row. Returns whether this list shows the comment.
     */
    public boolean refreshUploadState(String commentId) {
        if (mCommentList == null || commentId == null) return false;
        for (int i = 0; i < mCommentList.size(); i++) {
            if (commentId.equals(mCommentList.get(i).getId())) {
                notifyItemChanged(i, PAYLOAD_UPLOAD_STATE);
                return true;
            }
        }
        return false;
    }

    public class CommentHolder extends RecyclerView.ViewHolder
            implements GlobalVideoPlaybackManager.Playable {
        TextView usernameText;
        TextView contentText;
        /** Reel pages only: the one-line comment a tap opens in full. */
        private final @Nullable ExpandableCaption mReelCaption;
        private final ImageView mAvatarImage;
        private final TextView mReplyButton;

        // The reaction row and the time, on cards and reels alike.
        private final @Nullable TextView mTimeText;
        /** NEW on a take posted since the reader last opened the stream; cards and reels. */
        private final @Nullable View mNewTag;
        private final View mAddReactionButton;
        private final TextView[] mReactionChips;
        // The chip showing the reader's own reaction, where a reaction burst pops from.
        private @Nullable TextView mMineChip;
        private final @Nullable TextView mReactionMore;
        private final View mMoreButton;
        // Reels only: the rail has its own share button; cards share from the ⋮ menu.
        private final @Nullable View mShareButton;
        // Cards only: several files, or a comment this reader is sending (CommentAttachmentRows).
        private final @Nullable LinearLayout mAttachmentTiles;
        private final @Nullable ImageView mOutgoingVideoThumb;
        private final @Nullable TextView mOutgoingVideoDuration;
        private final @Nullable TextView mOutgoingVideoName;
        private final @Nullable TextView mOutgoingVideoMeta;
        private final @Nullable TextView mOutgoingVideoAction;
        private final @Nullable ProgressBar mUploadProgress;
        private final @Nullable TextView mUploadAction;
        /** The card's own look, put back when a recycled outgoing card shows another comment. */
        private final @Nullable android.graphics.drawable.Drawable mCardBackground;
        private final @Nullable android.content.res.ColorStateList mTimeColors;
        private final @Nullable android.content.res.ColorStateList mUsernameColors;
        // Cards only: a reel is a video, never a document.
        private final @Nullable View mDocumentRow;
        private final @Nullable TextView mDocumentName;
        private final @Nullable TextView mDocumentMeta;
        private BraveActivity activity;
        private final Button mReadMoreButton;

        private final ProgressBar mPostingProgressBar;
        private final TextView mFailedTextView;

        private final LinearLayout mActionItemsLayout;

        private final EditText mMessageEditText;
        private final LinearLayout mCommentLayout;


        private final CommentActionHost mParentFragment;

        private final boolean mIsReplyAdapter;
        private final boolean mIsReplyToReplyAdapter;

        ImageView commentImage;
        CardView commentMediaCard;
        PlayerView commentVideo;
        ExoPlayer player;
        ImageView playPauseIcon;
        ProgressBar videoProgressBar;
        private Context context;

        /** Inline cards only: a reel always plays with sound and has no mute button. */
        @Nullable ImageButton muteButton;
        Space mediaAspectRatioSpacer;
        ConstraintLayout mediaContainer;

        LinearLayout audioRow;
        ImageButton audioPlayPause;
        /** The card's voice-note progress (Figma "Express/Audio player"). */
        @Nullable SeekBar audioSeek;
        TextView audioTimer;
        private ExoPlayer mAudioPlayer;
        private long mAudioDurationMs;
        private final Handler mAudioTickHandler = new Handler(Looper.getMainLooper());
        private final Runnable mAudioTick = new Runnable() {
            @Override
            public void run() {
                updateAudioProgress();
                if (mAudioPlayer != null && mAudioPlayer.isPlaying()) {
                    mAudioTickHandler.postDelayed(this, 60);
                }
            }
        };

        private boolean mHasVideo;
        private boolean mIsVideoInitialized;
        /**
         * The player has been asked to load. A reel page binds its player without loading it
         * while another reel has the head start (GlobalVideoPlaybackManager#requestReelLoad).
         */
        private boolean mPlayerPrepared;

        /** Comment currently bound, so the player's size report can be attributed to it. */
        private Comment mBoundComment;

        // Thin progress line along the bottom of a reel page, ticked while the video plays.
        private final Handler mVideoTickHandler = new Handler(Looper.getMainLooper());
        /** The reader is dragging the reel's timeline: the tick leaves it where their finger is. */
        private boolean mScrubbingVideo;
        /** Reel pages only: holding the right half plays the video at 2× until the finger lifts. */
        @Nullable private HoldToFastForward mFastForward;
        private final Runnable mVideoTick = new Runnable() {
            @Override
            public void run() {
                if (player == null || videoProgressBar == null) return;
                long duration = player.getDuration();
                if (duration > 0 && !mScrubbingVideo) {
                    videoProgressBar.setProgress(
                            (int) (1000L * player.getCurrentPosition() / duration));
                }
                if (player.getPlayWhenReady()) {
                    mVideoTickHandler.postDelayed(this, 250);
                }
            }
        };

        private boolean shouldCloseKeyboardOnReply;

        CommentHolder(@NonNull View itemView, EditText messageEditText, CommentActionHost parentFragment, boolean isReplyAdapter, boolean isReplyToReplyAdapter) {
            super(itemView);
            this.context = itemView.getContext();

            mMessageEditText = messageEditText;
            mParentFragment = parentFragment;
            mIsReplyAdapter = isReplyAdapter;
            mIsReplyToReplyAdapter = isReplyToReplyAdapter;

            mAvatarImage = itemView.findViewById(R.id.avatar_image);
            usernameText = itemView.findViewById(R.id.username);
            contentText = itemView.findViewById(R.id.comment_content);
            View contentScroll = itemView.findViewById(R.id.comment_content_scroll);
            mReelCaption = contentScroll instanceof android.widget.ScrollView
                    ? new ExpandableCaption((android.widget.ScrollView) contentScroll, contentText, 1)
                    : null;
            mReplyButton = itemView.findViewById(R.id.btn_reply);
            mActionItemsLayout = itemView.findViewById(R.id.action_items);
            mCommentLayout = itemView.findViewById(R.id.comment_layout);
            mReadMoreButton = itemView.findViewById(R.id.btn_read_more_comment);

            commentImage = itemView.findViewById(R.id.comment_image);
            commentVideo = itemView.findViewById(R.id.comment_video);
            commentMediaCard = itemView.findViewById(R.id.comment_media_card);

            playPauseIcon = itemView.findViewById(R.id.play_pause_icon);
            videoProgressBar = itemView.findViewById(R.id.video_progress);
            // The "2×" badge is on the reel page layout only.
            View fastForwardBadge = itemView.findViewById(R.id.video_fast_forward_badge);
            if (fastForwardBadge != null) {
                // A reel is the whole page, so a hold on either side speeds it up.
                mFastForward = new HoldToFastForward(() -> player, fastForwardBadge, true);
            }

            mPostingProgressBar = itemView.findViewById(R.id.posting_progress_bar);
            mFailedTextView = itemView.findViewById(R.id.failed_text_view);

            mTimeText = itemView.findViewById(R.id.comment_time);
            mNewTag = itemView.findViewById(R.id.comment_new_tag);
            mCardBackground = mCommentLayout != null ? mCommentLayout.getBackground() : null;
            mTimeColors = mTimeText != null ? mTimeText.getTextColors() : null;
            mUsernameColors = usernameText != null ? usernameText.getTextColors() : null;
            mAddReactionButton = itemView.findViewById(R.id.btn_add_reaction);
            mReactionChips = new TextView[] {itemView.findViewById(R.id.reaction_chip_1),
                itemView.findViewById(R.id.reaction_chip_2),
                itemView.findViewById(R.id.reaction_chip_3)};
            mReactionMore = itemView.findViewById(R.id.reaction_more);
            mMoreButton = itemView.findViewById(R.id.btn_comment_more);
            mShareButton = itemView.findViewById(R.id.btn_comment_share);
            mAttachmentTiles = itemView.findViewById(R.id.comment_attachment_tiles);
            mOutgoingVideoThumb = itemView.findViewById(R.id.comment_outgoing_video_thumb);
            mOutgoingVideoDuration = itemView.findViewById(R.id.comment_outgoing_video_duration);
            mOutgoingVideoName = itemView.findViewById(R.id.comment_outgoing_video_name);
            mOutgoingVideoMeta = itemView.findViewById(R.id.comment_outgoing_video_meta);
            mOutgoingVideoAction = itemView.findViewById(R.id.comment_outgoing_video_action);
            mUploadProgress = itemView.findViewById(R.id.comment_upload_progress);
            mUploadAction = itemView.findViewById(R.id.comment_upload_action);
            View videoFrame = itemView.findViewById(R.id.comment_outgoing_video_frame);
            if (videoFrame != null) videoFrame.setClipToOutline(true);
            View railScrim = itemView.findViewById(R.id.reel_rail_scrim);
            if (railScrim != null) railScrim.setBackground(new ReelRailScrimDrawable());
            mDocumentRow = itemView.findViewById(R.id.comment_document_row);
            mDocumentName = itemView.findViewById(R.id.comment_document_name);
            mDocumentMeta = itemView.findViewById(R.id.comment_document_meta);

            audioRow = itemView.findViewById(R.id.comment_audio_row);
            audioPlayPause = itemView.findViewById(R.id.audio_play_pause);
            audioSeek = itemView.findViewById(R.id.audio_seek);
            audioTimer = itemView.findViewById(R.id.audio_timer);

            muteButton = itemView.findViewById(R.id.video_mute_button);
            mediaAspectRatioSpacer = itemView.findViewById(R.id.media_aspect_ratio_spacer);
            mediaContainer = (ConstraintLayout) mediaAspectRatioSpacer.getParent();

            if (this.context instanceof BraveActivity) {
                this.activity = (BraveActivity) this.context;
            } else {
                try {
                    this.activity = BraveActivity.getBraveActivity();
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e("CommentHolder", "BraveActivity not found for holder", e);
                }
            }
        }

        // The reel's hold-to-2× touch listener leaves taps to the card's own click.
        @SuppressLint("ClickableViewAccessibility")
        void bind(Comment comment, int position) {
            if (mBoundComment != comment) EmojiBurst.cancel(itemView);
            mBoundComment = comment;
            if (activity == null) {
                Log.e("CommentHolder.bind", "Activity is null, some UI updates might fail.");
            }

            Comment.UploadStatus status = comment.getUploadStatus();
            
            // 1. RESET STATE: Always assume success/normal state first
            mCommentLayout.setAlpha(1.0f);
            mPostingProgressBar.setVisibility(View.GONE);
            mFailedTextView.setVisibility(View.GONE);
            // A comment still on its way up has no id the server knows, so nothing can act on
            // it until it lands.
            boolean actionable = status != Comment.UploadStatus.POSTING
                    && status != Comment.UploadStatus.FAILED;
            mAddReactionButton.setEnabled(actionable);
            mMoreButton.setEnabled(actionable);
            mReplyButton.setEnabled(actionable);
            if (mShareButton != null) mShareButton.setEnabled(actionable);

            // 2. APPLY STATUS: Override defaults if status is special
            if (status == Comment.UploadStatus.POSTING) {
                mCommentLayout.setAlpha(0.6f);
                mPostingProgressBar.setVisibility(View.VISIBLE);
            } else if (status == Comment.UploadStatus.FAILED) {
                mFailedTextView.setVisibility(View.VISIBLE);
            }

            if (mNewTag != null) {
                mNewTag.setVisibility(comment.showsNewTag() ? View.VISIBLE : View.GONE);
            }

            if (mTimeText != null) {
                long createdAtMs = comment.getCreatedAtMs();
                if (createdAtMs > 0) {
                    mTimeText.setText(PostListAdapter.formatAge(
                            context, createdAtMs, System.currentTimeMillis()));
                    mTimeText.setVisibility(View.VISIBLE);
                } else {
                    mTimeText.setVisibility(View.GONE);
                }
            }

            if (comment.getUser() != null) {
                usernameText.setText(comment.getUser().getUsername());
            } else {
                String ytAuthor = comment.getYoutubeAuthorName();
                usernameText.setText(ytAuthor != null ? ytAuthor : "");
            }
            boolean contentClamped;
            if (mIsPagerMode) {
                // Pager rows clamp to a word budget — the measurer measured them that way (or,
                // once expanded, with the full body).
                contentText.setText(comment.getPagerDisplayContent());
                contentClamped = comment.isPagerContentClamped();
                if (!mIsReelMode) {
                    // Stacked pager rows sit tighter than the flat-list XML defaults. The reel
                    // overlay has its own layout and skips this.
                    CommentRowMeasurer.applyPagerRowSpacing(itemView);
                }
            } else {
                contentText.setText(comment.getDisplayContent());
                contentClamped = comment.isContentTruncated();
            }
            if (contentClamped) {
                mReadMoreButton.setVisibility(View.VISIBLE);
                mReadMoreButton.setOnClickListener(v -> {
                    if (mIsPagerMode) {
                        // The body grows where it stands and the page scrolls to cover it. It
                        // used to trigger a re-pack instead, which re-cut every page boundary
                        // and shuffled the surrounding comments out from under the reader.
                        comment.setPagerExpanded(true);
                    }
                    contentText.setText(comment.getContent());
                    mReadMoreButton.setVisibility(View.GONE);
                });
            } else {
                mReadMoreButton.setVisibility(View.GONE);
            }
            if (mReelCaption != null) {
                // A reel shows one line over the video (Figma "Full-screen Reel"); tapping the
                // line opens all of it over the video and tapping again folds it back.
                mReadMoreButton.setVisibility(View.GONE);
                mReelCaption.setText(comment.getContent());
            }

            // Every comment carries its reaction row: reacting works at every depth, only the
            // reply link depends on it.
            mActionItemsLayout.setVisibility(View.VISIBLE);
            mReplyButton.setVisibility(mIsReplyToReplyAdapter ? View.INVISIBLE : View.VISIBLE);
            bindReactions(comment);
            bindReactionActions(comment);
            bindDocument(comment);

            mHasVideo = false;
            mIsVideoInitialized = false;
            commentMediaCard.setVisibility(View.GONE);
            commentImage.setVisibility(View.GONE);
            commentVideo.setVisibility(View.GONE);
            if (muteButton != null) muteButton.setVisibility(View.GONE);

            if (player != null) {
                GlobalVideoPlaybackManager.getInstance().finishReelLoad(this);
                player.stop();
                player.release();
                player = null;
            }

            releaseAudioPlayer();
            // A comment with several files shows them as tiles instead (see bindAttachmentRows);
            // only a lone voice note keeps its player.
            boolean ownSlots = !comment.usesAttachmentRows();
            if (comment.hasAudioMedia() && (ownSlots || isLoneVoiceNote(comment))) {
                audioRow.setVisibility(View.VISIBLE);
                bindAudio(comment);
            } else {
                audioRow.setVisibility(View.GONE);
            }

            String imageUrl = comment.getDisplayImageUrl();
            String videoUrl = comment.getMediaVideoUrl();

            boolean hasImage = comment.hasImageMedia();
            boolean hasVideo = comment.hasVideoMedia();
            // Audio deliberately excluded: it has no media card, it renders as its own row.
            boolean hasMedia = comment.hasVisualMedia() && ownSlots;

            if (hasMedia) {
                commentMediaCard.setVisibility(View.VISIBLE);

                String urlString;
                String mediaType;

                if (hasVideo) {
                    urlString = videoUrl;
                    mediaType = "video";
                } else if (comment.hasGifMedia()) {
                    // A GIF lays out exactly like a still image; the type only decides that the
                    // loader has to keep the frames instead of decoding one bitmap out of them.
                    urlString = imageUrl;
                    mediaType = "gif";
                } else {
                    urlString = imageUrl;
                    mediaType = "image";
                }
                
                Uri mediaUri = Uri.parse(urlString);
                if (mIsReelMode) {
                    // On a reel page the video already fills the screen, so a tap toggles
                    // play/pause the way TikTok does instead of opening the full-screen viewer.
                    // The layout is full-bleed; the 16:9 spacer ratio never applies here.
                    applyReelResizeMode(comment.getMediaWidth(), comment.getMediaHeight());
                    commentMediaCard.setOnClickListener(v -> toggleReelPlayback());
                    if (mFastForward != null) {
                        // Taps still reach the card (play/pause); only a hold is kept from it.
                        commentMediaCard.setOnTouchListener(
                                (v, event) -> mFastForward.onTouch(v, event));
                    }
                } else {
                    commentMediaCard.setOnClickListener(
                            v -> openFullScreenViewer(mediaUri, mediaType));
                    // The card takes the design preset nearest the media's own shape (Figma
                    // "Express/Media": 16:9, 4:3 or 1:1) — the same one CommentRowMeasurer
                    // measured the row with. The video fits inside it whole, like the photo
                    // does (fitCenter): never cropped to fill the preset, with the card's
                    // background in any bars.
                    int[] ratio = comment.getInlineMediaRatio();
                    setAspectRatio(ratio[0], ratio[1]);
                    commentVideo.setResizeMode(
                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                }
                bindMediaContent(mediaUri, mediaType);
            }

            if(mMessageEditText == null && mParentFragment == null && activity != null){
                mActionItemsLayout.setVisibility(View.GONE);

                usernameText.setTextSize(11);
                contentText.setTextSize(12);
                mReadMoreButton.setVisibility(View.GONE);

                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) contentText.getLayoutParams();
                params.bottomMargin = 0;
                contentText.setLayoutParams(params);

                mCommentLayout.setPadding(10, 10, 10, 0);

                View.OnClickListener postClickListener = v -> {
                    if (activity != null) {
                        JSONObject payload = new JSONObject();
                        try {
                            payload.put("comment_id", comment.getId());
                            payload.put("post_id", comment.getPostParent());
                        } catch (JSONException e) {
                        }
                        String accessToken = activity.getAccessToken();
                        sendEventToPostHog(PostHogEventKeys.FEED_CLICKED_ON, accessToken, activity.getCurrentAppVersion(), payload);

                        activity.showCommentsBottomSheetFromPost(comment.getPostParent(), comment.getPostUsername(), comment.getPostContent(), comment.getPostAvatarUrl(), false);
                    }
                };
                usernameText.setOnClickListener(postClickListener);
                mCommentLayout.setOnClickListener(postClickListener);
                contentText.setOnClickListener(postClickListener);

                if(comment.getContent().length() > 75){
                    String contentString = comment.getContent().substring(0, 75) + "...";
                    contentText.setText(contentString);
                }
            }
                
            ProfileAvatar.load(mAvatarImage, avatarUrlFor(comment), null);

            bindAttachmentRows(comment);

            int replies = comment.getCommentCount();
            String repliesLabel = replies > 0
                    ? context.getResources().getQuantityString(
                            R.plurals.browser_express_comment_replies, replies, replies)
                    : context.getString(R.string.browser_express_comment_reply);
            if (mIsReelMode) {
                // The rail shows the bare count under its icon, and nothing while there is none.
                mReplyButton.setText(replies > 0 ? formatNumberCompact(replies) : "");
                mReplyButton.setContentDescription(repliesLabel);
            } else {
                mReplyButton.setText(repliesLabel);
            }
            shouldCloseKeyboardOnReply = replies > 0;

            if(mReplyButton != null){
                mReplyButton.setOnClickListener(v -> {
                    if (activity == null || mParentFragment == null) return; 

                    if(shouldCloseKeyboardOnReply){
                        mParentFragment.hideKeyboard();
                    }else{
                        mParentFragment.showKeyboardWithFocus();
                    }

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    String accessToken = activity.getAccessToken();

                    if(mIsReplyAdapter){
                        sendEventToPostHog(PostHogEventKeys.CLICKED_TO_VIEW_REPLY2REPLY, accessToken, activity.getCurrentAppVersion(), payload);
                        String parentCommentJson = new com.google.gson.Gson().toJson(comment);
                        mParentFragment.openRepliesToReply(comment.getId(), parentCommentJson);
                    } else if (!mIsReplyToReplyAdapter){
                        sendEventToPostHog(PostHogEventKeys.CLICKED_TO_VIEW_REPLIES, accessToken, activity.getCurrentAppVersion(), payload);
                        String parentCommentJson = new com.google.gson.Gson().toJson(comment);
                        mParentFragment.openReplies(comment.getId(), parentCommentJson);
                    }
                });
            }

        }

        /**
         * The rows for several files and the outgoing card's state (Figma "Outgoing comment
         * morph", 498:2056): the tiles or the sending video, the upload line, Retry or Cancel, and
         * the card's sending, failed or just-posted look.
         */
        void bindAttachmentRows(Comment comment) {
            CommentAttachmentRows.applyLayout(itemView, comment);
            List<CommentAttachment> files = comment.getAttachments();
            boolean outgoing = comment.isOutgoing();

            View tilesScroll = itemView.findViewById(R.id.comment_attachment_tiles_scroll);
            if (mAttachmentTiles != null && tilesScroll != null
                    && tilesScroll.getVisibility() == View.VISIBLE
                    && mAttachmentTiles.getTag() != files) {
                // Tagged with the list so upload ticks don't rebuild (and reload) the tiles.
                mAttachmentTiles.setTag(files);
                CommentAttachmentRows.bindTiles(
                        mAttachmentTiles, files, outgoing ? null : this::openAttachment);
            }

            CommentAttachment video =
                    outgoing && files.size() == 1 && files.get(0).isVideo() ? files.get(0) : null;
            if (video != null && mOutgoingVideoThumb != null) {
                if (mOutgoingVideoThumb.getTag() != video) {
                    mOutgoingVideoThumb.setTag(video);
                    Glide.with(mOutgoingVideoThumb)
                            .load(video.getUrl())
                            .centerCrop()
                            .into(mOutgoingVideoThumb);
                }
                if (mOutgoingVideoDuration != null) {
                    mOutgoingVideoDuration.setVisibility(
                            video.getDurationMs() > 0 ? View.VISIBLE : View.GONE);
                    mOutgoingVideoDuration.setText(
                            CommentComposerController.clock(video.getDurationMs()));
                }
                if (mOutgoingVideoName != null) {
                    String name = video.getName();
                    mOutgoingVideoName.setText(name != null && !name.isEmpty()
                                    ? name
                                    : context.getString(R.string.browser_express_composer_video));
                }
                if (mOutgoingVideoMeta != null) {
                    mOutgoingVideoMeta.setText(CommentAttachmentRows.videoMeta(context, video));
                }
            }

            if (!outgoing) {
                if (mCardBackground != null && mCommentLayout.getBackground() != mCardBackground) {
                    mCommentLayout.setBackground(mCardBackground);
                }
                if (mTimeColors != null && mTimeText != null) mTimeText.setTextColor(mTimeColors);
                if (mUsernameColors != null) usernameText.setTextColor(mUsernameColors);
                return;
            }

            Comment.UploadStatus status = comment.getUploadStatus();
            boolean sending = status == Comment.UploadStatus.POSTING;
            boolean failed = status == Comment.UploadStatus.FAILED;
            if (sending) {
                mCommentLayout.setBackgroundResource(
                        R.drawable.browser_express_comment_outgoing_sending_bg);
            } else if (failed) {
                mCommentLayout.setBackgroundResource(
                        R.drawable.browser_express_comment_outgoing_failed_bg);
            } else if (comment.isJustPosted()) {
                mCommentLayout.setBackgroundResource(
                        R.drawable.browser_express_comment_outgoing_posted_bg);
            } else if (mCardBackground != null) {
                mCommentLayout.setBackground(mCardBackground);
            }
            mCommentLayout.setAlpha(sending ? 0.55f : failed ? 0.82f : 1f);

            usernameText.setText(R.string.browser_express_comment_you);
            usernameText.setTextColor(ContextCompat.getColor(
                    context, R.color.browser_express_comment_outgoing_author));
            if (mTimeText != null && (sending || failed || comment.getCreatedAtMs() <= 0)) {
                mTimeText.setVisibility(View.VISIBLE);
                mTimeText.setText(uploadStatusText(comment));
                mTimeText.setTextColor(ContextCompat.getColor(
                        context, R.color.browser_express_comment_outgoing_status));
            }

            if (mUploadProgress != null) {
                mUploadProgress.setProgress(
                        Math.round(Math.max(0f, comment.getUploadProgress()) * 1000));
            }

            TextView action = video != null ? mOutgoingVideoAction : mUploadAction;
            if (action != null && action.getVisibility() == View.VISIBLE) {
                action.setText(failed ? R.string.browser_express_comment_upload_retry
                                      : R.string.browser_express_comment_upload_cancel);
                action.setBackgroundResource(failed
                                ? R.drawable.browser_express_comment_upload_retry_bg
                                : R.drawable.browser_express_comment_upload_cancel_bg);
                action.setTextColor(ContextCompat.getColor(context, failed
                                ? R.color.browser_express_comment_upload_retry_text
                                : R.color.browser_express_comment_upload_cancel_text));
                action.setOnClickListener(v -> {
                    if (mParentFragment == null) return;
                    if (failed) {
                        mParentFragment.retryUpload(comment.getId());
                    } else {
                        mParentFragment.cancelUpload(comment.getId());
                    }
                });
            }
        }

        /** "Sending…", "Uploading 42%", "Uploading…", "Upload failed" or "now". */
        private String uploadStatusText(Comment comment) {
            switch (comment.getUploadStatus()) {
                case FAILED:
                    return context.getString(R.string.browser_express_comment_status_failed);
                case POSTING: {
                    List<CommentAttachment> files = comment.getAttachments();
                    float progress = comment.getUploadProgress();
                    if (CommentAttachmentRows.hasVideo(files) && progress >= 0) {
                        return context.getString(
                                R.string.browser_express_comment_status_uploading_percent,
                                Math.round(progress * 100));
                    }
                    if (isLoneVoiceNote(comment)) {
                        return context.getString(R.string.browser_express_comment_status_uploading);
                    }
                    return context.getString(R.string.browser_express_comment_status_sending);
                }
                default:
                    return context.getString(R.string.browser_express_comment_status_now);
            }
        }

        /** Nothing on the comment but a voice note, which keeps its player row. */
        private boolean isLoneVoiceNote(Comment comment) {
            List<CommentAttachment> files = comment.getAttachments();
            return files.size() == 1
                    && CommentAttachment.KIND_AUDIO.equals(files.get(0).getKind());
        }

        /** A tapped tile of a posted comment: pictures full screen, files in a custom tab. */
        private void openAttachment(CommentAttachment attachment) {
            String url = attachment.getUrl();
            if (url == null || !url.startsWith("http")) return;
            switch (attachment.getKind()) {
                case CommentAttachment.KIND_VIDEO:
                    openFullScreenViewer(Uri.parse(url), "video");
                    break;
                case CommentAttachment.KIND_GIF:
                    openFullScreenViewer(Uri.parse(url), "gif");
                    break;
                case CommentAttachment.KIND_IMAGE:
                    openFullScreenViewer(Uri.parse(url), "image");
                    break;
                default:
                    TabUtils.openUrlInCustomTab(context, url);
                    break;
            }
        }

        /**
         * The link that shares this take: the post's /t page, on the take. Null for takes with no
         * such page: a web page's, or YouTube's own.
         */
        private @Nullable String shareLink(Comment comment) {
            String postId = comment.getPostParent();
            // Replies record only their parent comment; they are under the post this sheet shows.
            if (!ShareLinks.isId(postId) && mParentFragment != null) {
                postId = mParentFragment.sharePostId();
            }
            return ShareLinks.forTake(postId, comment.getId());
        }

        private void shareComment(Comment comment) {
                    if (activity == null) return;
                    String link = shareLink(comment);
                    if (link == null) return;

                    JSONObject payload = new JSONObject();
                    try {
                            payload.put("comment_id", comment.getId());
                    } catch (JSONException e) {
                    }
                    String accessToken = activity.getAccessToken();
                    sendEventToPostHog(PostHogEventKeys.COMMENT_REPLY_SHARE_CLICKED, accessToken, activity.getCurrentAppVersion(), payload);

                    // One short line, then the link: chat apps build the preview card from the
                    // page itself.
                    String message = "People say the craziest stuff! 👀 Check this out 👇\n\n" + link;

                    Intent sharingIntent = new Intent(Intent.ACTION_SEND);
                    sharingIntent.setType("text/plain");
                    sharingIntent.putExtra(Intent.EXTRA_TEXT, message);

                    List<Intent> pinnedIntents = new ArrayList<>();

                    Intent instaStory = new Intent(Intent.ACTION_SEND);
                    instaStory.setType("text/plain");
                    instaStory.putExtra(Intent.EXTRA_TEXT, message);
                    instaStory.setPackage("com.instagram.android");
                    if (!activity.getPackageManager().queryIntentActivities(instaStory, 0).isEmpty()) {
                        pinnedIntents.add(instaStory);
                    }

                    Intent whatsAppStatus = new Intent(Intent.ACTION_SEND);
                    whatsAppStatus.setType("text/plain");
                    whatsAppStatus.putExtra(Intent.EXTRA_TEXT, message);
                    whatsAppStatus.setPackage("com.whatsapp");
                    if (!activity.getPackageManager().queryIntentActivities(whatsAppStatus, 0).isEmpty()) {
                        pinnedIntents.add(whatsAppStatus);
                    }

                    Intent chooser = Intent.createChooser(sharingIntent, "Share via");
                    if (!pinnedIntents.isEmpty()) {
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, pinnedIntents.toArray(new Intent[0]));
                    }
                    activity.startActivity(chooser);
        }

        // ---------------------------------------------------------------------------------
        // Reactions
        // ---------------------------------------------------------------------------------

        /**
         * The reaction chips. On a card: the reader's own reaction first and highlighted (Figma
         * "Reaction selected"), then the most used others, up to three chips in all; "+N" counts
         * the reactions left over. On the reel rail: the three most used, in that order, with the
         * reader's own among them.
         */
        private void bindReactions(Comment comment) {
            if (mReactionChips == null) return;
            List<Comment.ReactionCount> reactions = comment.getReactions();
            String mine = comment.getMyReaction();

            List<Comment.ReactionCount> shown =
                    mIsReelMode ? railReactions(reactions, mine) : new ArrayList<>();
            if (mine != null && !mIsReelMode) {
                for (Comment.ReactionCount reaction : reactions) {
                    if (Comment.sameEmoji(reaction.getEmoji(), mine)) {
                        shown.add(reaction);
                        break;
                    }
                }
            }
            int limit = mIsReelMode ? shown.size() : 3;
            for (Comment.ReactionCount reaction : reactions) {
                if (shown.size() >= limit) break;
                if (!Comment.sameEmoji(reaction.getEmoji(), mine)) shown.add(reaction);
            }

            int shownTotal = 0;
            mMineChip = null;
            for (int i = 0; i < mReactionChips.length; i++) {
                TextView chip = mReactionChips[i];
                if (i >= shown.size()) {
                    chip.setVisibility(View.GONE);
                    continue;
                }
                Comment.ReactionCount reaction = shown.get(i);
                shownTotal += reaction.getCount();
                boolean isMine = mine != null && Comment.sameEmoji(reaction.getEmoji(), mine);
                if (isMine) mMineChip = chip;
                bindChip(chip, comment, reaction, isMine);
            }

            int rest = comment.getReactionCount() - shownTotal;
            if (mReactionMore != null) {
                if (rest > 0) {
                    mReactionMore.setText(context.getString(
                            R.string.browser_express_comment_reactions_more, rest));
                    mReactionMore.setVisibility(View.VISIBLE);
                } else {
                    mReactionMore.setVisibility(View.GONE);
                }
            }
        }

        /**
         * The rail's chips: the three most used reactions (most used first), the reader's own
         * taking the third place when it is not among them.
         */
        private List<Comment.ReactionCount> railReactions(
                List<Comment.ReactionCount> reactions, @Nullable String mine) {
            List<Comment.ReactionCount> shown =
                    new ArrayList<>(reactions.subList(0, Math.min(3, reactions.size())));
            if (mine == null) return shown;
            for (Comment.ReactionCount reaction : shown) {
                if (Comment.sameEmoji(reaction.getEmoji(), mine)) return shown;
            }
            for (Comment.ReactionCount reaction : reactions) {
                if (Comment.sameEmoji(reaction.getEmoji(), mine)) {
                    if (shown.size() == 3) shown.remove(2);
                    shown.add(reaction);
                    break;
                }
            }
            return shown;
        }

        private void bindChip(TextView chip, Comment comment, Comment.ReactionCount reaction,
                boolean mine) {
            float density = context.getResources().getDisplayMetrics().density;
            // Over a reel the chips keep fixed colours: the video is dark in either theme.
            int countColor;
            if (mIsReelMode) {
                countColor = ContextCompat.getColor(context, mine
                                ? R.color.browser_express_reel_reaction_selected_count
                                : R.color.browser_express_reel_reaction_count);
            } else {
                countColor = ContextCompat.getColor(context, mine
                                ? R.color.browser_express_comment_reaction_selected_count
                                : R.color.browser_express_comment_reaction_count);
            }
            SpannableStringBuilder text = new SpannableStringBuilder(reaction.getEmoji());
            if (mIsReelMode) {
                // The rail stacks a 16dp emoji over its count (Figma "Emoji · Like + count").
                text.setSpan(new AbsoluteSizeSpan(16, true), 0, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (mine) {
                // The reader's own emoji is drawn a size up, as the design's selected chip is.
                text.setSpan(new AbsoluteSizeSpan(14, true), 0, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int countStart = text.length();
            text.append(mIsReelMode ? '\n' : ' ').append(formatNumberCompact(reaction.getCount()));
            text.setSpan(new ForegroundColorSpan(countColor), countStart, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(text);
            if (mIsReelMode) {
                chip.setBackgroundResource(mine
                                ? R.drawable.browser_express_comment_reel_reaction_selected_bg
                                : R.drawable.browser_express_comment_reel_reaction_bg);
            } else {
                chip.setBackgroundResource(mine
                                ? R.drawable.browser_express_comment_reaction_selected_bg
                                : R.drawable.browser_express_comment_reaction_bg);
            }
            if (mIsReelMode) {
                // The rail's chips are a fixed size with their content centred.
                chip.setPadding(0, 0, 0, 0);
            } else {
                int horizontal = Math.round((mine ? 7 : 4) * density);
                int vertical = Math.round((mine ? 3 : 2) * density);
                chip.setPadding(horizontal, vertical, horizontal, vertical);
            }
            chip.setContentDescription(reaction.getEmoji() + " " + reaction.getCount());
            chip.setVisibility(View.VISIBLE);
            chip.setOnClickListener(v -> {
                if (comment.getUploadStatus() == Comment.UploadStatus.POSTING) return;
                if (mine) {
                    // Tapping your own reaction takes it back.
                    applyReaction(comment, null);
                    return;
                }
                // Any emoji is a reaction: someone else's chip is a one-tap "me too".
                applyReaction(comment, reaction.getEmoji());
            });
        }

        private void bindReactionActions(Comment comment) {
            if (mAddReactionButton != null) {
                mAddReactionButton.setOnClickListener(v -> openReactionPicker(comment));
            }
            if (mMoreButton != null) {
                mMoreButton.setOnClickListener(v -> showOverflowMenu(comment));
            }
            if (mShareButton != null) {
                mShareButton.setVisibility(shareLink(comment) != null ? View.VISIBLE : View.GONE);
                mShareButton.setOnClickListener(v -> shareComment(comment));
            }
        }

        private void openReactionPicker(Comment comment) {
            if (mParentFragment == null || !mParentFragment.isAdded()) return;
            mParentFragment.hideKeyboard();
            CommentReactionSheet.show(mParentFragment.getChildFragmentManager(), comment,
                    emoji -> applyReaction(comment, emoji));
        }

        /**
         * Sets (or, for null, removes) the reader's reaction: the card updates at once and the
         * server's answer then replaces the guess; a failure puts things back.
         */
        private void applyReaction(Comment comment, @Nullable String emoji) {
            if (activity == null) return;
            String previous = comment.getMyReaction();
            if (emoji == null && previous == null) return;
            if (emoji != null && Comment.sameEmoji(emoji, previous)) return;

            final List<Comment.ReactionCount> oldReactions =
                    new ArrayList<>(comment.getReactions());
            final int oldCount = comment.getReactionCount();
            comment.applyLocalReaction(emoji);
            bindReactions(comment);
            itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (emoji != null) {
                playReactionBurst(emoji);
                // +1 Karma for any emoji, a switch included; played at once, not on the reply.
                if (mParentFragment != null && mParentFragment.isAdded()) {
                    mParentFragment.onReactionAdded(itemView, mIsReelMode);
                }
            }

            String accessToken = activity.getAccessToken();
            JSONObject payload = new JSONObject();
            try {
                payload.put("comment_id", comment.getId());
                payload.put("emoji", emoji != null ? emoji : JSONObject.NULL);
            } catch (JSONException e) {
            }
            sendEventToPostHog(PostHogEventKeys.REACTION_GIVEN, accessToken,
                    activity.getCurrentAppVersion(), payload);

            Runnable revert = () -> {
                comment.setReactionState(oldReactions, oldCount, previous);
                if (mBoundComment == comment) bindReactions(comment);
                Toast.makeText(context, R.string.browser_express_comment_reaction_failed,
                             Toast.LENGTH_SHORT)
                        .show();
            };

            if (comment.isYouTubeOnly()) {
                java.util.List<Comment> ancestors = buildAncestors(comment);
                new YouTubeInteractUtil.Task(mPageUrl, ancestors, "reaction", null, accessToken,
                        new YouTubeInteractUtil.Callback() {
                            @Override
                            public void onReactionState(JSONObject response) {
                                comment.readReactions(response);
                            }

                            @Override
                            public void onSuccess(Map<String, String> resolvedIds,
                                    String targetId, int upvoteCount, int downvoteCount,
                                    int commentCount, Vote didVote) {
                                updateResolvedIds(resolvedIds);
                                if (mBoundComment == comment) bindReactions(comment);
                            }

                            @Override
                            public void onFailure(String error) {
                                revert.run();
                            }
                        })
                        .withEmoji(emoji)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                return;
            }

            CommentReactionUtil.setReaction(comment.getId(), emoji, accessToken,
                    new CommentReactionUtil.Callback() {
                        @Override
                        public void onReactionSaved(JSONObject result,
                                @Nullable String newAccessToken,
                                @Nullable String newRefreshToken) {
                            comment.readReactions(result);
                            if (mBoundComment == comment) bindReactions(comment);
                            if (newAccessToken != null) handleNewToken(newAccessToken);
                            // The floating bar's page totals refresh when the sheet closes.
                            if (activity != null) activity.notePageReactionsChanged();
                        }

                        @Override
                        public void onReactionFailed(String error) {
                            revert.run();
                        }
                    });
        }

        /**
         * The reaction burst (design handoff "emoji-burst"): over a card it fills that card, over
         * a reel the whole page. It pops from the chip that now shows the reader's emoji, so it
         * waits for the rebound chips to be laid out.
         */
        private void playReactionBurst(String emoji) {
            final Comment comment = mBoundComment;
            OneShotPreDrawListener.add(itemView, () -> {
                if (mBoundComment != comment) return;
                View origin = mMineChip != null && mMineChip.getVisibility() == View.VISIBLE
                        ? mMineChip
                        : mAddReactionButton;
                EmojiBurst.play(itemView, origin, emoji, mIsReelMode);
            });
            itemView.invalidate();
        }

        /** The ⋮ menu: reply, share (unless the rail has its own button) and copy. */
        private void showOverflowMenu(Comment comment) {
            if (mMoreButton == null) return;
            PopupMenu menu = new PopupMenu(context, mMoreButton);
            final int reply = 1;
            final int share = 2;
            final int copy = 3;
            if (mReplyButton.getVisibility() == View.VISIBLE) {
                menu.getMenu().add(0, reply, 0, R.string.browser_express_comment_reply);
            }
            if (mShareButton == null && shareLink(comment) != null) {
                menu.getMenu().add(0, share, 1, R.string.browser_express_comment_menu_share);
            }
            if (comment.getContent() != null && !comment.getContent().trim().isEmpty()) {
                menu.getMenu().add(0, copy, 2, R.string.browser_express_comment_menu_copy);
            }
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == reply) {
                    mReplyButton.performClick();
                } else if (item.getItemId() == share) {
                    shareComment(comment);
                } else if (item.getItemId() == copy) {
                    ClipboardManager clipboard = (ClipboardManager)
                            context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                context.getString(R.string.comments), comment.getContent()));
                        Toast.makeText(context, R.string.browser_express_comment_copied,
                                     Toast.LENGTH_SHORT)
                                .show();
                    }
                } else {
                    return false;
                }
                return true;
            });
            menu.show();
        }

        /** A document attachment: its name and "PDF · 1.2 MB"; tapping opens it. */
        private void bindDocument(Comment comment) {
            if (mDocumentRow == null) return;
            if (!comment.hasDocumentMedia()) {
                mDocumentRow.setVisibility(View.GONE);
                return;
            }
            String name = comment.getMediaDocumentName();
            if (mDocumentName != null) {
                mDocumentName.setText(name != null && !name.isEmpty()
                                ? name
                                : context.getString(R.string.browser_express_attach_document));
            }
            if (mDocumentMeta != null) {
                mDocumentMeta.setText(CommentDocuments.describe(
                        context, name, comment.getMediaDocumentSize()));
            }
            mDocumentRow.setVisibility(View.VISIBLE);
            mDocumentRow.setOnClickListener(v -> {
                String url = comment.getMediaDocumentUrl();
                if (url == null || !url.startsWith("http")) return; // Still uploading.
                // A custom tab opens over the comments, so closing it lands the reader back here.
                TabUtils.openUrlInCustomTab(context, url);
            });
        }

        public boolean hasVideo() {
            return mHasVideo && mIsVideoInitialized && player != null;
        }

        // ---------------------------------------------------------------------------------
        // Voice notes
        // ---------------------------------------------------------------------------------

        private void bindAudio(Comment comment) {
            // Duration comes from the server so the total reads correctly before the clip has
            // been fetched; the player's own duration takes over once it is known.
            mAudioDurationMs = Math.max(0, comment.getMediaDurationMs());
            if (audioSeek != null) {
                audioSeek.setMax(1000);
                audioSeek.setProgress(0);
                audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (!fromUser || mAudioDurationMs <= 0) return;
                        if (mAudioPlayer == null) initializeAudioPlayer(comment.getMediaAudioUrl());
                        if (mAudioPlayer != null) {
                            mAudioPlayer.seekTo(mAudioDurationMs * progress / 1000);
                        }
                        audioTimer.setText(PostListAdapter.formatDuration(
                                mAudioDurationMs * progress / 1000));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {}
                });
            }
            setAudioPlayIcon(false);
            audioTimer.setText(formatAudioTime(0));

            audioPlayPause.setOnClickListener(v -> {
                if (mAudioPlayer != null && mAudioPlayer.isPlaying()) {
                    GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingAudio();
                } else {
                    if (mAudioPlayer == null) initializeAudioPlayer(comment.getMediaAudioUrl());
                    GlobalVideoPlaybackManager.getInstance().playAudio(this);
                }
            });
        }

        private void initializeAudioPlayer(String audioUrl) {
            if (audioUrl == null || audioUrl.isEmpty()) return;
            mAudioPlayer =
                    new ExoPlayer.Builder(context)
                            .setMediaSourceFactory(
                                    CommentMediaCache.mediaSourceFactory(context))
                            .build();
            mAudioPlayer.setMediaItem(MediaItem.fromUri(audioUrl));
            mAudioPlayer.prepare();
            mAudioPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && mAudioPlayer != null) {
                        long known = mAudioPlayer.getDuration();
                        if (known > 0) mAudioDurationMs = known;
                        updateAudioProgress();
                    } else if (state == Player.STATE_ENDED) {
                        // Rewind so the row is ready to replay rather than stuck at the end.
                        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingAudio();
                        if (mAudioPlayer != null) mAudioPlayer.seekTo(0);
                        if (audioSeek != null) audioSeek.setProgress(0);
                        audioTimer.setText(formatAudioTime(0));
                    }
                }
            });
        }

        public void startAudioPlayback() {
            if (mAudioPlayer == null) return;
            mAudioPlayer.setPlayWhenReady(true);
            setAudioPlayIcon(true);
            mAudioTickHandler.removeCallbacks(mAudioTick);
            mAudioTickHandler.post(mAudioTick);
        }

        public void stopAudioPlayback() {
            if (mAudioPlayer != null) mAudioPlayer.setPlayWhenReady(false);
            mAudioTickHandler.removeCallbacks(mAudioTick);
            if (audioPlayPause != null) setAudioPlayIcon(false);
        }

        private void updateAudioProgress() {
            if (mAudioPlayer == null) return;
            long position = Math.max(0, mAudioPlayer.getCurrentPosition());
            audioTimer.setText(formatAudioTime(position));
            float fraction = mAudioDurationMs > 0 ? position / (float) mAudioDurationMs : 0f;
            if (audioSeek != null) audioSeek.setProgress(Math.round(fraction * 1000));
        }

        /**
         * The card's player reads like a voice post's: the length ("01:40") until it has
         * played, then where it is. The reel overlay keeps its "0:12 / 1:40".
         */
        private String formatAudioTime(long positionMs) {
            if (audioSeek == null) return AudioTime.formatProgress(positionMs, mAudioDurationMs);
            return PostListAdapter.formatDuration(positionMs > 0 ? positionMs : mAudioDurationMs);
        }

        private void setAudioPlayIcon(boolean playing) {
            if (audioSeek != null) {
                audioPlayPause.setImageResource(playing
                                ? R.drawable.ic_browser_express_post_audio_pause
                                : R.drawable.ic_browser_express_post_audio_play);
            } else {
                audioPlayPause.setImageResource(
                        playing ? R.drawable.ic_pause_filled : R.drawable.ic_play_filled);
            }
            audioPlayPause.setContentDescription(context.getString(
                    playing ? R.string.be_audio_pause : R.string.be_audio_play));
        }

        private void releaseAudioPlayer() {
            mAudioTickHandler.removeCallbacks(mAudioTick);
            GlobalVideoPlaybackManager.getInstance().onAudioHolderReleased(this);
            if (mAudioPlayer != null) {
                mAudioPlayer.stop();
                mAudioPlayer.release();
                mAudioPlayer = null;
            }
            mAudioDurationMs = 0;
        }

        /** Dragging (or tapping) the reel's timeline moves the video to that point. */
        private void bindVideoScrubbing(SeekBar timeline) {
            mScrubbingVideo = false;
            timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    // Keyboard and accessibility steps have no drag to finish; seek right away.
                    if (fromUser && !mScrubbingVideo) seekVideoTo(bar);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    mScrubbingVideo = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    mScrubbingVideo = false;
                    seekVideoTo(bar);
                }
            });
        }

        private void seekVideoTo(SeekBar timeline) {
            if (player == null) return;
            long duration = player.getDuration();
            if (duration <= 0) return;
            player.seekTo(duration * timeline.getProgress() / timeline.getMax());
        }

        @Override
        public void startPlayback() {
            if (player != null && mIsReelMode
                    && (!mPlayerPrepared || player.getPlaybackState() != Player.STATE_READY)) {
                // The reader is on this reel now: it loads ahead of the pages beside it.
                GlobalVideoPlaybackManager.getInstance().claimReelLoad(this);
                preparePlayer();
            }
            if (player != null) player.setPlayWhenReady(true);
            if (playPauseIcon != null) playPauseIcon.setVisibility(View.GONE);
            if (mIsReelMode) {
                mVideoTickHandler.removeCallbacks(mVideoTick);
                mVideoTickHandler.post(mVideoTick);
            }
        }

        @Override
        public void stopPlayback() {
            if (mFastForward != null) mFastForward.cancel();
            if (player != null) player.setPlayWhenReady(false);
            if (mIsReelMode) mVideoTickHandler.removeCallbacks(mVideoTick);
            // The centred play button tells the reader a paused video is a video, not a still.
            if (playPauseIcon != null && mHasVideo) playPauseIcon.setVisibility(View.VISIBLE);
        }

        /**
         * Hands the size ExoPlayer reported for the bound comment's video to the shared
         * dimension cache. First report per URL wins; the cache notifies whoever is waiting —
         * in reel mode that is the fragment, which re-packs a portrait video onto its own page.
         */
        private void reportVideoDimensions(int width, int height) {
            if (width <= 0 || height <= 0) return;
            Comment comment = mBoundComment;
            if (comment == null || !comment.hasVideoMedia()) return;
            if (!comment.hasCachedDimensions()) {
                comment.setMediaWidth(width);
                comment.setMediaHeight(height);
            }
            CommentVideoDimensions.recordDimensions(comment.getMediaVideoUrl(), width, height);
        }

        /**
         * A reel page fills the screen with a fully vertical video (centre-crop) and letterboxes
         * anything squarer, with black bars above and below, rather than blowing it up to the
         * page's height. Unknown dimensions leave the mode as it is.
         */
        private void applyReelResizeMode(int width, int height) {
            if (width <= 0 || height <= 0) return;
            commentVideo.setResizeMode(width / (float) height <= REEL_CROP_MAX_RATIO
                            ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }

        /**
         * Tap-to-toggle for a reel page, TikTok style. Goes through the playback manager so its
         * one-video-at-a-time slot stays truthful: playing again takes the slot back from
         * whatever else has it.
         */
        private void toggleReelPlayback() {
            if (player == null) return;
            GlobalVideoPlaybackManager manager = GlobalVideoPlaybackManager.getInstance();
            if (player.getPlayWhenReady()) {
                if (manager.getCurrentlyPlayingHolder() == this) {
                    manager.pauseCurrentlyPlayingVideo();
                } else {
                    stopPlayback();
                }
            } else {
                manager.playVideo(this);
            }
        }

        private void bindMediaContent(Uri mediaUri, String mediaType) {
            if ("video".equals(mediaType)) {
                mHasVideo = true; 
                commentImage.setVisibility(View.GONE);
                commentVideo.setVisibility(View.VISIBLE);
                if (muteButton != null) muteButton.setVisibility(View.VISIBLE);
                // An inline card waits for its page to start it; until then the play button
                // marks it as a video (Figma "Express/Media" Type=Video). A reel shows it only
                // once paused.
                if (playPauseIcon != null) {
                    playPauseIcon.setVisibility(mIsReelMode ? View.GONE : View.VISIBLE);
                }
                
                initializePlayer(mediaUri);
            } else {
                mHasVideo = false;
                if (playPauseIcon != null) playPauseIcon.setVisibility(View.GONE);
                // Only release the playback slot when this holder held it (a recycled video row
                // being rebound as an image). Image rows bind while adjacent reel pages are
                // pre-built, and pausing unconditionally here would stop the video the reader is
                // actually watching.
                if (GlobalVideoPlaybackManager.getInstance().getCurrentlyPlayingHolder() == this) {
                    GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
                }

                commentVideo.setVisibility(View.GONE);
                if (muteButton != null) muteButton.setVisibility(View.GONE);
                commentImage.setVisibility(View.VISIBLE);

                String urlString = mediaUri.toString();
                if (!urlString.startsWith("http")) {
                    // A comment still uploading points at the local file it is about to send,
                    // and ImageLoader only speaks https. Glide reads a content:// or file:// URI
                    // directly — and animates a GIF sitting behind one.
                    int cornerRadiusPx = Math.round(MEDIA_CORNER_RADIUS_DP
                            * context.getResources().getDisplayMetrics().density);
                    Glide.with(activity)
                            .load(mediaUri)
                            .transform(new FitCenter(), new RoundedCorners(cornerRadiusPx))
                            .into(commentImage);
                } else {
                    // One cached path for stills and GIFs alike: Glide decodes an animated GIF
                    // from the bytes themselves, so the "gif" mediaType needs no separate
                    // pipeline, and the disk cache means a rebind after scrolling does not
                    // re-download the attachment from the media origin.
                    ImageLoader.downloadImageCached(urlString, Glide.with(activity), false,
                            MEDIA_CORNER_RADIUS_DP, commentImage, null);
                }
            }
        }

        private void setAspectRatio(int width, int height) {
            if (width > 0 && height > 0) {
                ConstraintSet constraintSet = new ConstraintSet();
                constraintSet.clone(mediaContainer);
                constraintSet.setDimensionRatio(mediaAspectRatioSpacer.getId(), String.format(Locale.US, "H,%d:%d", width, height));
                constraintSet.applyTo(mediaContainer);
            }
        }

        private void initializePlayer(Uri videoUri) {
            if (mFastForward != null) mFastForward.cancel();
            if (player != null) {
                GlobalVideoPlaybackManager.getInstance().finishReelLoad(this);
                player.stop();
                player.release();
            }
            mPlayerPrepared = false;

            // Reads through the shared media disk cache: a reel swiped back to, or an inline
            // card scrolled past twice, replays from local bytes instead of re-streaming from
            // the origin. Starts as soon as half a second is in rather than ExoPlayer's 2.5s.
            player =
                    new ExoPlayer.Builder(context)
                            .setMediaSourceFactory(
                                    CommentMediaCache.mediaSourceFactory(context))
                            .setLoadControl(CommentMediaCache.newFastStartLoadControl())
                            .build();
            commentVideo.setPlayer(player);
            commentVideo.setUseController(false);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);

            // The player learns the video's size as soon as it prepares the stream — usually
            // before, and more reliably than, the metadata probe in CommentVideoDimensions.
            // Reporting it there is what promotes a portrait video playing as an inline card
            // onto its own reel page without waiting for that probe.
            player.addListener(new Player.Listener() {
                @Override
                public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize size) {
                    // A quarter turn the decoder left unapplied is drawn rotated: use the shape
                    // on screen.
                    boolean quarterTurn = size.unappliedRotationDegrees == 90
                            || size.unappliedRotationDegrees == 270;
                    int width = quarterTurn ? size.height : size.width;
                    int height = quarterTurn ? size.width : size.height;
                    reportVideoDimensions(width, height);
                    // The cached size the page was bound with is first-report-wins; the stream's
                    // own size has the last word on how the reel is framed.
                    if (mIsReelMode) applyReelResizeMode(width, height);
                }

                // Playable, or never going to be: the reels waiting behind this one may load.
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        GlobalVideoPlaybackManager.getInstance().finishReelLoad(
                                CommentHolder.this);
                    }
                }

                @Override
                public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                    GlobalVideoPlaybackManager.getInstance().finishReelLoad(CommentHolder.this);
                }
            });

            if (muteButton != null) {
                muteButton.setOnClickListener(v -> {
                    if (player != null) {
                        if (player.getVolume() > 0) {
                            player.setVolume(0f);
                            muteButton.setImageResource(R.drawable.volume_off);
                        } else {
                            player.setVolume(1f);
                            muteButton.setImageResource(R.drawable.volume_on);
                        }
                    }
                });
            }

            if (mIsReelMode) {
                // A reel is the page — it plays with sound, like TikTok, and has no mute button.
                // Inline cards on shared pages keep the muted-by-default behaviour below.
                player.setVolume(1f);
                if (videoProgressBar != null) {
                    videoProgressBar.setMax(1000);
                    videoProgressBar.setProgress(0);
                    videoProgressBar.setVisibility(View.VISIBLE);
                    if (videoProgressBar instanceof SeekBar) bindVideoScrubbing((SeekBar) videoProgressBar);
                }
                if (playPauseIcon != null) playPauseIcon.setVisibility(View.GONE);
            } else {
                player.setVolume(0f);
                if (muteButton != null) muteButton.setImageResource(R.drawable.volume_off);
            }

            MediaItem mediaItem;
            String urlString = videoUri.toString();
            if (urlString.startsWith("http")) {
                mediaItem = MediaItem.fromUri(urlString);
            } else {
                mediaItem = MediaItem.fromUri(videoUri);
            }
            
            player.setMediaItem(mediaItem);
            // The reel the pager opens on loads first. The reels bound beside it, which are only
            // preloading, wait until it can play instead of sharing the connection with it on the
            // way to its first frame; the one the reader swipes to loads right away (see
            // startPlayback). Inline cards load as they bind.
            if (!mIsReelMode || GlobalVideoPlaybackManager.getInstance().requestReelLoad(
                        this, this::preparePlayer)) {
                preparePlayer();
            }

            mIsVideoInitialized = true;
        }

        /** Starts the bound player loading, once. */
        private void preparePlayer() {
            if (player == null || mPlayerPrepared) return;
            mPlayerPrepared = true;
            player.prepare();
        }

        public void onViewRecycled() {
            EmojiBurst.cancel(itemView);
            if (GlobalVideoPlaybackManager.getInstance().getCurrentlyPlayingHolder() == this) {
                GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
            }

            mVideoTickHandler.removeCallbacks(mVideoTick);
            if (mFastForward != null) mFastForward.cancel();
            releaseAudioPlayer();

            // Whether it was loading ahead or waiting its turn, the reels behind it go on.
            GlobalVideoPlaybackManager.getInstance().finishReelLoad(this);
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
            mPlayerPrepared = false;
            if (commentImage != null && context != null) {
                Glide.with(context).clear(commentImage);
            }

            mIsVideoInitialized = false;
        }

        private void handleNewToken(String newAccessToken) {
            if (activity == null) return;
            try {
                activity.setAccessToken(newAccessToken);
                JSONObject decodedAccessTokenObj = getDecodedToken(newAccessToken);
                if (decodedAccessTokenObj != null && decodedAccessTokenObj.has("username")) {
                    Log.i("TokenHandler", "Token refreshed. New username (if changed): " + decodedAccessTokenObj.getString("username"));
                }
            } catch (JSONException e) {
                Log.e("TokenHandler", "Error decoding new access token", e);
            }
        }


        public String formatNumberCompact(long number) { 
            if (number >= 1_000_000_000) { // Billions
                return String.format(Locale.getDefault(), "%.1fB", number / 1_000_000_000.0);
            } else if (number >= 1_000_000) { // Millions
                return String.format(Locale.getDefault(), "%.1fM", number / 1_000_000.0);
            } else if (number >= 10_000) { // 10K+
                 return String.format(Locale.getDefault(), "%dK", number / 1_000);
            } else if (number >= 1_000) { // 1.0K to 9.9K
                return String.format(Locale.getDefault(), "%.1fK", number / 1_000.0);
            } else {
                return String.format(Locale.getDefault(), "%d", number);
            }
        }

        /** Sends {@code event} for the signed-in user, else this install. */
        private void sendEventToPostHog(String event, String accessToken, String pInfo, JSONObject payload) {
            PostHogUtil.capture(event, payload);
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
                byte[] data = Base64.decode(base64EncodedBody, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); 
                String decodedString = new String(data, "UTF-8");
                return new JSONObject(decodedString);
            }catch(JSONException e){
                Log.e("TokenDecoder", "JSON parsing error in token: " + e.getMessage());
                return null;
            }catch(UnsupportedEncodingException e){
                Log.e("TokenDecoder", "UTF-8 encoding not supported: " + e.getMessage());
                return null;
            } catch(IllegalArgumentException e) {
                Log.e("TokenDecoder", "Base64 decoding error: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Builds the ancestors array for a yt_interact call: on a replies page the comment being
     * answered followed by the target comment, otherwise just the target.
     */
    private java.util.List<Comment> buildAncestors(Comment targetComment) {
        java.util.List<Comment> ancestors = new ArrayList<>();
        if (mIsReplyAdapter && mParentComment != null) {
            ancestors.add(mParentComment);
        }
        ancestors.add(targetComment);
        return ancestors;
    }

    /**
     * Updates in-memory Comment objects after yt_interact returns resolvedIds.
     * Maps youtubeId → DB _id for all ancestors that were registered.
     */
    private void updateResolvedIds(Map<String, String> resolvedIds) {
        if (resolvedIds == null || resolvedIds.isEmpty()) return;
        if (mParentComment != null && mParentComment.getYoutubeId() != null
                && resolvedIds.containsKey(mParentComment.getYoutubeId())) {
            mParentComment.setId(resolvedIds.get(mParentComment.getYoutubeId()));
        }
        for (Comment c : mCommentList) {
            String ytId = c.getYoutubeId();
            if (ytId != null && resolvedIds.containsKey(ytId)) {
                c.setId(resolvedIds.get(ytId));
            }
        }
    }

    /**
     * A comment author's avatar: their own picture, else their default face (see
     * {@link ProfileAvatar#resolve}), the same on every comment of theirs. Null when there is
     * nothing to pick a face with.
     */
    static @Nullable String avatarUrlFor(Comment comment) {
        if (comment.getUser() != null) {
            return ProfileAvatar.resolve(comment.getUser().getAvatar(), comment.getUser().getId());
        }
        return ProfileAvatar.resolve(comment.getYoutubeAvatarUrl(), comment.getYoutubeId());
    }

    private void openFullScreenViewer(Uri mediaUri, String mediaType) {
        if (mParentFragment != null && mParentFragment.isAdded()) {
            CommentHolder currentHolder = GlobalVideoPlaybackManager.getInstance().getCurrentlyPlayingHolder();
        
            if (currentHolder != null) {
                GlobalVideoPlaybackManager.getInstance().setHolderToResume(currentHolder);
            }

            GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();

            MediaViewerFragment viewer = MediaViewerFragment.newInstance(mediaUri, mediaType, false);
            viewer.show(mParentFragment.getChildFragmentManager(), MediaViewerFragment.class.getSimpleName());
        }
    }

    @Override
    public void onViewRecycled(@NonNull CommentHolder holder) {
        super.onViewRecycled(holder); 
        holder.onViewRecycled();      
    }
}