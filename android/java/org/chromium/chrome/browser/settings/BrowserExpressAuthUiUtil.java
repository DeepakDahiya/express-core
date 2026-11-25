/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.ui.UiUtils;
import org.chromium.ui.util.ColorUtils;

final class BrowserExpressAuthUiUtil {
    private static final String PREF_ENTERED_NAME = "BrowserExpressSigninName";
    private static final String INDIA_COUNTRY_CODE = "+91";
    private static final float AUTH_HERO_TEXT_SCALE = 0.85f;
    private static final float AUTH_HERO_TEXT_REDUCTION_SP = 2f;
    private static final float AUTH_TEXT_REDUCTION_SP = 2f;
    private static final float BLAZINGLY_FAST_TITLE_TEXT_SIZE_SP = 10f;
    private static final float BLAZINGLY_FAST_SUBTITLE_TEXT_SIZE_SP = 9f;
    private static final float MIN_RUNTIME_TEXT_SIZE_SP = 9f;

    private BrowserExpressAuthUiUtil() {}

    /**
     * Registration escape hatch shared by the auth screens: the user declined phone
     * verification, so they get the same generate-username auto-login non-India users get
     * automatically — logged in, just without a phone number on the account. They go straight
     * back to the browser and the account is made in the background (BrowserExpressGuestLogin).
     */
    static void skipAuthWithAutoLogin(Fragment fragment, String flow) {
        BrowserExpressAuthJourney.track(PostHogEventKeys.AUTH_REGISTRATION_SKIPPED, flow);

        // Already signed in (a guest adding a phone number): closing only closes. A new guest
        // account would replace the one they have.
        if (!BrowserExpressGuestLogin.hasSession()) {
            // Releases the settings activity's auth lock, so finish() below goes through.
            BrowserExpressGuestLogin.start(flow);
        }

        // Same post-auth navigation the OTP path uses: bring the browser forward and finish the
        // auth settings screen.
        Context appContext = ContextUtils.getApplicationContext();
        Activity activity = fragment != null ? fragment.getActivity() : null;
        Context launchContext = activity != null ? activity : appContext;
        Intent intent = new Intent(launchContext, ChromeTabbedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        intent.setAction(Intent.ACTION_VIEW);
        launchContext.startActivity(intent);
        if (activity != null) {
            activity.finish();
        }
    }

    /**
     * The UGC sign-up page's window: edge to edge with the keyboard resizing nothing, so the page
     * can follow the keyboard frame by frame and keep clear of the bars itself. No app bar, and
     * clear bars with light icons over the page's dark background.
     */
    static void applySignupWindowChrome(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        int softInput = window.getAttributes().softInputMode;
        int resize = (softInput & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (resize != softInput) window.setSoftInputMode(resize);
        UiUtils.setStatusBarColor(window, Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        View decor = window.getDecorView();
        UiUtils.setStatusBarIconColor(decor.getRootView(), false);
        UiUtils.setNavigationBarIconColor(decor.getRootView(), false);

        View appBar = activity.findViewById(R.id.app_bar_layout);
        if (appBar != null) appBar.setVisibility(View.GONE);
    }

    static void applyAuthPageChrome(Activity activity) {
        if (activity == null) return;
        View actionBar = applyOwnNavigationChrome(
                activity, activity.getColor(R.color.browser_express_signin_background));
        if (actionBar instanceof Toolbar) {
            Toolbar toolbar = (Toolbar) actionBar;
            applyAuthToolbarColors(toolbar);
            toolbar.post(() -> applyAuthToolbarColors(toolbar));
        }
    }

    /**
     * For a page that draws its own top bar (the auth screens, the Profile): hides the settings
     * toolbar and colours the status bar area {@code backgroundColor}. Returns the hidden toolbar.
     */
    static @Nullable View applyOwnNavigationChrome(Activity activity, int backgroundColor) {
        // The page colour, so the status bar blends into the page.
        Window window = activity.getWindow();
        if (window != null) {
            UiUtils.setStatusBarColor(window, backgroundColor);
            UiUtils.setStatusBarIconColor(
                    window.getDecorView().getRootView(),
                    !ColorUtils.shouldUseLightForegroundOnBackground(backgroundColor));
        }

        // The AppBarLayout is the view carrying android:fitsSystemWindows in
        // settings_activity.xml, so it is what holds the fragment clear of the status bar — the
        // content below it is positioned by appbar_scrolling_view_behavior against its bottom
        // edge. Hiding it therefore did not just remove the toolbar: it dropped the whole auth
        // screen under the status bar, where the system window takes the touches. That is why
        // the close button in the top corner would not respond on a phone with a tall status bar
        // (a Galaxy S24) while it still worked on one with a 24dp bar, where enough of the
        // button's 36dp poked out below to catch a tap.
        //
        // Hide the toolbar inside the bar instead. With its only child gone the AppBarLayout
        // measures to exactly the status bar inset, so it keeps doing its spacing job while
        // showing nothing but the colour the auth gradient starts with.
        View appBar = activity.findViewById(R.id.app_bar_layout);
        if (appBar != null) {
            appBar.setVisibility(View.VISIBLE);
            appBar.setBackgroundColor(backgroundColor);
            // liftOnScroll animates elevation through a state list animator; left alone it would
            // draw a shadow across the top of the auth screen as soon as the page scrolled.
            appBar.setStateListAnimator(null);
            appBar.setElevation(0f);
        }

        View actionBar = activity.findViewById(R.id.action_bar);
        if (actionBar != null) {
            actionBar.setVisibility(View.GONE);
            actionBar.setBackgroundColor(backgroundColor);
        }
        return actionBar;
    }

    static void restoreSettingsPageChrome(Activity activity) {
        if (activity == null) return;

        View appBar = activity.findViewById(R.id.app_bar_layout);
        if (appBar != null) {
            appBar.setVisibility(View.VISIBLE);
        }

        View actionBar = activity.findViewById(R.id.action_bar);
        if (actionBar != null) {
            actionBar.setVisibility(View.VISIBLE);
        }
    }

    static void applyCompactText(Context context, View root) {
        if (context == null || root == null) return;

        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            CharSequence text = textView.getText();
            if (isAuthBrandText(context, text)) return;
            if (textView.getId() == R.id.browser_express_blazingly_fast_title) {
                textView.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        spToPx(context, BLAZINGLY_FAST_TITLE_TEXT_SIZE_SP));
                return;
            }
            if (textView.getId() == R.id.browser_express_blazingly_fast_subtitle) {
                textView.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        spToPx(context, BLAZINGLY_FAST_SUBTITLE_TEXT_SIZE_SP));
                return;
            }

            boolean isHeroText = isAuthHeroText(context, text);
            float textReductionPx = spToPx(
                    context,
                    isHeroText ? AUTH_HERO_TEXT_REDUCTION_SP : AUTH_TEXT_REDUCTION_SP);
            float compactTextSizePx = isHeroText
                    ? textView.getTextSize() * AUTH_HERO_TEXT_SCALE - textReductionPx
                    : Math.max(
                            spToPx(context, MIN_RUNTIME_TEXT_SIZE_SP),
                            textView.getTextSize() - textReductionPx);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, compactTextSizePx);
        }

        if (!(root instanceof ViewGroup)) return;
        ViewGroup viewGroup = (ViewGroup) root;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            applyCompactText(context, viewGroup.getChildAt(i));
        }
    }

    private static void applyAuthToolbarColors(Toolbar toolbar) {
        int iconColor = toolbar.getContext().getColor(R.color.browser_express_icon_tint);
        toolbar.setTitleTextColor(iconColor);
        toolbar.setSubtitleTextColor(iconColor);
        tintDrawable(toolbar.getNavigationIcon(), iconColor);
        tintDrawable(toolbar.getOverflowIcon(), iconColor);
        tintMenu(toolbar.getMenu(), iconColor);
    }

    private static void tintMenu(Menu menu, int color) {
        if (menu == null) return;

        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            tintDrawable(item.getIcon(), color);
        }
    }

    private static void tintDrawable(Drawable drawable, int color) {
        if (drawable == null) return;
        drawable.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    /**
     * Remembers the name typed on the details step, so the confirmation after the code is
     * verified can greet the user by it.
     */
    static void saveEnteredName(String name) {
        getSigninPrefs().edit().putString(PREF_ENTERED_NAME, name).apply();
    }

    static String getEnteredName() {
        return getSigninPrefs().getString(PREF_ENTERED_NAME, null);
    }

    private static SharedPreferences getSigninPrefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_PHONE, 0);
    }

    /**
     * Masks all but the last four digits, grouped in fives: "+919000003210" becomes
     * "+91 ••••• •3210".
     */
    static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return "";
        String prefix = phoneNumber.startsWith(INDIA_COUNTRY_CODE) ? INDIA_COUNTRY_CODE + " " : "+";
        String national = phoneNumber.startsWith(INDIA_COUNTRY_CODE)
                ? phoneNumber.substring(INDIA_COUNTRY_CODE.length())
                : phoneNumber;
        String digits = national.replaceAll("[^0-9]", "");
        StringBuilder masked = new StringBuilder(prefix);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 5 == 0) masked.append(' ');
            masked.append(i < digits.length() - 4 ? '\u2022' : digits.charAt(i));
        }
        return masked.toString();
    }

    private static float spToPx(Context context, float sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
    }

    private static boolean isAuthBrandText(Context context, CharSequence text) {
        return text != null && context.getString(R.string.browser_express_brand_word)
                .contentEquals(text);
    }

    private static boolean isAuthHeroText(Context context, CharSequence text) {
        if (text == null) return false;
        return context.getString(R.string.browser_express_hero_the).contentEquals(text)
                || context.getString(R.string.browser_express_hero_eyebrow).contentEquals(text)
                || context.getString(R.string.browser_express_login_desc).contentEquals(text)
                || context.getString(R.string.browser_express_signup_desc).contentEquals(text);
    }
}
