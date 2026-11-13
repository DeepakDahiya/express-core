/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.R;

/**
 * Edit profile's spinner (design handoff "Edit profile"): a 2dp ring in the divider colour with a
 * quarter of it in the arc colour, one turn every 800ms. Save shows it while saving, the
 * username field while it checks.
 */
public class EditProfileSpinner extends View {
    private static final long PERIOD_MS = 800;
    private static final float STROKE_DP = 2f;

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();

    public EditProfileSpinner(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float stroke = STROKE_DP * getResources().getDisplayMetrics().density;
        for (Paint paint : new Paint[] {mTrack, mArc}) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }
        mTrack.setColor(ContextCompat.getColor(context, R.color.browser_express_profile_divider));
        mArc.setColor(ContextCompat.getColor(context, R.color.browser_express_edit_profile_link));
    }

    public void setArcColor(int color) {
        mArc.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = mTrack.getStrokeWidth() / 2f;
        mBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(mBounds, mTrack);
        float turn = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
        // The arc is the ring's top quarter (a border-top), turning clockwise.
        canvas.drawArc(mBounds, 225f + 360f * turn, 90f, false, mArc);
        if (isShown()) postInvalidateOnAnimation();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) invalidate();
    }
}
