package org.chromium.chrome.browser.ntp;
import org.chromium.chrome.browser.browser_express_comments.Vote;
import org.chromium.chrome.browser.browser_express_comments.Comment;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.ArrayList;

public class Post{  
    private final String _id;  
    private final String content;  
    private final String type;  
    private final String title;  
    private final String imageUrl;  
    private final String url;  
    private final int upvoteCount;  
    private final int downvoteCount;  
    private final int commentCount;  
    private final String publisherName;  
    private final String publisherImageUrl;  
    private final Boolean redirect;
    private final Boolean showFull;
    private final Vote didVote;
    private final SubPost subPost;
    private List<Comment> comments;
    // Set by the parser after construction; each may be missing from older API responses.
    private long createdAtMs;
    private int reactionCount;
    private List<String> topReactions = new ArrayList<>();
    private @Nullable String hashtag;
    private @Nullable String audioUrl;
    private long audioDurationMs;
    private @Nullable NewTakes newTakes;
    /** The server's pick for the first-launch walkthrough to feature (walkthroughPostId). */
    private boolean walkthroughFeatured;

    public Post(String _id, String content, String type, String title, String imageUrl, String url, int upvoteCount, int downvoteCount, int commentCount, String publisherName, String publisherImageUrl, Boolean redirect, Boolean showFull, Vote vote, SubPost subPost) {  
        this._id = _id;  
        this.content = content;
        this.type = type;
        this.title = title;
        this.imageUrl = imageUrl;
        this.url = url;
        this.upvoteCount = upvoteCount;
        this.downvoteCount = downvoteCount;
        this.commentCount = commentCount;
        this.publisherName = publisherName;
        this.didVote = vote;
        this.redirect = redirect;
        this.showFull = showFull;
        this.publisherImageUrl = publisherImageUrl;
        this.subPost = subPost;
    }  

    public List<Comment> getComments() {  
        return this.comments;  
    }  

    public void setComments(List<Comment> comments) {  
        this.comments = comments;  
    }  

    public String getId() {  
        return this._id;  
    }  

    public String getType() {  
        return this.type;  
    }  

    public String getImageUrl() {  
        return this.imageUrl;  
    }  

    public String getUrl() {  
        return this.url;  
    }  

    public Boolean getShowFull() {  
        return this.showFull;  
    }

    public String getTitle() {  
        return this.title;  
    }

    public Boolean getRedirect() {  
        return this.redirect;  
    }

    public String getPublisherImageUrl() {  
        return this.publisherImageUrl;  
    }  

    public String getPublisherName() {  
        return this.publisherName;  
    }  

    public Vote getDidVote() {  
        return this.didVote;  
    }  

    public String getContent() {  
        return this.content;  
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

    public SubPost getSubPost() {
        return this.subPost;
    }

    /** When the post (or the tweet/reel it mirrors) was published, or 0 if unknown. */
    public long getCreatedAtMs() {
        return this.createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    /** Reactions summed over the post's top-level comments. */
    public int getReactionCount() {
        return this.reactionCount;
    }

    /** The most used of those reactions' emoji, most used first. */
    public List<String> getTopReactions() {
        return this.topReactions;
    }

    public void setReactions(int reactionCount, List<String> topReactions) {
        this.reactionCount = reactionCount;
        this.topReactions = topReactions;
    }

    /** The post's hashtag (KohliKaKamaal), without the "#"; null when it has none. */
    public @Nullable String getHashtag() {
        return this.hashtag;
    }

    public void setHashtag(@Nullable String hashtag) {
        this.hashtag = hashtag;
    }

    /** A voice post's audio, or null. */
    public @Nullable String getAudioUrl() {
        return this.audioUrl;
    }

    /** The audio's length if the server knows it, else 0. */
    public long getAudioDurationMs() {
        return this.audioDurationMs;
    }

    public void setAudio(@Nullable String audioUrl, long audioDurationMs) {
        this.audioUrl = audioUrl;
        this.audioDurationMs = audioDurationMs;
    }

    /**
     * The takes posted since the reader last opened this post's stream, while the card shows
     * them; null otherwise, and once the reader opens the stream from the card.
     */
    public @Nullable NewTakes getNewTakes() {
        return this.newTakes;
    }

    public void setNewTakes(@Nullable NewTakes newTakes) {
        this.newTakes = newTakes;
    }

    /** Whether the server picked this post for the first-launch walkthrough to feature. */
    public boolean isWalkthroughFeatured() {
        return this.walkthroughFeatured;
    }

    public void setWalkthroughFeatured(boolean walkthroughFeatured) {
        this.walkthroughFeatured = walkthroughFeatured;
    }
}  
