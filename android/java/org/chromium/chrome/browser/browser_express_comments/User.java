package org.chromium.chrome.browser.browser_express_comments;

public class User{  
    private final String _id;  
    private final String username;  
    private final String avatar;

    public User(String _id, String username, String avatar) {  
        this._id = _id;  
        this.username = username;
        this.avatar = avatar;
    }  

    public String getId() {  
        return this._id;  
    }  

    public String getUsername() {  
        return this.username;  
    }  

    public String getAvatar() {  
        return this.avatar;
    }
}  
