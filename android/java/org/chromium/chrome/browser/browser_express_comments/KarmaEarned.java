/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.chrome.browser.settings.BrowserExpressProfileApi;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Karma earned" (design handoff design_handoff_karma_earned): every reaction the reader adds
 * earns them +1 Karma, and once the emoji burst has peaked their own picture confirms it with one
 * light sweep, a Karma-pink ring that breathes in and out, and a "+1" drifting off it ("+1 Karma"
 * the first time, so they learn what it means).
 *
 * <p>On a text page the picture is the comment bar's avatar, and the effect draws over it in the
 * sheet's {@link android.view.ViewOverlay}, the label rising. A reel page has no picture on
 * screen, so a 30dp copy slides out from behind the folded "Add your take" pill, shimmers and
 * tucks back, the label drifting down; it draws in the page's karma layer, which lies under the
 * pill.
 *
 * <p>Times are ms from the tap, when the burst fires. A new reaction restarts the timeline, so
 * there is only ever one label. With animations off only the label fades in and out in place.
 */
public final class KarmaEarned {
    private static final float SHIMMER_START = 800f;
    private static final float SHIMMER_MS = 700f;
    private static final float LABEL_IN_MS = 200f;
    private static final float LABEL_SCALE_MS = 250f;
    private static final float LABEL_DRIFT_MS = 1000f;
    /** From the shimmer's start: the label holds until 1500ms, then fades by 1800ms. */
    private static final float LABEL_HOLD_MS = 700f;
    private static final float LABEL_OUT_MS = 300f;
    private static final float PICTURE_IN_START = 600f;
    private static final float PICTURE_IN_MS = 250f;
    private static final float PICTURE_OUT_START = 2100f;
    private static final float PICTURE_OUT_MS = 300f;
    private static final float TEXT_END = 1800f;
    private static final float VIDEO_END = 2400f;

    private static final float LABEL_MAX_ALPHA = 0.9f;
    private static final float RING_MAX_ALPHA = 0.6f;
    private static final float DRIFT_DP = 14f;
    private static final float SLIDE_DP = 24f;
    private static final float RING_GAP_DP = 3f;
    private static final float RING_DP = 1.5f;
    private static final float EDGE_DP = 1.5f;
    private static final float LABEL_SP = 12f;
    private static final float LABEL_LINE_DP = 14f;
    /** The sweep: a band of white at 60% across the picture, its gradient at 105°. */
    private static final int SWEEP_WHITE = 0x99FFFFFF;
    private static final double SWEEP_ANGLE = Math.toRadians(105);

    private static final String PREFS = "browser_express_karma";
    private static final String PREF_SEEN_LABEL = "seen_label_";

    private static final Map<View, Layer> sLayers = new WeakHashMap<>();

    private KarmaEarned() {}

    /**
     * On a text page: plays over {@code avatar}, the comment bar's picture, in {@code host}'s
     * overlay. {@code sweep} is false while the picture is covered (e.g. by picked files).
     */
    static void playOnAvatar(View host, View avatar, boolean sweep, String accessToken) {
        Layer layer = layerFor(host);
        if (layer == null) return;
        layer.start(avatar, false, sweep, null, label(host.getContext(), accessToken));
    }

    /**
     * On a reel page: {@code picture} (the reader's, or the placeholder when null) slides out
     * from behind the page's "Add your take" pill, which folds first if it is showing its label.
     */
    static void playOnReel(View page, @Nullable Drawable picture, String accessToken) {
        View host = page.findViewById(R.id.reel_karma_layer);
        View anchor = page.findViewById(R.id.reel_karma_anchor);
        if (host == null || anchor == null) return;
        Layer layer = layerFor(host);
        if (layer == null) return;
        // It folds in 600ms, just as the picture sets off.
        ReelTakeBubble bubble = ReelTakeBubble.from(page.findViewById(R.id.reel_take_bubble));
        if (bubble != null) bubble.foldNow();
        if (picture == null) {
            picture = AppCompatResources.getDrawable(
                    host.getContext(), R.drawable.browser_express_post_avatar_placeholder);
        }
        layer.start(anchor, true, true, picture, label(host.getContext(), accessToken));
    }

    /** The reel page {@code row} is on, the ancestor holding a karma layer; null on text pages. */
    static @Nullable View reelPageOf(View row) {
        for (View view = row; view.getParent() instanceof View; ) {
            view = (View) view.getParent();
            if (view.findViewById(R.id.reel_karma_layer) != null) return view;
        }
        return null;
    }

    /** Stops whatever plays in {@code host}, e.g. when its page is bound to another reel. */
    static void cancel(@Nullable View host) {
        if (host == null) return;
        Layer layer = sLayers.remove(host);
        if (layer == null) return;
        layer.stop();
        host.getOverlay().remove(layer);
    }

    private static @Nullable Layer layerFor(View host) {
        if (host.getWidth() == 0 || host.getHeight() == 0 || !host.isAttachedToWindow()) {
            return null;
        }
        Layer layer = sLayers.get(host);
        if (layer == null) {
            layer = new Layer(host);
            host.getOverlay().add(layer);
            sLayers.put(host, layer);
        }
        layer.setBounds(0, 0, host.getWidth(), host.getHeight());
        return layer;
    }

    /**
     * "+1 Karma" on this account's first Karma ever, "+1" after that. The account's flag lives on
     * the server (hasSeenKarmaLabel) and is mirrored here so the label never waits for it.
     */
    private static String label(Context context, String accessToken) {
        String userId = ProfileAvatar.selfId(accessToken);
        SharedPreferences prefs = prefs();
        String key = PREF_SEEN_LABEL + userId;
        if (userId == null || prefs.getBoolean(key, false)) {
            return context.getString(R.string.browser_express_karma_earned);
        }
        prefs.edit().putBoolean(key, true).apply();
        BrowserExpressProfileApi.markKarmaLabelSeen(accessToken);
        return context.getString(R.string.browser_express_karma_earned_first);
    }

    /**
     * Reconciles the account's "+1 Karma" flag with {@code seenOnServer}, as /v1/user/me reported
     * it: either side having seen it counts, and the server is told if only this device knew.
     */
    public static void syncLabelSeen(@Nullable String accessToken, boolean seenOnServer) {
        String userId = ProfileAvatar.selfId(accessToken);
        if (userId == null) return;
        String key = PREF_SEEN_LABEL + userId;
        boolean seenHere = prefs().getBoolean(key, false);
        if (seenOnServer && !seenHere) {
            prefs().edit().putBoolean(key, true).apply();
        } else if (seenHere && !seenOnServer) {
            BrowserExpressProfileApi.markKarmaLabelSeen(accessToken);
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** One host's effect, drawn in its overlay and stepped on each vsync while it plays. */
    private static final class Layer extends Drawable implements Choreographer.FrameCallback {
        // Weak: sLayers is keyed by the host, and a value holding its key would never be freed.
        private final WeakReference<View> mHost;
        private WeakReference<View> mAnchor = new WeakReference<>(null);
        private final float mDp;
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics mMetrics = new Paint.FontMetrics();
        private final int[] mHostXY = new int[2];
        private final int[] mAnchorXY = new int[2];
        private final RectF mPicture = new RectF();
        private final Rect mPictureBounds = new Rect();
        private final RectF mLayerBounds = new RectF();
        private final Path mCircle = new Path();
        private float mSweepShaderSize;
        private boolean mOnVideo;
        private boolean mSweepOn;
        private boolean mReduced;
        private @Nullable Drawable mPictureDrawable;
        private String mLabel = "";
        private int mRingColor;
        private int mGlowColor;
        private int mLabelColor;
        private int mLabelShadowColor;
        private long mStartNanos;
        private float mT;
        private boolean mRunning;

        Layer(View host) {
            mHost = new WeakReference<>(host);
            mDp = host.getResources().getDisplayMetrics().density;
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(RING_DP * mDp);
            mEdge.setStyle(Paint.Style.STROKE);
            mEdge.setStrokeWidth(EDGE_DP * mDp);
            mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP,
                    host.getResources().getDisplayMetrics()));
            mText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            mText.setFontFeatureSettings("'tnum'");
        }

        void start(View anchor, boolean onVideo, boolean sweep, @Nullable Drawable picture,
                String label) {
            Context context = anchor.getContext();
            mAnchor = new WeakReference<>(anchor);
            mOnVideo = onVideo;
            mSweepOn = sweep;
            mReduced = !ValueAnimator.areAnimatorsEnabled();
            mPictureDrawable = picture;
            mLabel = label;
            // The light theme's glow is a little tighter than the dark one's and the reel's.
            float glowBlurDp = 12f;
            if (onVideo) {
                // Reel chrome sits on the video, so it is the same in both themes.
                mRingColor = color(context, R.color.browser_express_karma_on_video);
                mLabelColor = mRingColor;
                mGlowColor = color(context, R.color.browser_express_karma_on_video_glow);
                mLabelShadowColor =
                        color(context, R.color.browser_express_karma_on_video_label_shadow);
                mEdge.setColor(color(context, R.color.browser_express_karma_on_video_edge));
                mShadow.setColor(color(context, R.color.browser_express_karma_on_video_shadow));
                mShadow.setMaskFilter(
                        new BlurMaskFilter(blurRadius(8f), BlurMaskFilter.Blur.NORMAL));
            } else {
                mRingColor = color(context, R.color.browser_express_karma_ring);
                mLabelColor = color(context, R.color.browser_express_karma_label);
                mGlowColor = color(context, R.color.browser_express_karma_glow);
                mLabelShadowColor = Color.TRANSPARENT;
                int night = context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                if (night != Configuration.UI_MODE_NIGHT_YES) glowBlurDp = 10f;
            }
            mGlow.setMaskFilter(
                    new BlurMaskFilter(blurRadius(glowBlurDp), BlurMaskFilter.Blur.OUTER));

            mStartNanos = System.nanoTime();
            mT = 0;
            if (!mRunning) {
                mRunning = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
            invalidateSelf();
        }

        void stop() {
            if (mRunning) {
                mRunning = false;
                Choreographer.getInstance().removeFrameCallback(this);
            }
            mPictureDrawable = null;
            invalidateSelf();
        }

        /** A CSS blur of {@code cssDp} as a Skia blur radius (CSS's sigma is half its blur). */
        private float blurRadius(float cssDp) {
            return Math.max(0.5f, (cssDp * mDp / 2f - 0.5f) / 0.57735f);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            View host = mHost.get();
            if (host == null || !host.isAttachedToWindow() || mAnchor.get() == null) {
                stop();
                return;
            }
            mT = Math.max(0f, (frameTimeNanos - mStartNanos) / 1e6f);
            if (mT >= (mOnVideo ? VIDEO_END : TEXT_END)) {
                stop();
                return;
            }
            invalidateSelf();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!mRunning) return;
            View host = mHost.get();
            View anchor = mAnchor.get();
            if (host == null || anchor == null || anchor.getWidth() == 0) return;
            host.getLocationInWindow(mHostXY);
            anchor.getLocationInWindow(mAnchorXY);
            float left = mAnchorXY[0] - mHostXY[0];
            float top = mAnchorXY[1] - mHostXY[1];
            mPicture.set(left, top, left + anchor.getWidth(), top + anchor.getHeight());
            if (mOnVideo) {
                drawOnVideo(canvas);
            } else {
                drawOnAvatar(canvas);
            }
        }

        /** The comment bar: the ring and sweep on the avatar, the label rising from its top. */
        private void drawOnAvatar(Canvas canvas) {
            drawShimmer(canvas);
            drawLabel(canvas, mPicture.left + 14 * mDp, mPicture.top - 10 * mDp, -1);
        }

        /**
         * The reel: the picture slides 24dp out from behind the pill (600-850ms) and back
         * (2100-2400ms), carrying its ring, sweep and label, which drifts down.
         */
        private void drawOnVideo(Canvas canvas) {
            float in = easeOut(ramp(mT, PICTURE_IN_START, PICTURE_IN_MS));
            float out = easeOut(ramp(mT, PICTURE_OUT_START, PICTURE_OUT_MS));
            float show = Math.min(in, 1 - out);
            if (show <= 0) return;
            float r = mPicture.width() / 2f;
            float cx = mPicture.centerX();
            float cy = mPicture.centerY();

            mLayerBounds.set(mPicture.left - 24 * mDp, mPicture.top - 24 * mDp,
                    mPicture.right + 96 * mDp, mPicture.top + 84 * mDp);
            int save = canvas.saveLayerAlpha(mLayerBounds, Math.round(255 * show));
            if (!mReduced) {
                canvas.translate((1 - show) * SLIDE_DP * mDp, 0);
                float scale = 0.85f + 0.15f * show;
                canvas.scale(scale, scale, cx, cy);
            }
            canvas.drawCircle(cx, cy + 2 * mDp, r, mShadow);
            if (mPictureDrawable != null) {
                canvas.save();
                mCircle.rewind();
                mCircle.addCircle(cx, cy, r, Path.Direction.CW);
                canvas.clipPath(mCircle);
                mPicture.round(mPictureBounds);
                mPictureDrawable.setBounds(mPictureBounds);
                mPictureDrawable.draw(canvas);
                canvas.restore();
            }
            canvas.drawCircle(cx, cy, r + EDGE_DP * mDp / 2f, mEdge);
            drawShimmer(canvas);
            drawLabel(canvas, mPicture.left + 7 * mDp, mPicture.top + 34 * mDp, 1);
            canvas.restoreToCount(save);
        }

        /** 800-1500ms: one sweep across the picture, and the ring breathing 0 → 0.6 → 0. */
        private void drawShimmer(Canvas canvas) {
            if (mReduced) return;
            float p = ramp(mT, SHIMMER_START, SHIMMER_MS);
            if (p <= 0 || p >= 1) return;
            float r = mPicture.width() / 2f;
            float cx = mPicture.centerX();
            float cy = mPicture.centerY();

            float ring = RING_MAX_ALPHA * (float) Math.sin(Math.PI * p);
            float ringR = r + RING_GAP_DP * mDp;
            mGlow.setColor(mGlowColor);
            mGlow.setAlpha(Math.round(Color.alpha(mGlowColor) * ring));
            canvas.drawCircle(cx, cy, ringR, mGlow);
            mRing.setColor(mRingColor);
            mRing.setAlpha(Math.round(255 * ring));
            canvas.drawCircle(cx, cy, ringR + RING_DP * mDp / 2f, mRing);

            if (!mSweepOn) return;
            float size = mPicture.width();
            if (size != mSweepShaderSize) {
                mSweepShaderSize = size;
                mSweep.setShader(sweepShader(size, mPicture.height()));
            }
            canvas.save();
            mCircle.rewind();
            mCircle.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.clipPath(mCircle);
            canvas.translate(mPicture.left + (-1 + 2 * easeOut(p)) * size, mPicture.top);
            canvas.drawRect(0, 0, size, mPicture.height(), mSweep);
            canvas.restore();
        }

        /**
         * The label from 800ms: in over 200ms and up from 0.8× over 250ms, drifting 14dp over
         * 1000ms ({@code direction} -1 up, 1 down), out from 1500 to 1800ms. {@code left} and
         * {@code top} place its 14dp line box.
         */
        private void drawLabel(Canvas canvas, float left, float top, int direction) {
            float k = mT - SHIMMER_START;
            if (k <= 0) return;
            float alpha = LABEL_MAX_ALPHA * ramp(k, 0, LABEL_IN_MS)
                    * (1 - ramp(k, LABEL_HOLD_MS, LABEL_OUT_MS));
            if (alpha <= 0) return;
            float drift = mReduced
                    ? 0
                    : direction * DRIFT_DP * mDp * easeOut(ramp(k, 0, LABEL_DRIFT_MS));
            float scale = mReduced ? 1 : 0.8f + 0.2f * easeOut(ramp(k, 0, LABEL_SCALE_MS));

            float width = mText.measureText(mLabel);
            float line = LABEL_LINE_DP * mDp;
            float cx = left + width / 2f;
            float cy = top + line / 2f + drift;
            mText.setColor(mLabelColor);
            mText.setAlpha(Math.round(255 * alpha));
            if (Color.alpha(mLabelShadowColor) > 0) {
                // A shadow keeps its own colour's alpha, so it fades with the label by hand.
                mText.setShadowLayer(blurRadius(4f), 0, mDp, ColorUtils.setAlphaComponent(
                        mLabelShadowColor, Math.round(Color.alpha(mLabelShadowColor) * alpha)));
            } else {
                mText.clearShadowLayer();
            }
            mText.getFontMetrics(mMetrics);
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            canvas.drawText(mLabel, left, cy - (mMetrics.ascent + mMetrics.descent) / 2f, mText);
            canvas.restore();
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

    private static int color(Context context, int id) {
        return ContextCompat.getColor(context, id);
    }

    /** The sweep band for a {@code w}×{@code h} picture at the origin: CSS's 105° gradient. */
    private static Shader sweepShader(float w, float h) {
        float dx = (float) Math.sin(SWEEP_ANGLE);
        float dy = (float) -Math.cos(SWEEP_ANGLE);
        float half = (w * Math.abs(dx) + h * Math.abs(dy)) / 2f;
        return new LinearGradient(w / 2f - dx * half, h / 2f - dy * half, w / 2f + dx * half,
                h / 2f + dy * half,
                new int[] {Color.TRANSPARENT, Color.TRANSPARENT, SWEEP_WHITE, Color.TRANSPARENT,
                    Color.TRANSPARENT},
                new float[] {0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Where {@code t} is in the {@code length} after {@code start}, clamped to [0, 1]. */
    private static float ramp(float t, float start, float length) {
        return Math.max(0f, Math.min(1f, (t - start) / length));
    }

    private static float easeOut(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }
}
