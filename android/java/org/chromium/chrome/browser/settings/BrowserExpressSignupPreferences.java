/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.IntentUtils;
import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.browser_express_push.BrowserExpressPushTokenManager;
import org.chromium.chrome.browser.browser_express_signup.UgcCodeBoxes;
import org.chromium.chrome.browser.browser_express_signup.UgcSignupHeroView;
import org.chromium.chrome.browser.browser_express_signup.UgcSignupLogic;
import org.chromium.chrome.browser.browser_express_signup.UgcSignupLogic.ResendState;
import org.chromium.chrome.browser.browser_express_signup.UgcSignupLogic.ResendView;
import org.chromium.chrome.browser.referral.ReferralHelper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UGC sign-up screen (ugc-signup-handoff): one screen, two steps under a shared animated hero.
 *
 * <pre>
 *   details ──Jump in──▶ code ──last digit (auto)──▶ browser
 *      │                  └──Edit / back──▶ details (number kept)
 *      └──Guest──▶ browser, as a guest (BrowserExpressGuestLogin)
 * </pre>
 *
 * <p>New and returning numbers share the flow: the server answers "signup" or "login". The window
 * is edge to edge, so the panel can ride the keyboard frame by frame; while a field has focus the
 * headline collapses and the hero shrinks into the space that is left.
 */
public class BrowserExpressSignupPreferences extends BravePreferenceFragment {
    private static final String TAG = "BESignup";

    /**
     * Digits in a code. The design has six; the server sends four, so four until it changes
     * (generatePhoneOtp in browser-express-node).
     */
    private static final int CODE_LENGTH = 4;
    /** The first lone {@link #CODE_LENGTH}-digit group in the SMS; the app hash comes last. */
    private static final Pattern CODE_IN_SMS =
            Pattern.compile("(?<!\\d)(\\d{" + CODE_LENGTH + "})(?!\\d)");

    private static final int STEP_DETAILS = 0;
    private static final int STEP_CODE = 1;

    // Layout and motion (src/theme.ts).
    private static final long LAYOUT_MS = 320;
    private static final PathInterpolator LAYOUT_EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    private static final long HEADLINE_OUT_MS = 110;
    private static final long HEADLINE_IN_MS = 220;
    private static final long HEADLINE_IN_DELAY_MS = 220;
    private static final long SUB_IN_DELAY_MS = 240;
    private static final long STEP_FADE_MS = 160;
    private static final long VERIFY_MIN_MS = 600;
    private static final long TICK_MS = 250;
    /** After focus moves on purpose, a keyboard that blinks shut doesn't count as closed. */
    private static final long BLUR_GRACE_MS = 600;
    private static final float HEADER_TOP_DP = 11 - 14;
    private static final float HERO_TOP_DP = 49;
    private static final float HERO_GAP_CLOSED_DP = 30;
    private static final float HERO_GAP_KEYBOARD_DP = 14;
    private static final float PANEL_PADDING_BOTTOM_DP = 28;
    /** The panel's bottom padding less the 12dp it keeps above the keyboard. */
    private static final float KEYBOARD_OVERLAP_DP = 16;
    private static final float FORM_TOP_DP = 22;
    private static final float TITLE_SP = 34;
    private static final float TITLE_KEYBOARD_SP = 28;
    private static final float TITLE_LINE_RATIO = 35.5f / 34f;
    private static final float PRESSED_SCALE = 0.98f;

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();

    private View mRoot;
    private UgcSignupHeroView mHero;
    private View mHeader;
    private View mGuest;
    private LinearLayout mPanel;
    private View mDetails;
    private View mIntro;
    private TextView mHeadline;
    private TextView mSub;
    private View mForm;
    private EditText mNameInput;
    private EditText mPhoneInput;
    private TextView mDetailsError;
    private View mCode;
    private TextView mCodeTitle;
    private TextView mCodeSub;
    private UgcCodeBoxes mCodeBoxes;
    private TextView mCodeError;
    private TextView mResend;
    private View mVerify;
    private View mVerifySpinner;
    private TextView mVerifyLabel;

    private float mDensity;
    private int mInsetTop;
    private int mInsetBottom;
    private boolean mImeAnimating;
    private boolean mImeVisible;
    private long mBlurGraceUntilMs;

    private int mStep = STEP_DETAILS;
    private boolean mTyping;
    private float mTypingFraction;
    private @Nullable ValueAnimator mTypingAnimator;
    private int mIntroHeight;

    private String mDigits = "";
    private final Map<String, ResendState> mResendByNumber = new HashMap<>();
    private final Map<String, String> mPurposeByNumber = new HashMap<>();
    private @Nullable ResendState mResendState;
    private int mSendGeneration;
    private boolean mFormatting;
    private boolean mSettingCode;
    private boolean mVerifying;
    private boolean mAutoFilled;
    private boolean mPendingAutoSubmit;
    private boolean mSignedIn;

    private @Nullable OnBackPressedCallback mBackCallback;
    private final Runnable mTick = this::tick;
    private @Nullable BroadcastReceiver mSmsReceiver;

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set("");
        return inflater.inflate(R.layout.browser_express_ugc_signup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mDensity = getResources().getDisplayMetrics().density;
        mRoot = view.findViewById(R.id.ugc_signup_root);
        mHero = view.findViewById(R.id.ugc_signup_hero);
        mHeader = view.findViewById(R.id.ugc_signup_header);
        mGuest = view.findViewById(R.id.ugc_signup_guest);
        mPanel = view.findViewById(R.id.ugc_signup_panel);
        mDetails = view.findViewById(R.id.ugc_signup_details);
        mIntro = view.findViewById(R.id.ugc_signup_intro);
        mHeadline = view.findViewById(R.id.ugc_signup_headline);
        mSub = view.findViewById(R.id.ugc_signup_sub);
        mForm = view.findViewById(R.id.ugc_signup_form);
        mNameInput = view.findViewById(R.id.ugc_signup_name);
        mPhoneInput = view.findViewById(R.id.ugc_signup_phone);
        mDetailsError = view.findViewById(R.id.ugc_signup_details_error);
        mCode = view.findViewById(R.id.ugc_signup_code);
        mCodeTitle = view.findViewById(R.id.ugc_signup_code_title);
        mCodeSub = view.findViewById(R.id.ugc_signup_code_sub);
        mCodeBoxes = view.findViewById(R.id.ugc_signup_code_boxes);
        mCodeError = view.findViewById(R.id.ugc_signup_code_error);
        mResend = view.findViewById(R.id.ugc_signup_resend);
        mVerify = view.findViewById(R.id.ugc_signup_verify);
        mVerifySpinner = view.findViewById(R.id.ugc_signup_verify_spinner);
        mVerifyLabel = view.findViewById(R.id.ugc_signup_verify_label);

        bindCopy(view);
        bindDetails();
        bindCode();
        bindInsets();

        // A tap outside the form closes the keyboard.
        mRoot.setOnClickListener(v -> closeKeyboard());
        mGuest.setOnClickListener(v -> continueAsGuest());

        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Back on the code step is Edit.
                if (!mVerifying) backToDetails(null);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), mBackCallback);

        // The hero fills whatever the panel leaves above it, every frame.
        mRoot.getViewTreeObserver().addOnPreDrawListener(mPlaceHero);

        BrowserExpressAuthJourney.track(PostHogEventKeys.AUTH_SCREEN_VIEWED, "signup");
    }

    @Override
    public void onResume() {
        super.onResume();
        // If auth already completed (e.g. a code was verified on another screen of this flow),
        // don't sit on the sign-up page: close it so the user lands on the browser.
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (!mSignedIn && activity.getAccessToken() != null && getActivity() != null) {
                Log.i(TAG, "Access token present on resume; closing the sign-up page");
                getActivity().finish();
                return;
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
        }
        // A code that arrived while the page was in the background is checked now, from a resumed
        // state, as a tap would be.
        if (mPendingAutoSubmit) {
            mPendingAutoSubmit = false;
            mRoot.post(() -> submitCode("sms"));
        }
        if (mStep == STEP_CODE) tick();
    }

    @Override
    public void onPause() {
        mRoot.removeCallbacks(mTick);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        ViewTreeObserver observer = mRoot.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(mPlaceHero);
        mRoot.removeCallbacks(mTick);
        if (mTypingAnimator != null) mTypingAnimator.cancel();
        stopSmsRetriever();
        super.onDestroyView();
    }

    // ------------------------------------------------------------------------------- binding

    private void bindCopy(View view) {
        Context context = requireContext();
        String accentWord = getString(R.string.browser_express_ugc_signup_headline_accent);
        String headline = getString(R.string.browser_express_ugc_signup_headline, accentWord);
        SpannableStringBuilder styledHeadline = new SpannableStringBuilder(headline);
        int at = headline.indexOf(accentWord);
        if (at >= 0) {
            styledHeadline.setSpan(
                    new ForegroundColorSpan(color(R.color.browser_express_ugc_signup_accent)), at,
                    at + accentWord.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mHeadline.setText(styledHeadline);

        // "Name (optional)", with "(optional)" fainter and in the regular weight.
        String label = getString(R.string.browser_express_ugc_signup_name_label);
        String optional = getString(R.string.browser_express_ugc_signup_name_optional);
        SpannableStringBuilder nameLabel = new SpannableStringBuilder(label).append(' ');
        int start = nameLabel.length();
        nameLabel.append(optional);
        nameLabel.setSpan(new ForegroundColorSpan(color(R.color.browser_express_ugc_signup_faint)),
                start, nameLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface regular = font(context, R.font.browser_express_ugc_text_regular);
        if (regular != null) {
            nameLabel.setSpan(new TypefaceSpan(regular), start, nameLabel.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ((TextView) view.findViewById(R.id.ugc_signup_name_label)).setText(nameLabel);

        pressScale(view.findViewById(R.id.ugc_signup_jump_in));
        pressScale(mVerify);
    }

    private void bindDetails() {
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> onFocusChanged();
        mNameInput.setOnFocusChangeListener(focusListener);
        mPhoneInput.setOnFocusChangeListener(focusListener);

        mNameInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                mHero.react();
            }
        });
        mNameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT) return false;
            mPhoneInput.requestFocus();
            return true;
        });

        // Keep digits only and show them as "98765 43210", whatever was typed or pasted.
        mPhoneInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (mFormatting) return;
                String formatted = UgcSignupLogic.formatPhone(UgcSignupLogic.normalizePhone(s));
                if (!formatted.contentEquals(s)) {
                    mFormatting = true;
                    s.replace(0, s.length(), formatted);
                    mFormatting = false;
                }
                // Never red while typing: the error goes on the next keystroke.
                showError(mDetailsError, null);
                mHero.react();
            }
        });
        mPhoneInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) return false;
            submitDetails();
            return true;
        });

        mRoot.findViewById(R.id.ugc_signup_jump_in).setOnClickListener(v -> submitDetails());

        mIntro.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mTypingFraction == 0f && mIntro.getLayoutParams().height
                    == ViewGroup.LayoutParams.WRAP_CONTENT) {
                mIntroHeight = b - t;
            }
        });
    }

    private void bindCode() {
        mCodeBoxes.setLength(CODE_LENGTH);
        mCodeBoxes.getInput().setOnFocusChangeListener((v, hasFocus) -> onFocusChanged());
        mCodeBoxes.setListener(this::onCodeChanged);
        mCodeSub.setMovementMethod(LinkMovementMethod.getInstance());
        mCodeSub.setHighlightColor(Color.TRANSPARENT);
        mResend.setOnClickListener(v -> resend());
        mVerify.setOnClickListener(v -> submitCode("manual"));
    }

    /**
     * The window is edge to edge (BrowserExpressAuthUiUtil.applySignupWindowChrome), so the
     * screen keeps clear of the system bars itself, and lifts the panel with the keyboard: at
     * once when the keyboard just appears, frame by frame when it animates.
     */
    private void bindInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            mInsetTop = bars.top;
            mInsetBottom = bars.bottom;
            FrameLayout.LayoutParams header = (FrameLayout.LayoutParams) mHeader.getLayoutParams();
            int headerTop = bars.top + dp(HEADER_TOP_DP);
            if (header.topMargin != headerTop) {
                header.topMargin = headerTop;
                mHeader.setLayoutParams(header);
            }
            int panelBottom = dp(PANEL_PADDING_BOTTOM_DP) + bars.bottom;
            if (mPanel.getPaddingBottom() != panelBottom) {
                mPanel.setPadding(mPanel.getPaddingLeft(), mPanel.getPaddingTop(),
                        mPanel.getPaddingRight(), panelBottom);
            }
            if (!mImeAnimating) liftPanel(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (mImeVisible && !imeVisible) onKeyboardClosed();
            mImeVisible = imeVisible;
            return insets;
        });
        ViewCompat.setWindowInsetsAnimationCallback(mRoot,
                new WindowInsetsAnimationCompat.Callback(
                        WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                    @Override
                    public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                        if (isIme(animation)) mImeAnimating = true;
                    }

                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                            @NonNull List<WindowInsetsAnimationCompat> running) {
                        for (WindowInsetsAnimationCompat animation : running) {
                            if (isIme(animation)) {
                                liftPanel(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                                break;
                            }
                        }
                        return insets;
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                        if (!isIme(animation)) return;
                        mImeAnimating = false;
                        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mRoot);
                        if (insets != null) {
                            liftPanel(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                        }
                    }
                });
        ViewCompat.requestApplyInsets(mRoot);
    }

    private static boolean isIme(WindowInsetsAnimationCompat animation) {
        return (animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0;
    }

    /** Keeps the panel's content 12dp above a keyboard {@code imeBottom} pixels tall. */
    private void liftPanel(int imeBottom) {
        float lift = Math.max(0, imeBottom - dp(KEYBOARD_OVERLAP_DP) - mInsetBottom);
        mPanel.setTranslationY(-lift);
    }

    private final ViewTreeObserver.OnPreDrawListener mPlaceHero = () -> {
        float contentTop = mPanel.getY() + mPanel.getPaddingTop();
        float gap = lerp(HERO_GAP_CLOSED_DP, HERO_GAP_KEYBOARD_DP, mTypingFraction) * mDensity;
        mHero.setRegion(mInsetTop + HERO_TOP_DP * mDensity, contentTop - gap);
        return true;
    };

    // ------------------------------------------------------------------- typing and keyboard

    /** Android back with the keyboard up only hides it: drop focus so the layout comes back. */
    private void onKeyboardClosed() {
        if (SystemClock.elapsedRealtime() < mBlurGraceUntilMs) return;
        View focused = mRoot.findFocus();
        if (focused instanceof EditText) mRoot.requestFocus();
    }

    private void closeKeyboard() {
        hideKeyboard();
        mRoot.requestFocus();
    }

    /** Focus moves between fields in one go; settle on the final state before animating. */
    private void onFocusChanged() {
        mRoot.post(() -> {
            if (getView() == null) return;
            View focused = mRoot.findFocus();
            setTyping(focused instanceof EditText);
        });
    }

    private void setTyping(boolean typing) {
        if (typing == mTyping) return;
        mTyping = typing;
        if (mTypingAnimator != null) mTypingAnimator.cancel();
        if (mStep == STEP_DETAILS && mIntroHeight == 0) mIntroHeight = mIntro.getHeight();
        ValueAnimator animator = ValueAnimator.ofFloat(mTypingFraction, typing ? 1f : 0f);
        animator.setDuration(LAYOUT_MS);
        animator.setInterpolator(LAYOUT_EASE);
        animator.addUpdateListener(a -> applyTypingFraction((float) a.getAnimatedValue()));
        animator.start();
        mTypingAnimator = animator;

        // The headline and sub fade out fast, and back in once the panel has settled so they never
        // show clipped.
        if (typing) {
            mHeadline.animate().alpha(0f).setStartDelay(0).setDuration(HEADLINE_OUT_MS).start();
            mSub.animate().alpha(0f).setStartDelay(0).setDuration(HEADLINE_OUT_MS).start();
        } else {
            mHeadline.animate().alpha(1f).setStartDelay(HEADLINE_IN_DELAY_MS)
                    .setDuration(HEADLINE_IN_MS).start();
            mSub.animate().alpha(1f).setStartDelay(SUB_IN_DELAY_MS)
                    .setDuration(HEADLINE_IN_MS).start();
        }
    }

    /**
     * 0 at rest, 1 while typing: collapses the headline on the details step, shrinks the title on
     * the code step and narrows the hero's gap above the panel.
     */
    private void applyTypingFraction(float fraction) {
        mTypingFraction = fraction;
        ViewGroup.LayoutParams intro = mIntro.getLayoutParams();
        intro.height = fraction == 0f || mIntroHeight == 0
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : Math.round(mIntroHeight * (1f - fraction));
        mIntro.setLayoutParams(intro);
        ViewGroup.MarginLayoutParams form = (ViewGroup.MarginLayoutParams) mForm.getLayoutParams();
        form.topMargin = Math.round(lerp(FORM_TOP_DP, 0, fraction) * mDensity);
        mForm.setLayoutParams(form);

        float titleSp = lerp(TITLE_SP, TITLE_KEYBOARD_SP, fraction);
        mCodeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        mCodeTitle.setLineHeight(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                titleSp * TITLE_LINE_RATIO, getResources().getDisplayMetrics())));
        mRoot.invalidate();
    }

    // -------------------------------------------------------------------------- details step

    private void submitDetails() {
        if (mVerifying) return;
        String digits = UgcSignupLogic.normalizePhone(mPhoneInput.getText());
        if (!UgcSignupLogic.isValidPhone(digits)) {
            showError(mDetailsError, getString(R.string.browser_express_ugc_signup_error_phone));
            reject();
            return;
        }
        showError(mDetailsError, null);
        String name = mNameInput.getText().toString().trim();
        mDigits = digits;

        // Back via Edit with the same number: keep its resend schedule and don't send again.
        ResendState seen = mResendByNumber.get(digits);
        mResendState = seen != null ? seen : ResendState.initial(SystemClock.elapsedRealtime());
        mResendByNumber.put(digits, mResendState);
        goToCode();
        if (seen != null) return;

        try {
            JSONObject props = new JSONObject();
            props.put("flow", "signup");
            props.put("has_name", !name.isEmpty());
            BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_REQUESTED, props);
        } catch (JSONException ignored) {
        }

        // The code step is already up; the code goes out meanwhile.
        int generation = ++mSendGeneration;
        String phone = UgcSignupLogic.toE164(digits);
        BrowserExpressPhoneAuthApi.sendCode(phone, name.isEmpty() ? null : name, result -> {
            if (result.ok) {
                String purpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN.equals(
                                         result.purpose)
                        ? BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN
                        : BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
                mPurposeByNumber.put(digits, purpose);
                rememberNumber(phone, purpose);
                serverWait(digits, result.resendAvailableInSeconds);
                return;
            }
            BrowserExpressAuthJourney.trackError(
                    PostHogEventKeys.OTP_SEND_FAILED, "signup", describe(result));
            // Not sent: next time this number sends afresh.
            mResendByNumber.remove(digits);
            if (getView() == null) return;
            if (generation == mSendGeneration && mStep == STEP_CODE && digits.equals(mDigits)) {
                backToDetails(sendErrorText(result));
            }
        });
    }

    /** The code step for {@link #mDigits}. */
    private void goToCode() {
        mStep = STEP_CODE;
        mAutoFilled = false;
        mPendingAutoSubmit = false;
        setVerifying(false);
        showError(mCodeError, null);
        setCodeQuietly("");
        bindCodeSub();
        swapSteps(mDetails, mCode);
        if (mBackCallback != null) mBackCallback.setEnabled(true);

        focusAndShowKeyboard(mCodeBoxes.getInput());
        startSmsRetriever();
        tick();
        BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_VERIFY_SCREEN_VIEWED, getFlow());
    }

    /** "Code sent to 98765 43210 · Edit". */
    private void bindCodeSub() {
        Context context = requireContext();
        String number = UgcSignupLogic.formatPhone(mDigits);
        String sent = getString(R.string.browser_express_ugc_signup_code_sent_to, number);
        SpannableStringBuilder sub = new SpannableStringBuilder(sent);
        Typeface medium = font(context, R.font.browser_express_ugc_text_medium);
        int at = sent.indexOf(number);
        if (at >= 0) {
            sub.setSpan(new ForegroundColorSpan(color(R.color.browser_express_ugc_signup_ink)), at,
                    at + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (medium != null) {
                sub.setSpan(new TypefaceSpan(medium), at, at + number.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        sub.append(" · ");
        int editStart = sub.length();
        sub.append(getString(R.string.browser_express_ugc_signup_edit));
        sub.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                if (!mVerifying) backToDetails(null);
            }

            @Override
            public void updateDrawState(@NonNull TextPaint paint) {
                paint.setColor(color(R.color.browser_express_ugc_signup_accent));
                paint.setUnderlineText(false);
            }
        }, editStart, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (medium != null) {
            sub.setSpan(new TypefaceSpan(medium), editStart, sub.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mCodeSub.setText(sub);
    }

    private void backToDetails(@Nullable String error) {
        mStep = STEP_DETAILS;
        mPendingAutoSubmit = false;
        setVerifying(false);
        mRoot.removeCallbacks(mTick);
        stopSmsRetriever();
        swapSteps(mCode, mDetails);
        if (mBackCallback != null) mBackCallback.setEnabled(false);
        showError(mDetailsError, error);
        if (error != null) reject();
        focusAndShowKeyboard(mPhoneInput);
    }

    private void swapSteps(View from, View to) {
        from.setVisibility(View.GONE);
        to.setAlpha(0f);
        to.setVisibility(View.VISIBLE);
        to.animate().alpha(1f).setStartDelay(0).setDuration(STEP_FADE_MS).start();
    }

    // ----------------------------------------------------------------------------- code step

    private void onCodeChanged(String code) {
        if (mSettingCode) return;
        showError(mCodeError, null);
        if (!code.isEmpty()) mHero.react();
        // The last digit submits, typed, pasted or filled from the SMS.
        if (code.length() == CODE_LENGTH) {
            if (!isResumed()) {
                mPendingAutoSubmit = true;
                return;
            }
            submitCode(mAutoFilled ? "sms" : "auto");
        }
    }

    private void submitCode(String source) {
        if (mVerifying || mStep != STEP_CODE) return;
        String code = mCodeBoxes.getCode();
        if (code.length() < CODE_LENGTH) {
            showError(mCodeError, getResources().getQuantityString(
                    R.plurals.browser_express_ugc_signup_error_incomplete, CODE_LENGTH,
                    CODE_LENGTH));
            return;
        }
        setVerifying(true);
        hideKeyboard();
        try {
            JSONObject props = new JSONObject();
            props.put("flow", getFlow());
            props.put("source", source);
            BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_SUBMITTED, props);
        } catch (JSONException ignored) {
        }

        long startedMs = SystemClock.elapsedRealtime();
        String phone = UgcSignupLogic.toE164(mDigits);
        BrowserExpressPhoneAuthApi.verifyCode(phone, code, result -> {
            if (result.ok) {
                mSignedIn = true;
                // Saved now, so leaving during the spinner's minimum still signs the user in.
                saveSession(result.accessToken, result.refreshToken);
                trackSignedIn(result.accessToken);
                stopSmsRetriever();
                if (getView() == null) return;
                haptic(true);
                // "Getting you in…" stays at least 600 ms, so it never flashes.
                long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
                mRoot.postDelayed(this::openBrowser, Math.max(0, VERIFY_MIN_MS - elapsedMs));
                return;
            }
            BrowserExpressAuthJourney.trackError(
                    PostHogEventKeys.OTP_VERIFY_FAILED, getFlow(), describe(result));
            if (getView() == null) return;
            setVerifying(false);
            setCodeQuietly("");
            mAutoFilled = false;
            if ("otp_rate_limited".equals(result.error)) {
                serverWait(mDigits, result.retryAfterSeconds);
            }
            showError(mCodeError, verifyErrorText(result));
            reject();
            focusAndShowKeyboard(mCodeBoxes.getInput());
        });
    }

    private void resend() {
        if (mVerifying || mResendState == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!mResendState.canResend(now)) return;
        String digits = mDigits;
        mResendState = mResendState.afterResend(now);
        mResendByNumber.put(digits, mResendState);
        setCodeQuietly("");
        showError(mCodeError, null);
        tick();

        String purpose = mPurposeByNumber.get(digits);
        if (purpose == null) purpose = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
        BrowserExpressPhoneAuthApi.resendCode(UgcSignupLogic.toE164(digits), purpose, result -> {
            if (result.ok) {
                BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_RESENT, getFlow());
                serverWait(digits, result.resendAvailableInSeconds);
                // A fresh five-minute window for the new code.
                if (getView() != null && mStep == STEP_CODE) startSmsRetrieverTask();
                return;
            }
            serverWait(digits, result.retryAfterSeconds);
            if (getView() != null && mStep == STEP_CODE && digits.equals(mDigits)) {
                showError(mCodeError, sendErrorText(result));
            }
        });
    }

    /** The server's "not before N seconds" for {@code digits}; it always wins over ours. */
    private void serverWait(String digits, int seconds) {
        ResendState state = mResendByNumber.get(digits);
        if (state == null || seconds <= 0) return;
        state = state.atLeast(seconds, SystemClock.elapsedRealtime());
        mResendByNumber.put(digits, state);
        if (digits.equals(mDigits)) {
            mResendState = state;
            if (getView() != null && mStep == STEP_CODE) tick();
        }
    }

    /** Redraws the resend button, and again a quarter second later while on the code step. */
    private void tick() {
        mRoot.removeCallbacks(mTick);
        if (mStep != STEP_CODE || mResendState == null) return;
        ResendView view = mResendState.viewAt(SystemClock.elapsedRealtime());
        int colorRes;
        String text;
        switch (view.kind) {
            case ResendView.SENT:
                text = getString(R.string.browser_express_ugc_signup_code_sent);
                colorRes = R.color.browser_express_ugc_signup_ink;
                break;
            case ResendView.LOCKED:
                text = getString(R.string.browser_express_ugc_signup_resend_locked);
                colorRes = R.color.browser_express_ugc_signup_dim;
                break;
            case ResendView.COUNTDOWN:
                text = getString(
                        R.string.browser_express_ugc_signup_resend_in, view.countdownText());
                colorRes = R.color.browser_express_ugc_signup_dim;
                break;
            default:
                text = getString(R.string.browser_express_ugc_signup_resend);
                colorRes = R.color.browser_express_ugc_signup_accent;
                break;
        }
        if (!text.contentEquals(mResend.getText())) mResend.setText(text);
        mResend.setTextColor(color(colorRes));
        boolean enabled = view.isEnabled() && !mVerifying;
        mResend.setEnabled(enabled);
        mResend.setClickable(enabled);
        if (view.kind != ResendView.LOCKED && isResumed()) mRoot.postDelayed(mTick, TICK_MS);
    }

    private void setVerifying(boolean verifying) {
        mVerifying = verifying;
        mCodeBoxes.setVerifying(verifying);
        mVerifySpinner.setVisibility(verifying ? View.VISIBLE : View.GONE);
        mVerifyLabel.setText(verifying ? R.string.browser_express_ugc_signup_verifying
                                       : R.string.browser_express_ugc_signup_verify);
        mVerify.setEnabled(!verifying);
        if (mStep == STEP_CODE) tick();
    }

    /** Sets the code without it counting as a change: no auto-submit, the error stays. */
    private void setCodeQuietly(String code) {
        mSettingCode = true;
        mCodeBoxes.setCode(code);
        mSettingCode = false;
    }

    // -------------------------------------------------------------------- SMS auto-read

    /**
     * Listens for the code's SMS through the SMS Retriever API: the message carries this app's
     * hash, so Play services hands it over with no permission and no prompt.
     */
    private void startSmsRetriever() {
        if (mSmsReceiver == null) {
            mSmsReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) return;
                    if (intent.getExtras() == null) return;
                    Status status =
                            IntentUtils.safeGetParcelableExtra(intent, SmsRetriever.EXTRA_STATUS);
                    if (status == null) return;
                    if (status.getStatusCode() == CommonStatusCodes.SUCCESS) {
                        // Only app-hash broadcasts carry the message; a web page's WebOTP
                        // consent broadcast has none, and is left alone.
                        String message =
                                intent.getExtras().getString(SmsRetriever.EXTRA_SMS_MESSAGE);
                        if (message != null) onSms(message);
                    } else if (status.getStatusCode() == CommonStatusCodes.TIMEOUT) {
                        // Play services stops after about five minutes; listen again while the
                        // code step is up.
                        if (mStep == STEP_CODE) startSmsRetrieverTask();
                    }
                }
            };
            // SEND_PERMISSION: only Play services can send it, so no app can fake a code.
            ContextUtils.registerExportedBroadcastReceiver(ContextUtils.getApplicationContext(),
                    mSmsReceiver, new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                    SmsRetriever.SEND_PERMISSION);
        }
        startSmsRetrieverTask();
    }

    private void startSmsRetrieverTask() {
        SmsRetriever.getClient(ContextUtils.getApplicationContext())
                .startSmsRetriever()
                .addOnFailureListener(e -> Log.w(TAG, "SMS retriever didn't start: %s", e));
    }

    private void stopSmsRetriever() {
        if (mSmsReceiver == null) return;
        try {
            ContextUtils.getApplicationContext().unregisterReceiver(mSmsReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mSmsReceiver = null;
    }

    private void onSms(String message) {
        if (getView() == null || mStep != STEP_CODE || mVerifying) return;
        Matcher matcher = CODE_IN_SMS.matcher(message);
        if (!matcher.find()) return;
        mAutoFilled = true;
        BrowserExpressAuthJourney.track(PostHogEventKeys.OTP_AUTOFILLED_FROM_SMS, getFlow());
        // Fills the boxes like typing would; the last digit submits.
        mCodeBoxes.setCode(matcher.group(1));
    }

    // ---------------------------------------------------------------------- leaving

    private void continueAsGuest() {
        if (mVerifying) return;
        hideKeyboard();
        // Back to the browser at once; the account follows in the background, and Home says
        // "You're @name for now" when it arrives.
        BrowserExpressAuthUiUtil.skipAuthWithAutoLogin(this, "signup");
    }

    /**
     * Saves the session through the browser activity, which also links the referral, the push
     * token and the shared take; straight to the token store if the browser isn't found.
     */
    private void saveSession(String accessToken, String refreshToken) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            activity.setAccessToken(accessToken);
            activity.setRefreshToken(refreshToken);
            return;
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.w(TAG, "Browser not found; saving the session directly");
        }
        Context appContext = ContextUtils.getApplicationContext();
        appContext.getSharedPreferences(BravePreferenceKeys.BROWSER_EXPRESS_ACCESS_TOKEN, 0)
                .edit()
                .putString(BraveActivity.ACCESS_TOKEN_KEY, accessToken)
                .putString("RefreshToken", refreshToken)
                .apply();
        ReferralHelper.linkReferralToAccount(appContext, accessToken);
        BrowserExpressPushTokenManager.syncIfNeeded("login");
    }

    /** "User Signed Up" or "Login", on the account's own id. */
    private void trackSignedIn(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return;
            byte[] decoded =
                    Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String userId = new JSONObject(new String(decoded, StandardCharsets.UTF_8))
                    .getString("_id");
            String flow = getFlow();
            JSONObject payload = new JSONObject();
            try {
                payload.put("app_version", BraveActivity.getBraveActivity().getCurrentAppVersion());
            } catch (BraveActivity.BraveActivityNotFoundException ignored) {
            }
            payload.put("flow", flow);
            payload.put("auto_read", mAutoFilled);
            String event = BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_LOGIN.equals(flow)
                    ? PostHogEventKeys.LOGIN
                    : PostHogEventKeys.USER_SIGNED_UP;
            new PostHogUtil.PostHogWorkerTask(event, userId, payload)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (Exception e) {
            Log.w(TAG, "Sign-in event not sent: %s", e.getMessage());
        }
    }

    /**
     * Back to the running browser, finishing this page: CLEAR_TOP delivers the intent to the
     * existing browser and SINGLE_TOP reuses it.
     */
    private void openBrowser() {
        if (getActivity() == null) return;
        Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setAction(Intent.ACTION_VIEW);
        startActivity(intent);
        getActivity().finish();
    }

    /** "signup" or "login" for the number on screen. */
    private String getFlow() {
        String purpose = mPurposeByNumber.get(mDigits);
        return purpose != null ? purpose : BraveActivity.BROWSER_EXPRESS_OTP_PURPOSE_SIGNUP;
    }

    private void rememberNumber(String phone, String purpose) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            activity.setBrowserExpressPhoneNumber(phone);
            activity.setBrowserExpressOtpPurpose(purpose);
        } catch (BraveActivity.BraveActivityNotFoundException ignored) {
        }
    }

    // ---------------------------------------------------------------------- errors

    private String sendErrorText(BrowserExpressPhoneAuthApi.Result result) {
        if (result.network) return getString(R.string.browser_express_ugc_signup_error_offline);
        switch (result.error) {
            case "otp_rate_limited":
                return rateLimitedText(result.retryAfterSeconds);
            case "invalid_phone":
                return getString(R.string.browser_express_ugc_signup_error_phone);
            case "invalid_name":
                return getString(R.string.browser_express_ugc_signup_error_name);
            case "account_disabled":
                if (!result.message.isEmpty()) return result.message;
                break;
            default:
                break;
        }
        return getString(R.string.browser_express_ugc_signup_error_send_failed);
    }

    private String verifyErrorText(BrowserExpressPhoneAuthApi.Result result) {
        if (result.network) return getString(R.string.browser_express_ugc_signup_error_offline);
        switch (result.error) {
            case "phone_challenge_not_found":
                return getString(R.string.browser_express_ugc_signup_error_expired);
            case "too_many_otp_attempts":
                return getString(R.string.browser_express_ugc_signup_error_locked);
            case "otp_rate_limited":
                return rateLimitedText(result.retryAfterSeconds);
            case "account_disabled":
                if (!result.message.isEmpty()) return result.message;
                break;
            default:
                break;
        }
        return getString(R.string.browser_express_ugc_signup_error_wrong_code);
    }

    private String rateLimitedText(int retryAfterSeconds) {
        int minutes = Math.max(1, (int) Math.ceil((retryAfterSeconds > 0 ? retryAfterSeconds : 60)
                / 60.0));
        return getResources().getQuantityString(
                R.plurals.browser_express_ugc_signup_error_rate_limited, minutes, minutes);
    }

    private static String describe(BrowserExpressPhoneAuthApi.Result result) {
        if (result.network) return "network";
        return result.error.isEmpty() ? "HTTP " + result.httpCode : result.error;
    }

    /** Shows {@code error} under the form, or hides the line for null; read out when shown. */
    private void showError(TextView view, @Nullable String error) {
        if (error == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(error);
        view.setVisibility(View.VISIBLE);
        view.announceForAccessibility(error);
    }

    private void reject() {
        haptic(false);
    }

    private void haptic(boolean success) {
        int feedback;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            feedback = success ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.REJECT;
        } else {
            feedback = success ? HapticFeedbackConstants.CONTEXT_CLICK
                               : HapticFeedbackConstants.LONG_PRESS;
        }
        mRoot.performHapticFeedback(feedback);
    }

    // ---------------------------------------------------------------------- helpers

    private void focusAndShowKeyboard(EditText input) {
        mBlurGraceUntilMs = SystemClock.elapsedRealtime() + BLUR_GRACE_MS;
        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) input.getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) mRoot.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mRoot.getWindowToken(), 0);
    }

    /** Shrinks a button a little while it is held. */
    private static void pressScale(View button) {
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[] {android.R.attr.state_pressed}, scaleTo(button, PRESSED_SCALE));
        animator.addState(new int[0], scaleTo(button, 1f));
        button.setStateListAnimator(animator);
    }

    private static Animator scaleTo(View view, float scale) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, scale));
        set.setDuration(100);
        return set;
    }

    private int color(int colorRes) {
        return requireContext().getColor(colorRes);
    }

    private static @Nullable Typeface font(Context context, int fontRes) {
        try {
            return ResourcesCompat.getFont(context, fontRes);
        } catch (android.content.res.Resources.NotFoundException e) {
            return null;
        }
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    /** A TextWatcher that only needs afterTextChanged. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
