/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.TextView;

/** TextView for the Premium YouTube gradient title in the shortcut prompt. */
public class GoldenYouTubeGradientTextView extends TextView {
    private static final int START_COLOR = 0xFFFF2D2D;
    private static final int END_COLOR = 0xFFC42BDF;

    public GoldenYouTubeGradientTextView(Context context) {
        super(context);
    }

    public GoldenYouTubeGradientTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GoldenYouTubeGradientTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateGradient();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        updateGradient();
    }

    private void updateGradient() {
        CharSequence text = getText();
        if (text == null || text.length() == 0) {
            getPaint().setShader(null);
            return;
        }

        float textWidth = getPaint().measureText(text.toString());
        if (textWidth <= 0) return;

        getPaint().setShader(
                new LinearGradient(0, 0, textWidth, 0, START_COLOR, END_COLOR,
                        Shader.TileMode.CLAMP));
        invalidate();
    }
}
