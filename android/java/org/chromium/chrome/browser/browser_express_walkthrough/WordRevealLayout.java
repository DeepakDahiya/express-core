/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A title that appears word by word (README §3: 90ms apart, each over 350ms, rising 8dp). The
 * words wrap like text. The single TextView child in XML is the style template: its text, size,
 * font, colour and letter spacing are copied onto one view per word, and its paddingEnd is the
 * gap between words. Screen readers get the whole title at once.
 */
public class WordRevealLayout extends ViewGroup {
    private @Nullable TextView mTemplate;
    private final List<TextView> mWords = new ArrayList<>();
    private int mGap;
    private int[] mLineOf = new int[0];

    public WordRevealLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() == 0 || !(getChildAt(0) instanceof TextView)) return;
        mTemplate = (TextView) getChildAt(0);
        mGap = mTemplate.getPaddingEnd();
        removeAllViews();
        setText(mTemplate.getText());
    }

    void setText(CharSequence text) {
        removeAllViews();
        mWords.clear();
        setContentDescription(text);
        if (mTemplate == null) return;
        for (String word : text.toString().trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            TextView view = new TextView(getContext());
            view.setText(word);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, mTemplate.getTextSize());
            view.setTypeface(mTemplate.getTypeface());
            view.setTextColor(mTemplate.getTextColors());
            view.setLetterSpacing(mTemplate.getLetterSpacing());
            view.setIncludeFontPadding(mTemplate.getIncludeFontPadding());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.setLineHeight(mTemplate.getLineHeight());
            }
            view.setMaxLines(1);
            view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            mWords.add(view);
        }
        // Which line each word lands on, filled in by onMeasure.
        mLineOf = new int[mWords.size()];
    }

    /** Hides every word until {@link #reveal}. */
    void hideWords() {
        for (View word : mWords) {
            word.animate().cancel();
            word.setAlpha(0f);
        }
    }

    /** The words appear one after another, the first {@code startDelayMs} from now. */
    void reveal(long startDelayMs) {
        float rise = WalkthroughMotion.dp(getContext(), WalkthroughMotion.RISE_DP);
        for (int i = 0; i < mWords.size(); i++) {
            WalkthroughMotion.fadeIn(mWords.get(i),
                    startDelayMs + i * WalkthroughMotion.WORD_STAGGER, WalkthroughMotion.WORD_MS,
                    rise);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // MeasureSpec keeps sizes in 30 bits.
        int maxWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? (1 << 30) - 1
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int count = Math.min(getChildCount(), mLineOf.length);
        int lineWidth = 0;
        int lineHeight = 0;
        int widest = 0;
        int height = 0;
        int line = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            int start = lineWidth == 0 ? 0 : lineWidth + mGap;
            if (lineWidth > 0 && start + w > maxWidth) {
                height += lineHeight;
                widest = Math.max(widest, lineWidth);
                line++;
                lineWidth = w;
                lineHeight = child.getMeasuredHeight();
            } else {
                lineWidth = start + w;
                lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
            mLineOf[i] = line;
        }
        height += lineHeight;
        widest = Math.max(widest, lineWidth);
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec)
                : widest + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int width = r - l;
        int x = 0;
        int y = getPaddingTop();
        int lineHeight = 0;
        int line = 0;
        for (int i = 0; i < Math.min(getChildCount(), mLineOf.length); i++) {
            View child = getChildAt(i);
            if (mLineOf[i] != line) {
                line = mLineOf[i];
                y += lineHeight;
                x = 0;
                lineHeight = 0;
            }
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            int left = rtl ? width - getPaddingRight() - x - w : getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + mGap;
            lineHeight = Math.max(lineHeight, h);
        }
    }
}
