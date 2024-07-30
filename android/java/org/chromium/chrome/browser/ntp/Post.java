package org.chromium.chrome.browser.ntp;
import org.chromium.chrome.browser.browser_express_comments.Vote;

public class Post{  
    private String _id;  
    private String content;  
    private String type;  
    private String title;  
    private String imageUrl;  
    private String url;  
    private int upvoteCount;  
    private int downvoteCount;  
    private int commentCount;  
    private String publisherName;  
    private String publisherImageUrl;  
    private Boolean redirect;
    private Boolean showFull;
    private Vote didVote;
    private TweetSubPost tweetSubPost;

    public Post(String _id, String content, String type, String title, String imageUrl, String url, int upvoteCount, int downvoteCount, int commentCount, String publisherName, String publisherImageUrl, Boolean redirect, Boolean showFull, Vote vote, TweetSubPost tweetSubPost) {  
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
        this.tweetSubPost = tweetSubPost;
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

    public TweetSubPost getTweetSubPost() {
        return this.tweetSubPost;
    }
}  
