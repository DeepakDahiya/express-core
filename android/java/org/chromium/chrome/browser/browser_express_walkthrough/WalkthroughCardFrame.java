/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_walkthrough;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Holds one walkthrough card (its first child) with room around it for the card's soft shadow
 * and, on hero cards, its wasabi glow (README §2). The shadow is drawn by the frame's background
 * in that margin, so the frame is larger than the card: callers place it with {@link
 * #cardOffsetX} / {@link #cardOffsetY}. A caret, the frame's second child if any, pokes out of
 * the card's top edge to point at what the card is about.
 */
public class WalkthroughCardFrame extends FrameLayout {
    /** Prompt cards: 0 14px 36px rgba(0,0,0,.35). */
    static final float SHADOW_BLUR_DP = 36f;
    static final float SHADOW_DY_DP = 14f;
    static final float SHADOW_ALPHA = 0.35f;

    /** Hero cards add 0 0 26px rgba(196,240,50,.2). */
    static final float GLOW_BLUR_DP = 26f;
    static final float GLOW_ALPHA = 0.2f;

    /** The small cards (step 4's label, the toast): 0 8px 24px rgba(0,0,0,.25). */
    static final float LIGHT_SHADOW_BLUR_DP = 24f;
    static final float LIGHT_SHADOW_DY_DP = 8f;
    static final float LIGHT_SHADOW_ALPHA = 0.25f;

    private static final float CARET_DP = 10f;

    private @Nullable View mCaret;
    private float mCaretCenterX;

    public WalkthroughCardFrame(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * Draws the card's shadow, and the glow of a hero card, around a card with corners of
     * {@code radiusDp}.
     */
    void configure(boolean heroGlow, boolean lightShadow, float radiusDp, int glowColor) {
        Context context = getContext();
        float shadowBlur = WalkthroughMotion.dp(context,
                lightShadow ? LIGHT_SHADOW_BLUR_DP : SHADOW_BLUR_DP);
        float shadowDy = WalkthroughMotion.dp(context,
                lightShadow ? LIGHT_SHADOW_DY_DP : SHADOW_DY_DP);
        int shadowColor = Color.argb(
                Math.round(255 * (lightShadow ? LIGHT_SHADOW_ALPHA : SHADOW_ALPHA)), 0, 0, 0);
        int[] colors;
        float[] blurs;
        float[] offsets;
        if (heroGlow) {
            int glow = Color.argb(Math.round(255 * GLOW_ALPHA), Color.red(glowColor),
                    Color.green(glowColor), Color.blue(glowColor));
            colors = new int[] {glow, shadowColor};
            blurs = new float[] {WalkthroughMotion.dp(context, GLOW_BLUR_DP), shadowBlur};
            offsets = new float[] {0f, shadowDy};
        } else {
            colors = new int[] {shadowColor};
            blurs = new float[] {shadowBlur};
            offsets = new float[] {shadowDy};
        }
        // A blur reaches about its own radius past the shape (two sigmas), moved by its offset.
        int side = 0;
        int top = 0;
        int bottom = 0;
        for (int i = 0; i < blurs.length; i++) {
            side = Math.max(side, Math.round(blurs[i]));
            top = Math.max(top, Math.round(blurs[i] - offsets[i]));
            bottom = Math.max(bottom, Math.round(blurs[i] + offsets[i]));
        }
        // The caret pokes 5dp (7dp turned 45°) out of the card's top.
        top = Math.max(top, Math.round(WalkthroughMotion.dp(context, CARET_DP)));
        Rect padding = new Rect(side, top, side, bottom);
        setBackground(new WalkthroughShapes.ShadowDrawable(colors, blurs, offsets, padding,
                WalkthroughMotion.dp(context, radiusDp)));
        setPadding(side, top, side, bottom);
    }

    /** How far right of the frame's left edge the card starts. */
    int cardOffsetX() {
        return getPaddingLeft();
    }

    /** How far below the frame's top the card starts. */
    int cardOffsetY() {
        return getPaddingTop();
    }

    /** Points the caret up from the card's top edge, its tip {@code centerXPx} into the card. */
    void setCaretX(float centerXPx) {
        if (mCaretCenterX == centerXPx) return;
        mCaretCenterX = centerXPx;
        requestLayout();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 1) mCaret = getChildAt(1);
        if (mCaret != null) mCaret.setRotation(45f);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mCaret == null) return;
        float half = WalkthroughMotion.dp(getContext(), CARET_DP) / 2f;
        mCaret.setTranslationX(mCaretCenterX - half);
        mCaret.setTranslationY(-half);
    }
}
