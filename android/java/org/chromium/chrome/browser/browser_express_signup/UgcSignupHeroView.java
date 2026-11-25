/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_signup;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.chromium.base.Log;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.R;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The sign-up screen's hero (ugc-signup-handoff, src/components/Hero.tsx): twelve topic tiles on
 * a two-row conveyor that bounce on a beat, a sound wave behind them that surges on the beat and on
 * every keystroke, emoji floating up at the edges, and an optional "N reacting" pill.
 *
 * <p>One frame clock drives all of it. The screen tells the hero where it may draw
 * ({@link #setRegion}); when the keyboard opens that region shrinks and so does everything in it,
 * tiles down to 40dp. Coordinates follow the handoff's 390dp-wide design, with x scaled to this
 * view's width. Decorative: hidden from screen readers. With animations turned off in system
 * settings it is a still picture.
 */
public class UgcSignupHeroView extends View {
    private static final String TAG = "UgcSignupHero";

    private static final float DESIGN_WIDTH = 390f;

    // Conveyor (src/logic/conveyor.ts), in dp; x in design units.
    private static final int[] TILES = {
        R.drawable.browser_express_ugc_signup_tile_cricket,
        R.drawable.browser_express_ugc_signup_tile_gossip,
        R.drawable.browser_express_ugc_signup_tile_politics,
        R.drawable.browser_express_ugc_signup_tile_movies,
        R.drawable.browser_express_ugc_signup_tile_music_headphones,
        R.drawable.browser_express_ugc_signup_tile_news_and_chai,
        R.drawable.browser_express_ugc_signup_tile_sports_trophy,
        R.drawable.browser_express_ugc_signup_tile_bollywood,
        R.drawable.browser_express_ugc_signup_tile_gaming_controller,
        R.drawable.browser_express_ugc_signup_tile_popcorn_entertainment,
        R.drawable.browser_express_ugc_signup_tile_music_microphone,
        R.drawable.browser_express_ugc_signup_tile_gaming_arcade,
    };
    private static final int TILE_COUNT = TILES.length;
    private static final float LAP_S = 64f;
    private static final float TILE_MAX = 104f;
    private static final float TILE_MIN = 40f;
    private static final float ENTER_X = 400f;
    private static final float DROP_DWELL = 36f;
    private static final float[] TILE_TILT = {-4f, 3f, -2f, 4f, -3f, 2f};
    private static final float EDGE_FADE = 0.12f;
    /** Tile shadow: 0 14 22 rgba(0,0,0,.5). */
    private static final float SHADOW_DY = 14f;
    private static final float SHADOW_BLUR = 11f;
    private static final int SHADOW_COLOR = 0x80000000;

    // Bounce ("thump").
    private static final float THUMP_PERIOD_S = 1.3f;
    private static final float THUMP_STAGGER_S = 0.17f;
    private static final float THUMP_LIFT = 4f;
    private static final float THUMP_SCALE = 0.04f;

    // Sound wave.
    private static final int BARS = 26;
    private static final float BAR_WIDTH = 8f;
    private static final float WAVE_SIDE = 22f;
    private static final float WAVE_OPACITY = 0.4f;
    private static final float[] EQ_T = {0f, 0.3f, 0.55f, 0.8f, 1f};
    private static final float[] EQ_V = {0.22f, 1f, 0.5f, 0.85f, 0.35f};

    // Beat and surge.
    private static final long BEAT_MS = 1300;
    private static final long SURGE_EASE_MS = 300;
    private static final long SURGE_HOLD_BEAT_MS = 220;
    private static final long SURGE_HOLD_KEY_MS = 160;
    private static final float SURGE_SCALE = 1.32f;
    private static final long REACT_THROTTLE_MS = 110;
    private static final PathInterpolator SURGE_EASE = new PathInterpolator(0.2f, 0.9f, 0.3f, 1f);

    // Emoji: Gen Z picks first, the usual ones last; 💀 and 😭 twice on purpose.
    private static final String[] EMOJI = {
        "💀", "🥀", "🐬", "🗿", "🫠",
        "🫡", "💅", "🤡", "🧢", "🫶",
        "🤌", "💀", "😭", "🔥", "😭",
        "😂", "❤️", "👀",
    };
    private static final int MAX_EMOJI = 14;
    private static final float EMOJI_RISE = 150f;

    // Live pill.
    private static final float PILL_HEIGHT = 30f;
    private static final float PILL_PADDING = 13f;
    private static final float PILL_DOT = 6f;
    private static final float PILL_GAP = 7f;
    private static final long PILL_PULSE_MS = 1200;

    private static final float[] BAR_HEIGHT = new float[BARS];
    private static final float[] BAR_DURATION_S = new float[BARS];
    private static final float[] BAR_DELAY_S = new float[BARS];
    private static final boolean[] BAR_HOT = new boolean[BARS];

    static {
        for (int i = 0; i < BARS; i++) {
            double env = Math.pow(Math.sin(Math.PI * (i + 0.5) / BARS), 1.3);
            BAR_HEIGHT[i] = Math.round(26 + env * 150);
            BAR_DURATION_S[i] = (420 + ((i * 173) % 380)) / 1000f;
            BAR_DELAY_S[i] = ((i * 211) % 800) / 1000f;
            BAR_HOT[i] = env > 0.78;
        }
    }

    /** Where the tiles sit for one region; y in dp, x in design units. */
    private static final class Geometry {
        float tile;
        float k;
        float y1;
        float y2;
        float seam;
        float row;
        float exitX;
        float loop;
    }

    private static final class Emoji {
        String ch;
        float x;
        float size;
        long bornMs;
        long durationMs;
        float opacity;
        float lift;
        float dx;
        float r0;
        float r1;
    }

    private final float mDensity;
    private final Random mRandom = new Random();
    private final Geometry mGeometry = new Geometry();
    private final RectF mRect = new RectF();
    private final Paint.FontMetrics mFontMetrics = new Paint.FontMetrics();

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mFadePaint = new Paint();
    private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHotBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mEmojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mPillNumberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private @Nullable Bitmap[] mTiles;
    private @Nullable Bitmap[] mShadows;
    private int[][] mShadowOffsets;
    private boolean mLoading;

    private float mRegionTopDp;
    private float mRegionBottomDp;
    private boolean mHasRegion;

    private boolean mAnimate;
    private long mStartMs;
    private long mLastBeat;
    private long mSurgeStartMs;
    private long mSurgeHoldMs;
    private float mSurgeFrom;
    private long mLastReactMs;
    private final List<Long> mPendingSpawns = new ArrayList<>();
    private final List<Emoji> mEmoji = new ArrayList<>();

    private @Nullable Integer mLiveCount;
    private @Nullable String mLiveNumber;
    private @Nullable String mLiveSuffix;

    public UgcSignupHeroView(Context context) {
        this(context, null);
    }

    public UgcSignupHeroView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        int accent = context.getColor(R.color.browser_express_ugc_signup_accent);
        int pop = context.getColor(R.color.browser_express_ugc_signup_pop);
        mBarPaint.setColor(accent);
        mBarPaint.setAlpha(Math.round(255 * WAVE_OPACITY));
        mHotBarPaint.setColor(pop);
        mHotBarPaint.setAlpha(Math.round(255 * WAVE_OPACITY));
        mShadowPaint.setColor(SHADOW_COLOR);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mEmojiPaint.setTextAlign(Paint.Align.CENTER);

        mPillPaint.setColor(context.getColor(R.color.browser_express_ugc_signup_pill_bg));
        mPillBorderPaint.setColor(context.getColor(R.color.browser_express_ugc_signup_pill_border));
        mPillBorderPaint.setStyle(Paint.Style.STROKE);
        mPillBorderPaint.setStrokeWidth(mDensity);
        mPillDotPaint.setColor(pop);
        float pillTextSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
        mPillNumberPaint.setColor(context.getColor(R.color.browser_express_ugc_signup_ink));
        mPillNumberPaint.setTextSize(pillTextSize);
        mPillTextPaint.setColor(context.getColor(R.color.browser_express_ugc_signup_pill_text));
        mPillTextPaint.setTextSize(pillTextSize);
    }

    /**
     * Where the hero may draw, in this view's pixels: from {@code topPx} down to
     * {@code bottomPx}. Call it every frame while the keyboard or the panel moves.
     */
    public void setRegion(float topPx, float bottomPx) {
        float top = topPx / mDensity;
        float bottom = bottomPx / mDensity;
        if (mHasRegion && top == mRegionTopDp && bottom == mRegionBottomDp) return;
        mRegionTopDp = top;
        mRegionBottomDp = bottom;
        mHasRegion = true;
        invalidate();
    }

    /** A real count of people reacting right now, or null (the default) to hide the pill. */
    public void setLiveCount(@Nullable Integer count) {
        mLiveCount = count;
        if (count != null) {
            mLiveNumber = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))
                    .format(count);
            String full = getContext().getString(
                    R.string.browser_express_ugc_signup_reacting, mLiveNumber);
            int at = full.indexOf(mLiveNumber);
            mLiveSuffix = at >= 0 ? full.substring(at + mLiveNumber.length()) : " " + full;
        }
        invalidate();
    }

    /** A keystroke: the room reacts with a small surge and, usually, one emoji. */
    public void react() {
        if (!mAnimate) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastReactMs < REACT_THROTTLE_MS) return;
        mLastReactMs = now;
        startSurge(now, SURGE_HOLD_KEY_MS);
        if (mRandom.nextFloat() < 0.6f) spawnEmoji(now);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAnimate = ValueAnimator.areAnimatorsEnabled();
        mStartMs = SystemClock.uptimeMillis();
        mLastBeat = 0;
        mEmoji.clear();
        mPendingSpawns.clear();
        if (mAnimate) {
            // The opening scatter, before the first beat.
            for (int i = 0; i < 5; i++) mPendingSpawns.add(mStartMs + i * 300L);
        }
        loadTiles();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && mAnimate) postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mFadePaint.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[] {0f, EDGE_FADE, 1f - EDGE_FADE, 1f}, Shader.TileMode.CLAMP));
    }

    /** Decodes the tiles and bakes their shadows off the UI thread. */
    private void loadTiles() {
        if (mTiles != null || mLoading) return;
        mLoading = true;
        Resources resources = getResources();
        PostTask.postTask(TaskTraits.USER_VISIBLE_MAY_BLOCK, () -> {
            Bitmap[] tiles = new Bitmap[TILE_COUNT];
            Bitmap[] shadows = new Bitmap[TILE_COUNT];
            int[][] offsets = new int[TILE_COUNT][2];
            Paint blur = new Paint();
            try {
                for (int i = 0; i < TILE_COUNT; i++) {
                    tiles[i] = BitmapFactory.decodeResource(resources, TILES[i]);
                    if (tiles[i] == null) continue;
                    // The blur in the bitmap's own pixels, which are drawn at 104dp.
                    float blurPx = SHADOW_BLUR * tiles[i].getWidth() / TILE_MAX;
                    blur.setMaskFilter(new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL));
                    shadows[i] = tiles[i].extractAlpha(blur, offsets[i]);
                }
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "Hero tiles not loaded: %s", e.getMessage());
                tiles = null;
            }
            final Bitmap[] loadedTiles = tiles;
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> {
                mLoading = false;
                if (loadedTiles == null) return;
                mTiles = loadedTiles;
                mShadows = shadows;
                mShadowOffsets = offsets;
                invalidate();
            });
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mHasRegion || getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        float t = mAnimate ? (now - mStartMs) / 1000f : 0f;
        if (mAnimate) {
            runBeats(now);
            runPendingSpawns(now);
        }
        computeGeometry(mRegionTopDp, mRegionBottomDp, mGeometry);
        float scaleX = getWidth() / mDensity / DESIGN_WIDTH;

        drawWave(canvas, mGeometry, t, now, scaleX);
        drawTiles(canvas, mGeometry, t, scaleX);
        drawEmoji(canvas, mGeometry, now, scaleX);
        drawPill(canvas, mGeometry, now);

        if (mAnimate) postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------------------- geometry

    private static void computeGeometry(float regionTop, float regionBottom, Geometry g) {
        float h = regionBottom - regionTop;
        g.tile = Math.max(TILE_MIN, Math.min(TILE_MAX, (h - 16f) / 2.19f));
        g.k = g.tile / TILE_MAX;
        float gap = 20f * g.k;
        g.y1 = regionTop + (h - (2 * g.tile + gap)) / 2f;
        g.y2 = g.y1 + g.tile + gap;
        g.seam = g.y1 + g.tile + gap / 2f;
        g.row = 410f + g.tile;
        g.exitX = -g.tile - 10f;
        float spacing = Math.max(118f * g.k, (2 * g.row + DROP_DWELL) / TILE_COUNT);
        g.loop = spacing * TILE_COUNT;
    }

    /** Top-left of tile {@code i} at {@code t} seconds: {x in design units, y in dp}. */
    private static void tilePosition(Geometry g, int i, float t, float[] out) {
        float frac = (((float) i / TILE_COUNT + t / LAP_S) % 1f + 1f) % 1f;
        float u = frac * g.loop;
        if (u < g.row) {
            out[0] = ENTER_X - u;
            out[1] = g.y1;
        } else if (u < g.row + DROP_DWELL) {
            out[0] = g.exitX;
            out[1] = g.y2;
        } else if (u < 2 * g.row + DROP_DWELL) {
            out[0] = g.exitX + (u - g.row - DROP_DWELL);
            out[1] = g.y2;
        } else {
            out[0] = ENTER_X;
            out[1] = g.y1;
        }
    }

    /** The thump's lift, 0 to 1, of tile {@code i} at {@code t} seconds. */
    private static float thump(int i, float t) {
        float delay = (i * THUMP_STAGGER_S) % THUMP_PERIOD_S;
        float p = (((t + delay) / THUMP_PERIOD_S) % 1f + 1f) % 1f;
        if (p < 0.1f) return easeInOut(p / 0.1f);
        if (p < 0.26f) return 1f - easeInOut((p - 0.1f) / 0.16f);
        return 0f;
    }

    private static float easeInOut(float x) {
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    // ---------------------------------------------------------------------------------- tiles

    private final float[] mPosition = new float[2];

    private void drawTiles(Canvas canvas, Geometry g, float t, float scaleX) {
        Bitmap[] tiles = mTiles;
        Bitmap[] shadows = mShadows;
        if (tiles == null || shadows == null) return;
        float d = mDensity;
        // The band the tiles and their shadows can reach, faded 12% at each side.
        float bandTop = Math.max(0, (g.y1 - 12f) * d);
        float bandBottom = Math.min(getHeight(), (g.y2 + g.tile + SHADOW_DY + 2 * SHADOW_BLUR) * d);
        if (bandBottom <= bandTop) return;
        int layer = canvas.saveLayer(0, bandTop, getWidth(), bandBottom, null);
        for (int i = 0; i < TILE_COUNT; i++) {
            Bitmap tile = tiles[i];
            if (tile == null) continue;
            tilePosition(g, i, t, mPosition);
            float a = mAnimate ? thump(i, t) : 0f;
            float centerX = mPosition[0] * scaleX * d + g.tile * d / 2f;
            float centerY = (mPosition[1] + g.tile / 2f - THUMP_LIFT * a * g.k) * d;
            // The bitmap (312px, nodpi) is drawn at 104dp; scale it to the tile, then the bounce.
            float bitmapScale = TILE_MAX * d / tile.getWidth();
            float scale = g.k * (1f + THUMP_SCALE * a) * bitmapScale;
            canvas.save();
            canvas.translate(centerX, centerY);
            canvas.rotate(TILE_TILT[i % TILE_TILT.length]);
            canvas.scale(scale, scale);
            float left = -tile.getWidth() / 2f;
            float top = -tile.getHeight() / 2f;
            Bitmap shadow = shadows[i];
            if (shadow != null) {
                canvas.drawBitmap(shadow, left + mShadowOffsets[i][0],
                        top + mShadowOffsets[i][1] + SHADOW_DY * d / bitmapScale, mShadowPaint);
            }
            canvas.drawBitmap(tile, left, top, mBitmapPaint);
            canvas.restore();
        }
        canvas.drawRect(0, bandTop, getWidth(), bandBottom, mFadePaint);
        canvas.restoreToCount(layer);
    }

    // ----------------------------------------------------------------------------------- wave

    private void drawWave(Canvas canvas, Geometry g, float t, long now, float scaleX) {
        float d = mDensity;
        float surge = surgeAt(now);
        boolean hot = mSurgeStartMs != 0 && now - mSurgeStartMs < mSurgeHoldMs;
        float waveScale = g.k * (1f + (SURGE_SCALE - 1f) * surge);
        float left = WAVE_SIDE * scaleX * d;
        float right = getWidth() - WAVE_SIDE * scaleX * d;
        float barWidth = BAR_WIDTH * d;
        float step = (right - left - barWidth) / (BARS - 1);
        float seam = g.seam * d;
        for (int i = 0; i < BARS; i++) {
            float eq = mAnimate ? eqAt(t, BAR_DURATION_S[i], BAR_DELAY_S[i]) : EQ_V[0];
            float half = BAR_HEIGHT[i] * eq * waveScale * d / 2f;
            float x = left + i * step;
            mRect.set(x, seam - half, x + barWidth, seam + half);
            float radius = Math.min(barWidth / 2f, half);
            Paint paint = hot && BAR_HOT[i] ? mHotBarPaint : mBarPaint;
            canvas.drawRoundRect(mRect, radius, radius, paint);
        }
    }

    /** One bar's level: the eq keyframes, played back and forth, eased per segment. */
    private static float eqAt(float t, float duration, float delay) {
        float c = (t + delay) / duration;
        int n = (int) Math.floor(c);
        float p = c - n;
        if (n % 2 == 1) p = 1f - p;
        int s = 0;
        while (s < 3 && p > EQ_T[s + 1]) s++;
        float lp = (p - EQ_T[s]) / (EQ_T[s + 1] - EQ_T[s]);
        return EQ_V[s] + (EQ_V[s + 1] - EQ_V[s]) * easeInOut(lp);
    }

    // ------------------------------------------------------------------------- beat and surge

    private void runBeats(long now) {
        long beat = (now - mStartMs) / BEAT_MS;
        if (beat <= mLastBeat) return;
        mLastBeat = beat;
        startSurge(now, SURGE_HOLD_BEAT_MS);
        int burst = 2 + (mRandom.nextBoolean() ? 1 : 0);
        for (int i = 0; i < burst; i++) mPendingSpawns.add(now + i * 260L);
    }

    /** Eases toward full over 300 ms, then back from wherever it got to once the hold ends. */
    private void startSurge(long now, long holdMs) {
        mSurgeFrom = surgeAt(now);
        mSurgeStartMs = now;
        mSurgeHoldMs = holdMs;
    }

    private float surgeAt(long now) {
        if (mSurgeStartMs == 0) return 0f;
        long elapsed = now - mSurgeStartMs;
        if (elapsed < mSurgeHoldMs) {
            return rise(Math.min(1f, elapsed / (float) SURGE_EASE_MS));
        }
        float atRelease = rise(Math.min(1f, mSurgeHoldMs / (float) SURGE_EASE_MS));
        float f = Math.min(1f, (elapsed - mSurgeHoldMs) / (float) SURGE_EASE_MS);
        return atRelease * (1f - SURGE_EASE.getInterpolation(f));
    }

    private float rise(float f) {
        return mSurgeFrom + (1f - mSurgeFrom) * SURGE_EASE.getInterpolation(f);
    }

    // ---------------------------------------------------------------------------------- emoji

    private void runPendingSpawns(long now) {
        Iterator<Long> it = mPendingSpawns.iterator();
        while (it.hasNext()) {
            if (it.next() <= now) {
                it.remove();
                spawnEmoji(now);
            }
        }
    }

    /** One emoji at the left or right edge, never over the tiles. */
    private void spawnEmoji(long now) {
        Emoji e = new Emoji();
        boolean left = mRandom.nextBoolean();
        e.ch = EMOJI[mRandom.nextInt(EMOJI.length)];
        e.x = left ? 6f + mRandom.nextFloat() * 44f : 318f + mRandom.nextFloat() * 44f;
        e.size = 20f + mRandom.nextFloat() * 8f;
        e.bornMs = now;
        e.durationMs = 2600 + (long) (mRandom.nextFloat() * 1600);
        e.opacity = 0.7f + mRandom.nextFloat() * 0.25f;
        e.lift = mRandom.nextFloat() * 30f;
        e.dx = (mRandom.nextFloat() - 0.5f) * 40f;
        e.r0 = (mRandom.nextFloat() - 0.5f) * 30f;
        e.r1 = (mRandom.nextFloat() - 0.5f) * 30f;
        if (mEmoji.size() >= MAX_EMOJI) mEmoji.remove(0);
        mEmoji.add(e);
    }

    private void drawEmoji(Canvas canvas, Geometry g, long now, float scaleX) {
        float d = mDensity;
        float k = 0.6f + 0.4f * g.k;
        Iterator<Emoji> it = mEmoji.iterator();
        while (it.hasNext()) {
            Emoji e = it.next();
            float v = (now - e.bornMs) / (float) e.durationMs;
            if (v >= 1f) {
                it.remove();
                continue;
            }
            // Fades in over the first 12% of its life and out over the last 45%.
            float opacity = v < 0.12f ? v / 0.12f * e.opacity
                    : v < 0.55f ? e.opacity : e.opacity * (1f - (v - 0.55f) / 0.45f);
            float baseY = 136f - e.lift - e.size;
            float y = g.seam + (baseY - EMOJI_RISE * v) * k;
            float x = DESIGN_WIDTH / 2f + (e.x - DESIGN_WIDTH / 2f) * k + e.dx * v * k;
            mEmojiPaint.setTextSize(e.size * d);
            mEmojiPaint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, opacity))));
            float scale = k * (0.6f + 0.4f * v);
            canvas.save();
            canvas.translate((x + e.size / 2f) * scaleX * d, (y + e.size / 2f) * d);
            canvas.rotate(e.r0 + (e.r1 - e.r0) * v);
            canvas.scale(scale, scale);
            mEmojiPaint.getFontMetrics(mFontMetrics);
            canvas.drawText(e.ch, 0, -(mFontMetrics.ascent + mFontMetrics.descent) / 2f,
                    mEmojiPaint);
            canvas.restore();
        }
    }

    // ----------------------------------------------------------------------------------- pill

    private void drawPill(Canvas canvas, Geometry g, long now) {
        if (mLiveCount == null || mLiveNumber == null || mLiveSuffix == null) return;
        ensurePillTypefaces();
        float d = mDensity;
        float numberWidth = mPillNumberPaint.measureText(mLiveNumber);
        float textWidth = numberWidth + mPillTextPaint.measureText(mLiveSuffix);
        float width = (2 * PILL_PADDING + PILL_DOT + PILL_GAP) * d + textWidth;
        float height = PILL_HEIGHT * d;
        float scale = Math.max(0.86f, g.k);
        canvas.save();
        canvas.translate(getWidth() / 2f, g.seam * d);
        canvas.scale(scale, scale);
        mRect.set(-width / 2f, -height / 2f, width / 2f, height / 2f);
        canvas.drawRoundRect(mRect, height / 2f, height / 2f, mPillPaint);
        mRect.inset(d / 2f, d / 2f);
        canvas.drawRoundRect(mRect, height / 2f, height / 2f, mPillBorderPaint);

        // The dot pulses 1 → .35 → 1 over 1.2 s.
        float phase = mAnimate ? ((now - mStartMs) % PILL_PULSE_MS) / (float) PILL_PULSE_MS : 0f;
        float pulse = phase < 0.5f ? easeInOut(phase * 2f) : 1f - easeInOut((phase - 0.5f) * 2f);
        mPillDotPaint.setAlpha(Math.round(255 * (1f - 0.65f * pulse)));
        float x = -width / 2f + PILL_PADDING * d;
        canvas.drawCircle(x + PILL_DOT * d / 2f, 0, PILL_DOT * d / 2f, mPillDotPaint);
        x += (PILL_DOT + PILL_GAP) * d;
        mPillTextPaint.getFontMetrics(mFontMetrics);
        float baseline = -(mFontMetrics.ascent + mFontMetrics.descent) / 2f;
        canvas.drawText(mLiveNumber, x, baseline, mPillNumberPaint);
        canvas.drawText(mLiveSuffix, x + numberWidth, baseline, mPillTextPaint);
        canvas.restore();
    }

    private boolean mPillTypefacesSet;

    private void ensurePillTypefaces() {
        if (mPillTypefacesSet) return;
        mPillTypefacesSet = true;
        Typeface regular = loadFont(R.font.browser_express_ugc_text_regular);
        Typeface semibold = loadFont(R.font.browser_express_ugc_text_semibold);
        if (regular != null) mPillTextPaint.setTypeface(regular);
        if (semibold != null) mPillNumberPaint.setTypeface(semibold);
    }

    private @Nullable Typeface loadFont(int fontRes) {
        try {
            return ResourcesCompat.getFont(getContext(), fontRes);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }
}
