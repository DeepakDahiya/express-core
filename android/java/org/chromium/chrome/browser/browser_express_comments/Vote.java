package org.chromium.chrome.browser.browser_express_comments;

public class Vote{  
    private String _id;  
    private String type;  

    public Vote(String _id, String type) {  
        this._id = _id;  
        this.type = type;
    }  

    public String getId() {  
        return this._id;  
    }  

    public String getType() {  
        return this.type;  
    }  
}  
