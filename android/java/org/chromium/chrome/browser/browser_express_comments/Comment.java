package org.chromium.chrome.browser.browser_express_comments;

public class Comment{  
    private String _id;  
    private String content;  
    private int upvoteCount;  
    private int downvoteCount;  
    private int commentCount;  
    private User user;
    private Vote didVote;

    public Comment(String _id, String content, int upvoteCount, int downvoteCount, int commentCount, User user, Vote vote) {  
        this._id = _id;  
        this.content = content;
        this.upvoteCount = upvoteCount;
        this.downvoteCount = downvoteCount;
        this.commentCount = commentCount;
        this.user = user;
        this.didVote = vote;
    }  

    public String getId() {  
        return this._id;  
    }  

    public void setId(String _id) {  
        this._id = _id;  
    }  

    public User getUser() {  
        return this.user;  
    }  

    public void setUser(User user) {  
        this.user = user;  
    }  

    public User getDidVote() {  
        return this.didVote;  
    }  

    public String getContent() {  
        return this.content;  
    }  

    public void setContent(String content) {  
        this.content = content;  
    }  

    public int getUpvoteCount() {  
        return this.upvoteCount;  
    }  

    public void setUpvoteCount(int upvoteCount) {  
        this.upvoteCount = upvoteCount;  
    } 

    public int getDownvoteCount() {  
        return this.downvoteCount;  
    }  

    public void setDownvoteCount(int downvoteCount) {  
        this.downvoteCount = downvoteCount;  
    }  

    public int getCommentCount() {  
        return this.commentCount;  
    }  

    public void setCommentCount(int commentCount) {  
        this.commentCount = commentCount;  
    }  
}  
