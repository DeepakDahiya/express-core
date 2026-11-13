/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.chrome.browser.util.TabUtils;
import org.chromium.chrome.browser.youtube_premium.YouTubePremiumBottomSheetFragment;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Arrays;

/**
 * The reader's own Profile (design handoff "Profile page"): who they are, their three points —
 * Karma ("you give"), Taste ("you spot it first") and Cred ("you create") — and a short menu.
 *
 * <p>When they come back and a point has grown since the page last showed it, that card counts up
 * from the old value with a "+N" chip beside it, one card after another, and the page then rests
 * exactly as it would have without the animation. What was shown goes back to the server
 * ({@link BrowserExpressProfileApi#markScoresSeen}) as soon as the numbers arrive, so leaving
 * mid-animation still counts as seen. The first visit, and any visit with nothing new, just shows
 * the numbers; with animations off the chips only blink in and out.
 */
public class BrowserExpressProfilePreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = "ExpressProfile";
    private static final String YOUTUBE_SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&amp;uilel=3&amp;passive=true"
                    + "&amp;continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin"
                    + "%3Dtrue%26app%3Dm%26hl%3Den-GB%26next%3D%252F&amp;hl=en-GB";

    // The return visit, in ms (tokens.json → motion.returnVisit). Card i starts at
    // FIRST_CARD + i × STAGGER whether or not it has anything to count.
    private static final float FIRST_CARD_MS = 300f;
    private static final float STAGGER_MS = 260f;
    private static final float CHIP_IN_MS = 250f;
    private static final float COUNT_DELAY_MS = 120f;
    private static final float COUNT_MS = 750f;
    private static final float CHIP_OUT_START_MS = 2600f;
    private static final float CHIP_OUT_STAGGER_MS = 120f;
    private static final float CHIP_OUT_MS = 400f;
    private static final long TOTAL_MS = 3240;
    /** With animations off the chips show for this long. */
    private static final long REDUCED_CHIP_MS = 1500;

    // "Profile updated" after Edit profile saves (design handoff "Edit profile" §7): in over the
    // first 12% (rising 8dp), held to 85%, then out (sinking 4dp).
    private static final long UPDATED_TOAST_MS = 2400;
    private static final float UPDATED_TOAST_IN = 0.12f;
    private static final float UPDATED_TOAST_OUT = 0.85f;
    private static final float UPDATED_TOAST_RISE_DP = 8f;
    private static final float UPDATED_TOAST_SINK_DP = 4f;
    /** A save older than this was not the reason the page is showing. */
    private static final long UPDATED_TOAST_FRESH_MS = 10000;

    /** The design's ease-out, per stretch of the toast. */
    private static final Interpolator DECELERATE = new DecelerateInterpolator();

    /** When Edit profile last saved (elapsedRealtime), until the page has said so. */
    private static long sProfileUpdatedAtMs;

    /** Per point, in BrowserExpressProfileApi.SCORE_KEYS order: its card and how it looks. */
    private static final int[][] STATS = {
        {R.id.profile_karma, R.drawable.ic_browser_express_profile_karma,
            R.string.browser_express_profile_karma_eyebrow,
            R.string.browser_express_profile_karma_label,
            R.string.browser_express_profile_karma_description,
            R.color.browser_express_profile_karma_accent,
            R.color.browser_express_profile_karma_tint,
            R.color.browser_express_profile_karma_wash},
        {R.id.profile_taste, R.drawable.ic_browser_express_profile_taste,
            R.string.browser_express_profile_taste_eyebrow,
            R.string.browser_express_profile_taste_label,
            R.string.browser_express_profile_taste_description,
            R.color.browser_express_profile_taste_accent,
            R.color.browser_express_profile_taste_tint,
            R.color.browser_express_profile_taste_wash},
        {R.id.profile_cred, R.drawable.ic_browser_express_profile_cred,
            R.string.browser_express_profile_cred_eyebrow,
            R.string.browser_express_profile_cred_label,
            R.string.browser_express_profile_cred_description,
            R.color.browser_express_profile_cred_accent,
            R.color.browser_express_profile_cred_tint,
            R.color.browser_express_profile_cred_wash},
    };

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();
    private final NumberFormat mNumbers = NumberFormat.getIntegerInstance();
    private StatCard[] mCards = new StatCard[0];
    private ImageView mAvatar;
    private TextView mName;
    private TextView mHandle;
    /** What the cards show once the numbers have arrived; null until then. */
    private @Nullable int[] mShownScores;
    private @Nullable ValueAnimator mReturnVisit;
    private TextView mUpdatedToast;
    private @Nullable ValueAnimator mUpdatedToastAnimator;

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.browser_express_profile_title));
        return inflater.inflate(R.layout.browser_express_profile_settings, container, false);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAvatar = view.findViewById(R.id.profile_avatar);
        mName = view.findViewById(R.id.profile_name);
        mHandle = view.findViewById(R.id.profile_handle);
        mUpdatedToast = view.findViewById(R.id.profile_updated_toast);
        mCards = new StatCard[STATS.length];
        for (int i = 0; i < STATS.length; i++) {
            mCards[i] = new StatCard(view.findViewById(STATS[i][0]), STATS[i]);
        }
        View menu = view.findViewById(R.id.profile_menu);
        menu.setClipToOutline(true);

        view.findViewById(R.id.profile_back).setOnClickListener(
                v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        view.findViewById(R.id.profile_more).setOnClickListener(this::showOverflowMenu);

        BraveActivity activity;
        try {
            activity = BraveActivity.getBraveActivity();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "No browser activity for the profile", e);
            return;
        }
        String accessToken = activity.getAccessToken();
        JSONObject token = decodeToken(accessToken);
        String appVersion = activity.getCurrentAppVersion();

        view.findViewById(R.id.profile_edit)
                .setOnClickListener(v -> activity.openBrowserExpressEditProfileSettings());
        view.findViewById(R.id.profile_menu_logout).setOnClickListener(v -> activity.logout());
        view.findViewById(R.id.profile_menu_youtube).setOnClickListener(v -> {
            track(PostHogEventKeys.YTP_PREMIUM_CLICKED_ON_QUICKLINK, token, appVersion, null);
            TabUtils.openUrlInSameTab(YOUTUBE_SIGN_IN_URL);
            Intent intent = new Intent(requireActivity(), ChromeTabbedActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.setAction(Intent.ACTION_VIEW);
            startActivity(intent);
        });
        view.findViewById(R.id.profile_menu_referral).setOnClickListener(v -> {
            track(PostHogEventKeys.REFERRAL_CLICKED_IN_PROFILE, token, appVersion,
                    token != null ? token.optString("referralCode", "") : "");
            YouTubePremiumBottomSheetFragment.showByUserAction(getParentFragmentManager());
        });

        // Who they are, from the session at once; /v1/user/me refreshes it.
        ProfileAvatar.loadSelf(mAvatar, accessToken);
        if (token != null) bindIdentity(textOf(token, "name"), textOf(token, "username"));
        track(PostHogEventKeys.PROFILE_VIEWED, token, appVersion, null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sProfileUpdatedAtMs != 0) {
            boolean fresh =
                    SystemClock.elapsedRealtime() - sProfileUpdatedAtMs < UPDATED_TOAST_FRESH_MS;
            sProfileUpdatedAtMs = 0;
            if (fresh) showUpdatedToast();
        }
        // Also after Edit, whose changes the page should show on return.
        String accessToken;
        try {
            accessToken = BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return;
        }
        if (TextUtils.isEmpty(accessToken)) return;
        BrowserExpressProfileApi.fetch(accessToken, profile -> {
            if (profile != null && isAdded() && getView() != null) bind(profile, accessToken);
        });
    }

    @Override
    public void onDestroyView() {
        // A count cut short rests at its end; the server already has these numbers as seen.
        if (mReturnVisit != null) mReturnVisit.cancel();
        if (mUpdatedToastAnimator != null) mUpdatedToastAnimator.cancel();
        super.onDestroyView();
    }

    /** Called by Edit profile once it has saved; the Profile says so when it shows again. */
    static void notifyProfileUpdated() {
        sProfileUpdatedAtMs = SystemClock.elapsedRealtime();
    }

    /** "✓ Profile updated" for 2.4s; with animations off it shows and goes without moving. */
    private void showUpdatedToast() {
        if (mUpdatedToastAnimator != null) mUpdatedToastAnimator.cancel();
        float dp = getResources().getDisplayMetrics().density;
        mUpdatedToast.setVisibility(View.VISIBLE);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(UPDATED_TOAST_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            if (t < UPDATED_TOAST_IN) {
                float in = DECELERATE.getInterpolation(t / UPDATED_TOAST_IN);
                mUpdatedToast.setAlpha(in);
                mUpdatedToast.setTranslationY((1f - in) * UPDATED_TOAST_RISE_DP * dp);
            } else if (t < UPDATED_TOAST_OUT) {
                mUpdatedToast.setAlpha(1f);
                mUpdatedToast.setTranslationY(0f);
            } else {
                float out = DECELERATE.getInterpolation(
                        (t - UPDATED_TOAST_OUT) / (1f - UPDATED_TOAST_OUT));
                mUpdatedToast.setAlpha(1f - out);
                mUpdatedToast.setTranslationY(out * UPDATED_TOAST_SINK_DP * dp);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mUpdatedToastAnimator != animation) return;
                mUpdatedToastAnimator = null;
                mUpdatedToast.setVisibility(View.GONE);
            }
        });
        mUpdatedToastAnimator = animator;
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator.start();
        } else {
            mUpdatedToast.setAlpha(1f);
            mUpdatedToast.setTranslationY(0f);
            mUpdatedToast.postDelayed(() -> {
                if (mUpdatedToastAnimator != animator) return;
                mUpdatedToastAnimator = null;
                mUpdatedToast.setVisibility(View.GONE);
            }, UPDATED_TOAST_MS);
        }
    }

    private void bind(BrowserExpressProfileApi.Profile profile, String accessToken) {
        bindIdentity(profile.name, profile.username);
        if (profile.avatarUrl != null) ProfileAvatar.load(mAvatar, profile.avatarUrl, null);

        // The first answer counts up from the last visit; a later one (back from Edit) from
        // what is on screen. With no last visit there is nothing to count from.
        int[] from = mShownScores != null ? mShownScores : profile.scoresSeen;
        int[] to = profile.scores;
        if (!Arrays.equals(profile.scoresSeen, to)) {
            BrowserExpressProfileApi.markScoresSeen(accessToken, to);
        }
        if (mReturnVisit != null) return; // Still counting the first answer.
        mShownScores = to.clone();
        if (from == null || !anyGain(from, to)) {
            for (int i = 0; i < mCards.length; i++) mCards[i].rest(to[i]);
            return;
        }
        playReturnVisit(from, to);
    }

    private void bindIdentity(@Nullable String name, @Nullable String username) {
        if (username != null) {
            mHandle.setText(getString(R.string.browser_express_profile_handle, username));
        }
        mName.setText(name != null ? name : username != null ? username : "");
    }

    private void playReturnVisit(int[] from, int[] to) {
        for (int i = 0; i < mCards.length; i++) mCards[i].prepare(from[i], to[i]);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Reduced motion: the final numbers at once, the chips only while they hold.
            for (int i = 0; i < mCards.length; i++) mCards[i].blinkChip(REDUCED_CHIP_MS);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, TOTAL_MS);
        animator.setDuration(TOTAL_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            for (int i = 0; i < mCards.length; i++) mCards[i].pose(t, i);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mReturnVisit != animation) return;
                mReturnVisit = null;
                for (int i = 0; i < mCards.length; i++) mCards[i].rest(to[i]);
            }
        });
        mReturnVisit = animator;
        animator.start();
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        final int settings = 1;
        menu.getMenu().add(Menu.NONE, settings, Menu.NONE,
                R.string.browser_express_profile_menu_settings);
        try {
            String version = BraveActivity.getBraveActivity().getCurrentAppVersion();
            menu.getMenu()
                    .add(getString(R.string.browser_express_profile_menu_version, version))
                    .setEnabled(false);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            // No version line then.
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != settings) return false;
            SettingsNavigationFactory.createSettingsNavigation().startSettings(requireContext());
            return true;
        });
        menu.show();
    }

    /** One point's card. */
    private final class StatCard {
        private final View mTile;
        private final TextView mGain;
        private final TextView mScore;
        private final View mPlaceholder;
        private final WashDrawable mWash;
        private final float mDp;
        private int mFrom;
        private int mTo;

        StatCard(View card, int[] stat) {
            Context context = card.getContext();
            mDp = context.getResources().getDisplayMetrics().density;
            int accent = ContextCompat.getColor(context, stat[5]);
            int tint = ContextCompat.getColor(context, stat[6]);

            mWash = new WashDrawable(ContextCompat.getColor(context, stat[7]), mDp);
            Drawable shape =
                    AppCompatResources.getDrawable(context, R.drawable.browser_express_profile_card_bg);
            card.setBackground(new LayerDrawable(new Drawable[] {shape, mWash}));
            card.setClipToOutline(true);

            mTile = card.findViewById(R.id.profile_stat_tile);
            mTile.setBackground(rounded(tint, 12 * mDp));
            ImageView icon = card.findViewById(R.id.profile_stat_icon);
            icon.setImageResource(stat[1]);
            icon.setColorFilter(accent);
            TextView eyebrow = card.findViewById(R.id.profile_stat_eyebrow);
            eyebrow.setText(stat[2]);
            eyebrow.setTextColor(accent);
            ((TextView) card.findViewById(R.id.profile_stat_label)).setText(stat[3]);
            ((TextView) card.findViewById(R.id.profile_stat_description)).setText(stat[4]);
            mGain = card.findViewById(R.id.profile_stat_gain);
            mGain.setBackground(rounded(tint, 10 * mDp));
            mGain.setTextColor(accent);
            mScore = card.findViewById(R.id.profile_stat_score);
            mPlaceholder = card.findViewById(R.id.profile_stat_score_placeholder);
        }

        /** At rest: the number, no chip, no pulse. */
        void rest(int score) {
            showScore(score);
            mGain.setVisibility(View.GONE);
            mTile.setScaleX(1f);
            mTile.setScaleY(1f);
            mWash.setPulse(0f);
        }

        void prepare(int from, int to) {
            mFrom = from;
            mTo = to;
            int gain = Math.max(0, to - from);
            mGain.setText(getString(R.string.browser_express_profile_gain, mNumbers.format(gain)));
            showScore(gain > 0 ? from : to);
        }

        /**
         * Card {@code index}'s pose {@code t} ms into the return visit: the chip rises in at its
         * start, the number counts up 120ms later while the tile and wash pulse once, and from
         * 2.6s the chip lifts away. A card with nothing to count keeps its slot and stays still.
         */
        void pose(float t, int index) {
            int gain = mTo - mFrom;
            if (gain <= 0) return;
            float start = FIRST_CARD_MS + STAGGER_MS * index;
            float in = easeOut(ramp(t, start, CHIP_IN_MS));
            float out = easeOut(ramp(t, CHIP_OUT_START_MS + CHIP_OUT_STAGGER_MS * index,
                    CHIP_OUT_MS));
            boolean chipShown = t > start - 1 && out < 1;
            if (chipShown != (mGain.getVisibility() == View.VISIBLE)) {
                mGain.setVisibility(chipShown ? View.VISIBLE : View.GONE);
            }
            mGain.setAlpha(in * (1 - out));
            float chipScale = out > 0 ? 1 - 0.1f * out : 0.85f + 0.15f * in;
            mGain.setScaleX(chipScale);
            mGain.setScaleY(chipScale);
            mGain.setTranslationY((out > 0 ? -out : 1 - in) * 6 * mDp);

            float count = ramp(t, start + COUNT_DELAY_MS, COUNT_MS);
            mScore.setText(mNumbers.format(Math.round(mFrom + gain * easeOut(count))));
            float pulse = (float) Math.sin(Math.PI * count);
            mTile.setScaleX(1 + 0.08f * pulse);
            mTile.setScaleY(1 + 0.08f * pulse);
            mWash.setPulse(pulse);
        }

        void blinkChip(long holdMs) {
            if (mTo - mFrom <= 0) return;
            mGain.setAlpha(1f);
            mGain.setVisibility(View.VISIBLE);
            mGain.postDelayed(() -> mGain.setVisibility(View.GONE), holdMs);
        }

        private void showScore(int score) {
            mScore.setText(mNumbers.format(score));
            mScore.setVisibility(View.VISIBLE);
            mPlaceholder.setVisibility(View.GONE);
        }
    }

    /**
     * A card's decorative corner wash: a 240dp circle of its point's colour fading out by 65%
     * of the way to its farthest corner, centred 40dp in from the card's right edge and 10dp
     * below its top, at 70% at rest. A pulse grows it 12% and brings it to full strength.
     */
    private static final class WashDrawable extends Drawable {
        private static final float RADIUS_DP = 120f;
        /** CSS's 65%, of the farthest-corner distance of the 240dp box (120√2), over 120. */
        private static final float FADE_END = 0.65f * 1.41421f;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mMatrix = new Matrix();
        private final float mDp;
        private float mPulse;

        WashDrawable(int color, float dp) {
            mDp = dp;
            mPaint.setShader(new RadialGradient(0, 0, RADIUS_DP * FADE_END * dp,
                    color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }

        /** 0 at rest, 1 at the pulse's peak. */
        void setPulse(float pulse) {
            if (pulse == mPulse) return;
            mPulse = pulse;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.right - 40 * mDp;
            float cy = bounds.top + 10 * mDp;
            float scale = 1 + 0.12f * mPulse;
            mMatrix.setScale(scale, scale);
            mMatrix.postTranslate(cx, cy);
            mPaint.getShader().setLocalMatrix(mMatrix);
            mPaint.setAlpha(Math.round(255 * (0.7f + 0.3f * mPulse)));
            canvas.drawCircle(cx, cy, RADIUS_DP * mDp * scale, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private static boolean anyGain(int[] from, int[] to) {
        for (int i = 0; i < to.length; i++) {
            if (to[i] > from[i]) return true;
        }
        return false;
    }

    private static float ramp(float t, float start, float length) {
        return Math.max(0f, Math.min(1f, (t - start) / length));
    }

    private static float easeOut(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }

    private static @Nullable String textOf(JSONObject json, String key) {
        String value = json.isNull(key) ? null : json.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    /** The session's claims (id, name, username, referral code); null when it can't be read. */
    private static @Nullable JSONObject decodeToken(@Nullable String accessToken) {
        if (TextUtils.isEmpty(accessToken)) return null;
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] payload = Base64.decode(
                    parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException e) {
            Log.e(TAG, "Unreadable session token", e);
            return null;
        }
    }

    private static void track(String event, @Nullable JSONObject token, String appVersion,
            @Nullable String referralCode) {
        if (token == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("app_version", appVersion);
            if (referralCode != null) payload.put("referral_code", referralCode);
            new PostHogUtil.PostHogWorkerTask(event, token.getString("_id"), payload)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (JSONException e) {
            Log.e(TAG, "PostHog payload", e);
        }
    }
}
