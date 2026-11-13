/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * The Telegram-style emoji explosion played when the reader reacts to a comment, ported from the
 * design handoff's emoji-burst.js (src/design_handoff_emoji_burst, "Motion spec").
 *
 * <p>On a card the burst fills that card and is clipped to it; on a reel page it pops from the
 * tapped rail chip, explodes from a centre near it in 2–3 random lobes and fills the page. Every
 * emoji works: about 60 hand-tuned families (❤️ brings 💖💕💘🌹, 🔥 brings 💥⚡🧨), a flag bursts
 * as copies of itself with 🎉✨🎊🏆, and any other emoji brings its neighbours in Unicode's order
 * ({@link EmojiCatalog}) with a ring colour sampled from its own pixels.
 *
 * <p>It draws in the container's {@link android.view.ViewOverlay}: no layout, no touches. Each
 * emoji is rasterised once into a bitmap and frames only draw bitmaps, so a reel burst (about 90
 * draws a frame) stays light. With animations turned off only the pop on the chip plays.
 * Distances are in dp, times in seconds, drag is exponential per second.
 */
final class EmojiBurst {
    private static final int SPRITE = 96;
    private static final int MAX_PARTICLES = 600;
    private static final int FLAG_COLOR = 0xFFFFC83D;
    private static final int FALLBACK_COLOR = 0xFFFFC83D;
    private static final String SPARKLES = "✨";

    private static final int KIND_HERO = 0;
    private static final int KIND_RING = 1;
    private static final int KIND_EMOJI = 2;
    private static final int KIND_DOT = 3;

    private static final Random RANDOM = new Random();
    private static final Map<View, Layer> sLayers = new WeakHashMap<>();
    private static final LruCache<String, Family> sFamilies = new LruCache<>(64);
    private static final LruCache<String, Bitmap> sSprites = new LruCache<>(48);
    private static @Nullable Map<String, Family> sThemes;
    private static @Nullable Map<String, int[]> sCatalogIndex;
    private static @Nullable String[][] sCatalogSections;
    private static @Nullable Paint sSpritePaint;
    /** The text size the sprites are drawn at; a particle of size s is scaled by s / this. */
    private static float sSpriteTextSize;

    private EmojiBurst() {}

    /**
     * Plays a burst of {@code emoji} in {@code container}, popping from {@code origin} (the
     * container's centre when null). {@code fullscreen} is the reel page's burst; otherwise it is
     * a card's, clipped to the card's rounded corners.
     */
    static void play(View container, @Nullable View origin, String emoji, boolean fullscreen) {
        if (emoji == null || emoji.isEmpty()) return;
        if (container.getWidth() == 0 || container.getHeight() == 0) return;
        if (!container.isAttachedToWindow()) return;
        Layer layer = sLayers.get(container);
        if (layer == null) {
            layer = new Layer(container);
            container.getOverlay().add(layer);
            sLayers.put(container, layer);
        }
        layer.setBounds(0, 0, container.getWidth(), container.getHeight());
        layer.mCornerRadius = fullscreen ? 0f : cornerRadiusOf(container);

        float ox = container.getWidth() / 2f;
        float oy = container.getHeight() / 2f;
        if (origin != null && origin.getWidth() > 0) {
            int[] host = new int[2];
            int[] tile = new int[2];
            container.getLocationInWindow(host);
            origin.getLocationInWindow(tile);
            ox = tile[0] - host[0] + origin.getWidth() / 2f;
            oy = tile[1] - host[1] + origin.getHeight() / 2f;
        }

        float dp = container.getResources().getDisplayMetrics().density;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Reduced motion: only the pop on the chip.
            List<Particle> pop = new ArrayList<>();
            pop.add(hero(emoji, ox, oy, ox, oy - 8 * dp, (fullscreen ? 30 : 22) * dp, 0.25f,
                    0.45f));
            layer.add(pop);
            return;
        }
        Family family = family(emoji);
        layer.add(fullscreen
                        ? fullscreen(layer, dp, emoji, family, ox, oy)
                        : contained(layer, dp, emoji, family, ox, oy));
    }

    /** Stops and removes the burst in {@code container}, e.g. when its row is recycled. */
    static void cancel(View container) {
        Layer layer = sLayers.remove(container);
        if (layer == null) return;
        layer.stop();
        container.getOverlay().remove(layer);
    }

    private static float cornerRadiusOf(View view) {
        Drawable background = view.getBackground();
        if (background instanceof GradientDrawable) {
            return Math.max(0f, ((GradientDrawable) background).getCornerRadius());
        }
        return 0f;
    }

    // ---------- the two shapes ----------

    /** A card: 30 emoji and 18 sparks stretched sideways to fill the wide card. */
    private static List<Particle> contained(
            Layer layer, float dp, String emoji, Family family, float ox, float oy) {
        float w = layer.getBounds().width();
        float h = layer.getBounds().height();
        float cx = w * 0.5f;
        float cy = h * 0.48f;
        float hx = cx + (ox - cx) * 0.25f;
        float hy = Math.min(cy, oy - 24 * dp);

        List<Particle> list = new ArrayList<>();
        list.add(hero(emoji, ox, oy, ox, oy - 10 * dp, 22 * dp, 0.28f, 0.5f));
        list.add(ring(hx, hy, 6 * dp, Math.max(w, h) * 0.55f, 6 * dp, family.color));
        for (int i = 0; i < 30; i++) {
            double a = rnd(0, Math.PI * 2);
            float speed = rnd(160, 520) * dp;
            Particle p = emoji(family.set[i % family.set.length], hx, hy);
            p.vx = (float) Math.cos(a) * speed * 1.5f;
            p.vy = (float) Math.sin(a) * speed * 0.8f;
            p.drag = 2.6f;
            p.gravity = 90 * dp;
            p.size = rnd(12, 24) * dp;
            list.add(p);
        }
        for (int i = 0; i < 18; i++) {
            double a = rnd(0, Math.PI * 2);
            float speed = rnd(120, 420) * dp;
            list.add(dot(hx, hy, (float) Math.cos(a) * speed * 1.4f,
                    (float) Math.sin(a) * speed * 0.8f, 3f, 60 * dp, rnd(1.5f, 3f) * dp,
                    family.color));
        }
        return list;
    }

    /**
     * A reel: the pop on the tile, then a burst from a centre near it in 2–3 lobes of random
     * direction, width and reach, so no two taps fill the screen alike.
     */
    private static List<Particle> fullscreen(
            Layer layer, float dp, String emoji, Family family, float ox, float oy) {
        final float w = layer.getBounds().width();
        final float h = layer.getBounds().height();
        final float margin = 18 * dp;
        final float drag = 4f;
        final float cx = clamp(ox - rnd(50, 160) * dp, margin + 40 * dp, w - margin - 20 * dp);
        final float cy = clamp(oy + rnd(-170, 70) * dp, margin + 60 * dp, h - margin - 60 * dp);

        int lobeCount = RANDOM.nextFloat() < 0.45f ? 3 : 2;
        double[][] lobes = new double[lobeCount][];
        double weightSum = 0;
        for (int i = 0; i < lobeCount; i++) {
            // Mostly towards the screen (the rail is on the right), sometimes anywhere.
            double direction = RANDOM.nextFloat() < 0.75f ? rnd(Math.PI * 0.55, Math.PI * 1.45)
                                                          : rnd(0, Math.PI * 2);
            lobes[i] = new double[] {direction, rnd(0.3, 0.75), rnd(0.55, 1), rnd(0.6, 1.4)};
            weightSum += lobes[i][3];
        }

        List<Particle> list = new ArrayList<>();
        list.add(hero(emoji, ox, oy, ox - 6 * dp, oy - 14 * dp, 30 * dp, 0.3f, 0.55f));
        list.add(ring(cx, cy, 8 * dp, (float) (rnd(0.5, 0.75) * Math.hypot(w, h)), 7 * dp,
                family.color));
        int count = Math.round(rnd(48, 62));
        for (int i = 0; i < count; i++) {
            double a;
            double distance;
            if (RANDOM.nextFloat() < 0.72f) {
                double[] lobe = pickLobe(lobes, weightSum);
                a = lobe[0] + gauss() * lobe[1];
                distance = reach(a, cx, cy, w, h, margin) * lobe[2]
                        * Math.pow(RANDOM.nextDouble(), 0.65);
            } else {
                // The tight core around the centre.
                a = rnd(0, Math.PI * 2);
                distance = Math.min(reach(a, cx, cy, w, h, margin), rnd(24, 130) * dp);
            }
            distance = Math.max(14 * dp, distance);
            Particle p = emoji(family.set[i % family.set.length], cx, cy);
            // With drag d an emoji launched at distance × d comes to rest at that distance.
            p.vx = (float) (Math.cos(a) * distance * drag);
            p.vy = (float) (Math.sin(a) * distance * drag);
            p.drag = drag;
            p.gravity = 40 * dp;
            p.size = rnd(16, 30) * dp
                    * (1 - 0.3f * (float) Math.min(1, distance / (420 * dp)));
            list.add(p);
        }
        for (int i = 0; i < 24; i++) {
            double[] lobe = pickLobe(lobes, weightSum);
            double a = lobe[0] + gauss() * lobe[1] * 1.3;
            double distance = reach(a, cx, cy, w, h, margin) * rnd(0.15, 0.6);
            Particle p = dot(cx, cy, (float) (Math.cos(a) * distance * 3.2),
                    (float) (Math.sin(a) * distance * 3.2), 3.2f, 60 * dp,
                    rnd(1.8f, 3.4f) * dp, i % 3 != 0 ? family.color : Color.WHITE);
            p.life = rnd(0.55f, 0.9f);
            list.add(p);
        }
        return list;
    }

    /** How far from (cx, cy) along angle {@code a} the screen edge, less the margin, is. */
    private static double reach(double a, float cx, float cy, float w, float h, float margin) {
        double dx = Math.cos(a);
        double dy = Math.sin(a);
        double tx = dx < 0 ? (cx - margin) / -dx : (w - margin - cx) / Math.max(dx, 1e-3);
        double ty = dy < 0 ? (cy - margin) / -dy : (h - margin - cy) / Math.max(dy, 1e-3);
        return Math.max(0, Math.min(tx, ty));
    }

    private static double[] pickLobe(double[][] lobes, double weightSum) {
        double r = RANDOM.nextDouble() * weightSum;
        for (double[] lobe : lobes) {
            r -= lobe[3];
            if (r <= 0) return lobe;
        }
        return lobes[0];
    }

    private static Particle hero(String emoji, float x0, float y0, float x1, float y1, float size,
            float hold, float life) {
        Particle p = new Particle(KIND_HERO);
        p.emoji = emoji;
        p.x0 = x0;
        p.y0 = y0;
        p.x1 = x1;
        p.y1 = y1;
        p.size = size;
        p.hold = hold;
        p.life = life;
        return p;
    }

    private static Particle ring(float x, float y, float r0, float r1, float lineWidth, int color) {
        Particle p = new Particle(KIND_RING);
        p.x = x;
        p.y = y;
        p.r0 = r0;
        p.r1 = r1;
        p.lineWidth = lineWidth;
        p.color = color;
        p.life = 0.7f;
        p.delay = 0.12f;
        return p;
    }

    private static Particle emoji(String emoji, float x, float y) {
        Particle p = new Particle(KIND_EMOJI);
        p.emoji = emoji;
        p.x = x;
        p.y = y;
        p.rot = rnd(-0.5f, 0.5f);
        p.spin = rnd(-4f, 4f);
        p.life = rnd(0.9f, 1.5f);
        p.delay = 0.14f + rnd(0f, 0.12f);
        return p;
    }

    private static Particle dot(float x, float y, float vx, float vy, float drag, float gravity,
            float radius, int color) {
        Particle p = new Particle(KIND_DOT);
        p.x = x;
        p.y = y;
        p.vx = vx;
        p.vy = vy;
        p.drag = drag;
        p.gravity = gravity;
        p.radius = radius;
        p.color = color;
        p.life = rnd(0.5f, 0.9f);
        p.delay = 0.14f;
        return p;
    }

    // ---------- the overlay ----------

    private static final class Particle {
        final int kind;
        @Nullable String emoji;
        @Nullable Bitmap sprite;
        float x0, y0, x1, y1;
        float x, y, vx, vy, drag, gravity;
        float rot, spin, size, hold;
        float radius, r0, r1, lineWidth;
        int color;
        float life, delay, t;

        Particle(int kind) {
            this.kind = kind;
        }
    }

    /** One container's particles, drawn in its overlay and stepped on each vsync while alive. */
    private static final class Layer extends Drawable implements Choreographer.FrameCallback {
        // Weak: sLayers is keyed by the host, and a value holding its key would never be freed.
        private final WeakReference<View> mHost;
        private final float mDensity;
        private final List<Particle> mItems = new ArrayList<>();
        private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix mMatrix = new Matrix();
        private final Path mClip = new Path();
        private final RectF mClipRect = new RectF();
        private float mCornerRadius;
        private long mLastFrameNanos;
        private boolean mRunning;

        Layer(View host) {
            mHost = new WeakReference<>(host);
            mDensity = host.getResources().getDisplayMetrics().density;
            mStroke.setStyle(Paint.Style.STROKE);
        }

        void add(List<Particle> particles) {
            mItems.addAll(particles);
            if (mItems.size() > MAX_PARTICLES) {
                mItems.subList(0, mItems.size() - MAX_PARTICLES).clear();
            }
            if (!mRunning) {
                mRunning = true;
                mLastFrameNanos = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
            invalidateSelf();
        }

        void stop() {
            mItems.clear();
            if (mRunning) {
                mRunning = false;
                Choreographer.getInstance().removeFrameCallback(this);
            }
            invalidateSelf();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            View host = mHost.get();
            if (host == null || !host.isAttachedToWindow()) {
                stop();
                return;
            }
            float dt = mLastFrameNanos == 0
                    ? 1 / 60f
                    : Math.min(0.033f, (frameTimeNanos - mLastFrameNanos) / 1e9f);
            mLastFrameNanos = frameTimeNanos;
            step(dt);
            invalidateSelf();
            if (mItems.isEmpty()) {
                mRunning = false;
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void step(float dt) {
            for (int i = mItems.size() - 1; i >= 0; i--) {
                Particle p = mItems.get(i);
                p.t += dt;
                if (p.t >= p.life + p.delay) {
                    mItems.remove(i);
                    continue;
                }
                if (p.t < p.delay) continue;
                if (p.kind == KIND_EMOJI || p.kind == KIND_DOT) {
                    float f = (float) Math.exp(-p.drag * dt);
                    p.vx *= f;
                    p.vy = p.vy * f + p.gravity * dt;
                    p.x += p.vx * dt;
                    p.y += p.vy * dt;
                    if (p.kind == KIND_EMOJI) p.rot += p.spin * dt;
                }
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mItems.isEmpty()) return;
            int save = canvas.save();
            Rect bounds = getBounds();
            if (mCornerRadius > 0) {
                mClipRect.set(bounds);
                mClip.rewind();
                mClip.addRoundRect(mClipRect, mCornerRadius, mCornerRadius, Path.Direction.CW);
                canvas.clipPath(mClip);
            } else {
                canvas.clipRect(bounds);
            }
            // In the order added: the pop under the ring, the ring under the particles.
            for (int i = 0; i < mItems.size(); i++) {
                Particle p = mItems.get(i);
                float t = p.t - p.delay;
                if (t < 0) continue;
                float k = t / p.life;
                switch (p.kind) {
                    case KIND_RING:
                        mStroke.setColor(p.color);
                        mStroke.setAlpha(alpha255((1 - k) * 0.55f));
                        mStroke.setStrokeWidth(p.lineWidth * (1 - k) + 0.5f * mDensity);
                        canvas.drawCircle(p.x, p.y, p.r0 + (p.r1 - p.r0) * easeOut(k), mStroke);
                        break;
                    case KIND_DOT:
                        mFill.setColor(p.color);
                        mFill.setAlpha(alpha255(1 - k));
                        canvas.drawCircle(p.x, p.y, p.radius * (1 - k * 0.6f), mFill);
                        break;
                    case KIND_HERO: {
                        float in = Math.min(1, t / 0.18f);
                        float out = Math.max(0, (t - p.hold) / (p.life - p.hold));
                        float travel = easeOut(in) - easeOut(out);
                        float back = easeOut(out);
                        float size = p.size * (easeOutBack(in) * (1 - back) + 0.3f * back);
                        float rot = (float) Math.sin(t * 14) * 0.08f * (1 - out);
                        drawEmoji(canvas, p, p.x0 + (p.x1 - p.x0) * travel,
                                p.y0 + (p.y1 - p.y0) * travel, size, rot, 1 - out * out);
                        break;
                    }
                    default: {
                        float pop = Math.min(1, t / 0.12f);
                        float size = p.size * (0.4f + 0.6f * easeOutBack(pop));
                        float alpha = k > 0.55f ? 1 - (k - 0.55f) / 0.45f : 1;
                        drawEmoji(canvas, p, p.x, p.y, size, p.rot, alpha);
                        break;
                    }
                }
            }
            canvas.restoreToCount(save);
        }

        private void drawEmoji(
                Canvas canvas, Particle p, float x, float y, float size, float rot, float alpha) {
            if (alpha <= 0 || size <= 0.5f || p.emoji == null) return;
            if (p.sprite == null) p.sprite = sprite(p.emoji);
            float scale = size / sSpriteTextSize;
            mMatrix.setTranslate(-SPRITE / 2f, -SPRITE / 2f);
            mMatrix.postScale(scale, scale);
            mMatrix.postRotate((float) Math.toDegrees(rot));
            mMatrix.postTranslate(x, y);
            mBitmapPaint.setAlpha(alpha255(alpha));
            canvas.drawBitmap(p.sprite, mMatrix, mBitmapPaint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------- sprites (each emoji rasterised once, then drawn as a bitmap) ----------

    private static Paint spritePaint() {
        if (sSpritePaint == null) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextAlign(Paint.Align.LEFT);
            // The design's 78% of the bitmap, shrunk if the font's emoji would overflow it.
            float textSize = SPRITE * 0.78f;
            paint.setTextSize(textSize);
            Rect glyph = new Rect();
            paint.getTextBounds("😀", 0, "😀".length(), glyph);
            float largest = Math.max(glyph.width(), glyph.height());
            if (largest > SPRITE * 0.9f) {
                textSize *= SPRITE * 0.9f / largest;
                paint.setTextSize(textSize);
            }
            sSpriteTextSize = textSize;
            sSpritePaint = paint;
        }
        return sSpritePaint;
    }

    private static Bitmap sprite(String emoji) {
        Bitmap sprite = sSprites.get(emoji);
        if (sprite != null) return sprite;
        Paint paint = spritePaint();
        sprite = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888);
        Rect glyph = new Rect();
        paint.getTextBounds(emoji, 0, emoji.length(), glyph);
        // Centre the glyph itself, so it spins about its middle.
        new Canvas(sprite).drawText(emoji, SPRITE / 2f - glyph.exactCenterX(),
                SPRITE / 2f - glyph.exactCenterY(), paint);
        sSprites.put(emoji, sprite);
        return sprite;
    }

    // ---------- families ----------

    /** The ring and spark colour, and the emoji the burst throws (taken in turn). */
    private static final class Family {
        final int color;
        final String[] set;

        Family(int color, String[] set) {
            this.color = color;
            this.set = set;
        }
    }

    private static Family family(String emoji) {
        Family family = sFamilies.get(emoji);
        if (family == null) {
            family = resolveFamily(emoji);
            sFamilies.put(emoji, family);
        }
        return family;
    }

    /**
     * A flag, then a hand-tuned theme (skin tones and variation selectors stripped; a ZWJ
     * sequence falls back to its first emoji), then the emoji's neighbours. The emoji itself is
     * always in the set 2–3 times so it stays the dominant shape.
     */
    private static Family resolveFamily(String emoji) {
        String key = normalize(emoji);
        if (isFlag(key)) {
            return new Family(FLAG_COLOR, join(new String[] {emoji, emoji, emoji}, FLAG_FAMILY));
        }
        Map<String, Family> themes = themes();
        Family theme = themes.get(key);
        if (theme == null && !key.isEmpty()) theme = themes.get(firstCodePoint(key));
        if (theme != null) {
            return new Family(theme.color, join(new String[] {emoji, emoji}, theme.set));
        }

        List<String> set = new ArrayList<>(Arrays.asList(emoji, emoji, emoji));
        set.addAll(neighbours(key));
        set.add(SPARKLES);
        return new Family(sampleColor(emoji), set.toArray(new String[0]));
    }

    private static String[] join(String[] head, String[] tail) {
        String[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private static String[] join(String[] head, String tail) {
        return join(head, tail.split(" "));
    }

    private static Map<String, Family> themes() {
        if (sThemes != null) return sThemes;
        Map<String, Family> byName = new HashMap<>();
        for (String[] theme : THEMES) {
            byName.put(theme[0], new Family(Color.parseColor(theme[1]), theme[2].split(" ")));
        }
        Map<String, Family> byEmoji = new HashMap<>();
        // Later themes win, as in the design's table (😇 is "pray", not "happy").
        for (String[] members : MEMBERS) {
            Family family = byName.get(members[0]);
            for (String member : members[1].split(" ")) byEmoji.put(normalize(member), family);
        }
        sThemes = byEmoji;
        return byEmoji;
    }

    /**
     * Up to six emoji next to this one in Unicode's order, within its picker section (🍎 brings
     * 🍏🍐🍊…), that this device can draw.
     */
    private static List<String> neighbours(String key) {
        if (sCatalogIndex == null) {
            String[] raw = {EmojiCatalog.SMILEYS_PEOPLE, EmojiCatalog.ANIMALS_NATURE,
                EmojiCatalog.FOOD_DRINK, EmojiCatalog.ACTIVITY, EmojiCatalog.TRAVEL_PLACES,
                EmojiCatalog.OBJECTS, EmojiCatalog.SYMBOLS};
            String[][] sections = new String[raw.length][];
            Map<String, int[]> index = new HashMap<>();
            for (int s = 0; s < raw.length; s++) {
                sections[s] = raw[s].trim().split(" ");
                for (int i = 0; i < sections[s].length; i++) {
                    index.put(normalize(sections[s][i]), new int[] {s, i});
                }
            }
            sCatalogSections = sections;
            sCatalogIndex = index;
        }
        List<String> out = new ArrayList<>();
        int[] at = sCatalogIndex.get(key);
        if (at == null && !key.isEmpty()) at = sCatalogIndex.get(firstCodePoint(key));
        if (at == null) return out;
        String[] section = sCatalogSections[at[0]];
        Paint paint = spritePaint();
        for (int d = 1; d <= 10 && out.size() < 6; d++) {
            for (int step : new int[] {-d, d}) {
                int i = at[1] + step;
                if (i < 0 || i >= section.length || out.size() >= 6) continue;
                if (paint.hasGlyph(section[i])) out.add(section[i]);
            }
        }
        return out;
    }

    /** The average of the emoji's saturated pixels, for emoji without a hand-tuned colour. */
    private static int sampleColor(String emoji) {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(26);
        Rect glyph = new Rect();
        paint.getTextBounds(emoji, 0, emoji.length(), glyph);
        new Canvas(bitmap).drawText(
                emoji, 16 - glyph.exactCenterX(), 16 - glyph.exactCenterY(), paint);
        int[] pixels = new int[32 * 32];
        bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32);
        bitmap.recycle();
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 200) continue;
            int pr = Color.red(pixel);
            int pg = Color.green(pixel);
            int pb = Color.blue(pixel);
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            if (max - min < 40 || max < 60) continue;
            r += pr;
            g += pg;
            b += pb;
            n++;
        }
        return n > 8 ? Color.rgb((int) (r / n), (int) (g / n), (int) (b / n)) : FALLBACK_COLOR;
    }

    /** Without variation selectors and skin tones, the form the tables are keyed by. */
    private static String normalize(String emoji) {
        StringBuilder out = new StringBuilder(emoji.length());
        for (int i = 0; i < emoji.length();) {
            int cp = emoji.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0E || cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF)) continue;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String firstCodePoint(String text) {
        return new String(Character.toChars(text.codePointAt(0)));
    }

    /** A country (two regional indicators), a subdivision (🏴 + tags), or 🏁 🚩 🎌. */
    private static boolean isFlag(String key) {
        int[] cps = key.codePoints().toArray();
        if (cps.length == 2 && isRegionalIndicator(cps[0]) && isRegionalIndicator(cps[1])) {
            return true;
        }
        if (cps.length >= 3 && cps[0] == 0x1F3F4 && cps[cps.length - 1] == 0xE007F) {
            for (int i = 1; i < cps.length - 1; i++) {
                if (cps[i] < 0xE0061 || cps[i] > 0xE007A) return false;
            }
            return true;
        }
        return key.equals("🏁") || key.equals("🚩") || key.equals("🎌");
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    // ---------- maths ----------

    private static float rnd(float a, float b) {
        return a + RANDOM.nextFloat() * (b - a);
    }

    private static double rnd(double a, double b) {
        return a + RANDOM.nextDouble() * (b - a);
    }

    /** Roughly normal in [-1, 1]. */
    private static double gauss() {
        return (RANDOM.nextDouble() + RANDOM.nextDouble() + RANDOM.nextDouble() - 1.5) / 1.5;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float easeOut(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }

    private static float easeOutBack(float t) {
        float c = 1.9f;
        return 1 + (c + 1) * (float) Math.pow(t - 1, 3) + c * (float) Math.pow(t - 1, 2);
    }

    private static int alpha255(float alpha) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255);
    }

    // ---------- the design's tables (generated from emoji-burst.js THEMES / assign) ----------

    /** Theme name, ring and spark colour, the family it throws. */
    private static final String[][] THEMES = {
        {"love", "#FF4D7D", "💖 💕 💗 💘 💞 💓 🌹 ✨"},
        {"laugh", "#FFC83D", "🤣 😆 😹 💦 😂 ✨"},
        {"happy", "#FFC83D", "😊 😄 🌼 ☀️ ✨ 🌈"},
        {"cool", "#3AA3E8", "😎 🕶️ 🔥 ⚡ ✨ 💯"},
        {"fire", "#FF7A1A", "💥 ⚡ 🧨 🔥 ✨ 🌶️"},
        {"approve", "#577DFF", "👏 🙌 💪 🎉 ✨ 👍"},
        {"clap", "#FFB547", "👏 🙌 🎉 ⭐ ✨ 🔥"},
        {"wow", "#FFC83D", "🤯 😲 💫 ❗ 👀 ✨"},
        {"sad", "#5AA9E6", "💧 😢 🌧️ 💔 🥀 ☁️"},
        {"angry", "#E8453C", "💢 😤 🔥 ⚡ 💥 🌋"},
        {"scared", "#8C7AE6", "👻 😱 ⚡ 🌩️ 💀 🕸️"},
        {"party", "#A26BFF", "🎊 🥳 🎈 ✨ 🎉 🪅"},
        {"birthday", "#FF7EB6", "🎂 🎁 🎈 🕯️ 🎉 ✨"},
        {"gift", "#E8453C", "🎁 🎀 ✨ 🎉 💝 ⭐"},
        {"pray", "#FFC83D", "✨ 🙏 🕊️ 💫 🌟 ☀️"},
        {"hundred", "#E8453C", "💯 🔥 ⚡ ✨ 👏 🎯"},
        {"check", "#2FBF71", "✔️ 💯 🎯 🏆 ✨ 🟢"},
        {"cross", "#E8453C", "❌ 🚫 ⛔ 💥 ✖️"},
        {"star", "#FFC83D", "⭐ 🌟 ✨ 💫 🌠"},
        {"money", "#2FBF71", "💸 💰 🤑 💵 🪙 ✨"},
        {"win", "#FFB300", "🏆 🥇 🎉 ✨ 🎊 ⭐"},
        {"sport", "#2FBF71", "🏆 🥇 🎉 💪 🔥 ✨"},
        {"music", "#A26BFF", "🎵 🎶 🎧 🎤 ✨ 💃"},
        {"dance", "#FF5C7A", "💃 🕺 🎶 🪩 ✨ 🎉"},
        {"rocket", "#FF7A1A", "🚀 🌟 💫 🌙 ✨ 🔥"},
        {"think", "#FFC83D", "🤔 💭 ❓ 💡 🧠 ✨"},
        {"idea", "#FFC83D", "💡 ✨ 🧠 ⚡ 🌟"},
        {"kiss", "#FF4D7D", "💋 😘 💕 💖 ✨ 🌹"},
        {"sleep", "#8C7AE6", "💤 😴 🌙 ⭐ ☁️"},
        {"sick", "#7BC67E", "🤒 💊 🌡️ 🦠 🤧 💧"},
        {"skull", "#B8BCC8", "💀 ☠️ 👻 ⚰️ 🦴 ✨"},
        {"poop", "#8D5B3E", "💩 🪰 💨 🚽 ✨"},
        {"drink", "#FFB547", "🥂 🍾 🎉 ✨ 🫧"},
        {"flower", "#FF7EB6", "🌸 🌷 🌼 🌺 🌹 ✨"},
        {"nature", "#2FBF71", "🍃 🌿 🍀 🌱 ✨"},
        {"sun", "#FFB300", "☀️ 🌞 ✨ 🌈 🌤️"},
        {"night", "#8C7AE6", "🌙 ⭐ ✨ 🌌 💫"},
        {"rain", "#5AA9E6", "💧 ☔ 🌧️ 🌈 ☁️"},
        {"snow", "#9FD3F2", "❄️ ☃️ 🌨️ ✨ ⛄"},
        {"rainbow", "#FF5C7A", "🌈 ✨ 🦄 ☁️ 💖 ⭐"},
        {"water", "#3AA3E8", "💦 🌊 💧 🫧 ✨"},
        {"zap", "#FFC83D", "⚡ 💥 ✨ 🌩️ 💫"},
        {"boom", "#FF7A1A", "💥 🔥 ⚡ 💢 ✨"},
        {"ghost", "#B8BCC8", "👻 🎃 🦇 🕸️ ✨"},
        {"alien", "#7BC67E", "👽 🛸 🌌 ✨ 👾"},
        {"robot", "#9AA3B5", "🤖 ⚙️ 🔩 ⚡ ✨"},
        {"cat", "#FFB547", "🐱 🐾 😺 🧶 ✨"},
        {"dog", "#C08A5B", "🐶 🐾 🦴 💕 ✨"},
        {"unicorn", "#FF7EB6", "🦄 🌈 ✨ 💖 ⭐"},
        {"muscle", "#FFB547", "💪 🔥 ⚡ 🏋️ ✨"},
        {"ok", "#FFB547", "👌 ✨ 💯 ✔️ 👍"},
        {"wave", "#FFB547", "👋 ✨ 😊 💫"},
        {"nope", "#577DFF", "👎 💢 😒 ❌"},
        {"shock", "#E8453C", "🫨 ⚡ 💥 ❗ 😱"},
        {"shy", "#FF8FAB", "🥺 👉👈 💕 🌸 ✨"},
        {"holiday", "#2FBF71", "🎄 🎅 🎁 ❄️ ⭐ ✨"},
        {"halloween", "#FF7A1A", "🎃 👻 🦇 🕸️ 🍬"},
    };

    /** Theme name, the emoji that burst with it. */
    private static final String[][] MEMBERS = {
        {"love",
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 "
                        + "💖 💗 💓 💞 💕 💘 💝 ❣️ 💟 😍 🥰 😻 "
                        + "💑 💏 🫶 ❤️‍🔥 ❤️‍🩹 🌹"},
        {"laugh", "😂 🤣 😆 😹 😝 😜 🤪 😛"},
        {"happy", "😀 😃 😄 😁 😊 🙂 ☺️ 😇 🥲 😸 😺 😌 🤗 🫠 🙃"},
        {"cool", "😎 🤩 🕶️ 🆒"},
        {"fire", "🔥 🌶️ 🫕"},
        {"approve", "👍 🤙 🫡"},
        {"clap", "👏 🙌 🤝"},
        {"wow", "😮 😯 😲 🤯 😳 🫢 😦 😧 👀 ‼️ ⁉️ ❗ ❕"},
        {"sad", "😢 😭 🥹 😞 😔 😟 🙁 ☹️ 😿 💔 🥀 😥 😓 😪"},
        {"angry", "😡 😠 🤬 👿 😤 💢 😾"},
        {"scared", "😱 😨 😰 🫣"},
        {"party", "🎉 🎊 🥳 🪅 🎈 🪩 🎆 🎇 🧨"},
        {"birthday", "🎂 🍰 🧁 🕯️"},
        {"gift", "🎁 🎀 💌"},
        {"pray", "🙏 🕊️ 😇 🪬"},
        {"hundred", "💯"},
        {"check", "✅ ✔️ ☑️ 🆗 🟢"},
        {"cross", "❌ ❎ 🚫 ⛔ ✖️ 🛑"},
        {"star", "⭐ 🌟 ✨ 💫 🌠"},
        {"money", "💸 💰 🤑 💵 💴 💶 💷 🪙 💳 💲"},
        {"win", "🏆 🥇 🥈 🥉 🏅 🎖️ 👑"},
        {"sport",
                "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 "
                        + "🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🥊 🥋 🏋️ 🤸 ⛹️ "
                        + "🤾 🏌️ 🏄 🏊 🤽 🚴 🚵 🏇 🏃 🎯"},
        {"music", "🎵 🎶 🎧 🎤 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 📻"},
        {"dance", "💃 🕺 👯 🪩"},
        {"rocket", "🚀 🛰️ 🛸 🌌"},
        {"think", "🤔 🧐 💭 🤨 🫤 😕 ❓ ❔"},
        {"idea", "💡 🧠"},
        {"kiss", "💋 😘 😗 😙 😚 😽 👄"},
        {"sleep", "😴 💤 🥱 🛌 😪"},
        {"sick", "🤒 🤕 🤢 🤮 🤧 😷 🥴 🦠 💊 🌡️"},
        {"skull", "💀 ☠️ ⚰️ 🦴"},
        {"poop", "💩"},
        {"drink", "🥂 🍾 🍻 🍺 🍷 🍸 🍹 🥃 🧉 🍶"},
        {"flower", "🌸 🌷 🌼 🌺 🌻 💐 🪷 🪻 🏵️ 💮"},
        {"nature", "🍀 ☘️ 🌿 🍃 🌱 🌲 🌳 🌴 🌵 🍂 🍁 🪴"},
        {"sun", "☀️ 🌞 🌤️ ⛅ 🌅 🌄"},
        {"night", "🌙 🌛 🌜 🌝 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌃"},
        {"rain", "🌧️ ☔ ☂️ ⛈️ ☁️ 🌦️"},
        {"snow", "❄️ ☃️ ⛄ 🌨️ 🧊"},
        {"rainbow", "🌈 🏳️‍🌈"},
        {"water", "💦 💧 🌊 🫧"},
        {"zap", "⚡ 🌩️"},
        {"boom", "💥 💣"},
        {"ghost", "👻"},
        {"alien", "👽 👾 🛸"},
        {"robot", "🤖"},
        {"cat", "🐱 🐈 😼 🙀"},
        {"dog", "🐶 🐕 🦮 🐩"},
        {"unicorn", "🦄"},
        {"muscle", "💪 🦾"},
        {"ok", "👌 🤌 🤏"},
        {"wave", "👋 🖐️ ✋ 🤚"},
        {"nope", "👎 🙅 🙄 😒 😑 😐 😶"},
        {"shock", "🫨"},
        {"shy", "🥺 🫣 ☺️"},
        {"holiday", "🎄 🎅 🤶 🧑‍🎄 ⛄"},
        {"halloween", "🎃 🦇 🕸️ 🕷️"},
    };

    /** What a flag bursts with, besides copies of itself. */
    private static final String FLAG_FAMILY = "🎉 ✨ 🎊 🏆";
}
