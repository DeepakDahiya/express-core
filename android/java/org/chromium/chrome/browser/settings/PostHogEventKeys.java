package org.chromium.chrome.browser.settings;

/**
 * Every PostHog event the app sends, by area. Send them with {@link PostHogUtil#capture}, which
 * keys them to the signed-in user (else this install, merged into the user on sign-in) and adds
 * the common properties: app_version, $os, $os_version, $device_manufacturer, $device_model,
 * $session_id, signed_in and locale.
 *
 * <p>Web pages are only ever described by {@link PostHogUtil#pageProperties}: their domain and,
 * for searches, the search terms; never a full URL. Nothing is sent about incognito tabs.
 *
 * <p>The values are what PostHog shows; renaming one starts a new event there.
 */
public final class PostHogEventKeys {
        // App lifecycle
        public static final String APP_INSTALLED = "App Installed";
        /** A cold start. Sets the person properties (theme, default browser, notifications…). */
        public static final String APP_LAUNCHED = "App Launched";
        /** Back in front: background_ms. */
        public static final String APP_FOREGROUNDED = "App Foregrounded";
        /** Left: foreground_ms. */
        public static final String APP_BACKGROUNDED = "App Backgrounded";
        public static final String DEFAULT_BROWSER_SELECTED = "Default Browser Selected";
        public static final String NOTIFICATIONS_PERMISSION_GRANTED = "Notifications Permission Granted";
        public static final String NOTIFICATIONS_PERMISSION_DENIED = "Notifications Permission Denied";
        /** theme: system, light or dark. */
        public static final String THEME_CHANGED = "Theme Changed";

        // Account
        public static final String USER_SIGNED_UP = "User Signed Up";
        public static final String LOGIN = "Login";
        public static final String LOGOUT = "Logout";
        public static final String PROFILE_VIEWED = "Profile Viewed";
        public static final String PROFILE_UPDATED = "Profile Updated";

        // Browsing (page properties only; nothing from incognito tabs)
        /** A URL typed in the omnibox (not a search). */
        public static final String URL_ENTERED = "URL Entered";
        /** An omnibox search (typed or spoken): search_engine, search_query. */
        public static final String SEARCH_PERFORMED = "Search Performed";
        public static final String PAGE_VISITED = "Page Visited";
        public static final String HOME_VISITED = "Home Visited";
        /** A regular tab: background, or an incognito one (also INCOGNITO_TAB_OPENED). */
        public static final String TAB_OPENED = "Tab Opened";
        public static final String INCOGNITO_TAB_OPENED = "Incognito Tab Opened";
        /** source: ntp. */
        public static final String VOICE_SEARCH_USED = "Voice Search Used";
        /** source: ntp. */
        public static final String LENS_USED = "Lens Used";
        public static final String YOUTUBE_VISITED = "YouTube Visited";

        // Feed (per-post detail goes to the server's /v1/post/feed/events, not here)
        /** posts_seen, deepest_position, seen_ms, posts_opened, posts_hidden, video_watch_ms. */
        public static final String FEED_SESSION_ENDED = "Feed Session Ended";
        /** post_id, target (comments, video or article), position. */
        public static final String POST_CLICKED_ON = "Post Clicked On";
        /** post_id, count: a card's "12 new takes" callout was at least half on screen. */
        public static final String NEW_TAKES_SHOWN = "New Takes Shown";
        /** post_id, count: the card was tapped while it showed them. */
        public static final String NEW_TAKES_TAPPED = "New Takes Tapped";
        /** take_id, position (1-based, in the stream), post_id: a NEW take's page came up. */
        public static final String NEW_TAKE_VIEWED = "New Take Viewed";

        // First-launch walkthrough (design_handoff_walkthrough)
        /** step: search, scroll, post or video. Each time a step's prompt or motion starts. */
        public static final String WALKTHROUGH_STEP_SHOWN = "Walkthrough Step Shown";
        /**
         * step, action: what moved it on. search: reveal, skip, card or search_bar; post:
         * dive_in, card, post, pick_another or picked_post (with post_id).
         */
        public static final String WALKTHROUGH_ACTION = "Walkthrough Action";
        /** nudged: whether the swipe nudge had started before the reader's first swipe. */
        public static final String WALKTHROUGH_COMPLETED = "Walkthrough Completed";
        /** dismissed_by: got_it, swipe, timeout, rail or covered. */
        public static final String WALKTHROUGH_BONUS_TIP_SHOWN = "Walkthrough Bonus Tip Shown";
        public static final String WALKTHROUGH_BONUS_TIP_DISMISSED =
                "Walkthrough Bonus Tip Dismissed";
        /** post_id, position: "Not interested". */
        public static final String POST_HIDDEN = "Post Hidden";
        public static final String POST_UNHIDDEN = "Post Unhidden";
        /** post_id, surface. */
        public static final String POST_SHARED = "Post Shared";
        /** post_id, exit (close, rail, swipe or back), watch_ms, duration_ms. */
        public static final String POST_VIDEO_VIEWER_CLOSED = "Post Video Viewer Closed";
        /** sound_on. */
        public static final String FEED_SOUND_TOGGLED = "Feed Sound Toggled";
        /** page: first or next, error. */
        public static final String FEED_LOAD_FAILED = "Feed Load Failed";
        /** posts: the reader scrolled through every post the server had. */
        public static final String FEED_END_REACHED = "Feed End Reached";
        public static final String NTP_TICKER_CLICKED = "NTP Ticker Clicked";
        public static final String HOME_BANNER_CLICKED = "Home Banner Clicked";
        public static final String YT_INTRO_TUTORIAL_VIDEO_CLICKED =
                "YT Intro Tutorial Video Clicked";

        // Comments
        /** The comment button on a web page (domain). */
        public static final String BOTTOM_SHEET_CLICKED = "Bottom Sheet Clicked";
        /** comments_for (post, page or youtube), post_id, with_video, from_notification. */
        public static final String COMMENTS_OPENED = "Comments Opened";
        /** comments_for, post_id, duration_ms, replies_opened, comments_sent. */
        public static final String COMMENTS_CLOSED = "Comments Closed";
        /** A comment from inside the feed's comments (the post it belongs to). */
        public static final String FEED_CLICKED_ON = "Feed Clicked On";
        public static final String CLICKED_TO_VIEW_REPLIES = "Clicked to View Replies";
        public static final String CLICKED_TO_VIEW_REPLY2REPLY = "Clicked to View Reply2Reply";
        public static final String COMMENT_REPLY_SHARE_CLICKED = "Comment/Reply Share Clicked";
        /**
         * A share link opened in the app. via: app_link, in_app or install_referrer; post_id,
         * take_id, by (the sharer); result: opened, take_gone, post_gone or failed.
         */
        public static final String SHARE_LINK_OPENED = "Share Link Opened";
        /** "Add your take" opened; source: text_box, mic, attach or take_button. */
        public static final String TAKE_COMPOSER_OPENED = "Take Composer Opened";
        /** A draft thrown away from the take sheet. */
        public static final String TAKE_DISCARDED = "Take Discarded";
        /** kind: photo, camera, video, voice, gif or document. */
        public static final String COMMENT_ATTACHMENT_CHOSEN = "Comment Attachment Chosen";
        /** A comment sent (text, files or a voice note). */
        public static final String COMMENTED = "Commented";
        /**
         * A comment reached the server: parent_type, files (0 for text), kinds, bytes,
         * duration_ms since it was first sent.
         */
        public static final String COMMENT_UPLOAD_COMPLETED = "Comment Upload Completed";
        /** As above, for a send that failed (each failure, retries included), with error. */
        public static final String COMMENT_UPLOAD_FAILED = "Comment Upload Failed";
        /** An emoji reaction set on (or, with a null emoji, removed from) a comment. */
        public static final String REACTION_GIVEN = "Reaction Given";

        // YouTube premium features
        public static final String YTP_PREMIUM_CLICKED_ON_QUICKLINK = "YTP Premium Clicked on Quicklink";
        public static final String YTP_PREMIUM_CLICKED_ON_HOME = "YTP Premium Clicked on Home";
        public static final String YT_FEATURE_EXPLORED_PIP = "YT Feature Explored (PIP)";
        public static final String YT_FEATURE_EXPLORED_PIP_BG_PLAY = "YT Feature Explored (PIP + Background Play)";
        public static final String YT_FEATURE_EXPLORED_BG_PLAY = "YT Feature Explored (Background Play)";

        // Background-play onboarding tutorial funnel (the 2-step overlay on
        // YouTube /watch: "Go to home" countdown -> app backgrounded -> return ->
        // "Congratulations"). Distinct from the YT_FEATURE_EXPLORED_* events, which
        // fire when the feature is actually used later. Lives in
        // BrowsingModeBottomToolbarCoordinator.
        public static final String YT_BG_PLAY_TUTORIAL_SHOWN =
                "YT Background Play Tutorial Shown";
        public static final String YT_BG_PLAY_TUTORIAL_BACKGROUNDED =
                "YT Background Play Tutorial Backgrounded";
        public static final String YT_BG_PLAY_TUTORIAL_CONGRATS_SHOWN =
                "YT Background Play Tutorial Congrats Shown";
        public static final String YT_BG_PLAY_TUTORIAL_COMPLETED =
                "YT Background Play Tutorial Completed";

        // Referral events
        public static final String REFERRAL_CLICKED_IN_PROFILE = "Referral Clicked in Profile";
        public static final String REFERRAL_INITIATED = "Referral Initiated";
        public static final String REFERRAL_SOURCE = "Referral Source";

        // Referral attribution diagnostics (used to quantify Play Install Referrer gap)
        public static final String REFERRAL_INSTALL_REFERRER_RESULT =
                "referral_app_install_referrer_result";
        public static final String REFERRAL_DEEP_LINK_RECEIVED = "referral_app_deep_link_received";
        public static final String REFERRAL_FINGERPRINT_RESULT =
                "referral_app_fingerprint_match_attempted";
        public static final String REFERRAL_CLIPBOARD_RESULT = "referral_app_clipboard_result";
        public static final String REFERRAL_ATTRIBUTED = "referral_referred_device_tracked";
        // The app (or backend, via HTTP 422) refused to track the signed-in
        // user's own referral code — previously a +1 that /link clawed back.
        public static final String REFERRAL_SELF_REFERRAL_SKIPPED =
                "referral_app_self_referral_skipped";
        // Installed from a shared take's web page (install referrer source=share): post_id, source.
        public static final String REFERRAL_SHARE_INSTALL = "referral_app_share_install";

        // Registration / phone-OTP journey. Funnel order: AUTH_SCREEN_VIEWED ->
        // OTP_REQUESTED -> OTP_VERIFY_SCREEN_VIEWED -> OTP_SUBMITTED ->
        // USER_SIGNED_UP (or LOGIN). The pre-auth steps have no mongo user id yet,
        // so they are fired anonymously (anon-<deviceId>) and stitched onto the
        // mongo person by ReferralHelper.maybeIdentifyInstall on first auth.
        public static final String AUTH_SCREEN_VIEWED = "Auth Screen Viewed";
        public static final String OTP_REQUESTED = "OTP Requested";
        public static final String OTP_SEND_FAILED = "OTP Send Failed";
        public static final String OTP_VERIFY_SCREEN_VIEWED = "OTP Verify Screen Viewed";
        public static final String OTP_SUBMITTED = "OTP Submitted";
        public static final String OTP_VERIFY_FAILED = "OTP Verify Failed";
        public static final String OTP_RESENT = "OTP Resent";
        public static final String OTP_AUTOFILLED_FROM_SMS = "OTP Autofilled From SMS";
        // User tapped the register screen's close/skip button and fell back to the
        // generate-username auto-login instead of completing phone registration.
        public static final String AUTH_REGISTRATION_SKIPPED = "Registration Skipped (Auto Login)";

        // Push notifications (likes / replies). Server side fires "Push Sent";
        // together these give the funnel sent -> received -> opened.
        public static final String PUSH_TOKEN_REGISTERED = "Push Token Registered";
        public static final String PUSH_TOKEN_SYNC_FAILED = "Push Token Sync Failed";
        public static final String PUSH_RECEIVED = "Push Received";
        public static final String PUSH_OPENED = "Push Opened";
        public static final String PUSH_PERMISSION_PROMPTED = "Push Permission Prompted";

        // Declared but not sent by anything yet. Votes became emoji reactions
        // (REACTION_GIVEN); "received" events belong on the server, which sees them.
        public static final String SOCIAL_LOGIN_USED = "Social Login Used";
        public static final String LINK_CLICKED = "Link Clicked";
        public static final String AVG_TABS_OPENED_CONCURRENTLY = "Avg Tabs Opened Concurrently";
        public static final String BOOKMARK_ADDED = "Bookmark Added";
        public static final String DOWNLOAD_INITIATED = "Download Initiated";
        public static final String DOWNLOAD_COMPLETED = "Download Completed";
        public static final String HOME_SHORTCUT_CLICKED = "Home Shortcut Clicked";
        public static final String QUICK_LINK_USED = "Quick Link Used";
        public static final String HISTORY_VIEWED = "History Viewed";
        public static final String DEVICE_BACK_BUTTON_USED = "Device Back Button Used";
        public static final String REFRESH_USED = "Refresh Used";
        public static final String DEVICE_DARK_LIGHT_MODE = "Device Dark/Light Mode";
        public static final String KEYBOARD_LANGUAGE = "Keyboard Language";
        public static final String LANGUAGE_CHANGED = "Language Changed";
        public static final String SCROLL_DEPTH_ON_FEED = "Scroll Depth on Feed";
        public static final String SHARE_LINK_CLICKED = "Share Link Clicked";
        public static final String COMMENTS_PRESENT_ON_VISITED_URL = "Comments Present on Visited URL";
        public static final String UPVOTE_RECEIVED_ON_COMMENT = "Upvote Received on Comment";
        public static final String DOWNVOTE_RECEIVED_ON_COMMENT = "Downvote Received on Comment";
        public static final String REPLY_RECEIVED_ON_COMMENT = "Reply Received on Comment";
        public static final String ACTIVITY_ON_COMMENT_OR_REPLY = "Activity on Comment or Reply";
        public static final String UPVOTE_GIVEN = "Upvote Given";
        public static final String DOWNVOTE_GIVEN = "Downvote Given";
        public static final String YT_SIGN_IN_COMPLETE = "YT Sign-In Complete";
        public static final String REFERRAL_MODE_CHOSEN = "Mode of Referral Chosen";
        public static final String REFERRAL_SUCCESSFUL_COUNT = "Count of Successful Referrals";
}
