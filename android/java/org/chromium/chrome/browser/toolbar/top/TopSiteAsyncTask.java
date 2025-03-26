/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.toolbar.top;

import static org.chromium.ui.base.ViewUtils.dpToPx;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.bumptech.glide.Glide;
import org.chromium.chrome.browser.app.helpers.ImageLoader;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Base64;
import java.io.UnsupportedEncodingException;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.FragmentActivity;

import com.brave.playlist.enums.PlaylistOptions;
import com.brave.playlist.listener.PlaylistOnboardingActionClickListener;
import com.brave.playlist.listener.PlaylistOptionsListener;
import com.brave.playlist.model.PlaylistOptionsModel;
import com.brave.playlist.model.SnackBarActionModel;
import com.brave.playlist.util.ConnectionUtils;
import com.brave.playlist.util.ConstantUtils;
import com.brave.playlist.util.PlaylistPreferenceUtils;
import com.brave.playlist.util.PlaylistViewUtils;
import com.brave.playlist.view.PlaylistOnboardingPanel;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;

import org.json.JSONArray;

import org.chromium.base.ApiCompatibilityUtils;
import org.chromium.base.BraveFeatureList;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.BraveReflectionUtil;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.MathUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveRelaunchUtils;
import org.chromium.chrome.browser.BraveRewardsHelper;
import org.chromium.chrome.browser.BraveRewardsNativeWorker;
import org.chromium.chrome.browser.BraveRewardsObserver;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_stats.BraveStatsUtil;
import org.chromium.chrome.browser.crypto_wallet.controller.DAppsWalletController;
import org.chromium.chrome.browser.custom_layout.popup_window_tooltip.PopupWindowTooltip;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.customtabs.features.toolbar.CustomTabToolbar;
import org.chromium.chrome.browser.dialogs.BraveAdsSignupDialog;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.local_database.BraveStatsTable;
import org.chromium.chrome.browser.local_database.DatabaseHelper;
import org.chromium.chrome.browser.local_database.SavedBandwidthTable;
import org.chromium.chrome.browser.notifications.BraveNotificationWarningDialog;
import org.chromium.chrome.browser.notifications.BravePermissionUtils;
import org.chromium.chrome.browser.notifications.RewardsYouAreNotEarningDialog;
import org.chromium.chrome.browser.omnibox.LocationBarCoordinator;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.onboarding.SearchActivity;
import org.chromium.chrome.browser.onboarding.v2.HighlightItem;
import org.chromium.chrome.browser.onboarding.v2.HighlightView;
import org.chromium.chrome.browser.playlist.PlaylistServiceFactoryAndroid;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl;
import org.chromium.chrome.browser.playlist.PlaylistServiceObserverImpl.PlaylistServiceObserverImplDelegate;
import org.chromium.chrome.browser.playlist.PlaylistWarningDialogFragment.PlaylistWarningDialogListener;
import org.chromium.chrome.browser.playlist.settings.BravePlaylistPreferences;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettingsObserver;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.rewards.BraveRewardsPanel;
import org.chromium.chrome.browser.rewards.onboarding.RewardsOnboarding;
import org.chromium.chrome.browser.settings.AppearancePreferences;
import org.chromium.chrome.browser.shields.BraveShieldsHandler;
import org.chromium.chrome.browser.shields.BraveShieldsMenuObserver;
import org.chromium.chrome.browser.shields.BraveShieldsUtils;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabImpl;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.chrome.browser.theme.ThemeUtils;
import org.chromium.chrome.browser.toolbar.HomeButton;
import org.chromium.chrome.browser.toolbar.ToolbarDataProvider;
import org.chromium.chrome.browser.toolbar.ToolbarTabController;
import org.chromium.chrome.browser.toolbar.bottom.BottomToolbarVariationManager;
import org.chromium.chrome.browser.toolbar.menu_button.BraveMenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.menu_button.MenuButtonCoordinator;
import org.chromium.chrome.browser.toolbar.top.NavigationPopup.HistoryDelegate;
import org.chromium.chrome.browser.toolbar.top.ToolbarTablet.OfflineDownloader;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.chrome.browser.util.ConfigurationUtils;
import org.chromium.chrome.browser.util.PackageUtils;
import org.chromium.chrome.browser.widget.quickactionsearchandbookmark.promo.SearchWidgetPromoPanel;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.playlist.mojom.PlaylistEvent;
import org.chromium.playlist.mojom.PlaylistItem;
import org.chromium.playlist.mojom.PlaylistService;
import org.chromium.ui.UiUtils;
import org.chromium.ui.base.DeviceFormFactor;
import org.chromium.ui.base.ViewUtils;
import org.chromium.ui.interpolators.Interpolators;
import org.chromium.ui.util.ColorUtils;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;
import org.chromium.url.mojom.Url;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.chromium.chrome.browser.toolbar.bottom.BrowserExpressGetFirstCommentsUtil;
import java.net.MalformedURLException;
import org.chromium.chrome.browser.ntp_background_images.model.TopSite;
import java.io.File;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TopSiteAsyncTask extends AsyncTask<String, Void, TopSite> {
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
    protected TopSite doInBackground(String... params) {
        if (params.length == 0) return null;

        try {
            String mUrl = params[0];
            URL tempUrl = new URL(mUrl);
            String protocol = tempUrl.getProtocol();
            String host = tempUrl.getHost();

            // Download favicon in background
            String faviconPath = saveFavicon(context, mUrl);

            // Create TopSite object
            Log.d(TAG, "Creating TopSite for URL: " + mUrl);
            return new TopSite(
                getWebsiteName(mUrl), 
                protocol + "://" + host, 
                "#FFFFFF", 
                faviconPath
            );
        } catch (Exception e) {
            Log.e(TAG, "Error processing top site", e);
            return null;
        }
    }

    @Override
    protected void onPostExecute(TopSite topSite) {
        Log.e(TAG, "TopSiteAsyncTask onPostExecute");
        if (topSite != null) {
            try {
                Log.e(TAG, "Inserting TopSite: " + topSite.getName());
                databaseHelper.insertTopSite(topSite);
            } catch (Exception e) {
                Log.e(TAG, "Error inserting top site", e);
            }
        }
    }

    public String saveFavicon(Context context, String urlString) {
        try {
            URL fullUrl = new URL(urlString);
            String host = fullUrl.getHost();
            
            // List of potential favicon retrieval URLs
            List<String> faviconCandidates = generateFaviconCandidateUrls(fullUrl);
            
            for (String faviconUrlStr : faviconCandidates) {
                try {
                    URL faviconUrl = new URL(faviconUrlStr);
                    Bitmap favicon = downloadFavicon(faviconUrl);
                    
                    if (favicon != null) {
                        return saveFaviconBitmap(context, favicon, host);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Failed to download favicon from " + faviconUrlStr, e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Favicon download failed", e);
        }
        
        return null;
    }

    private List<String> generateFaviconCandidateUrls(URL fullUrl) {
        String baseUrl = fullUrl.getProtocol() + "://" + fullUrl.getHost();
        List<String> candidates = new ArrayList<>();
        
        // Standard favicon locations
        candidates.add(baseUrl + "/favicon.ico");
        candidates.add(baseUrl + "/apple-touch-icon.png");
        candidates.add(baseUrl + "/android-chrome-192x192.png");
        
        // More complex favicon URL
        String cleanHost = fullUrl.getHost().replaceAll("^www\\.", "");
        candidates.add(baseUrl + "/" + cleanHost + "-favicon.ico");
        
        return candidates;
    }

    private Bitmap downloadFavicon(URL faviconUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) faviconUrl.openConnection();
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestMethod("GET");
        
        // Check for successful response
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            return null;
        }
        
        // Check content type
        String contentType = connection.getContentType();
        if (contentType == null || 
            (!contentType.startsWith("image/") && 
             !contentType.contains("icon"))) {
            return null;
        }
        
        try (InputStream inputStream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(inputStream);
        }
    }

    private String saveFaviconBitmap(Context context, Bitmap favicon, String host) {
        try {
            // Generate unique filename using MD5 hash
            String fileName = generateUniqueFileName(host);
            
            // Get app-specific external files directory
            File faviconDir = new File(context.getExternalFilesDir(null), "favicons");
            if (!faviconDir.exists()) {
                faviconDir.mkdirs();
            }
            
            File faviconFile = new File(faviconDir, fileName);
            
            try (FileOutputStream out = new FileOutputStream(faviconFile)) {
                // Compress to PNG to ensure compatibility
                favicon.compress(Bitmap.CompressFormat.PNG, 100, out);
                return faviconFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving favicon", e);
            return null;
        }
    }

    private String generateUniqueFileName(String host) {
        try {
            // Use MD5 hash to create a unique, deterministic filename
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(host.getBytes());
            
            // Convert to hex
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString() + ".png";
        } catch (NoSuchAlgorithmException e) {
            // Fallback to timestamp-based filename
            return host.replaceAll("[^a-zA-Z0-9]", "_") + 
                   System.currentTimeMillis() + ".png";
        }
    }

    private String getWebsiteName(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return "Unknown Website"; // Handle empty input.
        }

        try {
            URL url = new URL(urlString);
            String host = url.getHost();

            // Handle IP addresses.
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return host;
            }

            // Remove "www." prefix (if present).
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // Split the host by dots.
            String[] parts = host.split("\\.");

            // Return the second-to-last part as the website name.
            if (parts.length >= 2) {
                return parts[parts.length - 2];
            } else {
                return host; // Return the entire host if it's simple.
            }

        } catch (MalformedURLException e) {
            // Default Fallback
            String cleanUrl = urlString;
            if (cleanUrl.startsWith("https://")) {
                cleanUrl = cleanUrl.substring(8);
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl = cleanUrl.substring(7);
            }

            if (cleanUrl.startsWith("www.")) {
                cleanUrl = cleanUrl.substring(4);
            }

            try{
                URL url = new URL("https://" + cleanUrl);
                return url.getHost();
            } catch (MalformedURLException e2){
                return cleanUrl;
            }

        }
    }
}
