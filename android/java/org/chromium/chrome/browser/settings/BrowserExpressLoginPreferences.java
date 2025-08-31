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
    private LinearLayout mParentLayout;
    private Button mBtnSignUp;
    private Button mBtnSignIn;
    private EditText mEmailEditText;
    private EditText mPasswordEditText;
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
        return inflater.inflate(R.layout.browser_express_login_settings, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        View view = getView();
        if (view != null) {
            mParentLayout = (LinearLayout) view.findViewById(R.id.layout_parent);
            mBtnSignUp = (Button) view.findViewById(R.id.btn_sign_up);
            mBtnSignIn = (Button) view.findViewById(R.id.btn_sign_in);
            mEmailEditText = (EditText) view.findViewById(R.id.browser_express_email);
            mPasswordEditText = (EditText) view.findViewById(R.id.browser_express_password);
            mErrorTextView = (TextView) view.findViewById(R.id.login_error_message);

            onClickViews();
        }
    }

    private void onClickViews() {
        mBtnSignUp.setOnClickListener(view -> {
            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                activity.openBrowserExpressSignupSettings();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            }
        });

        mBtnSignIn.setOnClickListener(view -> {
            String email = mEmailEditText.getText().toString();
            String password = mPasswordEditText.getText().toString();

            mErrorTextView.setText(R.string.browser_express_empty_text);
            mErrorTextView.setVisibility(View.INVISIBLE);

            String emptyString = "";

            if(password.equals(emptyString) || email.equals(emptyString)){
                mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
                mErrorTextView.setVisibility(View.VISIBLE);
                return;
            }

            mBtnSignIn.setClickable(false);
            mBtnSignIn.setText(R.string.browser_express_loading_title);

            Utils.hideKeyboard(getActivity());

            try {
                BraveActivity activity = BraveActivity.getBraveActivity();
                String pInfo = activity.getCurrentAppVersion();
                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("app_version", pInfo);
                PostHogUtil.PostHogWorkerTask postHogWorkerTask =
                        new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.LOGIN, "NEW_USER", payload);
                postHogWorkerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
            } catch (JSONException e) {
                Log.e("TokenHandler", "Error decoding new access token", e);
            }

            BrowserExpressLoginPreferencesUtil.LoginWorkerTask workerTask =
                    new BrowserExpressLoginPreferencesUtil.LoginWorkerTask(
                            email, password, loginCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        });
    }
    
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    private final BrowserExpressLoginPreferencesUtil.LoginCallback loginCallback =
            new BrowserExpressLoginPreferencesUtil.LoginCallback() {
                @Override
                public void loginSuccessful(String accessToken, String refreshToken) {
                    mBtnSignIn.setClickable(true);
                    mBtnSignIn.setText(R.string.browser_express_login_button_title);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setAccessToken(accessToken);
                        Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        intent.setAction(Intent.ACTION_VIEW);
                        Toast.makeText(activity, "Login Successful", Toast.LENGTH_SHORT).show();
                        startActivity(intent);
                        // if (getFragmentManager() != null) {
                        //     getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                        // }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void loginFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnSignIn.setClickable(true);
                    mBtnSignIn.setText(R.string.browser_express_login_button_title);
                }
            };
}
