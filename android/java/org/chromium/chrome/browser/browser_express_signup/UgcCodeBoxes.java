/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_signup;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.chromium.chrome.R;

/**
 * The verification code as a row of boxes over one hidden input (ugc-signup-handoff,
 * src/components/OtpBoxes.tsx). A single real input is what keeps paste and SMS autofill working.
 * The box that takes the next digit shows a blinking caret; every box turns accent while the code
 * verifies.
 */
public class UgcCodeBoxes extends FrameLayout {
    /** Told about every change, typed, pasted or filled from an SMS: digits only. */
    public interface Listener {
        void onCodeChanged(String code);
    }

    private static final long CARET_BLINK_MS = 500;

    private final LinearLayout mRow;
    private final EditText mInput;
    private final float mDensity;
    private @Nullable Typeface mDigitTypeface;
    private TextView[] mDigits = new TextView[0];
    private View[] mCarets = new View[0];
    private int mLength;
    private boolean mVerifying;
    private boolean mCaretOn = true;
    private boolean mSanitizing;
    private @Nullable Listener mListener;

    private final Runnable mBlink = new Runnable() {
        @Override
        public void run() {
            mCaretOn = !mCaretOn;
            updateBoxes();
        }
    };

    public UgcCodeBoxes(Context context) {
        this(context, null);
    }

    public UgcCodeBoxes(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        try {
            mDigitTypeface =
                    ResourcesCompat.getFont(context, R.font.browser_express_ugc_display_semibold);
        } catch (android.content.res.Resources.NotFoundException e) {
            mDigitTypeface = null;
        }

        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(mRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Covers the boxes, so a tap anywhere on them focuses it. Its text never shows.
        mInput = new EditText(context);
        mInput.setBackground(null);
        mInput.setPadding(0, 0, 0, 0);
        mInput.setTextColor(Color.TRANSPARENT);
        mInput.setHintTextColor(Color.TRANSPARENT);
        mInput.setHighlightColor(Color.TRANSPARENT);
        mInput.setCursorVisible(false);
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mInput.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_YES);
        mInput.setAutofillHints("smsOTPCode");
        mInput.setHint(R.string.browser_express_ugc_signup_code_description);
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                if (mSanitizing) return;
                String digits = editable.toString().replaceAll("\\D", "");
                if (digits.length() > mLength) digits = digits.substring(0, mLength);
                if (!digits.contentEquals(editable)) {
                    mSanitizing = true;
                    editable.replace(0, editable.length(), digits);
                    mSanitizing = false;
                }
                mCaretOn = true;
                updateBoxes();
                if (mListener != null) mListener.onCodeChanged(digits);
            }
        });
        mInput.setOnFocusChangeListener((v, hasFocus) -> {
            mCaretOn = true;
            updateBoxes();
        });
        addView(mInput, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setLength(4);
    }

    /** How many digits the code has. */
    public void setLength(int length) {
        if (length == mLength) return;
        mLength = length;
        mInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(length)});
        mRow.removeAllViews();
        mDigits = new TextView[length];
        mCarets = new View[length];
        int gap = Math.round(8 * mDensity);
        for (int i = 0; i < length; i++) {
            FrameLayout box = new FrameLayout(getContext());
            box.setBackgroundResource(R.drawable.browser_express_ugc_signup_code_box_bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) params.setMarginStart(gap);
            mRow.addView(box, params);

            TextView digit = new TextView(getContext());
            digit.setGravity(Gravity.CENTER);
            digit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            digit.setTextColor(
                    getContext().getColor(R.color.browser_express_ugc_signup_ink_strong));
            digit.setIncludeFontPadding(false);
            if (mDigitTypeface != null) digit.setTypeface(mDigitTypeface);
            box.addView(digit,
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mDigits[i] = digit;

            View caret = new View(getContext());
            caret.setBackgroundResource(R.drawable.browser_express_ugc_signup_caret);
            box.addView(caret, new LayoutParams(Math.round(2 * mDensity),
                    Math.round(26 * mDensity), Gravity.CENTER));
            mCarets[i] = caret;
        }
        mInput.setText("");
        updateBoxes();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** The input behind the boxes: focus it to type, or listen to its focus. */
    public EditText getInput() {
        return mInput;
    }

    public String getCode() {
        return mInput.getText().toString();
    }

    /** Sets the code, as an SMS autofill or a reset does; the listener hears about it. */
    public void setCode(String code) {
        mInput.setText(code);
        mInput.setSelection(mInput.length());
    }

    /** While the code verifies: every filled box accent, no caret, no typing. */
    public void setVerifying(boolean verifying) {
        mVerifying = verifying;
        mInput.setEnabled(!verifying);
        updateBoxes();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mBlink);
        super.onDetachedFromWindow();
    }

    private void updateBoxes() {
        String code = mInput.getText().toString();
        int caretAt = mInput.hasFocus() && !mVerifying && code.length() < mLength
                ? code.length() : -1;
        for (int i = 0; i < mLength; i++) {
            boolean filled = i < code.length();
            mDigits[i].setText(filled ? String.valueOf(code.charAt(i)) : "");
            View box = (View) mDigits[i].getParent();
            box.setSelected(filled);
            box.setActivated(i == caretAt || (mVerifying && filled));
            mCarets[i].setVisibility(i == caretAt && mCaretOn ? VISIBLE : INVISIBLE);
        }
        removeCallbacks(mBlink);
        if (caretAt >= 0) {
            postDelayed(mBlink, CARET_BLINK_MS);
        } else {
            mCaretOn = true;
        }
    }
}
