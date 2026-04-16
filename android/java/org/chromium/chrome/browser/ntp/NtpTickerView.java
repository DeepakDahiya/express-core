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
    // Gap between the trailing edge of one loop and the leading edge of the next.
    private static final float LOOP_GAP_DP = 64f;

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
        // Force a measure so the TextView reports its full intrinsic width
        // (it's wrap_content inside a clipping container).
        mTextView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int textWidth = mTextView.getMeasuredWidth();
        if (textWidth <= 0) return;

        final float gap = dp(LOOP_GAP_DP);
        // Travel = container + text + gap so the trailing edge fully clears.
        final float travel = containerWidth + textWidth + gap;
        final float pxPerSec = dp(SCROLL_DP_PER_SECOND);
        final long durationMs = (long) (travel / pxPerSec * 1000f);
        final float startX = containerWidth;
        final float endX = -(textWidth + gap);

        mAnimator = ValueAnimator.ofFloat(startX, endX);
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
