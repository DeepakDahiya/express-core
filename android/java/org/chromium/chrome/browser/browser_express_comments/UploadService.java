package org.chromium.chrome.browser.browser_express_comments;

import org.chromium.build.annotations.IdentifierNameString;
import org.chromium.chrome.browser.base.SplitCompatService;

/** See {@link WireguardServiceImpl}. */
public class UploadService extends SplitCompatService {
    @SuppressWarnings("FieldCanBeFinal") // @IdentifierNameString requires non-final
    private static @IdentifierNameString String sImplClassName =
            "org.chromium.chrome.browser.browser_express_comments.UploadServiceImpl";

    public UploadService() {
        super(sImplClassName);
    }
}