/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.icu.text.CompactDecimalFormat;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.browser_express_apps.InstalledAppsManager;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.settings.BravePreferenceFragment;
import org.chromium.chrome.browser.settings.BraveSearchEngineUtils;
import org.chromium.chrome.browser.settings.BraveSearchEnginesPreferences;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;
import org.chromium.components.user_prefs.UserPrefs;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Privacy & Security (Figma "Trust/Privacy settings" 1:5268): what the ad blocker has done, and
 * the protections behind it. The toggles are the user's choices, applied to Brave Shields as
 * {@link ShieldsControls} describes; they don't confirm anything is blocked on a given site.
 *
 * <p>The summary shows the browser's lifetime count of blocked ads and trackers (one count, as
 * the browser keeps them), the data that saved, and how many sites had something blocked since
 * this screen was added ({@link ExpressShieldsStats}).
 */
public class PrivacySettingsFragment extends BravePreferenceFragment {
    private static final String TAG = "PrivacySettings";
    /** Counts at least this big are shortened ("128K"), so the summary's columns hold them. */
    private static final long COMPACT_COUNT_FROM = 100_000;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    private @Nullable View mRoot;
    /** Each toggle's id and what it controls. */
    private final Map<Integer, Control> mControls = new LinkedHashMap<>();
    /** Set while toggles are moved to match the saved settings, which mustn't save them again. */
    private boolean mSyncingToggles;

    /** A protection toggle: how to read it, how to write it. */
    private interface Control {
        boolean isOn();

        void set(boolean on);
    }

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.browser_express_privacy_title));
        mRoot = inflater.inflate(R.layout.browser_express_privacy_settings, container, false);
        bind(mRoot);
        return mRoot;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    private void bind(View root) {
        bindToggle(root, R.id.privacy_row_block_trackers, R.id.privacy_block_trackers,
                new Control() {
                    @Override
                    public boolean isOn() {
                        return ShieldsControls.isBlockTrackersOn();
                    }

                    @Override
                    public void set(boolean on) {
                        ShieldsControls.setBlockTrackers(on);
                    }
                });
        bindToggle(root, R.id.privacy_row_block_ads, R.id.privacy_block_ads, new Control() {
            @Override
            public boolean isOn() {
                return ShieldsControls.isBlockAdsOn();
            }

            @Override
            public void set(boolean on) {
                ShieldsControls.setBlockAds(on);
            }
        });
        bindToggle(root, R.id.privacy_row_https, R.id.privacy_https, new Control() {
            @Override
            public boolean isOn() {
                return ShieldsControls.isHttpsUpgradeOn();
            }

            @Override
            public void set(boolean on) {
                ShieldsControls.setHttpsUpgrade(on);
            }
        });
        bindToggle(root, R.id.privacy_row_fingerprinting, R.id.privacy_fingerprinting,
                new Control() {
                    @Override
                    public boolean isOn() {
                        return ShieldsControls.isFingerprintingBlocked();
                    }

                    @Override
                    public void set(boolean on) {
                        ShieldsControls.setFingerprintingBlocked(on);
                    }
                });
        // Takes effect at the next launch: BraveActivity clears the data as it starts.
        bindToggle(root, R.id.privacy_row_clear_on_exit, R.id.privacy_clear_on_exit,
                new Control() {
                    @Override
                    public boolean isOn() {
                        return ChromeSharedPreferences.getInstance()
                                .readBoolean(BravePreferenceKeys.BRAVE_CLEAR_ON_EXIT, false);
                    }

                    @Override
                    public void set(boolean on) {
                        ChromeSharedPreferences.getInstance()
                                .writeBoolean(BravePreferenceKeys.BRAVE_CLEAR_ON_EXIT, on);
                    }
                });
        bindToggle(root, R.id.privacy_row_do_not_track, R.id.privacy_do_not_track, new Control() {
            @Override
            public boolean isOn() {
                return UserPrefs.get(profile()).getBoolean(Pref.ENABLE_DO_NOT_TRACK);
            }

            @Override
            public void set(boolean on) {
                UserPrefs.get(profile()).setBoolean(Pref.ENABLE_DO_NOT_TRACK, on);
            }
        });

        root.findViewById(R.id.privacy_row_clear_data).setOnClickListener(v ->
                SettingsNavigationFactory.createSettingsNavigation()
                        .startSettings(v.getContext(), SettingsFragment.CLEAR_BROWSING_DATA));
        root.findViewById(R.id.privacy_row_site_permissions).setOnClickListener(v ->
                SettingsNavigationFactory.createSettingsNavigation()
                        .startSettings(v.getContext(), SettingsFragment.SITE));
        root.findViewById(R.id.privacy_row_search_engine).setOnClickListener(v ->
                SettingsNavigationFactory.createSettingsNavigation()
                        .startSettings(v.getContext(), BraveSearchEnginesPreferences.class));

        int visibility = InstalledAppsManager.isAvailable() ? View.VISIBLE : View.GONE;
        root.findViewById(R.id.privacy_personalization_heading).setVisibility(visibility);
        root.findViewById(R.id.privacy_row_installed_apps).setVisibility(visibility);
        root.findViewById(R.id.privacy_installed_apps_note).setVisibility(visibility);
        bindToggle(root, R.id.privacy_row_installed_apps, R.id.privacy_installed_apps,
                new Control() {
                    @Override
                    public boolean isOn() {
                        return InstalledAppsManager.isSharingEnabled();
                    }

                    @Override
                    public void set(boolean on) {
                        if (on) {
                            // Keep the toggle off until the disclosure's Allow button is tapped.
                            refresh();
                            InstalledAppsManager.showConsent(getActivity(), () -> refresh());
                        } else {
                            InstalledAppsManager.turnOff();
                        }
                    }
                });
    }

    private static Profile profile() {
        return ProfileManager.getLastUsedRegularProfile();
    }

    private void bindToggle(View root, int rowId, int toggleId, Control control) {
        mControls.put(toggleId, control);
        ExpressToggle toggle = root.findViewById(toggleId);
        root.findViewById(rowId).setOnClickListener(v -> toggle.toggle());
        toggle.setOnCheckedChangeListener((button, isChecked) -> {
            if (mSyncingToggles) return;
            try {
                control.set(isChecked);
            } catch (RuntimeException e) {
                Log.e(TAG, "Couldn't save a privacy setting", e);
                // Back to what is actually saved.
                mSyncingToggles = true;
                toggle.setCheckedImmediately(!isChecked);
                mSyncingToggles = false;
                Toast.makeText(button.getContext(), R.string.browser_express_privacy_save_failed,
                                Toast.LENGTH_SHORT)
                        .show();
            }
            updateNotes();
        });
    }

    /** Reads every setting and stat again: they may have changed on another screen. */
    private void refresh() {
        if (mRoot == null) return;
        mSyncingToggles = true;
        for (Map.Entry<Integer, Control> entry : mControls.entrySet()) {
            ExpressToggle toggle = mRoot.findViewById(entry.getKey());
            try {
                toggle.setCheckedImmediately(entry.getValue().isOn());
            } catch (RuntimeException e) {
                Log.e(TAG, "Couldn't read a privacy setting", e);
            }
        }
        mSyncingToggles = false;
        updateNotes();
        updateSummary();
        updateSearchEngine();
    }

    private void updateNotes() {
        if (mRoot == null) return;
        boolean adsOn = ((ExpressToggle) mRoot.findViewById(R.id.privacy_block_ads)).isChecked();
        boolean clearOnExit =
                ((ExpressToggle) mRoot.findViewById(R.id.privacy_clear_on_exit)).isChecked();
        setText(R.id.privacy_protection_note,
                adsOn ? R.string.browser_express_privacy_protection_note
                      : R.string.browser_express_privacy_protection_note_off);
        setText(R.id.privacy_summary_caption,
                adsOn ? R.string.browser_express_privacy_summary_on
                      : R.string.browser_express_privacy_summary_off);
        setText(R.id.privacy_data_note,
                clearOnExit ? R.string.browser_express_privacy_data_note
                            : R.string.browser_express_privacy_data_note_off);
    }

    private void setText(int viewId, @StringRes int text) {
        if (mRoot == null) return;
        ((TextView) mRoot.findViewById(viewId)).setText(text);
    }

    private void updateSummary() {
        if (mRoot == null) return;
        String unavailable = getString(R.string.browser_express_privacy_count_unavailable);
        String blocked = unavailable;
        String dataSaved = unavailable;
        String sites = unavailable;
        try {
            Profile profile = profile();
            BravePrefServiceBridge bridge = BravePrefServiceBridge.getInstance();
            blocked = formatCount(bridge.getTrackersBlockedCount(profile)
                    + bridge.getAdsBlockedCount(profile));
            dataSaved = Formatter.formatShortFileSize(
                    mRoot.getContext(), Math.max(0, bridge.getDataSaved(profile)));
            sites = formatCount(ExpressShieldsStats.get().getProtectedSiteCount());
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't read the privacy summary", e);
        }
        showStat(R.id.privacy_stat_blocked, R.id.privacy_stat_blocked_value, blocked,
                R.string.browser_express_privacy_summary_blocked);
        showStat(R.id.privacy_stat_data, R.id.privacy_stat_data_value, dataSaved,
                R.string.browser_express_privacy_summary_data);
        showStat(R.id.privacy_stat_sites, R.id.privacy_stat_sites_value, sites,
                R.string.browser_express_privacy_summary_sites);
    }

    private void showStat(int statId, int valueId, String value, @StringRes int label) {
        if (mRoot == null) return;
        ((TextView) mRoot.findViewById(valueId)).setText(value);
        // Read as one: "1,284 Trackers & ads blocked".
        mRoot.findViewById(statId).setContentDescription(value + " " + getString(label));
    }

    private static String formatCount(long count) {
        if (count < 0) count = 0;
        if (count < COMPACT_COUNT_FROM) return NumberFormat.getIntegerInstance().format(count);
        return CompactDecimalFormat.getInstance(
                        Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
                .format(count);
    }

    private void updateSearchEngine() {
        if (mRoot == null) return;
        String name = null;
        try {
            name = BraveSearchEngineUtils.getDSEShortName(profile(), false);
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't read the search engine", e);
        }
        ((TextView) mRoot.findViewById(R.id.privacy_search_engine_name))
                .setText(name == null ? "" : name);
    }

    @Override
    public void onResume() {
        super.onResume();
        InstalledAppsManager.syncIfNeeded();
        refresh();
    }

    @Override
    public void onDestroyView() {
        mRoot = null;
        super.onDestroyView();
    }
}
