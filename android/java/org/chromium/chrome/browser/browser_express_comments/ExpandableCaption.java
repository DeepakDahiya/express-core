/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Text over a full-screen video (a reel's comment, a post video's caption) that a tap opens in
 * full and a second tap folds back.
 *
 * <p>Open, the text grows up over the video until it covers {@link #MAX_SCREEN_FRACTION} of the
 * screen, and scrolls past that rather than burying the video. A drag that starts on open text
 * with more to show scrolls the text, not the pager the video sits in.
 */
public final class ExpandableCaption {
    /** The most of the screen open text covers before it scrolls instead of growing. */
    private static final float MAX_SCREEN_FRACTION = 0.4f;

    private final ScrollView mScroll;
    private final TextView mText;
    private final int mCollapsedLines;
    private boolean mExpanded;

    /**
     * @param scroll the text's parent, which scrolls it once it is open past the cap.
     * @param collapsedLines how many lines the folded text shows.
     */
    // The touch listener only keeps a drag from the pager; taps still reach the text's click.
    @SuppressLint("ClickableViewAccessibility")
    public ExpandableCaption(ScrollView scroll, TextView text, int collapsedLines) {
        mScroll = scroll;
        mText = text;
        mCollapsedLines = collapsedLines;
        text.setOnClickListener(v -> setExpanded(!mExpanded));
        text.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (mScroll.canScrollVertically(1) || mScroll.canScrollVertically(-1))
                    && mScroll.getParent() != null) {
                // Above the scroll view only: it still has to take the drag itself.
                mScroll.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    /** Shows {@code text}, folded. */
    public void setText(CharSequence text) {
        mText.setText(text);
        setExpanded(false);
    }

    private void setExpanded(boolean expanded) {
        mExpanded = expanded;
        mText.setMaxLines(expanded ? Integer.MAX_VALUE : mCollapsedLines);
        mText.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        ViewGroup.LayoutParams params = mScroll.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (expanded && mText.getWidth() > 0) {
            // Measured now, at the width it already has, so the cap holds from the first frame
            // the text is open instead of it shooting up and then being cut back.
            mText.measure(
                    View.MeasureSpec.makeMeasureSpec(mText.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int cap = Math.round(
                    mText.getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_FRACTION);
            if (mText.getMeasuredHeight() > cap) params.height = cap;
        }
        mScroll.setLayoutParams(params);
        mScroll.scrollTo(0, 0);
    }
}
