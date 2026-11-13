/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import java.util.Locale;

/** Clock formatting shared by the voice-note recorder and the playback row. */
final class AudioTime {
    private AudioTime() {}

    /**
     * Formats a duration as {@code m:ss}, the form used on both the recording overlay and the
     * posted row. Negative input reads as zero rather than producing a "-0:01" style timer.
     */
    static String format(long durationMs) {
        long totalSeconds = durationMs > 0 ? durationMs / 1000 : 0;
        return String.format(
                Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** Formats the "elapsed / total" pair shown on a posted voice note. */
    static String formatProgress(long positionMs, long durationMs) {
        return format(positionMs) + " / " + format(durationMs);
    }
}
