/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

import androidx.appcompat.widget.SwitchCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;

import androidx.fragment.app.FragmentManager;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.IntentUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.Channel;
import org.chromium.brave_news.mojom.Publisher;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_news.BraveNewsControllerFactory;
import org.chromium.chrome.browser.brave_news.BraveNewsUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.crypto_wallet.util.Utils;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.night_mode.GlobalNightModeStateProviderHolder;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import androidx.preference.Preference;
import androidx.annotation.NonNull;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserExpressOtpVerifyPreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = "BrowserExpressOtp";
    private static final int OTP_LENGTH = 4;
    private static final int DEFAULT_RESEND_COOLDOWN_SECONDS = 60;
    // Delay before an auto-read OTP is submitted, giving the screen time to fully
    // settle after returning from the SMS-consent dialog before we verify+navigate.
    private static final int AUTO_SUBMIT_DELAY_MS = 700;
    private static final int RESUME_SETTLE_DELAY_MS = 350;
    // First standalone 4-digit group in the SMS; the trailing app-hash line is
    // at the end of the message, so the OTP wins the first match.
    private static final Pattern OTP_PATTERN = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");

    private View mBtnVerify;
    private TextView mBtnVerifyText;
    private TextView mBtnResendOtp;
    private TextView mBtnChangePhone;
    private EditText mOtpEditText;
    private View mOtpBoxesContainer;
    private TextView[] mOtpDigitViews;
    private TextView mPhoneTextView;
    private TextView mErrorTextView;
    private View mBtnClose;
    private View mBtnSkip;
    private View mVerifyContent;
    private View mSuccessContent;
    private boolean mSignedIn;
    private String mPhoneNumber;
    private boolean mOtpAutoSubmitted;
    private boolean mOtpVerifyInFlight;
    private boolean mPendingAutoSubmit;
    private final Handler mCountdownHandler = new Handler(Looper.getMainLooper());
    private Runnable mCountdownRunnable;
    private long mResendAvailableAtElapsedMs;
    private BroadcastReceiver mSmsRetrievedReceiver;
    private boolean mSmsReceiverRegistered;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set("");
        return inflater.inflate(R.layout.browser_express_otp_verify_settings, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        View view = getView();
        if (view != null) {
            mBtnVerify = view.findViewById(R.id.btn_verify);
            mBtnVerifyText = (TextView) view.findViewById(R.id.btn_verify_text);
            mBtnResendOtp = (TextView) view.findViewById(R.id.btn_resend_otp);
            mBtnChangePhone = (TextView) view.findViewById(R.id.btn_change_phone);
            mOtpEditText = (EditText) view.findViewById(R.id.browser_express_otp);
            mOtpBoxesContainer = view.findViewById(R.id.otp_boxes_container);
            mPhoneTextView = (TextView) view.findViewById(R.id.browser_express_otp_phone);
            mOtpDigitViews = new TextView[] {
                    (TextView) view.findViewById(R.id.otp_digit_1),
                    (TextView) view.findViewById(R.id.otp_digit_2),
                    (TextView) view.findViewById(R.id.otp_digit_3),
                    (TextView) view.findViewById(R.id.otp_digit_4)
            };
            mErrorTextView = (TextView) view.findViewById(R.id.login_error_message);
            mVerifyContent = view.findViewById(R.id.browser_express_signin_verify_content);
            mSuccessContent = view.findViewById(R.id.browser_express_signin_success);
            view.findViewById(R.id.btn_start_browsing).setOnClickListener(v -> openBrowser());
            // Close and "I'll do this later" skip sign-in, like on the details step; once the
            // number is verified, close just returns to the browser.
            mBtnClose = view.findViewById(R.id.btn_close);
            mBtnClose.setOnClickListener(v -> {
                if (mSignedIn) {
                    openBrowser();
                } else {
                    skipVerification();
                }
            });
            mBtnSkip = view.findViewById(R.id.btn_skip);
            mBtnSkip.setOnClickListener(v -> skipVerification());

            updatePhoneNumberText();
            setupOtpInput();
            onClickViews();
            mOtpEditText.postDelayed(this::focusOtpInput, 250);
            // An OTP was just sent to reach this screen, so start on cooldown.
            startResendCountdown(DEFAULT_RESEND_COOLDOWN_SECONDS);
            BrowserExpressAuthJourney.track(
                    PostHogEventKeys.OTP_VERIFY_SCREEN_VIEWED, getOtpFlow());
            startSmsOtpRetriever();
        }
    }

    @Override
    public void onDestroy() {
        stopSmsOtpRetriever();
        super.onDestroy();
    }

    /**
     * Starts listening for the OTP SMS via the GMS SMS Retriever API. The SMS
     * carries this app's 11-char hash, so GMS hands the full message to us in a
     * broadcast — no SMS permission and no user consent dialog involved.
     */
    private void startSmsOtpRetriever() {
        registerSmsRetrievedReceiver();
        startSmsRetrieverTask();
    }

    private void registerSmsRetrievedReceiver() {
        if (mSmsReceiverRegistered) return;
        if (mSmsRetrievedReceiver == null) {
            mSmsRetrievedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) return;
                    if (intent.getExtras() == null) return;
                    Status status =
                            IntentUtils.safeGetParcelableExtra(intent, SmsRetriever.EXTRA_STATUS);
                    if (status == null) return;
                    switch (status.getStatusCode()) {
                        case CommonStatusCodes.SUCCESS:
                            // Only app-hash retriever broadcasts carry the message
                            // itself; a WebOTP user-consent broadcast (from a web
                            // page) has no EXTRA_SMS_MESSAGE — leave those alone.
                            String message =
                                    intent.getExtras().getString(SmsRetriever.EXTRA_SMS_MESSAGE);
                            if (message != null) handleRetrievedSms(message);
                            break;
                        case CommonStatusCodes.TIMEOUT:
                            // GMS stops listening ~5 minutes after start; re-arm so a
                            // late SMS is still caught while this screen is up.
                            Log.e(TAG, "SMS retriever timed out; restarting");
                            startSmsRetrieverTask();
                            break;
                    }
                }
            };
        }
        IntentFilter filter = new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION);
        // SEND_PERMISSION restricts the broadcast sender to Play services, so
        // other apps can't spoof an OTP.
        ContextUtils.registerExportedBroadcastReceiver(
                ContextUtils.getApplicationContext(),
                mSmsRetrievedReceiver,
                filter,
                SmsRetriever.SEND_PERMISSION);
        mSmsReceiverRegistered = true;
    }

    private void startSmsRetrieverTask() {
        SmsRetriever.getClient(ContextUtils.getApplicationContext())
                .startSmsRetriever()
                .addOnSuccessListener(unused -> Log.e(TAG, "SMS retriever listening"))
                .addOnFailureListener(e -> Log.e(TAG, "SMS retriever failed to start: " + e));
    }

    private void stopSmsOtpRetriever() {
        if (!mSmsReceiverRegistered) return;
        mSmsReceiverRegistered = false;
        try {
            ContextUtils.getApplicationContext().unregisterReceiver(mSmsRetrievedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleRetrievedSms(String message) {
        Matcher matcher = OTP_PATTERN.matcher(message);
        if (!matcher.find()) {
            Log.e(TAG, "SMS retrieved but no " + OTP_LENGTH + "-digit OTP found");
            return;
        }
        String otp = matcher.group(1);
        Log.e(TAG, "OTP auto-read from SMS");
        BrowserExpressAuthJourney.track(
                PostHogEventKeys.OTP_AUTOFILLED_FROM_SMS, getOtpFlow());
        if (mOtpEditText != null) {
            // Setting the text runs the same TextWatcher path as typing: the
            // digit boxes update, then maybeAutoSubmitOtp() verifies with the
            // backend (deferred to onResume if the app is backgrounded).
            mOtpEditText.setText(otp);
            mOtpEditText.setSelection(otp.length());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        resumeResendCountdown();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Fire any auto-submit that was deferred because the OTP arrived while the
        // fragment was not yet resumed. Now that we're resumed, this runs the exact
        // same submit+verify+navigate path a manual button tap does. Posted so it
        // runs after the resume transition settles.
        if (mPendingAutoSubmit && mOtpEditText != null) {
            Log.e(TAG, "OTP auto-submit resuming deferred submit");
            mOtpEditText.postDelayed(() -> fireAutoSubmit("resume"), RESUME_SETTLE_DELAY_MS);
        }
    }

    @Override
    public void onStop() {
        pauseResendCountdown();
        super.onStop();
    }

    private void updatePhoneNumberText() {
        mPhoneNumber = getStoredPhoneNumber();
        mPhoneTextView.setText(getSafeString(
                R.string.browser_express_signin_verify_description,
                OTP_LENGTH,
                BrowserExpressAuthUiUtil.maskPhoneNumber(mPhoneNumber)));
    }

    private String getStoredPhoneNumber() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_PHONE, 0)
                .getString(BraveActivity.BROWSER_EXPRESS_PHONE, null);
    }

    private void setupOtpInput() {
        View.OnClickListener focusOtpListener = view -> focusOtpInput();
        mOtpBoxesContainer.setOnClickListener(focusOtpListener);
        for (TextView digitView : mOtpDigitViews) {
            digitView.setOnClickListener(focusOtpListener);
        }
        mOtpEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                String otp = editable.toString();
                updateOtpBoxes(otp);
                maybeAutoSubmitOtp(otp);
            }
        });
        updateOtpBoxes("");
    }

    private void focusOtpInput() {
        mOtpEditText.requestFocus();
        Context context = getContext();
        if (context == null) return;
        InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(mOtpEditText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void updateOtpBoxes(String otp) {
        Context context = getContext();
        if (context == null) return;
        int length = otp != null ? otp.length() : 0;
        // The box the next digit goes into is outlined; once all are filled, the last one.
        int activeIndex = Math.min(length, mOtpDigitViews.length - 1);
        for (int i = 0; i < mOtpDigitViews.length; i++) {
            TextView digitView = mOtpDigitViews[i];
            if (i < length) {
                digitView.setText(String.valueOf(otp.charAt(i)));
                digitView.setTextColor(
                        context.getColor(R.color.browser_express_signin_text_primary));
            } else {
                digitView.setText(R.string.browser_express_signin_code_placeholder);
                digitView.setTextColor(
                        context.getColor(R.color.browser_express_signin_text_secondary));
            }
            digitView.setSelected(i == activeIndex);
        }
    }

    private void onClickViews() {
        mBtnChangePhone.setOnClickListener(view -> openPreviousAuthPage());

        mBtnVerify.setOnClickListener(view -> submitOtp("manual"));

        mBtnResendOtp.setOnClickListener(view -> {
            String phoneNumber = null;
            String purpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                phoneNumber = activity.getBrowserExpressPhoneNumber();
                purpose = activity.getBrowserExpressOtpPurpose();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }

            mErrorTextView.setText(R.string.browser_express_empty_text);
            mErrorTextView.setVisibility(View.INVISIBLE);

            if(phoneNumber == null){
                mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            mOtpAutoSubmitted = false;
            mBtnResendOtp.setClickable(false);
            mBtnResendOtp.setText(R.string.browser_express_signin_resending);

            BrowserExpressOtpVerifyPreferencesUtil.ResendOtpWorkerTask workerTask =
                    new BrowserExpressOtpVerifyPreferencesUtil.ResendOtpWorkerTask(
                            phoneNumber, purpose, resendOtpCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
    }

    private void submitOtp(String source) {
        if (mBtnVerify == null) {
            Log.e(TAG, "OTP submit skipped source=" + source + " reason=missing_button");
            return;
        }
        if (mOtpVerifyInFlight) {
            // A verify request is already running; skip to avoid a duplicate.
            // This is an explicit flag (reset in both callbacks) rather than the
            // button's clickable state, which the SMS/lifecycle churn could leave
            // stuck and silently swallow every auto-submit.
            Log.e(TAG, "OTP submit skipped source=" + source + " reason=verify_in_flight");
            return;
        }

        String otp = mOtpEditText.getText().toString().trim();
        String phoneNumber = mPhoneNumber != null ? mPhoneNumber : getStoredPhoneNumber();
        Log.e(
                TAG,
                "OTP submit attempt source="
                        + source
                        + " otpLength="
                        + otp.length()
                        + " hasPhone="
                        + (phoneNumber != null));

        mErrorTextView.setText(R.string.browser_express_empty_text);
        mErrorTextView.setVisibility(View.INVISIBLE);

        if(phoneNumber == null){
            mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
            mErrorTextView.setVisibility(View.VISIBLE);
            mOtpAutoSubmitted = false;
            return;
        }

        if (otp.length() < mOtpDigitViews.length) {
            mErrorTextView.setText(
                    getSafeQuantityString(
                            R.plurals.browser_express_signin_incomplete_code,
                            OTP_LENGTH,
                            OTP_LENGTH));
            mErrorTextView.setVisibility(View.VISIBLE);
            mOtpAutoSubmitted = false;
            return;
        }

        mBtnVerify.setClickable(false);
        mBtnVerifyText.setText(R.string.browser_express_signin_verifying);

        Utils.hideKeyboard(getActivity());

        try {
            JSONObject props = new JSONObject();
            props.put("flow", getOtpFlow());
            props.put("source", source);
            BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_SUBMITTED, props);
        } catch (JSONException ignored) {
        }

        mOtpVerifyInFlight = true;
        Log.e(TAG, "OTP verify task executing source=" + source);
        BrowserExpressOtpVerifyPreferencesUtil.OtpVerifyWorkerTask workerTask =
                new BrowserExpressOtpVerifyPreferencesUtil.OtpVerifyWorkerTask(
                        phoneNumber, otp, otpVerifyCallback);
        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void maybeAutoSubmitOtp(String otp) {
        if (otp == null || otp.length() < OTP_LENGTH) {
            mOtpAutoSubmitted = false;
            mPendingAutoSubmit = false;
            return;
        }
        requestAutoSubmit("otp_text");
    }

    private void requestAutoSubmit(String source) {
        if (mOtpEditText == null) return;
        String otp = mOtpEditText.getText().toString().trim();
        if (otp.length() != OTP_LENGTH) {
            Log.e(TAG, "OTP auto-submit skipped source=" + source + " reason=invalid_length length="
                    + otp.length());
            return;
        }
        if (mOtpAutoSubmitted || mOtpVerifyInFlight) {
            Log.e(TAG, "OTP auto-submit skipped source=" + source + " reason=already_handled");
            return;
        }
        // Only auto-submit — which then verifies and navigates to the browser —
        // from a fully resumed, foreground state. If the OTP is ever filled while
        // the fragment is not resumed, submitting + startActivity() from there can
        // be silently dropped by the system; defer to onResume() so automation
        // always follows the same resumed path a manual tap does.
        if (!isResumed()) {
            mPendingAutoSubmit = true;
            Log.e(TAG, "OTP auto-submit deferred until resume source=" + source);
            return;
        }
        fireAutoSubmit(source);
    }

    private void fireAutoSubmit(String source) {
        if (mOtpEditText == null) return;
        if (mOtpAutoSubmitted || mOtpVerifyInFlight) return;
        mPendingAutoSubmit = false;
        mOtpAutoSubmitted = true;
        Log.e(TAG, "OTP auto-submit firing source=" + source);
        mOtpEditText.postDelayed(() -> submitOtp(source), AUTO_SUBMIT_DELAY_MS);
    }

    private void openPreviousAuthPage() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            activity.openBrowserExpressSignupSettings(isBrowserExpressAuthRequired());
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    private final BrowserExpressOtpVerifyPreferencesUtil.OtpVerifyCallback otpVerifyCallback =
            new BrowserExpressOtpVerifyPreferencesUtil.OtpVerifyCallback() {
                @Override
                public void otpVerifySuccessful(String accessToken, String refreshToken) {
                    Log.e("Express Browser OTP", "INSIDE SINGUP SUCCESSFUL");
                    mOtpVerifyInFlight = false;
                    mBtnVerify.setClickable(true);
                    mBtnVerifyText.setText(R.string.browser_express_signin_verify);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setAccessToken(accessToken);
                        activity.setRefreshToken(refreshToken);

                        try {
                            String purpose = activity.getBrowserExpressOtpPurpose();
                            String[] parts = accessToken.split("\\.");
                            if (parts.length >= 2) {
                                byte[] decoded = Base64.decode(parts[1], Base64.DEFAULT);
                                JSONObject jwt = new JSONObject(new String(decoded, "UTF-8"));
                                String userId = jwt.getString("_id");
                                JSONObject payload = new JSONObject();
                                payload.put("app_version", activity.getCurrentAppVersion());
                                payload.put("flow", purpose);
                                payload.put("auto_read", mOtpAutoSubmitted);
                                String eventKey = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN.equals(
                                        purpose)
                                        ? PostHogEventKeys.LOGIN
                                        : PostHogEventKeys.USER_SIGNED_UP;
                                PostHogUtil.PostHogWorkerTask task =
                                        new PostHogUtil.PostHogWorkerTask(eventKey, userId, payload);
                                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                            }
                        } catch (Exception ex) {
                            Log.e("OtpVerifyPrefs",
                                    "PostHog signup error: " + ex.getMessage());
                        }

                        // The session is saved; confirm in place and let "Start browsing"
                        // (or close) take the user back.
                        showSignedInState();
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void otpVerifyFailed(String error, int retryAfterSeconds) {
                    Log.e("Express Browser OTP", "INSIDE SINGUP FAILED");
                    BrowserExpressAuthJourney.trackError(
                            PostHogEventKeys.OTP_VERIFY_FAILED, getOtpFlow(), error);
                    showAuthError(error, retryAfterSeconds);

                    mOtpVerifyInFlight = false;
                    mOtpAutoSubmitted = false;
                    mBtnVerify.setClickable(true);
                    mBtnVerifyText.setText(R.string.browser_express_signin_verify);
                }
            };

    private final BrowserExpressOtpVerifyPreferencesUtil.ResendOtpCallback resendOtpCallback =
            new BrowserExpressOtpVerifyPreferencesUtil.ResendOtpCallback() {
                @Override
                public void resendOtpSuccessful(int resendAvailableInSeconds) {
                    BrowserExpressAuthJourney.track(
                            PostHogEventKeys.OTP_RESENT, getOtpFlow());
                    // Restart the retriever's 5-minute window for the fresh OTP.
                    startSmsOtpRetriever();
                    mErrorTextView.setText(R.string.browser_express_empty_text);
                    mErrorTextView.setVisibility(View.INVISIBLE);
                    // The cooldown line below reads "Code sent. You can resend in N seconds."
                    startResendCountdown(
                            resendAvailableInSeconds > 0
                                    ? resendAvailableInSeconds
                                    : DEFAULT_RESEND_COOLDOWN_SECONDS);
                }

                @Override
                public void resendOtpFailed(String error, int retryAfterSeconds) {
                    showAuthError(error, retryAfterSeconds);
                    if (retryAfterSeconds > 0) {
                        startResendCountdown(retryAfterSeconds);
                    } else {
                        stopResendCountdown();
                    }
                }
            };

    private void startResendCountdown(int seconds) {
        if (mBtnResendOtp == null) return;
        if (seconds <= 0) {
            stopResendCountdown();
            return;
        }
        mResendAvailableAtElapsedMs = SystemClock.elapsedRealtime() + seconds * 1000L;
        mBtnResendOtp.setEnabled(false);
        mBtnResendOtp.setClickable(false);
        scheduleCountdownTick();
    }

    private void resumeResendCountdown() {
        if (mBtnResendOtp == null) return;
        if (mResendAvailableAtElapsedMs > SystemClock.elapsedRealtime()) {
            mBtnResendOtp.setEnabled(false);
            mBtnResendOtp.setClickable(false);
            scheduleCountdownTick();
        }
    }

    private void pauseResendCountdown() {
        if (mCountdownRunnable != null) {
            mCountdownHandler.removeCallbacks(mCountdownRunnable);
        }
    }

    private void stopResendCountdown() {
        pauseResendCountdown();
        mResendAvailableAtElapsedMs = 0;
        if (mBtnResendOtp != null) {
            mBtnResendOtp.setEnabled(true);
            mBtnResendOtp.setClickable(true);
            mBtnResendOtp.setText(R.string.browser_express_signin_resend);
        }
    }

    private void scheduleCountdownTick() {
        if (mCountdownRunnable == null) {
            mCountdownRunnable = this::tickResendCountdown;
        }
        mCountdownHandler.removeCallbacks(mCountdownRunnable);
        tickResendCountdown();
    }

    private void tickResendCountdown() {
        Context context = getContext();
        if (mBtnResendOtp == null || context == null) return;
        long remainingMs = mResendAvailableAtElapsedMs - SystemClock.elapsedRealtime();
        int remainingSeconds = (int) Math.ceil(remainingMs / 1000.0);
        if (remainingSeconds <= 0) {
            stopResendCountdown();
            return;
        }
        mBtnResendOtp.setText(
                context.getResources()
                        .getQuantityString(
                                R.plurals.browser_express_signin_resend_cooldown,
                                remainingSeconds,
                                remainingSeconds));
        mCountdownHandler.postDelayed(mCountdownRunnable, 1000);
    }

    private String formatCountdown(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
    }

    private void showAuthError(String error, int retryAfterSeconds) {
        if (mErrorTextView == null) return;
        String message = (error != null && !error.isEmpty())
                ? error
                : getSafeString(R.string.browser_express_fill_all_fields_text);
        if (retryAfterSeconds > 0) {
            message = message + " (" + formatCountdown(retryAfterSeconds) + ")";
        }
        mErrorTextView.setText(message);
        mErrorTextView.setVisibility(View.VISIBLE);
    }

    private String getSafeString(int resId, Object... formatArgs) {
        Context context = getContext();
        return context != null ? context.getString(resId, formatArgs) : "";
    }

    private String getSafeQuantityString(int resId, int quantity, Object... formatArgs) {
        Context context = getContext();
        return context != null
                ? context.getResources().getQuantityString(resId, quantity, formatArgs)
                : "";
    }

    /** Swaps the code panel for the "You're all set" confirmation. */
    private void showSignedInState() {
        mSignedIn = true;
        stopResendCountdown();
        stopSmsOtpRetriever();
        Utils.hideKeyboard(getActivity());
        String name = BrowserExpressAuthUiUtil.getEnteredName();
        TextView headline = mSuccessContent.findViewById(
                R.id.browser_express_signin_success_headline);
        headline.setText(name != null && !name.isEmpty()
                ? getSafeString(R.string.browser_express_signin_success_headline, name)
                : getSafeString(R.string.browser_express_signin_success_headline_no_name));
        mVerifyContent.setVisibility(View.GONE);
        mSuccessContent.setVisibility(View.VISIBLE);
    }

    /**
     * Returns to the already-running browser and finishes every auth Settings screen stacked
     * above it (the details and code screens are separate SettingsActivity instances on top of
     * ChromeTabbedActivity). CLEAR_TOP finishes those activities and delivers the intent to the
     * existing browser; SINGLE_TOP reuses it instead of recreating.
     */
    private void openBrowser() {
        if (getActivity() == null) return;
        Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setAction(Intent.ACTION_VIEW);
        Log.e(TAG, "Sign-in done: launching browser (CLEAR_TOP) + finishing auth settings");
        startActivity(intent);
        // Belt-and-suspenders: close this screen in case it sits in a task where CLEAR_TOP
        // can't reach it.
        getActivity().finish();
    }

    /** Close / "I'll do this later" before verifying: continue as a guest. */
    private void skipVerification() {
        BrowserExpressAuthUiUtil.skipAuthWithAutoLogin(this, getOtpFlow());
    }

    private boolean isBrowserExpressAuthRequired() {
        Bundle args = getArguments();
        return args != null
                && args.getBoolean(
                        BraveSettingsActivity.EXTRA_BROWSER_EXPRESS_AUTH_REQUIRED, false);
    }

    /** "login" or "signup" — the purpose this OTP screen was opened for. */
    private String getOtpFlow() {
        try {
            return BraveActivity.getBraveActivity().getBrowserExpressOtpPurpose();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return null;
        }
    }
}
