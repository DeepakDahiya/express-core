package org.chromium.chrome.browser.browser_express_comments;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.chrome.browser.app.helpers.ImageLoader;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class Comment{
    private String _id;
    private final String content;
    private final int upvoteCount;
    private final int downvoteCount;
    private int trendingScore;
    private final int commentCount;
    private final String commentParent;
    private final String pageParent;
    private final String postParent;
    private final String postContent;
    private final String postUsername;
    private final String postAvatarUrl;
    private final String mediaImageUrl;
    private String mediaGifUrl;
    private final String mediaVideoUrl;
    private String mediaAudioUrl;
    private int mediaDurationMs;
    private final User user;
    private final Vote didVote;

    private String youtubeId;
    private String youtubeAuthorName;
    private String youtubeAvatarUrl;

    private int mediaWidth;
    private int mediaHeight;

    /** A document attachment; see {@link #hasDocumentMedia()}. */
    private String mediaDocumentUrl;
    private String mediaDocumentName;
    private String mediaDocumentMime;
    private long mediaDocumentSize;

    /**
     * Every file on the comment, in the author's order. Only comments posted with several files
     * (or by the attachment flow) have these; the single-media fields above still describe the
     * first one, which is all older comments have.
     */
    private List<CommentAttachment> attachments;

    /** ISO-8601 creation time as the API sends it; null for comments it did not date. */
    private String createdAt;

    /**
     * Reaction totals, most used first, as the API sends them. Null when the source never
     * reported reactions (YouTube's own comments, older cached payloads) — {@link #getReactions()}
     * then falls back to the legacy vote counts, which are 👍/👎 reactions under another name.
     */
    private List<ReactionCount> reactions;
    private int reactionCount = -1;
    /** The reader's own reaction; "" (not null) once the API said they have none. */
    private String myReaction;

    /** One emoji's total on a comment. */
    public static class ReactionCount {
        private final String emoji;
        private final int count;

        public ReactionCount(String emoji, int count) {
            this.emoji = emoji;
            this.count = count;
        }

        public String getEmoji() {
            return emoji;
        }

        public int getCount() {
            return count;
        }
    }

    public enum UploadStatus { PENDING, POSTING, SUCCEEDED, FAILED }

    private UploadStatus uploadStatus = UploadStatus.SUCCEEDED;

    public Comment(String _id, String content, int upvoteCount, int downvoteCount, int commentCount, String pageParent, String postParent, String commentParent, User user, Vote vote, String mediaImageUrl, String mediaVideoUrl, String postContent, String postUsername, String postAvatarUrl) {  
        this._id = _id;  
        this.content = content;
        this.upvoteCount = upvoteCount;
        this.downvoteCount = downvoteCount;
        this.commentCount = commentCount;
        this.user = user;
        this.didVote = vote;
        this.commentParent = commentParent;
        this.pageParent = pageParent;
        this.postParent = postParent;
        this.mediaImageUrl = mediaImageUrl;
        this.mediaVideoUrl = mediaVideoUrl;
        this.postContent = postContent;
        this.postUsername = postUsername;
        this.postAvatarUrl = postAvatarUrl;
    }  

    // ---------------------------------------------------------------------------------------
    // Reactions
    // ---------------------------------------------------------------------------------------

    /** The 👍 reaction; stored server side as the upvote count. */
    public static final String LIKE_EMOJI = "\uD83D\uDC4D";
    /**
     * The classic reactions. Any single emoji is a reaction; these are the picker's quick picks
     * until the reader has recent ones, and the server breaks count ties in this order.
     */
    public static final String[] REACTION_EMOJIS = {
        LIKE_EMOJI, // 👍
        "\u2764\uFE0F", // ❤️
        "\uD83D\uDE02", // 😂
        "\uD83D\uDE2E", // 😮
        "\uD83D\uDE22", // 😢
        "\uD83D\uDE21", // 😡
    };

    /** Same emoji whether or not a keyboard added the U+FE0F variation selector. */
    public static boolean sameEmoji(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return false;
        return a.replace("\uFE0F", "").equals(b.replace("\uFE0F", ""));
    }

    /** Reaction totals, most used first. Never null. */
    public List<ReactionCount> getReactions() {
        if (reactions != null) return reactions;
        // Sources that predate reactions: votes are the 👍/👎 reactions.
        List<ReactionCount> legacy = new ArrayList<>();
        if (upvoteCount > 0) legacy.add(new ReactionCount(LIKE_EMOJI, upvoteCount));
        if (downvoteCount > 0) legacy.add(new ReactionCount("\uD83D\uDC4E", downvoteCount));
        if (legacy.size() == 2 && downvoteCount > upvoteCount) Collections.reverse(legacy);
        return legacy;
    }

    /** Every reaction on the comment, including the reader's. */
    public int getReactionCount() {
        if (reactionCount >= 0) return reactionCount;
        int total = 0;
        for (ReactionCount reaction : getReactions()) total += reaction.getCount();
        return total;
    }

    /** The reader's reaction emoji, or null when they have not reacted. */
    @Nullable
    public String getMyReaction() {
        if (myReaction != null) return myReaction.isEmpty() ? null : myReaction;
        if (didVote == null || didVote.getType() == null) return null;
        if ("up".equals(didVote.getType())) return LIKE_EMOJI;
        if ("down".equals(didVote.getType())) return "\uD83D\uDC4E";
        return null;
    }

    /** Replaces the reaction state, e.g. after the reader reacted or the server answered. */
    public void setReactionState(
            List<ReactionCount> reactions, int reactionCount, @Nullable String myReaction) {
        this.reactions = reactions != null ? reactions : new ArrayList<>();
        this.reactionCount = Math.max(0, reactionCount);
        this.myReaction = myReaction != null ? myReaction : "";
    }

    /**
     * What the counts become once the reader's reaction changes to {@code emoji} (null removes
     * it) — the optimistic update shown before the server answers.
     */
    public void applyLocalReaction(@Nullable String emoji) {
        String previous = getMyReaction();
        List<ReactionCount> next = new ArrayList<>();
        boolean added = false;
        for (ReactionCount reaction : getReactions()) {
            int count = reaction.getCount();
            if (sameEmoji(reaction.getEmoji(), previous)) count--;
            if (sameEmoji(reaction.getEmoji(), emoji)) {
                count++;
                added = true;
            }
            if (count > 0) next.add(new ReactionCount(reaction.getEmoji(), count));
        }
        if (emoji != null && !added) next.add(new ReactionCount(emoji, 1));
        Collections.sort(next, (a, b) -> b.getCount() - a.getCount());
        int total = getReactionCount() - (previous != null ? 1 : 0) + (emoji != null ? 1 : 0);
        setReactionState(next, total, emoji);
    }

    /**
     * Reads the reaction fields of a comment (or of a reaction endpoint's answer). Absent fields
     * leave the current state alone, so a source that does not report reactions keeps the
     * legacy-vote fallback.
     */
    public void readReactions(JSONObject obj) {
        JSONArray array = obj.optJSONArray("reactions");
        if (array == null) return;
        List<ReactionCount> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String emoji = item.optString("emoji", "");
            int count = item.optInt("count", 0);
            if (!emoji.isEmpty() && count > 0) list.add(new ReactionCount(emoji, count));
        }
        int total = obj.optInt("reactionCount", -1);
        if (total < 0) {
            total = 0;
            for (ReactionCount reaction : list) total += reaction.getCount();
        }
        String mine = obj.isNull("myReaction") ? null : obj.optString("myReaction", null);
        setReactionState(list, total, mine);
    }

    /**
     * Reads the fields every comments endpoint can carry beyond the constructor's: reactions,
     * the creation time and a document attachment.
     */
    public void readExtras(JSONObject obj) {
        if (obj == null) return;
        readReactions(obj);
        if (!obj.isNull("createdAt")) createdAt = obj.optString("createdAt", null);
        // Posted since the reader last opened this post's stream (signed in, post streams only).
        isNew = obj.optBoolean("isNew", false);
        if (!obj.isNull("mediaDocumentUrl")) {
            mediaDocumentUrl = obj.optString("mediaDocumentUrl", null);
            mediaDocumentName = obj.isNull("mediaDocumentName")
                    ? null
                    : obj.optString("mediaDocumentName", null);
            mediaDocumentMime = obj.isNull("mediaDocumentMime")
                    ? null
                    : obj.optString("mediaDocumentMime", null);
            mediaDocumentSize = obj.optLong("mediaDocumentSize", 0);
        }
        JSONArray attachmentList = obj.optJSONArray("attachments");
        if (attachmentList != null) attachments = CommentAttachment.readList(attachmentList);
    }

    // ---------------------------------------------------------------------------------------
    // Attachments
    // ---------------------------------------------------------------------------------------

    /** The comment's files; empty when it has none or predates the attachment list. */
    public List<CommentAttachment> getAttachments() {
        return attachments != null ? attachments : Collections.emptyList();
    }

    public void setAttachments(@Nullable List<CommentAttachment> attachments) {
        this.attachments = attachments;
    }

    /** Several files, shown as a row of tiles rather than in the single-media slots. */
    public boolean hasAttachmentGallery() {
        return getAttachments().size() > 1;
    }

    // ---------------------------------------------------------------------------------------
    // Outgoing: a comment this reader just sent from the sheet (CommentUploadManager). It keeps
    // the design's "outgoing" card — disabled while it uploads, highlighted once posted — for as
    // long as this list shows it. UI state only, so none of it is serialized.
    // ---------------------------------------------------------------------------------------

    private transient boolean outgoing;
    /** 0 to 1 while files upload; negative while the share is unknown (just started, text). */
    private transient float uploadProgress = -1f;
    /** Posted moments ago: drawn with the accent outline until the highlight fades. */
    private transient boolean justPosted;
    /** Why the upload failed, for the card; null otherwise. */
    private transient String uploadError;

    public boolean isOutgoing() {
        return outgoing;
    }

    public void setOutgoing(boolean outgoing) {
        this.outgoing = outgoing;
    }

    public float getUploadProgress() {
        return uploadProgress;
    }

    public void setUploadProgress(float progress) {
        this.uploadProgress = progress;
    }

    public boolean isJustPosted() {
        return justPosted;
    }

    public void setJustPosted(boolean justPosted) {
        this.justPosted = justPosted;
    }

    @Nullable
    public String getUploadError() {
        return uploadError;
    }

    public void setUploadError(@Nullable String error) {
        this.uploadError = error;
    }

    // ---------------------------------------------------------------------------------------
    // Creation time
    // ---------------------------------------------------------------------------------------

    public void setCreatedAt(@Nullable String isoTimestamp) {
        this.createdAt = isoTimestamp;
    }

    /** Creation time in epoch millis, or 0 when unknown. */
    public long getCreatedAtMs() {
        if (createdAt == null || createdAt.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                java.util.Date date = format.parse(createdAt);
                if (date != null) return date.getTime();
            } catch (ParseException e) {
                // Try the next pattern.
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------
    // Document attachment
    // ---------------------------------------------------------------------------------------

    public boolean hasDocumentMedia() {
        return isMediaUrlPresent(mediaDocumentUrl);
    }

    public String getMediaDocumentUrl() {
        return mediaDocumentUrl;
    }

    public String getMediaDocumentName() {
        return mediaDocumentName;
    }

    public String getMediaDocumentMime() {
        return mediaDocumentMime;
    }

    public long getMediaDocumentSize() {
        return mediaDocumentSize;
    }

    public void setDocument(String url, String name, String mime, long sizeBytes) {
        this.mediaDocumentUrl = url;
        this.mediaDocumentName = name;
        this.mediaDocumentMime = mime;
        this.mediaDocumentSize = sizeBytes;
    }

    public UploadStatus getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(UploadStatus status) { this.uploadStatus = status; }

    public int getMediaWidth() { return mediaWidth; }
    public void setMediaWidth(int width) { this.mediaWidth = width; }
    public int getMediaHeight() { return mediaHeight; }
    public void setMediaHeight(int height) { this.mediaHeight = height; }
    public boolean hasCachedDimensions() { return mediaWidth > 0 && mediaHeight > 0; }

    public int getTrendingScore() { return trendingScore; }
    public void setTrendingScore(int score) { this.trendingScore = score; }

    public String getId() {
        return this._id;
    }

    public void setId(String id) {
        this._id = id;
    }

    /**
     * Returns true when this is a YouTube comment that has not yet been registered in our DB.
     * A comment is YouTube-only when both its _id and youtubeId are the same YouTube comment ID.
     */
    public boolean isYouTubeOnly() {
        return youtubeId != null && youtubeId.equals(_id);
    }

    public String getYoutubeId() { return youtubeId; }
    public void setYoutubeId(String id) { this.youtubeId = id; }

    public String getYoutubeAuthorName() { return youtubeAuthorName; }
    public void setYoutubeAuthorName(String name) { this.youtubeAuthorName = name; }

    public String getYoutubeAvatarUrl() { return youtubeAvatarUrl; }
    public void setYoutubeAvatarUrl(String url) { this.youtubeAvatarUrl = url; }

    public String getMediaImageUrl() {  
        return this.mediaImageUrl;  
    }

    public String getMediaGifUrl() {
        return this.mediaGifUrl;
    }

    public void setMediaGifUrl(String url) {
        this.mediaGifUrl = url;
    }

    public String getMediaVideoUrl() {
        return this.mediaVideoUrl;
    }

    public String getMediaAudioUrl() {
        return this.mediaAudioUrl;
    }

    public void setMediaAudioUrl(String url) {
        this.mediaAudioUrl = url;
    }

    /** Length of an audio comment in milliseconds; 0 when the server has not reported one. */
    public int getMediaDurationMs() {
        return this.mediaDurationMs;
    }

    public void setMediaDurationMs(int durationMs) {
        this.mediaDurationMs = durationMs;
    }

    /** The comments API sends the literal string "null" for absent media, so guard for it too. */
    private static boolean isMediaUrlPresent(String url) {
        return url != null && !url.isEmpty() && !url.equals("null");
    }

    public boolean hasVideoMedia() {
        return isMediaUrlPresent(this.mediaVideoUrl);
    }

    /**
     * True for a still image and for an animated GIF alike — a GIF is an image as far as layout
     * is concerned, and the server sends its URL in both fields so older builds keep rendering
     * it. Ask {@link #hasGifMedia()} to decide whether it has to animate.
     */
    public boolean hasImageMedia() {
        return isMediaUrlPresent(this.mediaImageUrl) || isMediaUrlPresent(this.mediaGifUrl);
    }

    /**
     * An animated GIF. The dedicated field is what the API sends, but a GIF also reaches us
     * inside mediaImageUrl — from an import, or from an app build older than the field — so the
     * URL itself is the fallback answer.
     */
    public boolean hasGifMedia() {
        return isMediaUrlPresent(this.mediaGifUrl)
                || (isMediaUrlPresent(this.mediaImageUrl) && ImageLoader.isGif(this.mediaImageUrl));
    }

    /**
     * The URL a still-or-animated media card draws. Both fields carry a GIF; the dedicated one
     * wins so a comment is still rendered when only it came back.
     */
    public String getDisplayImageUrl() {
        return isMediaUrlPresent(this.mediaGifUrl) ? this.mediaGifUrl : this.mediaImageUrl;
    }

    public boolean hasAudioMedia() {
        return isMediaUrlPresent(this.mediaAudioUrl);
    }

    /**
     * Media drawn in the comment body: an image or a video. An audio comment renders as an
     * ordinary fixed-height row instead of a media card.
     */
    public boolean hasVisualMedia() {
        return hasVideoMedia() || hasImageMedia();
    }

    /**
     * A vertical video — the only kind of comment the reel pager gives a whole page to, rendered
     * TikTok-style. Everything else (images, landscape video, video whose dimensions are not yet
     * known) packs inline with the text comments as a 16:9 media card.
     *
     * <p>Dimensions come from the API when it sends them, otherwise from
     * {@link CommentVideoDimensions}; until either has run this returns false, and the comment is
     * re-packed onto its own page once the dimensions arrive.
     *
     * <p>A comment with several files never gets a reel page (its video is one tile of many), and
     * neither does one this reader is sending: it stays a card in the thread, showing its upload,
     * until the list next loads.
     */
    public boolean isPortraitVideo() {
        if (outgoing || hasAttachmentGallery()) return false;
        return hasVideoMedia() && hasCachedDimensions() && mediaHeight > mediaWidth;
    }

    /**
     * Shown as the outgoing card or a tile row instead of the single-media slots (voice-note row,
     * media card, document row).
     */
    public boolean usesAttachmentRows() {
        return outgoing || hasAttachmentGallery();
    }

    /**
     * The media preset an inline card draws this comment's photo or landscape video at (Figma
     * "Express/Media": 16:9, 4:3 or 1:1), as {width, height}: the one nearest the media's own
     * shape, or 16:9 while that is unknown. {@link CommentRowMeasurer} and the card binder both
     * ask here, so a row is measured at the shape it is drawn at.
     */
    public int[] getInlineMediaRatio() {
        if (!hasCachedDimensions()) return new int[] {16, 9};
        float ratio = mediaWidth / (float) mediaHeight;
        if (ratio >= 1.55f) return new int[] {16, 9};
        if (ratio >= 1.15f) return new int[] {4, 3};
        return new int[] {1, 1};
    }

    public boolean hasMedia() {
        return hasVisualMedia() || hasAudioMedia() || hasDocumentMedia();
    }

    public String getCommentParent() {  
        return this.commentParent;  
    }

    public String getPageParent() {  
        return this.pageParent;  
    }

    public String getPostParent() {  
        return this.postParent;  
    }

    public String getPostContent() {  
        return this.postContent;  
    }

    public String getPostUsername() {  
        return this.postUsername;  
    }

    public String getPostAvatarUrl() {  
        return this.postAvatarUrl;  
    }

    public User getUser() {  
        return this.user;  
    }  

    public Vote getDidVote() {  
        return this.didVote;  
    }  

    public String getContent() {
        return this.content;
    }

    /**
     * How much of a comment body a list row shows before "read more". The row measurer and the
     * row binder both go through {@link #getDisplayContent()}, so a page is measured with exactly
     * the text it will draw.
     */
    public static final int DISPLAY_CONTENT_LIMIT = 150;

    /**
     * Word budget for a body on a reel-pager row. Derived from the flat list's
     * {@link #DISPLAY_CONTENT_LIMIT} of 150 characters — roughly 25 words at typical English
     * word length — reduced by 30%. Both {@link CommentRowMeasurer} and the pager bind path go
     * through {@link #getPagerDisplayContent()}, so pages are measured with exactly the text
     * they draw.
     */
    public static final int PAGER_CONTENT_WORD_LIMIT = 18;

    /**
     * UI state, not data: the reader tapped "see more" on a pager row, so the full body shows —
     * and is measured — from now on. Transient so the optimistic-upload JSON round trip does not
     * carry it.
     */
    private transient boolean pagerExpanded;

    /**
     * A take posted since the reader last opened this post's stream, so it wears a NEW tag
     * (design_handoff_new_takes §4). The server decides; see browser-express-node
     * docs/new-takes.md.
     */
    private boolean isNew;

    /** UI state: the reader has scrolled past this new take, so its tag is gone. */
    private transient boolean newSeen;

    public boolean isNew() {
        return isNew;
    }

    /** Whether the NEW tag shows: a new take the reader hasn't scrolled past yet. */
    public boolean showsNewTag() {
        return isNew && !newSeen;
    }

    public void markNewSeen() {
        newSeen = true;
    }

    public boolean isPagerExpanded() {
        return pagerExpanded;
    }

    public void setPagerExpanded(boolean expanded) {
        this.pagerExpanded = expanded;
    }

    /**
     * Body as shown on a reel-pager row: cut after {@link #PAGER_CONTENT_WORD_LIMIT} words, or
     * everything once the reader expanded the comment.
     */
    public String getPagerDisplayContent() {
        if (this.content == null) return "";
        if (this.pagerExpanded) return this.content;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < this.content.length(); i++) {
            boolean whitespace = Character.isWhitespace(this.content.charAt(i));
            if (!whitespace && !inWord) {
                words++;
                if (words > PAGER_CONTENT_WORD_LIMIT) {
                    return this.content.substring(0, i).replaceAll("\\s+$", "") + "…";
                }
            }
            inWord = !whitespace;
        }
        return this.content;
    }

    /** True when {@link #getPagerDisplayContent()} hides part of the body ("see more" shows). */
    public boolean isPagerContentClamped() {
        return this.content != null && !this.content.equals(getPagerDisplayContent());
    }

    public boolean isContentTruncated() {
        return this.content != null && this.content.length() > DISPLAY_CONTENT_LIMIT;
    }

    /** Body as first shown in a row — cut short when {@link #isContentTruncated()}. */
    public String getDisplayContent() {
        if (this.content == null) return "";
        if (!isContentTruncated()) return this.content;
        return this.content.substring(0, DISPLAY_CONTENT_LIMIT) + "...";
    }

    public int getUpvoteCount() {  
        return this.upvoteCount;  
    }  

    public int getDownvoteCount() {  
        return this.downvoteCount;  
    }  

    public int getCommentCount() {  
        return this.commentCount;  
    }  
}  
