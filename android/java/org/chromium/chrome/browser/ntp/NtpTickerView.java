/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.ntp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.util.TabUtils;

/**
 * NTP ticker — a thin pinned bar at the top of the New Tab Page header that
 * continuously side-scrolls a single string supplied by the backend. Tapping
 * anywhere on the bar opens the associated URL. When the backend marks the
 * payload as dismissable, a trailing close button is shown; dismissals are
 * keyed on the text content so a backend rotation re-shows the ticker.
 */
public class NtpTickerView extends FrameLayout {
    // Pixels per second the text travels. Tuned to feel readable but not slow.
    private static final float SCROLL_DP_PER_SECOND = 60f;
    // Separator placed between each repeated copy of the ticker text.
    // U+25C7 = WHITE DIAMOND. Padded with spaces for visual breathing room.
    private static final String SEPARATOR = "     \u25C7     ";

    private FrameLayout mTextContainer;
    private TextView mTextView;
    private ImageButton mCloseButton;
    private ValueAnimator mAnimator;
    private String mUrl;
    private String mCurrentText;

    public NtpTickerView(Context context) {
        super(context);
        init(context);
    }

    public NtpTickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NtpTickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.ntp_ticker, this, true);
        mTextContainer = findViewById(R.id.ntp_ticker_text_container);
        mTextView = findViewById(R.id.ntp_ticker_text);
        mCloseButton = findViewById(R.id.ntp_ticker_close);
        // Dark background and a subtle ripple so the whole bar feels tappable.
        setBackgroundColor(0xFF1A1A1A);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> {
            if (!TextUtils.isEmpty(mUrl)) {
                TabUtils.openUrlInSameTab(mUrl);
            }
        });
    }

    /**
     * Bind the backend payload. The view will configure visibility, click URL,
     * dismiss affordance, and (re)start the scrolling animation as needed.
     */
    public void bind(NtpTickerUtil.TickerData data) {
        if (data == null || !data.visible
                || TextUtils.isEmpty(data.text)
                || NtpTickerUtil.isDismissed(data.text)) {
            hide();
            return;
        }
        mUrl = data.url;
        mCurrentText = data.text;
        mTextView.setText(data.text);
        if (data.dismissable) {
            mCloseButton.setVisibility(View.VISIBLE);
            mCloseButton.setOnClickListener(v -> {
                NtpTickerUtil.markDismissed(mCurrentText);
                hide();
            });
        } else {
            mCloseButton.setVisibility(View.GONE);
            mCloseButton.setOnClickListener(null);
        }
        setVisibility(View.VISIBLE);
        // Wait for layout so we know the container & text widths before animating.
        mTextContainer.post(this::startScrolling);
    }

    private void hide() {
        cancelAnimator();
        setVisibility(View.GONE);
    }

    private void startScrolling() {
        cancelAnimator();
        final int containerWidth = mTextContainer.getWidth()
                - mTextContainer.getPaddingStart() - mTextContainer.getPaddingEnd();
        if (containerWidth <= 0) return;

        // Measure the width of a single "text + separator" unit by binding
        // just that string and forcing a measure pass.
        final String unit = mCurrentText + SEPARATOR;
        mTextView.setText(unit);
        mTextView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int unitWidth = mTextView.getMeasuredWidth();
        if (unitWidth <= 0) return;

        // Render enough copies that the strip always covers the viewport,
        // even when translated by one full unit. Need ceil(container/unit)+1.
        final int copies = (int) Math.ceil((double) containerWidth / unitWidth) + 1;
        final StringBuilder sb = new StringBuilder(unit.length() * copies);
        for (int i = 0; i < copies; i++) sb.append(unit);
        mTextView.setText(sb.toString());
        // Force the TextView's bounds to the full strip width. With
        // wrap_content the parent FrameLayout caps measured width at the
        // container width, so the trailing copies would never paint and the
        // animation would scroll into empty space.
        final int stripWidth = unitWidth * copies;
        ViewGroup.LayoutParams lp = mTextView.getLayoutParams();
        if (lp.width != stripWidth) {
            lp.width = stripWidth;
            mTextView.setLayoutParams(lp);
        }
        mTextView.setTranslationX(0f);

        // Translate by exactly one unit; on RESTART the next copy is already
        // in the previous copy's position so the loop is visually seamless.
        final float pxPerSec = dp(SCROLL_DP_PER_SECOND);
        final long durationMs = (long) (unitWidth / pxPerSec * 1000f);

        mAnimator = ValueAnimator.ofFloat(0f, -unitWidth);
        mAnimator.setDuration(durationMs);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setRepeatMode(ValueAnimator.RESTART);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> mTextView.setTranslationX((float) a.getAnimatedValue()));
        mAnimator.start();
    }

    private void cancelAnimator() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == View.VISIBLE && mAnimator == null
                && !TextUtils.isEmpty(mCurrentText)) {
            mTextContainer.post(this::startScrolling);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimator();
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
