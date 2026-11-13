/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.app.Activity;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.chromium.chrome.browser.omnibox.LocationBarDataProvider;
import org.chromium.chrome.browser.omnibox.status.BraveStatusMediator;
import org.chromium.chrome.browser.omnibox.status.StatusCoordinator.PageInfoAction;
import org.chromium.chrome.browser.page_info.ChromePageInfoHighlight;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.toolbar.ToolbarDataProvider;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.page_info.PageInfoController;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Puts the trust marker in the URL bar and the site trust sheet behind it.
 *
 * <p>The marker replaces the security chip's icon on web pages (http and https) only; Chromium's
 * own icons stay on everything else. Each browser window's location bar has one marker, which
 * scans while its tab loads and shows the result when the load settles.
 */
public final class TrustMarkers implements BraveStatusMediator.TrustMarkerSource {
    private static @Nullable TrustMarkers sInstance;

    /**
     * One marker per location bar. The values are weak too: a marker holds its window's context,
     * which holds the location bar, so a strong value would keep the key alive forever. While a
     * marker is on screen the status view holds it; when it isn't, it's cheap to make again.
     */
    private final WeakHashMap<LocationBarDataProvider, WeakReference<TrustMarkerDrawable>>
            mMarkers = new WeakHashMap<>();

    private TrustMarkers() {}

    /** Starts supplying markers to the URL bar. Safe to call more than once. */
    public static void install() {
        if (sInstance != null) return;
        sInstance = new TrustMarkers();
        BraveStatusMediator.setTrustMarkerSource(sInstance);
    }

    /**
     * Wraps the URL bar's page info action: a plain tap on a web page's marker opens the site
     * trust sheet; permission and store highlights, and other pages, keep Chromium's page info.
     */
    public static PageInfoAction wrapPageInfoAction(PageInfoAction pageInfoAction) {
        return (tab, highlight) -> {
            if (isPlainTap(highlight) && showSheet(tab)) return;
            pageInfoAction.show(tab, highlight);
        };
    }

    private static boolean isPlainTap(ChromePageInfoHighlight highlight) {
        return highlight.getHighlightedPermission() == PageInfoController.NO_HIGHLIGHTED_PERMISSION
                && !highlight.shouldHighlightStoreInfo();
    }

    private static boolean showSheet(Tab tab) {
        if (tab == null || !UrlUtilities.isHttpOrHttps(tab.getUrl())) return false;
        if (tab.getWindowAndroid() == null) return false;
        Activity activity = tab.getWindowAndroid().getActivity().get();
        if (!(activity instanceof FragmentActivity)) return false;
        return SiteTrustSheet.show((FragmentActivity) activity, tab);
    }

    /**
     * A page started loading in the tab a toolbar shows: its marker scans. The toolbar's data
     * provider is also its location bar's (both are the LocationBarModel).
     */
    public static void onPageLoadStarted(ToolbarDataProvider toolbarDataProvider) {
        TrustMarkerDrawable marker = existingMarker(toolbarDataProvider);
        if (marker != null) marker.startScan();
    }

    /** The load settled: the marker shows the page's status. */
    public static void onPageLoadFinished(ToolbarDataProvider toolbarDataProvider) {
        TrustMarkerDrawable marker = existingMarker(toolbarDataProvider);
        if (marker == null) return;
        LocationBarDataProvider provider = (LocationBarDataProvider) toolbarDataProvider;
        Tab tab = provider.getTab();
        if (tab != null) sync(marker, tab, provider);
    }

    private static @Nullable TrustMarkerDrawable existingMarker(
            ToolbarDataProvider toolbarDataProvider) {
        if (sInstance == null || !(toolbarDataProvider instanceof LocationBarDataProvider)) {
            return null;
        }
        return sInstance.existingMarker((LocationBarDataProvider) toolbarDataProvider);
    }

    @Override
    public @Nullable Drawable getTrustMarker(LocationBarDataProvider provider) {
        Tab tab = provider.getTab();
        if (tab == null || !UrlUtilities.isHttpOrHttps(provider.getCurrentGurl())) return null;
        TrustMarkerDrawable marker = existingMarker(provider);
        if (marker == null) {
            marker = new TrustMarkerDrawable(tab.getContext());
            mMarkers.put(provider, new WeakReference<>(marker));
        }
        sync(marker, tab, provider);
        return marker;
    }

    private @Nullable TrustMarkerDrawable existingMarker(LocationBarDataProvider provider) {
        WeakReference<TrustMarkerDrawable> reference = mMarkers.get(provider);
        return reference == null ? null : reference.get();
    }

    /** Brings a marker in line with its tab: scanning while it loads, else its status. */
    private static void sync(TrustMarkerDrawable marker, Tab tab, LocationBarDataProvider provider) {
        @SiteTrust.Status int status = SiteTrust.fromSecurityLevel(provider.getSecurityLevel());
        if (tab.isLoading()) {
            marker.setPendingStatus(status);
            marker.startScan();
        } else if (marker.isScanning()) {
            marker.showResult(status);
        } else if (marker.getStatus() != status) {
            marker.setStatus(status);
        }
    }
}
