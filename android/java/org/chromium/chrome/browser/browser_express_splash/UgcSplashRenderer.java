/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_splash;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import org.chromium.base.Log;
import org.chromium.base.StrictModeContext;
import org.chromium.chrome.R;

/**
 * Draws one frame of the UGC splash from ugc-splash-handoff at a time on the timeline: "UGC."
 * comes in, the letters split into a stack, each grows into its word (User / Generated / Content),
 * the pink full stop rides to "Content." and bursts once, and the phrase holds.
 *
 * <p>Every number is the handoff's (spec/splash-spec.json), in design units of a 390 x 844 frame.
 * Where the handoff's README, React Native component and reference video disagree, this follows the
 * video, which the README names the source of truth for motion. The video was rendered from the
 * web prototype, whose split and travel animations fill backwards and so replace the letters' 22pt
 * rise and the dot's 60pt drop: in the video the letters and the dot only fade in where they stand.
 * {@link #RISE_AND_DROP} turns the README's rise and drop back on.
 *
 * <p>Not thread-safe: made on the UI thread (it loads the font and grain tile), then used by one
 * thread only, the splash's render thread.
 */
final class UgcSplashRenderer {
    private static final String TAG = "UgcSplash";

    /** The README's rise (letters) and drop (dot). Off to match the reference video. */
    private static final boolean RISE_AND_DROP = false;

    /** Film grain over the screen. The README allows turning it off on frame drops. */
    private static final boolean GRAIN = true;

    // ---- Timeline, ms from the start (spec "timelineMs") -------------------------------------
    private static final float T_IN = 40;
    private static final float T_IN_STAGGER = 60;
    private static final float T_IN_DUR = 480;
    private static final float T_DOT_IN = 300;
    private static final float T_DOT_IN_DUR = 420;
    private static final float T_SPLIT = 780;
    private static final float T_SPLIT_STAGGER = 40;
    private static final float T_SPLIT_DUR = 520;
    private static final float T_WORDS = 1260;
    private static final float T_WORD_STAGGER = 55;
    private static final float T_WORD_DUR = 480;
    private static final float T_DIM = 1650;
    private static final float T_DIM_DUR = 500;
    private static final float T_TRAVEL = 1260;
    private static final float T_TRAVEL_DUR = 620;
    private static final float T_BURST = 1880;
    /** The hold ends and the fade starts; the splash waits here until the app is ready. */
    static final long T_OUT = 2700;
    static final long T_OUT_DUR = 300;
    static final PathInterpolator EASE_FADE = new PathInterpolator(.42f, 0f, 1f, 1f);

    // ---- Layout, design units of a 390 x 844 frame (spec "design") ---------------------------
    private static final float FRAME_W = 390;
    private static final float FRAME_H = 844;
    private static final float FRAME_CENTER_Y = 422;
    /**
     * Not in the handoff: the height that must fit, so a landscape screen scales by height instead
     * of width. The block is about 200 tall; on any portrait phone the width still decides.
     */
    private static final float FIT_H = 600;

    private static final float ROW_SIZE = 60;
    private static final float ROW_CENTER_Y = 438;
    private static final float[] ROW_X = {87.6f, 156.0f, 224.4f};
    private static final float STACK_SIZE = 46;
    private static final float STACK_X = 30;
    private static final float[] STACK_CENTER_Y = {362, 438, 514};
    private static final float LETTER_RISE_FROM = 22;

    private static final String[] WORDS = {"ser", "enerated", "ontent"};
    private static final float WORD_SIZE = 63;
    /** CSS letter-spacing -0.035em, i.e. -2.2 at 63. */
    private static final float WORD_LETTER_SPACING_EM = -0.035f;
    /** Gap from the letter's box to its word; the C's open side takes a tighter one. */
    private static final float[] WORD_GAP = {3, 3, -2};
    /** The prototype's word boxes start 31 above the line's centre (331 / 407 / 483). */
    private static final float WORD_TOP_ABOVE_CENTER = 31;
    /** Inter's baseline below the top of a line-height:1 box, which lands on the letter's bottom. */
    private static final float WORD_BASELINE_EM = 0.8637f;
    private static final float WORD_DIM_ALPHA = 0.54f;
    private static final float WORD_SLIDE_FROM = -16;
    private static final String WORD_FONT_VARIATION = "'wght' 800";

    private static final float DOT_ROW_SIZE = 13.2f;
    private static final float DOT_ROW_X = 289.2f;
    private static final float DOT_END_SIZE = 11.04f;
    private static final float DOT_END_GAP = 5;
    private static final float DOT_DROP_FROM = -60;
    private static final float DOT_OVERSHOOT = 4;

    /** Each burst pixel falls this far after it peaks. */
    private static final float PARTICLE_FALL = 18;

    // ---- Colours (spec "colors") ---------------------------------------------------------------
    static final int BACKGROUND = 0xFF0B0F09;
    private static final int INK = 0xFFF4F8EA;
    private static final int POP = 0xFFFF5FA2;
    private static final int WASABI = 0xFFBFD957;
    private static final float GRAIN_ALPHA = 0.075f;

    /** The burst's 13 pixels, precomputed so every launch looks the same (spec "particles"). */
    private static final Particle[] PARTICLES = {
        new Particle(4, POP, 35.93f, -4.21f, -52.1f, 55.0f, 759),
        new Particle(5, WASABI, 38.37f, -32.3f, 7.7f, 44.3f, 785),
        new Particle(7, INK, -30.13f, -24.33f, 37.4f, 47.5f, 634),
        new Particle(7, POP, -6.11f, -54.83f, 55.3f, -90.5f, 753),
        new Particle(8, POP, 28.38f, -37.23f, 13.9f, 98.9f, 856),
        new Particle(5, WASABI, -28.34f, -44.33f, -30.2f, -83.4f, 697),
        new Particle(6, INK, -36.21f, -42.22f, 10.3f, 55.0f, 848),
        new Particle(4, POP, -31.26f, -31.42f, 1.6f, -18.9f, 670),
        new Particle(8, POP, -38.44f, -1.25f, -28.1f, 39.5f, 860),
        new Particle(6, WASABI, -36.3f, -15.99f, -39.0f, -32.5f, 730),
        new Particle(5, INK, 5.01f, -45.65f, -48.0f, 97.6f, 739),
        new Particle(7, POP, 20.95f, -41.55f, 2.9f, 1.2f, 799),
        new Particle(8, POP, -17.03f, -45.68f, -44.8f, 20.8f, 711),
    };

    /**
     * The grain tile's jitter: the web prototype's keyframes (fractions of a layer twice the
     * frame's size), 0.9s round, steps(5) between each pair.
     */
    private static final float[][] GRAIN_KEYS = {
        {0, 0}, {-0.06f, 0.04f}, {0.05f, -0.07f}, {-0.03f, 0.08f}, {0.07f, 0.02f}, {0, 0}
    };
    private static final long GRAIN_ROUND_MS = 900;

    // ---- Easings: cubic-bezier over the whole segment, like CSS (spec "easingsCubicBezier") ----
    private static final PathInterpolator EASE_LETTER_IN = new PathInterpolator(.2f, 1.2f, .35f, 1f);
    private static final PathInterpolator EASE_SPLIT = new PathInterpolator(.65f, 0f, .25f, 1f);
    private static final PathInterpolator EASE_WORD = new PathInterpolator(.2f, .9f, .25f, 1f);
    private static final PathInterpolator EASE_DIM = new PathInterpolator(0f, 0f, .58f, 1f);
    private static final PathInterpolator EASE_DOT_IN = new PathInterpolator(.3f, 1.4f, .5f, 1f);
    private static final PathInterpolator EASE_TRAVEL = new PathInterpolator(.5f, 0f, .2f, 1f);
    private static final PathInterpolator EASE_PARTICLE = new PathInterpolator(.2f, .7f, .3f, 1f);

    /** The U, G and C (custom letterforms, not a font): 100 x 100 box, 26-unit strokes. */
    private static final Path[] GLYPHS = {glyphU(), glyphG(), glyphC()};
    /** The G's bar, filled. */
    private static final RectF G_BAR = new RectF(48.5f, 37f, 100f, 63f);
    private static final float GLYPH_BOX = 100;
    private static final float GLYPH_STROKE = 26;

    private final Paint mGlyphStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWordPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final @Nullable Paint mGrainPaint;
    private final Matrix mGrainMatrix = new Matrix();

    /** Scale (px per design unit) and the frame's origin; set by {@link #layout}. */
    private float mScale;
    private float mOriginX;
    private float mOriginY;
    private int mHeight;
    private final float[] mWordWidth = new float[WORDS.length];

    UgcSplashRenderer(Resources resources) {
        mGlyphStroke.setStyle(Paint.Style.STROKE);
        mGlyphStroke.setStrokeWidth(GLYPH_STROKE);
        mGlyphStroke.setStrokeCap(Paint.Cap.BUTT);
        mGlyphStroke.setColor(INK);
        mGlyphFill.setStyle(Paint.Style.FILL);
        mGlyphFill.setColor(INK);
        mDotPaint.setColor(POP);

        mWordPaint.setColor(INK);
        mWordPaint.setLetterSpacing(WORD_LETTER_SPACING_EM);
        mWordPaint.setTypeface(loadWordTypeface(resources));
        if (!mWordPaint.setFontVariationSettings(WORD_FONT_VARIATION)) {
            Log.w(TAG, "Inter weight axis not applied; words fall back to the font's default");
        }

        mGrainPaint = GRAIN ? loadGrainPaint(resources) : null;
    }

    /**
     * Scales the design frame to the surface's width (its height in landscape), centred so the G's
     * line sits at the centre, and measures the words at that size.
     */
    void layout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        mHeight = h;
        mScale = Math.min(w / FRAME_W, h / FIT_H);
        mOriginX = (w - FRAME_W * mScale) / 2;
        mOriginY = h / 2f - FRAME_CENTER_Y * mScale;
        mWordPaint.setTextSize(WORD_SIZE * mScale);
        for (int i = 0; i < WORDS.length; i++) mWordWidth[i] = mWordPaint.measureText(WORDS[i]);
    }

    /**
     * Draws the whole frame, background included, at {@code t} ms on the timeline (past
     * {@link #T_OUT} only while the burst's last pixels finish under the fade).
     *
     * @param grainMs Time since the splash started, which moves the grain (it keeps moving during
     *     the hold).
     */
    void draw(Canvas canvas, float t, long grainMs) {
        canvas.drawColor(BACKGROUND);
        if (mScale <= 0) return;
        drawGrain(canvas, grainMs);
        drawLetters(canvas, t);
        drawWords(canvas, t);
        drawDotAndBurst(canvas, t);
    }

    private float x(float designX) {
        return mOriginX + designX * mScale;
    }

    private float y(float designY) {
        return mOriginY + designY * mScale;
    }

    private void drawGrain(Canvas canvas, long grainMs) {
        Paint grain = mGrainPaint;
        if (grain == null) return;
        long ms = Math.max(0, grainMs) % GRAIN_ROUND_MS;
        long pairMs = GRAIN_ROUND_MS / (GRAIN_KEYS.length - 1);
        int pair = (int) (ms / pairMs);
        float step = (float) Math.floor((ms % pairMs) * 5f / pairMs) / 5f;
        float fx = lerp(GRAIN_KEYS[pair][0], GRAIN_KEYS[pair + 1][0], step);
        float fy = lerp(GRAIN_KEYS[pair][1], GRAIN_KEYS[pair + 1][1], step);
        // The layer is twice the frame, starting half a frame up and left.
        mGrainMatrix.setScale(mScale, mScale);
        mGrainMatrix.postTranslate(
                x(-FRAME_W / 2 + fx * 2 * FRAME_W), y(-FRAME_H / 2 + fy * 2 * FRAME_H));
        grain.getShader().setLocalMatrix(mGrainMatrix);
        canvas.drawPaint(grain);
    }

    private void drawLetters(Canvas canvas, float t) {
        for (int i = 0; i < GLYPHS.length; i++) {
            float in = EASE_LETTER_IN.getInterpolation(seg(t, T_IN + i * T_IN_STAGGER, T_IN_DUR));
            float alpha = clamp01(in);
            if (alpha <= 0) continue;
            float split =
                    EASE_SPLIT.getInterpolation(seg(t, T_SPLIT + i * T_SPLIT_STAGGER, T_SPLIT_DUR));
            float size = lerp(ROW_SIZE, STACK_SIZE, split);
            float left = lerp(ROW_X[i], STACK_X, split);
            float top =
                    lerp(ROW_CENTER_Y - ROW_SIZE / 2, STACK_CENTER_Y[i] - STACK_SIZE / 2, split);
            if (RISE_AND_DROP) top += (1 - in) * LETTER_RISE_FROM;

            int a = Math.round(alpha * 255);
            mGlyphStroke.setAlpha(a);
            mGlyphFill.setAlpha(a);
            float scale = size * mScale / GLYPH_BOX;
            int save = canvas.save();
            canvas.translate(x(left), y(top));
            canvas.scale(scale, scale);
            canvas.drawPath(GLYPHS[i], mGlyphStroke);
            if (i == 1) canvas.drawRect(G_BAR, mGlyphFill);
            canvas.restoreToCount(save);
        }
    }

    private void drawWords(Canvas canvas, float t) {
        for (int i = 0; i < WORDS.length; i++) {
            float p = EASE_WORD.getInterpolation(seg(t, T_WORDS + i * T_WORD_STAGGER, T_WORD_DUR));
            if (p <= 0) continue;
            float dim = EASE_DIM.getInterpolation(seg(t, T_DIM + i * T_WORD_STAGGER, T_DIM_DUR));
            mWordPaint.setAlpha(Math.round(255 * (1 - (1 - WORD_DIM_ALPHA) * dim)));

            float left = x(STACK_X + STACK_SIZE + WORD_GAP[i]) + (1 - p) * WORD_SLIDE_FROM * mScale;
            float baseline =
                    y(STACK_CENTER_Y[i] - WORD_TOP_ABOVE_CENTER + WORD_BASELINE_EM * WORD_SIZE);
            // A left-to-right wipe that slides with the word.
            int save = canvas.save();
            canvas.clipRect(left, 0, left + mWordWidth[i] * Math.min(1, p), mHeight);
            canvas.drawText(WORDS[i], left, baseline, mWordPaint);
            canvas.restoreToCount(save);
        }
    }

    private void drawDotAndBurst(Canvas canvas, float t) {
        float in = EASE_DOT_IN.getInterpolation(seg(t, T_DOT_IN, T_DOT_IN_DUR));
        float travel = EASE_TRAVEL.getInterpolation(seg(t, T_TRAVEL, T_TRAVEL_DUR));

        // In the row it sits on the row's baseline after the C; at the end, 5 after "Content",
        // bottom on the C's line.
        float rowX = x(DOT_ROW_X);
        float rowY = y(ROW_CENTER_Y + ROW_SIZE / 2 - DOT_ROW_SIZE);
        float endX = x(STACK_X + STACK_SIZE + WORD_GAP[2]) + mWordWidth[2] + DOT_END_GAP * mScale;
        float endY = y(STACK_CENTER_Y[2] + STACK_SIZE / 2 - DOT_END_SIZE);
        float endSize = DOT_END_SIZE * mScale;

        float dotAlpha = clamp01(in / 0.5f);
        if (dotAlpha > 0) {
            float size = lerp(DOT_ROW_SIZE, DOT_END_SIZE, travel) * mScale;
            float left = lerp(rowX, endX, travel);
            float top = lerp(rowY, endY, travel);
            if (RISE_AND_DROP) {
                // Falls onto the row, overshoots a little and settles (extrapolated past 1).
                float drop =
                        in <= 0.5f
                                ? lerp(DOT_DROP_FROM, DOT_OVERSHOOT, in / 0.5f)
                                : lerp(DOT_OVERSHOOT, 0, (in - 0.5f) / 0.5f);
                top += drop * mScale;
            }
            mDotPaint.setAlpha(Math.round(dotAlpha * 255));
            canvas.drawRect(left, top, left + size, top + size, mDotPaint);
        }

        if (t <= T_BURST) return;
        // Each pixel starts with its corner on the landed dot's centre, as in the prototype.
        float originX = endX + endSize / 2;
        float originY = endY + endSize / 2;
        for (Particle particle : PARTICLES) {
            float raw = (t - T_BURST) / particle.mDurationMs;
            if (raw >= 1) continue;
            float q = EASE_PARTICLE.getInterpolation(raw);
            // Keyframes at 0, .01, .6 and 1 of the eased progress.
            float alpha;
            float dx;
            float dy;
            float rotation;
            if (q <= 0.01f) {
                alpha = q / 0.01f;
                dx = 0;
                dy = 0;
                rotation = 0;
            } else if (q <= 0.6f) {
                float f = (q - 0.01f) / 0.59f;
                alpha = 1;
                dx = particle.mDx * f;
                dy = particle.mDy * f;
                rotation = particle.mRotation1 * f;
            } else {
                float f = (q - 0.6f) / 0.4f;
                alpha = 1 - f;
                dx = lerp(particle.mDx, particle.mDx * 1.15f, f);
                dy = lerp(particle.mDy, particle.mDy + PARTICLE_FALL, f);
                rotation = lerp(particle.mRotation1, particle.mRotation2, f);
            }
            if (alpha <= 0) continue;
            float size = particle.mSize * mScale;
            mParticlePaint.setColor(particle.mColor);
            mParticlePaint.setAlpha(Math.round(clamp01(alpha) * 255));
            int save = canvas.save();
            canvas.translate(originX + dx * mScale, originY + dy * mScale);
            canvas.rotate(rotation, size / 2, size / 2);
            canvas.drawRect(0, 0, size, size, mParticlePaint);
            canvas.restoreToCount(save);
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** Linear progress through [start, start + duration] at {@code t}, clamped to 0..1. */
    private static float seg(float t, float start, float duration) {
        return clamp01((t - start) / duration);
    }

    static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private static Typeface loadWordTypeface(Resources resources) {
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            Typeface inter = resources.getFont(R.font.browser_express_inter_variable);
            if (inter != null) return inter;
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Inter not loaded: %s", e.getMessage());
        }
        return Typeface.create(Typeface.SANS_SERIF, 800, false);
    }

    private static @Nullable Paint loadGrainPaint(Resources resources) {
        Bitmap tile;
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            tile = BitmapFactory.decodeResource(resources, R.drawable.browser_express_splash_grain);
        }
        if (tile == null) return null;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(GRAIN_ALPHA * 255));
        return paint;
    }

    /** "M13 0 V50 A37 37 0 0 0 87 50 V0". */
    private static Path glyphU() {
        Path path = new Path();
        path.moveTo(13, 0);
        path.lineTo(13, 50);
        path.arcTo(new RectF(13, 13, 87, 87), 180, -180, false);
        path.lineTo(87, 0);
        return path;
    }

    /** "M78.34 26.22 A37 37 0 1 0 87.00 50.00" (the bar is {@link #G_BAR}). */
    private static Path glyphG() {
        Path path = new Path();
        path.arcTo(new RectF(13, 13, 87, 87), angleFromCentre(78.34f, 26.22f), -320, true);
        return path;
    }

    /** "M77.50 25.24 A37 37 0 1 0 77.50 74.76". */
    private static Path glyphC() {
        Path path = new Path();
        float start = angleFromCentre(77.5f, 25.24f);
        path.arcTo(new RectF(13, 13, 87, 87), start, -(360 + 2 * start), true);
        return path;
    }

    /** The angle of a point on the glyphs' circle (centre 50,50), in Android's degrees. */
    private static float angleFromCentre(float px, float py) {
        return (float) Math.toDegrees(Math.atan2(py - 50, px - 50));
    }

    private static final class Particle {
        final float mSize;
        final int mColor;
        final float mDx;
        final float mDy;
        final float mRotation1;
        final float mRotation2;
        final float mDurationMs;

        Particle(
                float size,
                int color,
                float dx,
                float dy,
                float rotation1,
                float rotation2,
                float durationMs) {
            mSize = size;
            mColor = color;
            mDx = dx;
            mDy = dy;
            mRotation1 = rotation1;
            mRotation2 = rotation2;
            mDurationMs = durationMs;
        }
    }
}
