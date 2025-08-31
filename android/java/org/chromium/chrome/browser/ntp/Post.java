package org.chromium.chrome.browser.ntp;
import org.chromium.chrome.browser.browser_express_comments.Vote;
import org.chromium.chrome.browser.browser_express_comments.Comment;
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
}  
