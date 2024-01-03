package org.chromium.chrome.browser.browser_express_comments;

public class User{  
    private String _id;  
    private String username;  

    public User(String _id, String username) {  
        this._id = _id;  
        this.username = username;
    }  

    public String getUsername() {  
        return this.username;  
    }  

    public void setUsername(String username) {  
        this.username = username;  
    }  
}  
