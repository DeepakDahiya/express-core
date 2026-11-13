/* Copyright (c) 2021 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.omnibox.status;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.OneshotSupplier;
import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.browser.merchant_viewer.MerchantTrustSignalsCoordinator;
import org.chromium.chrome.browser.omnibox.LocationBarDataProvider;
import org.chromium.chrome.browser.omnibox.UrlBarEditingTextStateProvider;
import org.chromium.chrome.browser.omnibox.status.StatusProperties.StatusIconResource;
import org.chromium.chrome.browser.omnibox.status.StatusView.IconTransitionType;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.components.permissions.PermissionDialogController;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modelutil.PropertyModel;

public class BraveStatusMediator extends StatusMediator {
    /**
     * Supplies the drawable that stands in for the security chip's icon (Browser Express's trust
     * marker). Set from chrome_java, whose resources the marker draws with.
     */
    public interface TrustMarkerSource {
        /** The marker for the page {@code provider} shows, or null to keep Chromium's icon. */
        @Nullable
        Drawable getTrustMarker(LocationBarDataProvider provider);
    }

    private static @Nullable TrustMarkerSource sTrustMarkerSource;

    // To delete in bytecode, members from parent class will be used instead.
    private boolean mUrlHasFocus;

    private final PropertyModel mStatusModel;
    private final LocationBarDataProvider mDataProvider;
    private @DrawableRes int mSecurityIcon;
    private @ColorRes int mSecurityTint;

    public BraveStatusMediator(
            PropertyModel model,
            Context context,
            UrlBarEditingTextStateProvider urlBarEditingTextStateProvider,
            boolean isTablet,
            LocationBarDataProvider locationBarDataProvider,
            PermissionDialogController permissionDialogController,
            OneshotSupplier<TemplateUrlService> templateUrlServiceSupplier,
            ObservableSupplier<Profile> profileSupplier,
            PageInfoIphController pageInfoIphController,
            WindowAndroid windowAndroid,
            @Nullable
                    Supplier<MerchantTrustSignalsCoordinator>
                            merchantTrustSignalsCoordinatorSupplier) {
        super(
                model,
                context,
                urlBarEditingTextStateProvider,
                isTablet,
                locationBarDataProvider,
                permissionDialogController,
                templateUrlServiceSupplier,
                profileSupplier,
                pageInfoIphController,
                windowAndroid,
                merchantTrustSignalsCoordinatorSupplier);
        mStatusModel = model;
        mDataProvider = locationBarDataProvider;
    }

    public static void setTrustMarkerSource(@Nullable TrustMarkerSource source) {
        sTrustMarkerSource = source;
    }

    @Override
    void updateSecurityIcon(
            @DrawableRes int securityIcon, @ColorRes int tintList, @StringRes int desc) {
        mSecurityIcon = securityIcon;
        mSecurityTint = tintList;
        super.updateSecurityIcon(securityIcon, tintList, desc);
    }

    /**
     * Shows the trust marker wherever Chromium would show the security chip's icon. The marker
     * stands in for that icon (see {@link TrustMarkerIconResource#equals}), so Chromium setting
     * the icon again doesn't crossfade the marker out and back in on every update.
     */
    @Override
    void updateLocationBarIcon(@IconTransitionType int transitionType) {
        // StatusMediator's constructor calls this before ours has set the fields below.
        if (mStatusModel == null) {
            super.updateLocationBarIcon(transitionType);
            return;
        }
        StatusIconResource current = mStatusModel.get(StatusProperties.STATUS_ICON_RESOURCE);
        Drawable marker =
                sTrustMarkerSource != null && mSecurityIcon != 0
                        ? sTrustMarkerSource.getTrustMarker(mDataProvider)
                        : null;
        if (current instanceof TrustMarkerIconResource) {
            TrustMarkerIconResource shownMarker = (TrustMarkerIconResource) current;
            if (marker != null) {
                shownMarker.cover(mSecurityIcon, mSecurityTint);
            } else {
                shownMarker.cover(0, 0);
            }
        }
        super.updateLocationBarIcon(transitionType);
        if (marker == null || mUrlHasFocus) return;

        StatusIconResource shown = mStatusModel.get(StatusProperties.STATUS_ICON_RESOURCE);
        boolean showsSecurityIcon =
                shown instanceof TrustMarkerIconResource
                        || (shown != null && shown.getIconRes() == mSecurityIcon);
        if (!showsSecurityIcon) return;
        TrustMarkerIconResource markerResource =
                new TrustMarkerIconResource(marker, mSecurityIcon, mSecurityTint);
        markerResource.setTransitionType(transitionType);
        mStatusModel.set(StatusProperties.STATUS_ICON_RESOURCE, markerResource);
    }

    /**
     * The trust marker as a status icon. It compares equal to the security icon it covers, so
     * the property model ignores Chromium re-setting that icon while the marker shows.
     */
    private static final class TrustMarkerIconResource extends StatusIconResource {
        private @DrawableRes int mCoveredIcon;
        private @ColorRes int mCoveredTint;

        TrustMarkerIconResource(
                Drawable marker, @DrawableRes int coveredIcon, @ColorRes int coveredTint) {
            super(marker);
            cover(coveredIcon, coveredTint);
        }

        void cover(@DrawableRes int icon, @ColorRes int tint) {
            mCoveredIcon = icon;
            mCoveredTint = tint;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (other instanceof TrustMarkerIconResource) return super.equals(other);
            if (!(other instanceof StatusIconResource) || mCoveredIcon == 0) return false;
            StatusIconResource icon = (StatusIconResource) other;
            return icon.getIconRes() == mCoveredIcon && icon.getTint() == mCoveredTint;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Override
    boolean shouldDisplaySearchEngineIcon() {
        return super.shouldDisplaySearchEngineIcon() && mUrlHasFocus;
    }
}
