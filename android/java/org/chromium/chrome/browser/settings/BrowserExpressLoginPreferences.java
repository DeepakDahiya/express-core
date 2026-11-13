/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import org.chromium.ui.widget.Toast;
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
import java.util.Locale;
import org.json.JSONObject;
import android.content.pm.PackageInfo;
import org.json.JSONException;

public class BrowserExpressLoginPreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private Button mBtnSignUp;
    private View mBtnSignIn;
    private TextView mBtnSignInText;
    private EditText mPhoneEditText;
    private TextView mErrorTextView;
    private View mBtnClose;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set("");
        return inflater.inflate(R.layout.browser_express_login_settings, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        View view = getView();
        if (view != null) {
            mBtnSignUp = (Button) view.findViewById(R.id.btn_sign_up);
            mBtnSignIn = view.findViewById(R.id.btn_sign_in);
            mBtnSignInText = (TextView) view.findViewById(R.id.btn_sign_in_text);
            mPhoneEditText = (EditText) view.findViewById(R.id.browser_express_phone);
            mErrorTextView = (TextView) view.findViewById(R.id.login_error_message);
            mBtnClose = view.findViewById(R.id.btn_close);
            if (mBtnClose != null) {
                mBtnClose.setOnClickListener(
                        v -> BrowserExpressAuthUiUtil.skipAuthWithAutoLogin(this, "login"));
            }

            BrowserExpressAuthUiUtil.applyCompactText(getContext(), view);
            onClickViews();
            BrowserExpressAuthJourney.track(PostHogEventKeys.AUTH_SCREEN_VIEWED, "login");
        }
    }

    private void onClickViews() {
        mBtnSignUp.setOnClickListener(view -> {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                activity.openBrowserExpressSignupSettings(isBrowserExpressAuthRequired());
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }
        });

        mBtnSignIn.setOnClickListener(view -> {
            String rawPhoneNumber = mPhoneEditText.getText().toString().trim();
            String phoneNumber = normalizePhoneNumber(rawPhoneNumber);

            mErrorTextView.setText(R.string.browser_express_empty_text);
            mErrorTextView.setVisibility(View.GONE);

            String emptyString = "";

            if (rawPhoneNumber.equals(emptyString)) {
                mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            if (!isValidPhoneNumber(phoneNumber)) {
                mErrorTextView.setText(R.string.browser_express_phone_invalid_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            mBtnSignIn.setClickable(false);
            mBtnSignInText.setText(R.string.browser_express_loading_title);

            Utils.hideKeyboard(getActivity());

            BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_REQUESTED, "login");

            BrowserExpressLoginPreferencesUtil.LoginWorkerTask workerTask =
                    new BrowserExpressLoginPreferencesUtil.LoginWorkerTask(
                            phoneNumber, loginCallback);
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

    private final BrowserExpressLoginPreferencesUtil.LoginCallback loginCallback =
            new BrowserExpressLoginPreferencesUtil.LoginCallback() {
                @Override
                public void loginOtpSent(String phoneNumber) {
                    mBtnSignIn.setClickable(true);
                    mBtnSignInText.setText(R.string.browser_express_send_otp_button_title);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setBrowserExpressPhoneNumber(phoneNumber);
                        activity.setBrowserExpressOtpPurpose(
                                BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN);
                        activity.openBrowserExpressVerify(isBrowserExpressAuthRequired());
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void loginFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                    BrowserExpressAuthJourney.trackError(
                            PostHogEventKeys.OTP_SEND_FAILED, "login", error);
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnSignIn.setClickable(true);
                    mBtnSignInText.setText(R.string.browser_express_send_otp_button_title);
                }
            };

    private boolean isBrowserExpressAuthRequired() {
        Bundle args = getArguments();
        return args != null
                && args.getBoolean(
                        BraveSettingsActivity.EXTRA_BROWSER_EXPRESS_AUTH_REQUIRED, false);
    }
}
