/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_privacy;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.StringRes;

import org.chromium.chrome.R;
import org.chromium.components.security_state.ConnectionSecurityLevel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A site's trust status for the trust marker and site sheet (Figma "Trust Markers": Green,
 * Amber, Red). It comes from the connection's security level alone and never from the reader's
 * ad-blocking choice — a site with ads allowed is still exactly as secure.
 *
 * <p>The mapping (the design leaves it to product and engineering):
 * <ul>
 *   <li>Secure (green): HTTPS with a valid certificate.
 *   <li>Dangerous (red): a failed or broken HTTPS connection, or a page Safe Browsing flagged.
 *   <li>Warning (amber): everything else that is a web page — plain HTTP, HTTPS the browser
 *       still marks not secure (legacy TLS, mixed content).
 * </ul>
 */
public final class SiteTrust {
    @IntDef({Status.SECURE, Status.WARNING, Status.DANGEROUS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
        int SECURE = 0;
        int WARNING = 1;
        int DANGEROUS = 2;
    }

    private SiteTrust() {}

    public static @Status int fromSecurityLevel(@ConnectionSecurityLevel int level) {
        switch (level) {
            case ConnectionSecurityLevel.SECURE:
                return Status.SECURE;
            case ConnectionSecurityLevel.DANGEROUS:
                return Status.DANGEROUS;
            default:
                return Status.WARNING;
        }
    }

    /** The accessible name of a status, so it never depends on colour alone. */
    public static @StringRes int label(@Status int status) {
        switch (status) {
            case Status.SECURE:
                return R.string.browser_express_trust_status_secure;
            case Status.DANGEROUS:
                return R.string.browser_express_trust_status_dangerous;
            default:
                return R.string.browser_express_trust_status_warning;
        }
    }

    /** The disc colour behind the site sheet's status glyph. */
    public static int discColor(@Status int status) {
        switch (status) {
            case Status.SECURE:
                return R.color.browser_express_trust_secure;
            case Status.DANGEROUS:
                return R.color.browser_express_trust_dangerous;
            default:
                return R.color.browser_express_trust_warning;
        }
    }

    /** The white glyph on that disc: a checked shield, a slashed shield, or a warning sign. */
    public static @DrawableRes int discGlyph(@Status int status) {
        switch (status) {
            case Status.SECURE:
                return R.drawable.ic_browser_express_trust_shield_check;
            case Status.DANGEROUS:
                return R.drawable.ic_browser_express_trust_shield_slash;
            default:
                return R.drawable.ic_browser_express_trust_warning;
        }
    }
}
