package org.chromium.chrome.browser.ntp;

public class TweetSubPost{  
    private String tweetId;  
    private String linkToTweet;  
    private String content;  
    private String authorName;  
    private String authorUsername;  
    private Boolean authorVerified;  
    private String authorProfilePicture;  
    private String mediaType;  
    private String mediaImageUrl;  
    private String mediaVideoUrl;  
    private int mediaHeight;  
    private int mediaWidth;  
    
    public TweetSubPost(
        String tweetId,
        String linkToTweet,
        String content,
        String authorName,
        String authorUsername,
        Boolean authorVerified,
        String authorProfilePicture,
        String mediaType,
        String mediaImageUrl,
        String mediaVideoUrl,
        int mediaHeight,
        int mediaWidth
    ) {  
        this.tweetId = tweetId;
        this.linkToTweet = linkToTweet;
        this.content = content;
        this.authorName = authorName;
        this.authorUsername = authorUsername;
        this.authorVerified = authorVerified;
        this.authorProfilePicture = authorProfilePicture;
        this.mediaType = mediaType;
        this.mediaImageUrl = mediaImageUrl;
        this.mediaVideoUrl = mediaVideoUrl;
        this.mediaHeight = mediaHeight;
        this.mediaWidth = mediaWidth;
    }  

    public String getTweetId() {  
        return this.tweetId;  
    }  

    public String getLinkToTweet() {  
        return this.linkToTweet;  
    }  

    public String getContent() {  
        return this.content;  
    }  

    public String getAuthorName() {  
        return this.authorName;  
    }  

    public String getAuthorUsername() {  
        return this.authorUsername;  
    }  

    public Boolean getAuthorVerified() {  // Changed return type to Boolean
        return this.authorVerified;  
    }  

    public String getAuthorProfilePicture() {  
        return this.authorProfilePicture;  
    }  

    public String getMediaType() {  
        return this.mediaType;  
    }  

    public String getMediaImageUrl() {  
        return this.mediaImageUrl;  
    }  

    public String getMediaVideoUrl() {  
        return this.mediaVideoUrl;  
    }  

    public int getMediaHeight() {  
        return this.mediaHeight;  
    }  

    public int getMediaWidth() {  
        return this.mediaWidth;  
    }  
}  
