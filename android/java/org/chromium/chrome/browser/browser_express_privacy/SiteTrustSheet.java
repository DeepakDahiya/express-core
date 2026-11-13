/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveRewardsHelper;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.security_state.SecurityStateModel;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;

/**
 * The site trust sheet (Figma "Trust/Site sheet"), opened from the URL bar's trust marker: the
 * site, what was blocked on this page (live), the site's trust status, blocking ads on this site
 * only, and the way into the privacy settings.
 */
public class SiteTrustSheet extends BottomSheetDialogFragment
        implements ExpressShieldsStats.Observer {
    private static final String TAG = "SiteTrustSheet";

    /** Null once the sheet is recreated (e.g. after rotation): it then closes. */
    private @Nullable WeakReference<Tab> mTab;
    private @Nullable BraveRewardsHelper mFaviconHelper;
    private @Nullable TextView mBlockedCount;

    /** Shows the sheet for {@code tab}; false if it can't be shown right now. */
    static boolean show(FragmentActivity activity, Tab tab) {
        FragmentManager fragmentManager = activity.getSupportFragmentManager();
        if (fragmentManager.isStateSaved()) return false;
        if (fragmentManager.findFragmentByTag(TAG) != null) return true;
        SiteTrustSheet sheet = new SiteTrustSheet();
        sheet.mTab = new WeakReference<>(tab);
        sheet.show(fragmentManager, TAG);
        return true;
    }

    private @Nullable Tab tab() {
        Tab tab = mTab == null ? null : mTab.get();
        return tab == null || tab.isDestroyed() ? null : tab;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.BrowserExpressTrustSheetTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Tab tab = tab();
        if (tab == null) return null;
        View view = inflater.inflate(R.layout.browser_express_trust_sheet, container, false);
        bindSite(view, tab);
        bindStatus(view, tab);
        bindBlockAds(view, tab);
        view.findViewById(R.id.trust_advanced).setOnClickListener(v -> {
            dismiss();
            SettingsNavigationFactory.createSettingsNavigation()
                    .startSettings(v.getContext(), PrivacySettingsFragment.class);
        });
        ExpressShieldsStats stats = ExpressShieldsStats.get();
        stats.addObserver(this);
        showBlockedCount(stats.getBlockedCount(tab.getId()));
        return view;
    }

    private void bindSite(View view, Tab tab) {
        TextView host = view.findViewById(R.id.trust_host);
        host.setText(tab.getUrl().getHost());
        ImageView favicon = view.findViewById(R.id.trust_favicon);
        mFaviconHelper = new BraveRewardsHelper(tab);
        mFaviconHelper.retrieveLargeIcon(tab.getUrl().getSpec(), favicon::setImageBitmap);
        mBlockedCount = view.findViewById(R.id.trust_blocked_count);
    }

    private void bindStatus(View view, Tab tab) {
        @SiteTrust.Status
        int status =
                SiteTrust.fromSecurityLevel(
                        SecurityStateModel.getSecurityLevelForWebContents(tab.getWebContents()));
        View disc = view.findViewById(R.id.trust_status_disc);
        ViewCompat.setBackgroundTintList(
                disc,
                ColorStateList.valueOf(
                        ContextCompat.getColor(view.getContext(), SiteTrust.discColor(status))));
        disc.setContentDescription(getString(SiteTrust.label(status)));
        ImageView glyph = view.findViewById(R.id.trust_status_glyph);
        glyph.setImageResource(SiteTrust.discGlyph(status));
    }

    private void bindBlockAds(View view, Tab tab) {
        ExpressToggle toggle = view.findViewById(R.id.trust_block_ads);
        View label = view.findViewById(R.id.trust_block_ads_label);
        label.setOnClickListener(v -> toggle.toggle());
        String url = tab.getUrl().getSpec();
        Profile profile =
                tab.getWebContents() == null ? null : Profile.fromWebContents(tab.getWebContents());
        if (profile == null) {
            toggle.setEnabled(false);
            label.setEnabled(false);
            return;
        }
        toggle.setCheckedImmediately(ShieldsControls.isBlockingAdsOnSite(profile, url));
        toggle.setOnCheckedChangeListener((button, isChecked) -> {
            Tab current = tab();
            if (current == null) return;
            try {
                ShieldsControls.setBlockingAdsOnSite(profile, url, isChecked);
            } catch (RuntimeException e) {
                Log.e(TAG, "Couldn't change ad blocking for the site", e);
                toggle.setOnCheckedChangeListener(null);
                toggle.setCheckedImmediately(!isChecked);
                Toast.makeText(button.getContext(), R.string.browser_express_privacy_save_failed,
                                Toast.LENGTH_SHORT)
                        .show();
                bindBlockAds(view, current);
                return;
            }
            // The change applies to what the page loads next.
            if (current.isLoading()) current.stopLoading();
            current.reloadIgnoringCache();
        });
    }

    @Override
    public void onBlockedCountChanged(int tabId, int count) {
        Tab tab = tab();
        if (tab != null && tab.getId() == tabId) showBlockedCount(count);
    }

    private void showBlockedCount(int count) {
        if (mBlockedCount == null) return;
        mBlockedCount.setText(NumberFormat.getIntegerInstance().format(count));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (tab() == null) {
            dismissAllowingStateLoss();
            return;
        }
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onDestroyView() {
        ExpressShieldsStats.get().removeObserver(this);
        if (mFaviconHelper != null) {
            mFaviconHelper.detach();
            mFaviconHelper = null;
        }
        mBlockedCount = null;
        super.onDestroyView();
    }
}
