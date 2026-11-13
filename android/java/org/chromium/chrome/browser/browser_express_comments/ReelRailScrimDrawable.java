/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The reel rail's contrast scrim (Figma "Rail contrast scrim"). Black deepens toward the right
 * edge, and the whole band fades out at its top and bottom, so the white rail stays readable over
 * bright video. Like everything over media, it is the same in both themes.
 */
final class ReelRailScrimDrawable extends Drawable {
    // Across: clear on the left, 54% black at the right edge.
    private static final int[] ACROSS_COLORS = {0x00000000, 0x1A000000, 0x57000000, 0x8A000000};
    private static final float[] ACROSS_STOPS = {0f, 0.38f, 0.72f, 1f};
    // Down: the design's luminance mask, as alpha.
    private static final int[] DOWN_MASK = {
        0x00000000, 0x9E000000, 0xFF000000, 0xFF000000, 0x94000000, 0x00000000
    };
    private static final float[] DOWN_STOPS = {0f, 0.12f, 0.24f, 0.78f, 0.92f, 1f};

    private final Paint mPaint = new Paint();

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        Shader across = new LinearGradient(bounds.left, 0, bounds.right, 0, ACROSS_COLORS,
                ACROSS_STOPS, Shader.TileMode.CLAMP);
        Shader down = new LinearGradient(0, bounds.top, 0, bounds.bottom, DOWN_MASK, DOWN_STOPS,
                Shader.TileMode.CLAMP);
        // DST_IN keeps the across gradient (dst) where the mask (src) is opaque.
        mPaint.setShader(new ComposeShader(across, down, PorterDuff.Mode.DST_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRect(getBounds(), mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
