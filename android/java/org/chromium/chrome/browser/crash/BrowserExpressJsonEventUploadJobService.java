/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crash;

import org.chromium.build.annotations.IdentifierNameString;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.base.SplitCompatJobService;

/** See {@link BrowserExpressJsonEventUploadJobServiceImpl}. */
@NullMarked
public class BrowserExpressJsonEventUploadJobService extends SplitCompatJobService {
    @SuppressWarnings("FieldCanBeFinal") // @IdentifierNameString requires non-final
    private static @IdentifierNameString String sImplClassName =
            "org.chromium.chrome.browser.crash.BrowserExpressJsonEventUploadJobServiceImpl";

    public BrowserExpressJsonEventUploadJobService() {
        super(sImplClassName);
    }
}
