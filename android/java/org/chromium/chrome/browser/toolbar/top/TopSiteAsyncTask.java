package org.chromium.chrome.browser.toolbar.top;

import android.content.Context;
import android.os.AsyncTask;
import org.chromium.url.GURL;
import java.net.HttpURLConnection;
import java.net.URL;

public class TopSiteAsyncTask extends AsyncTask<GURL, Void, TopSite> {
    private static final String TAG = "TopSiteAsyncTask";
    private static final int CONNECTION_TIMEOUT = 15000; // 15 seconds
    private static final int READ_TIMEOUT = 15000; // 15 seconds

    private Context context;
    private DatabaseHelper databaseHelper;

    public TopSiteAsyncTask(Context context, DatabaseHelper databaseHelper) {
        this.context = context.getApplicationContext();
        this.databaseHelper = databaseHelper;
    }

    @Override
    protected TopSite doInBackground(GURL... params) {
        if (params.length == 0) return null;
        GURL url = params[0];
        
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url.getSpec()).openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return new TopSite(url.getSpec());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onPostExecute(TopSite result) {
        if (result != null) {
            databaseHelper.saveTopSite(result);
        }
    }
}
