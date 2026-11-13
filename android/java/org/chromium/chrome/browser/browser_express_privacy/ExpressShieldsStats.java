/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.chromium.base.ContextUtils;
import org.chromium.base.ObserverList;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettingsObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.url.GURL;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the ad blocker has done, for the site trust sheet and the privacy summary.
 *
 * <ul>
 *   <li>Per tab: how many requests were blocked on the page it shows now, reset when a page
 *       starts loading. Ads and trackers are one network filter, so they are counted together
 *       (the native side reports every network block as an ad).
 *   <li>Sites protected: how many different sites (registrable domains) had at least one thing
 *       blocked, kept on this device. Counted from when this was added; private tabs are never
 *       recorded.
 * </ul>
 *
 * <p>Lifetime totals of blocked requests and saved data come from Brave's own prefs; see
 * {@link PrivacySettingsFragment}.
 *
 * <p>It observes the shields events itself, once, rather than through a toolbar, since every
 * browser window has its own toolbar and each would count the same event again. The toolbars
 * only report page loads and closed tabs, which are per window anyway.
 */
public final class ExpressShieldsStats {
    /** Told about blocks on the page a tab shows. Called on the UI thread. */
    public interface Observer {
        void onBlockedCountChanged(int tabId, int count);
    }

    private static final String PREFS_NAME = "browser_express_shields_stats";
    private static final String KEY_PROTECTED_SITES = "protected_sites";

    private static @Nullable ExpressShieldsStats sInstance;

    private final Map<Integer, Integer> mBlockedCounts = new HashMap<>();
    /** The site each tab's page is on, until it's been recorded as protected; null when done. */
    private final Map<Integer, String> mUnrecordedSites = new HashMap<>();
    private final ObserverList<Observer> mObservers = new ObserverList<>();
    private @Nullable Set<String> mProtectedSites;

    private ExpressShieldsStats() {}

    /** The stats, observing shields events from the first call. Needs the native library. */
    public static ExpressShieldsStats get() {
        ThreadUtils.assertOnUiThread();
        if (sInstance == null) {
            sInstance = new ExpressShieldsStats();
            BraveShieldsContentSettings.getInstance()
                    .addObserver(
                            new BraveShieldsContentSettingsObserver() {
                                @Override
                                public void blockEvent(
                                        int tabId, String blockType, String subresource) {
                                    sInstance.onBlocked(tabId);
                                }

                                @Override
                                public void savedBandwidth(long savings) {}
                            });
        }
        return sInstance;
    }

    public void addObserver(Observer observer) {
        mObservers.addObserver(observer);
    }

    public void removeObserver(Observer observer) {
        mObservers.removeObserver(observer);
    }

    /** Requests blocked on the page {@code tabId} shows. */
    public int getBlockedCount(int tabId) {
        Integer count = mBlockedCounts.get(tabId);
        return count == null ? 0 : count;
    }

    /** Different sites that had something blocked, on this device. */
    public int getProtectedSiteCount() {
        return protectedSites().size();
    }

    /** A new page started loading in {@code tab}: its count starts again. */
    public void onPageLoadStarted(Tab tab, GURL url) {
        int tabId = tab.getId();
        mBlockedCounts.remove(tabId);
        mUnrecordedSites.remove(tabId);
        if (!tab.isIncognitoBranded() && UrlUtilities.isHttpOrHttps(url)) {
            String site = UrlUtilities.getDomainAndRegistry(url.getSpec(), false);
            if (TextUtils.isEmpty(site)) site = url.getHost();
            if (!TextUtils.isEmpty(site)) mUnrecordedSites.put(tabId, site);
        }
        notifyObservers(tabId, 0);
    }

    public void onTabDestroyed(int tabId) {
        mBlockedCounts.remove(tabId);
        mUnrecordedSites.remove(tabId);
    }

    private void onBlocked(int tabId) {
        int count = getBlockedCount(tabId) + 1;
        mBlockedCounts.put(tabId, count);
        String site = mUnrecordedSites.remove(tabId);
        if (site != null && protectedSites().add(site)) {
            // Written only when a new site is added, not on every block.
            prefs().edit().putStringSet(KEY_PROTECTED_SITES, new HashSet<>(mProtectedSites))
                    .apply();
        }
        notifyObservers(tabId, count);
    }

    private void notifyObservers(int tabId, int count) {
        for (Observer observer : mObservers) observer.onBlockedCountChanged(tabId, count);
    }

    private Set<String> protectedSites() {
        if (mProtectedSites == null) {
            // A copy: the set SharedPreferences returns must not be changed in place.
            mProtectedSites =
                    new HashSet<>(prefs().getStringSet(KEY_PROTECTED_SITES, new HashSet<>()));
        }
        return mProtectedSites;
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
