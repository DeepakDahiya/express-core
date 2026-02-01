package org.chromium.chrome.browser.browser_express_comments;

public class Comment{  
    private final String _id;  
    private final String content;  
    private final int upvoteCount;  
    private final int downvoteCount; 
    private final int trendingScore; 
    private final int commentCount;  
    private final String commentParent;  
    private final String pageParent;  
    private final String postParent;  
    private final String postContent;
    private final String postUsername;
    private final String postAvatarUrl;
    private final String mediaImageUrl;
    private final String mediaVideoUrl;
    private final User user;
    private final Vote didVote;

    private int mediaWidth;
    private int mediaHeight;

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

    public String getMediaImageUrl() {  
        return this.mediaImageUrl;  
    }

    public String getMediaVideoUrl() {  
        return this.mediaVideoUrl;  
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
