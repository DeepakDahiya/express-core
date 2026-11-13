/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

import androidx.fragment.app.FragmentManager;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.Channel;
import org.chromium.brave_news.mojom.Publisher;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_generate_username.BrowserExpressClaimUsernameUtil;
import org.chromium.ui.widget.Toast;
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
import androidx.preference.Preference;
import androidx.annotation.NonNull;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class BrowserExpressSignupPreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private View mBtnSignUp;
    private TextView mBtnSignUpText;
    private EditText mPhoneEditText;
    private EditText mNameEditText;
    private TextView mErrorTextView;
    private View mBtnClose;
    private View mBtnSkip;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set("");
        return inflater.inflate(R.layout.browser_express_signup_settings, container, false);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        View view = getView();
        if (view != null) {
            mBtnSignUp = view.findViewById(R.id.btn_sign_up);
            mBtnSignUpText = (TextView) view.findViewById(R.id.btn_sign_up_text);
            mPhoneEditText = (EditText) view.findViewById(R.id.browser_express_phone);
            mNameEditText = (EditText) view.findViewById(R.id.browser_express_name);
            mErrorTextView = (TextView) view.findViewById(R.id.login_error_message);
            mBtnClose = view.findViewById(R.id.btn_close);
            if (mBtnClose != null) {
                mBtnClose.setOnClickListener(v -> skipRegistrationWithAutoLogin());
            }
            // "Continue without signing in" leaves the same way the close button does.
            mBtnSkip = view.findViewById(R.id.btn_skip);
            if (mBtnSkip != null) {
                mBtnSkip.setOnClickListener(v -> skipRegistrationWithAutoLogin());
            }

            onClickViews();
            BrowserExpressAuthJourney.track(PostHogEventKeys.AUTH_SCREEN_VIEWED, "signup");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // If auth already completed (e.g. OTP was verified in the screen launched
        // above this one), don't sit on the login/signup page. Close it so the
        // user lands on the browser instead of being "stuck on login". This is the
        // task-topology-independent safety net for the post-verify navigation.
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (activity.getAccessToken() != null) {
                Log.e("Express Browser SIGNUP",
                        "Access token present on resume; closing auth settings page");
                if (getActivity() != null) {
                    getActivity().finish();
                }
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
    }

    private void onClickViews() {
        mBtnSignUp.setOnClickListener(view -> {
            String rawPhoneNumber = mPhoneEditText.getText().toString().trim();
            String phoneNumber = normalizePhoneNumber(rawPhoneNumber);
            String fullName = mNameEditText.getText().toString().trim();

            mErrorTextView.setText(R.string.browser_express_empty_text);
            mErrorTextView.setVisibility(View.INVISIBLE);

            if (rawPhoneNumber.isEmpty() || fullName.isEmpty()) {
                mErrorTextView.setText(R.string.browser_express_signin_missing_details);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            if (!isValidPhoneNumber(phoneNumber)) {
                mErrorTextView.setText(R.string.browser_express_phone_invalid_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            mBtnSignUp.setClickable(false);
            mBtnSignUpText.setText(R.string.browser_express_signin_sending_code);
            BrowserExpressAuthUiUtil.saveEnteredName(fullName);

            Utils.hideKeyboard(getActivity());

            JSONObject requestedProps = new JSONObject();
            try {
                requestedProps.put("flow", "signup");
                requestedProps.put("has_name", !fullName.isEmpty());
            } catch (JSONException ignored) {
            }
            BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_REQUESTED, requestedProps);

            BrowserExpressSignupPreferencesUtil.SignupWorkerTask workerTask =
                    new BrowserExpressSignupPreferencesUtil.SignupWorkerTask(
                            phoneNumber, fullName, signupCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
    }

    private String normalizePhoneNumber(String rawPhoneNumber) {
        if (rawPhoneNumber == null) return null;
        String digits = rawPhoneNumber.replaceAll("[^0-9]", "");
        if (digits.startsWith("91") && digits.length() == 12) {
            digits = digits.substring(2);
        } else if (digits.startsWith("0") && digits.length() == 11) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        if (rawPhoneNumber.trim().startsWith("+")
                && digits.length() >= 8
                && digits.length() <= 15) {
            return "+" + digits;
        }
        return null;
    }

    private boolean isValidPhoneNumber(String phoneNumber) {
        return phoneNumber != null && phoneNumber.matches("\\+[1-9][0-9]{7,14}");
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    private final BrowserExpressSignupPreferencesUtil.SignupCallback signupCallback =
            new BrowserExpressSignupPreferencesUtil.SignupCallback() {
                @Override
                public void signupSuccessful(String phoneNumber, String purpose) {
                    Log.e("Express Browser SIGNUP", "INSIDE SINGUP SUCCESSFUL");
                    mBtnSignUp.setClickable(true);
                    mBtnSignUpText.setText(R.string.browser_express_signin_send_code);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setBrowserExpressPhoneNumber(phoneNumber);
                        String otpPurpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN.equals(
                                purpose)
                                ? BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN
                                : BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
                        activity.setBrowserExpressOtpPurpose(otpPurpose);
                        activity.openBrowserExpressVerify(isBrowserExpressAuthRequired());
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void signupFailed(String error) {
                    Log.e("Express Browser SINGUP", "INSIDE SINGUP FAILED");
                    BrowserExpressAuthJourney.trackError(
                            PostHogEventKeys.OTP_SEND_FAILED, "signup", error);
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnSignUp.setClickable(true);
                    mBtnSignUpText.setText(R.string.browser_express_signin_send_code);
                }
            };

    /**
     * Register-screen fallback: the user tapped close or "Continue without signing in"
     * instead of signing up, so grant them the same generate-username auto-login that
     * non-India users get automatically. Shared with the login screen's close control.
     */
    private void skipRegistrationWithAutoLogin() {
        BrowserExpressAuthUiUtil.skipAuthWithAutoLogin(this, "signup");
    }

    private boolean isBrowserExpressAuthRequired() {
        Bundle args = getArguments();
        return args != null
                && args.getBoolean(
                        BraveSettingsActivity.EXTRA_BROWSER_EXPRESS_AUTH_REQUIRED, false);
    }
}
