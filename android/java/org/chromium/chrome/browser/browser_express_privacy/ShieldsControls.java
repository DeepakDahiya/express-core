/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.ALLOW_RESOURCE;
import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.BLOCK_THIRDPARTY_RESOURCE;
import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.DEFAULT;
import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS;
import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.RESOURCE_IDENTIFIER_COOKIES;
import static org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings.RESOURCE_IDENTIFIER_TRACKERS;

import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;

/**
 * The design's on/off protections, mapped onto Brave Shields' finer controls. Turning one on
 * picks Brave's standard level; a stricter level the user set elsewhere reads as on and is left
 * alone until they turn it off.
 *
 * <ul>
 *   <li>Block ads: the ads and trackers network filter (one control in Brave), with cosmetic
 *       filtering. On is "standard".
 *   <li>Block trackers: cross-site cookies, the trackers' other way of following you, since the
 *       filter above already covers tracking requests. On blocks third-party cookies.
 *   <li>Always use secure connections: HTTPS upgrades. On is "standard" (upgrade when the site
 *       supports it).
 *   <li>Block fingerprinting: on is "standard".
 * </ul>
 */
public final class ShieldsControls {
    /**
     * BravePrivacySettings keeps its own record of the cookie choice (0 strict, 1 standard, 2
     * allow) and shows that; kept in step so the two screens agree.
     */
    private static final String PREF_BLOCK_CROSS_SITE_COOKIES = "block_cross_site_cookies";
    private static final int COOKIES_STANDARD = 1;
    private static final int COOKIES_ALLOW = 2;

    private ShieldsControls() {}

    private static Profile profile() {
        return ProfileManager.getLastUsedRegularProfile();
    }

    public static boolean isBlockAdsOn() {
        return !ALLOW_RESOURCE.equals(BraveShieldsContentSettings.getTrackersPref());
    }

    public static void setBlockAds(boolean on) {
        if (on == isBlockAdsOn()) return;
        BraveShieldsContentSettings.setTrackersPref(on ? DEFAULT : ALLOW_RESOURCE);
    }

    public static boolean isBlockTrackersOn() {
        return !ALLOW_RESOURCE.equals(
                BraveShieldsContentSettings.getShieldsValue(
                        profile(), "", RESOURCE_IDENTIFIER_COOKIES));
    }

    public static void setBlockTrackers(boolean on) {
        if (on == isBlockTrackersOn()) return;
        BraveShieldsContentSettings.setCookiesPref(on ? BLOCK_THIRDPARTY_RESOURCE : ALLOW_RESOURCE);
        ChromeSharedPreferences.getInstance()
                .writeInt(PREF_BLOCK_CROSS_SITE_COOKIES, on ? COOKIES_STANDARD : COOKIES_ALLOW);
    }

    public static boolean isHttpsUpgradeOn() {
        return !ALLOW_RESOURCE.equals(BraveShieldsContentSettings.getHttpsUpgradePref());
    }

    public static void setHttpsUpgrade(boolean on) {
        if (on == isHttpsUpgradeOn()) return;
        BraveShieldsContentSettings.setHttpsUpgradePref(
                on ? BLOCK_THIRDPARTY_RESOURCE : ALLOW_RESOURCE);
    }

    public static boolean isFingerprintingBlocked() {
        return !ALLOW_RESOURCE.equals(BraveShieldsContentSettings.getFingerprintingPref());
    }

    public static void setFingerprintingBlocked(boolean on) {
        if (on == isFingerprintingBlocked()) return;
        BraveShieldsContentSettings.setFingerprintingPref(on ? DEFAULT : ALLOW_RESOURCE);
    }

    /** Whether ads are blocked on the site at {@code url}: shields up and the filter not off. */
    public static boolean isBlockingAdsOnSite(Profile profile, String url) {
        return BraveShieldsContentSettings.getShields(
                        profile, url, RESOURCE_IDENTIFIER_BRAVE_SHIELDS)
                && !ALLOW_RESOURCE.equals(
                        BraveShieldsContentSettings.getShieldsValue(
                                profile, url, RESOURCE_IDENTIFIER_TRACKERS));
    }

    /** Blocks or allows ads on the site at {@code url} only. The page needs a reload. */
    public static void setBlockingAdsOnSite(Profile profile, String url, boolean on) {
        if (on
                && !BraveShieldsContentSettings.getShields(
                        profile, url, RESOURCE_IDENTIFIER_BRAVE_SHIELDS)) {
            BraveShieldsContentSettings.setShields(
                    profile, url, RESOURCE_IDENTIFIER_BRAVE_SHIELDS, true, false);
        }
        BraveShieldsContentSettings.setShieldsValue(
                profile, url, RESOURCE_IDENTIFIER_TRACKERS, on ? DEFAULT : ALLOW_RESOURCE, false);
    }
}
