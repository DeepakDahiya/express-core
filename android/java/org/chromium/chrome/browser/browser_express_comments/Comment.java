package org.chromium.chrome.browser.browser_express_comments;

public class Comment{  
    private String _id;  
    private String content;  
    private int upvoteCount;  
    private int downvoteCount;  
    private int commentCount;  
    private String commentParent;  
    private String pageParent;  
    private String postParent;  
    private String postContent;
    private String postUsername;
    private String postAvatarUrl;
    private String mediaImageUrl;
    private String mediaVideoUrl;
    private User user;
    private Vote didVote;

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
