/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The reaction picker's chips (Figma 483:3263): as many to a row as fit, wrapping onto the next.
 * A full row is spread edge to edge, as the design's space-between row is; the last row keeps the
 * minimum gap from the start, so a lone chip doesn't drift to the far side.
 */
public class ReactionChipFlowLayout extends ViewGroup {
    private static final int MIN_GAP_DP = 5;
    private static final int ROW_GAP_DP = 8;

    private final int mMinGap;
    private final int mRowGap;
    /** Index of each row's first child, from the last measure. */
    private final List<Integer> mRowStarts = new ArrayList<>();

    public ReactionChipFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        mMinGap = Math.round(MIN_GAP_DP * density);
        mRowGap = Math.round(ROW_GAP_DP * density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = width - getPaddingLeft() - getPaddingRight();
        int childSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);

        mRowStarts.clear();
        int height = getPaddingTop() + getPaddingBottom();
        int rowWidth = 0;
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int childWidth = child.getMeasuredWidth();
            boolean newRow = mRowStarts.isEmpty() || rowWidth + mMinGap + childWidth > available;
            if (newRow) {
                if (!mRowStarts.isEmpty()) height += rowHeight + mRowGap;
                mRowStarts.add(i);
                rowWidth = childWidth;
                rowHeight = child.getMeasuredHeight();
            } else {
                rowWidth += mMinGap + childWidth;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
        }
        if (!mRowStarts.isEmpty()) height += rowHeight;
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        int top = getPaddingTop();
        for (int row = 0; row < mRowStarts.size(); row++) {
            int start = mRowStarts.get(row);
            int end = row + 1 < mRowStarts.size() ? mRowStarts.get(row + 1) : getChildCount();

            int count = 0;
            int widths = 0;
            int rowHeight = 0;
            for (int i = start; i < end; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                count++;
                widths += child.getMeasuredWidth();
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            boolean lastRow = row == mRowStarts.size() - 1;
            int gap = !lastRow && count > 1 ? (available - widths) / (count - 1) : mMinGap;

            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int x = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
            for (int i = start; i < end; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                int y = top + (rowHeight - h) / 2;
                int left = rtl ? x - w : x;
                child.layout(left, y, left + w, y + h);
                x = rtl ? x - w - gap : x + w + gap;
            }
            top += rowHeight + mRowGap;
        }
    }
}
