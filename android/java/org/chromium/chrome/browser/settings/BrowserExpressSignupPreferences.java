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
import android.widget.Button;
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

import java.util.List;

public class BrowserExpressSignupPreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private LinearLayout mParentLayout;
    private Button mBtnSignIn;
    private Button mBtnSignUp;
    private EditText mEmailEditText;
    private EditText mPasswordEditText;
    private EditText mConfirmPasswordEditText;
    private EditText mNameEditText;
    private TextView mErrorTextView;

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
            mParentLayout = (LinearLayout) view.findViewById(R.id.layout_parent);
            mBtnSignIn = (Button) view.findViewById(R.id.btn_sign_in);
            mBtnSignUp = (Button) view.findViewById(R.id.btn_sign_up);
            mEmailEditText = (EditText) view.findViewById(R.id.browser_express_email);
            mNameEditText = (EditText) view.findViewById(R.id.browser_express_name);
            mPasswordEditText = (EditText) view.findViewById(R.id.browser_express_password);
            mConfirmPasswordEditText = (EditText) view.findViewById(R.id.browser_express_confirm_password);
            mErrorTextView = (TextView) view.findViewById(R.id.login_error_message);

            onClickViews();
        }
    }

    private void onClickViews() {
        mBtnSignIn.setOnClickListener(view -> {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                activity.openBrowserExpressLoginSettings();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }
        });

        mBtnSignUp.setOnClickListener(view -> {
            String email = mEmailEditText.getText().toString();
            String name = mNameEditText.getText().toString();
            String password = mPasswordEditText.getText().toString();
            String confirmPassword = mConfirmPasswordEditText.getText().toString();

            mErrorTextView.setText(R.string.browser_express_empty_text);
            mErrorTextView.setVisibility(View.INVISIBLE);

            String emptyString = "";

            if(password.equals(emptyString) || email.equals(emptyString) || name.equals(emptyString)){
                mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            if (!password.equals(confirmPassword)){
                mErrorTextView.setText(R.string.browser_express_password_not_match_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            mBtnSignUp.setClickable(false);
            mBtnSignUp.setText(R.string.browser_express_loading_title);

            Utils.hideKeyboard(getActivity());

            BrowserExpressSignupPreferencesUtil.SignupWorkerTask workerTask =
                    new BrowserExpressSignupPreferencesUtil.SignupWorkerTask(
                            email, password, name, signupCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    private final BrowserExpressSignupPreferencesUtil.SignupCallback signupCallback =
            new BrowserExpressSignupPreferencesUtil.SignupCallback() {
                @Override
                public void signupSuccessful(String email) {
                    Log.e("Express Browser SIGNUP", "INSIDE SINGUP SUCCESSFUL");
                    mBtnSignUp.setClickable(true);
                    mBtnSignUp.setText(R.string.browser_express_signup_button_title);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setBrowserExpressEmail(email);
                        activity.openBrowserExpressVerify();
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void signupFailed(String error) {
                    Log.e("Express Browser SINGUP", "INSIDE SINGUP FAILED");
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnSignUp.setClickable(true);
                    mBtnSignUp.setText(R.string.browser_express_signup_button_title);
                }
            };
}
