/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_signup;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.json.JSONObject;

import org.chromium.chrome.R;

import java.nio.charset.StandardCharsets;

/**
 * "You're @chai_ninja_284 for now" (ugc-signup-handoff, src/components/GuestToast.tsx): the note a
 * guest sees once their account exists. It fades in 450 ms after it is asked for, sits above the
 * bottom bar and starts fading out at 3.6 s. It never takes a touch.
 */
public final class UgcGuestToast {
    private static final long IN_DELAY_MS = 450;
    private static final long HIDE_AT_MS = 3600;
    private static final long FADE_MS = 240;
    private static final float RISE_DP = 8;
    private static final float BOTTOM_DP = 100;

    private UgcGuestToast() {}

    /** The username in a session's access token (a JWT), or null when it has none. */
    public static @Nullable String usernameFromAccessToken(@Nullable String accessToken) {
        if (accessToken == null) return null;
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] payload =
                    Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String username = new JSONObject(new String(payload, StandardCharsets.UTF_8))
                    .optString("username", "");
            return username.isEmpty() ? null : username;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shows the toast over {@code activity} for {@code username} (without the "@"). */
    public static void show(Activity activity, String username) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        float density = activity.getResources().getDisplayMetrics().density;

        String handle = "@" + username;
        String text = activity.getString(R.string.browser_express_ugc_guest_toast, handle);
        SpannableStringBuilder styled = new SpannableStringBuilder(text);
        int at = text.indexOf(handle);
        if (at >= 0) {
            int end = at + handle.length();
            styled.setSpan(new ForegroundColorSpan(
                                   activity.getColor(R.color.browser_express_ugc_signup_ink)),
                    at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Typeface semibold = loadFont(activity, R.font.browser_express_ugc_text_semibold);
            if (semibold != null) {
                styled.setSpan(new TypefaceSpan(semibold), at, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        TextView toast = new TextView(activity);
        toast.setText(styled);
        toast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toast.setTextColor(activity.getColor(R.color.browser_express_ugc_signup_pill_text));
        Typeface regular = loadFont(activity, R.font.browser_express_ugc_text_regular);
        if (regular != null) toast.setTypeface(regular);
        toast.setBackgroundResource(R.drawable.browser_express_ugc_guest_toast_bg);
        toast.setPadding(Math.round(12 * density), Math.round(7 * density),
                Math.round(12 * density), Math.round(7 * density));
        toast.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = Math.round(BOTTOM_DP * density);
        toast.setAlpha(0f);
        toast.setTranslationY(RISE_DP * density);
        content.addView(toast, params);

        toast.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(IN_DELAY_MS)
                .setDuration(FADE_MS)
                .withEndAction(() -> toast.animate()
                        .alpha(0f)
                        .translationY(RISE_DP * density)
                        .setStartDelay(HIDE_AT_MS - IN_DELAY_MS - FADE_MS)
                        .setDuration(FADE_MS)
                        .withEndAction(() -> content.removeView(toast))
                        .start())
                .start();
    }

    private static @Nullable Typeface loadFont(Activity activity, int fontRes) {
        try {
            return ResourcesCompat.getFont(activity, fontRes);
        } catch (android.content.res.Resources.NotFoundException e) {
            return null;
        }
    }
}
