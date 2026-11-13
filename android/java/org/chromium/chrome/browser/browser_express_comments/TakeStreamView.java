/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Add your take" topic card's floating stream (design handoff "Add your take" §2.3): seven
 * items rising in turn from the card's bottom-right corner while the sheet is open — the faces of
 * people in the conversation, each with a small emoji, and loose emoji. Each rises 112dp with a
 * slight sideways drift over 2.7–3.2s, fades in over its first 12% and out after, and grows from
 * 0.6× to full size and back to 0.85×; they set off 450ms apart, starting 500ms after the sheet
 * opens. With animations off the stream stays still and empty.
 */
public class TakeStreamView extends View {
    private static final int ITEMS = 7;
    private static final long START_DELAY_MS = 500;
    private static final long STAGGER_MS = 450;
    /** One item's loop: every item comes round again once all seven have set off. */
    private static final long PERIOD_MS = ITEMS * STAGGER_MS;
    private static final float RISE_DP = 112f;
    private static final float FADE_IN = 0.12f;
    private static final float RING_DP = 1.5f;

    private final float mDp;
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEmoji = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mGlyph = new Rect();
    private final RectF mFace = new RectF();
    private final List<Bitmap> mFaces = new ArrayList<>();
    private final List<String> mEmojiSet = new ArrayList<>();
    private final List<CustomTarget<Bitmap>> mLoads = new ArrayList<>();
    private long mStartedAt;
    private boolean mRunning;

    public TakeStreamView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = context.getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(RING_DP * mDp);
        mRing.setColor(ContextCompat.getColor(context, R.color.browser_express_take_stream_ring));
        mEmoji.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * What floats: faces from {@code avatarUrls} (loaded round) and {@code emoji}. Either may be
     * empty; with neither the stream shows the design's default reactions.
     */
    void setContent(List<String> avatarUrls, List<String> emoji) {
        clearFaces();
        mEmojiSet.clear();
        mEmojiSet.addAll(emoji);
        if (mEmojiSet.isEmpty()) {
            for (String e : new String[] {"🔥", "👏", "😮", "❤️"}) mEmojiSet.add(e);
        }
        int size = Math.round(24 * mDp);
        for (String url : avatarUrls) {
            CustomTarget<Bitmap> target = new CustomTarget<Bitmap>(size, size) {
                @Override
                public void onResourceReady(@NonNull Bitmap face,
                        @Nullable Transition<? super Bitmap> transition) {
                    mFaces.add(face);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {}
            };
            mLoads.add(target);
            try {
                Glide.with(this).asBitmap().load(url).circleCrop().into(target);
            } catch (IllegalArgumentException e) {
                // The activity is gone; the stream does without faces.
            }
        }
    }

    void start() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        mStartedAt = SystemClock.uptimeMillis();
        mRunning = true;
        postInvalidateOnAnimation();
    }

    void stop() {
        mRunning = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        clearFaces();
        super.onDetachedFromWindow();
    }

    private void clearFaces() {
        for (CustomTarget<Bitmap> target : mLoads) {
            try {
                Glide.with(this).clear(target);
            } catch (IllegalArgumentException e) {
                // The activity is gone, and its requests with it.
            }
        }
        mLoads.clear();
        mFaces.clear();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!mRunning) return;
        long t = SystemClock.uptimeMillis() - mStartedAt - START_DELAY_MS;
        for (int i = 0; i < ITEMS; i++) {
            long local = t - i * STAGGER_MS;
            if (local < 0) continue;
            // 2.7–3.2s, different for each item but the same every time round.
            long duration = 2700 + (i * 173L) % 501;
            float p = (local % PERIOD_MS) / (float) duration;
            if (p > 1f) continue;
            drawItem(canvas, i, p);
        }
        postInvalidateOnAnimation();
    }

    private void drawItem(Canvas canvas, int i, float p) {
        float alpha = p < FADE_IN ? p / FADE_IN : 1f - (p - FADE_IN) / (1f - FADE_IN);
        float scale = p < 0.5f ? 0.6f + 0.4f * (p / 0.5f) : 1f - 0.15f * ((p - 0.5f) / 0.5f);
        // Spread over the stream's width, drifting a little to one side and back.
        float x = getWidth() * (0.25f + 0.5f * ((i * 37) % 7) / 6f)
                + (float) Math.sin(Math.PI * p) * (i % 2 == 0 ? 6 : -6) * mDp;
        float y = getHeight() - 14 * mDp - RISE_DP * mDp * easeOut(p);
        boolean face = i % 2 == 0 && !mFaces.isEmpty();
        String emoji = mEmojiSet.get(i % mEmojiSet.size());
        if (face) {
            Bitmap bitmap = mFaces.get((i / 2) % mFaces.size());
            float r = (20 + 4 * ((i / 2) % 2)) * mDp / 2f * scale;
            mFace.set(x - r, y - r, x + r, y + r);
            mBitmapPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(bitmap, null, mFace, mBitmapPaint);
            mRing.setAlpha(Math.round(230 * alpha));
            canvas.drawCircle(x, y, r + RING_DP * mDp / 2f, mRing);
            drawEmoji(canvas, emoji, x + r * 0.75f, y + r * 0.75f, 10 * scale, alpha);
        } else {
            drawEmoji(canvas, emoji, x, y, (14 + 4 * ((i * 3) % 3) / 2f) * scale, alpha);
        }
    }

    private void drawEmoji(Canvas canvas, String emoji, float cx, float cy, float sizeSp,
            float alpha) {
        mEmoji.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp,
                getResources().getDisplayMetrics()));
        mEmoji.setAlpha(Math.round(255 * alpha));
        mEmoji.getTextBounds(emoji, 0, emoji.length(), mGlyph);
        canvas.drawText(emoji, cx - mGlyph.exactCenterX(), cy - mGlyph.exactCenterY(), mEmoji);
    }

    private static float easeOut(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }
}
