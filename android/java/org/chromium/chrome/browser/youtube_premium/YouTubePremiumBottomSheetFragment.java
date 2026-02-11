/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.referral.ReferralCodeUtil;
import org.chromium.chrome.browser.youtube_premium.YouTubePremiumAccessUtil.PremiumAccessCallback;
import org.chromium.chrome.browser.youtube_premium.YouTubePremiumAccessUtil.PremiumAccessData;

import java.util.Locale;

/**
 * Bottomsheet dialog that shows YouTube premium access status and referral information.
 */
public class YouTubePremiumBottomSheetFragment extends BottomSheetDialogFragment {
    private static final String TAG = "YTPremiumBottomSheet";
    private static final String ARG_IS_PERMANENT = "is_permanent";

    // Cooldown period - show bottomsheet once per 30 seconds (for testing, change to 60*60*1000 for 1 hour in production)
    private static final long COOLDOWN_MS = 30 * 1000; // 30 seconds for testing

    private TextView mTimerDays;
    private TextView mTimerHours;
    private TextView mTimerMinutes;
    private TextView mTimerSeconds;
    private TextView mPremiumMessage;
    private TextView mReferralCount;
    private Button mReferButton;
    private View mCloseButton;
    private ProgressBar mLoadingIndicator;

    private boolean mIsBlocked = false;
    private boolean mIsPermanent = false;
    private String mReferralCode = null;

    public static YouTubePremiumBottomSheetFragment newInstance() {
        return new YouTubePremiumBottomSheetFragment();
    }

    public static YouTubePremiumBottomSheetFragment newInstance(boolean isPermanent) {
        YouTubePremiumBottomSheetFragment fragment = new YouTubePremiumBottomSheetFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_PERMANENT, isPermanent);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Check if the bottomsheet should be shown based on cooldown period.
     */
    public static boolean shouldShowBottomSheet() {
        long lastShown = ChromeSharedPreferences.getInstance()
                .readLong(BravePreferenceKeys.YOUTUBE_PREMIUM_BOTTOMSHEET_LAST_SHOWN, 0);
        long now = System.currentTimeMillis();
        return (now - lastShown) > COOLDOWN_MS;
    }

    /**
     * Show the bottomsheet if cooldown has passed.
     */
    public static void showIfNeeded(FragmentManager fragmentManager) {
        if (!shouldShowBottomSheet()) return;
        try {
            if (fragmentManager.isStateSaved()) return;
            if (fragmentManager.findFragmentByTag(TAG) != null) return;
            YouTubePremiumBottomSheetFragment fragment = newInstance(false);
            fragment.show(fragmentManager, TAG);

            // Update last shown timestamp
            ChromeSharedPreferences.getInstance()
                    .writeLong(BravePreferenceKeys.YOUTUBE_PREMIUM_BOTTOMSHEET_LAST_SHOWN,
                               System.currentTimeMillis());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot show bottom sheet after onSaveInstanceState: " + e.getMessage());
        }
    }

    /**
     * Show the bottomsheet permanently (until closed by user).
     * Bypasses cooldown check.
     */
    public static void showPermanent(FragmentManager fragmentManager) {
        try {
            if (fragmentManager.isStateSaved()) return;
            if (fragmentManager.findFragmentByTag(TAG) != null) return;
            YouTubePremiumBottomSheetFragment fragment = newInstance(true);
            fragment.show(fragmentManager, TAG);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot show bottom sheet after onSaveInstanceState: " + e.getMessage());
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mIsPermanent = getArguments().getBoolean(ARG_IS_PERMANENT, false);
        }
        setStyle(STYLE_NORMAL, R.style.AppSetDefaultBottomSheetDialogTheme);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_youtube_premium_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set up expanded state
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog != null) {
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        // Initialize views
        mTimerDays = view.findViewById(R.id.timer_days);
        mTimerHours = view.findViewById(R.id.timer_hours);
        mTimerMinutes = view.findViewById(R.id.timer_minutes);
        mTimerSeconds = view.findViewById(R.id.timer_seconds);
        mPremiumMessage = view.findViewById(R.id.premium_message);
        mReferralCount = view.findViewById(R.id.referral_count);
        mReferButton = view.findViewById(R.id.btn_refer);
        mCloseButton = view.findViewById(R.id.close_button);
        mLoadingIndicator = view.findViewById(R.id.loading_indicator);

        // Configure UI based on mode
        if (mIsPermanent) {
            mCloseButton.setVisibility(View.VISIBLE);
            mCloseButton.setOnClickListener(v -> dismiss());
        } else {
            mCloseButton.setVisibility(View.GONE);
        }

        // Set up refer button
        mReferButton.setOnClickListener(v -> shareReferralLink());

        // Fetch premium access data
        fetchPremiumAccessData();
    }

    private void fetchPremiumAccessData() {
        mLoadingIndicator.setVisibility(View.VISIBLE);

        String accessToken = null;
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            accessToken = activity.getAccessToken();
            // Try to get referralCode from JWT first (preferred per doc)
            mReferralCode = activity.getReferralCodeFromToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Could not get BraveActivity: " + e.getMessage());
        }

        YouTubePremiumAccessUtil.GetPremiumAccessWorkerTask workerTask =
                new YouTubePremiumAccessUtil.GetPremiumAccessWorkerTask(accessToken,
                        new PremiumAccessCallback() {
                    @Override
                    public void onSuccess(PremiumAccessData data) {
                        if (getActivity() == null || !isAdded()) return;

                        mLoadingIndicator.setVisibility(View.GONE);
                        updateUI(data);
                        mIsBlocked = data.isBlocked;

                        // Use referralCode from access response if not in JWT
                        if (mReferralCode == null && data.referralCode != null) {
                            mReferralCode = data.referralCode;
                        }

                        // Cache data
                        ChromeSharedPreferences.getInstance()
                                .writeInt(BravePreferenceKeys.YOUTUBE_PREMIUM_ACCESS_DAYS,
                                          data.accessDaysRemaining);
                        ChromeSharedPreferences.getInstance()
                                .writeBoolean(BravePreferenceKeys.YOUTUBE_PREMIUM_USER_BLOCKED,
                                              data.isBlocked);

                        if (mIsBlocked || mIsPermanent) {
                            // Make dialog non-cancelable for blocked/permanent
                            setCancelable(false);
                            if (getDialog() != null) {
                                getDialog().setCanceledOnTouchOutside(false);
                            }
                        } else {
                            // Dismissible by swipe or tapping outside
                            setCancelable(true);
                            if (getDialog() != null) {
                                getDialog().setCanceledOnTouchOutside(true);
                            }
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (getActivity() == null || !isAdded()) return;

                        Log.e(TAG, "Failed to fetch premium data: " + error);
                        mLoadingIndicator.setVisibility(View.GONE);

                        // Use cached data or defaults
                        int cachedDays = ChromeSharedPreferences.getInstance()
                                .readInt(BravePreferenceKeys.YOUTUBE_PREMIUM_ACCESS_DAYS, 7);
                        boolean cachedBlocked = ChromeSharedPreferences.getInstance()
                                .readBoolean(BravePreferenceKeys.YOUTUBE_PREMIUM_USER_BLOCKED, false);

                        PremiumAccessData fallbackData = new PremiumAccessData(
                                0, cachedDays,
                                getString(R.string.youtube_premium_message_default),
                                cachedBlocked, null);
                        updateUI(fallbackData);

                        if (cachedBlocked || mIsPermanent) {
                            setCancelable(false);
                        } else {
                            setCancelable(true);
                            if (getDialog() != null) {
                                getDialog().setCanceledOnTouchOutside(true);
                            }
                        }
                    }
                });
        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void updateUI(PremiumAccessData data) {
        // Timer: display DD:00:00:00 (only days from backend, rest are 00)
        int days = data.accessDaysRemaining;
        mTimerDays.setText(String.format(Locale.US, "%02d", days));
        mTimerHours.setText("00");
        mTimerMinutes.setText("00");
        mTimerSeconds.setText("00");

        // Referral count: "{N} referred"
        mReferralCount.setText(getString(R.string.youtube_premium_referred, data.referralCount));

        // Backend message
        mPremiumMessage.setText(data.message);
    }

    private void shareReferralLink() {
        if (mReferralCode != null) {
            doShareReferralLink(mReferralCode);
            return;
        }

        // Fallback: fetch referralCode from GET /v1/referral/code endpoint
        String accessToken = null;
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            accessToken = activity.getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Could not get BraveActivity: " + e.getMessage());
            return;
        }

        ReferralCodeUtil.GetReferralCodeWorkerTask task =
                new ReferralCodeUtil.GetReferralCodeWorkerTask(accessToken,
                        new ReferralCodeUtil.ReferralCodeCallback() {
                    @Override
                    public void onSuccess(String referralCode) {
                        mReferralCode = referralCode;
                        doShareReferralLink(referralCode);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to fetch referral code: " + error);
                    }
                });
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void doShareReferralLink(String referralCode) {
        // Build Play Store URL with referrer parameter so Google's Install Referrer API
        // can capture the referral code on fresh installs.
        // Format: referrer=utm_source=referral&referral_code=<code>
        String encodedReferrer = "utm_source%3Dreferral%26referral_code%3D" + referralCode;
        String referralLink = "https://play.google.com/store/apps/details?id="
                + requireActivity().getPackageName()
                + "&referrer=" + encodedReferrer;

        String shareText = getString(R.string.youtube_premium_share_text, referralLink);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);

        startActivity(Intent.createChooser(shareIntent,
                getString(R.string.youtube_premium_share_title)));
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
    }
}
