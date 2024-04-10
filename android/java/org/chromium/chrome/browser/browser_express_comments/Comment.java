package org.chromium.chrome.browser.browser_express_comments;

public class Comment{  
    private String _id;  
    private String content;  
    private int upvoteCount;  
    private int downvoteCount;  
    private int commentCount;  
    private String commentParent;  
    private String pageParent;  
    private User user;
    private Vote didVote;

    public Comment(String _id, String content, int upvoteCount, int downvoteCount, int commentCount, String pageParent, String commentParent, User user, Vote vote) {  
        this._id = _id;  
        this.content = content;
        this.upvoteCount = upvoteCount;
        this.downvoteCount = downvoteCount;
        this.commentCount = commentCount;
        this.user = user;
        this.didVote = vote;
        this.commentParent = commentParent;
        this.pageParent = pageParent;
    }  

    public String getId() {  
        return this._id;  
    }  

    public String getCommentParent() {  
        return this.commentParent;  
    }

    public String getPageParent() {  
        return this.pageParent;  
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
