/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_splash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.chromium.base.Log;
import org.chromium.chrome.R;

/**
 * The UGC splash over the whole window: the animation ({@link UgcSplashRenderer}) plays, the
 * phrase holds until the app is ready, and the splash fades into the app.
 *
 * <p>The animation is drawn twice. On its own thread, straight into a surface over the window, in
 * step with the display: the browser starting up underneath keeps the UI thread busy for whole
 * seconds at a time, and drawn only there the animation would stand still. And in the window
 * itself on the UI thread, under that surface, which hides it; should the surface not show, the
 * splash still does.
 *
 * <p>The surface's thread keeps the timeline: when the phrase is up and the app is ready, the
 * window's copy goes and the surface fades into the app. If the surface never got a frame drawn,
 * the window's copy fades instead.
 */
final class UgcSplashView extends FrameLayout {
    private static final String TAG = "UgcSplash";

    interface Listener {
        /**
         * The splash has faded out, or its window is going away.
         *
         * @param windowGone The window is detaching it: it goes with the window and must not be
         *     removed from its parent now, mid-detach.
         */
        void onFinished(boolean windowGone);
    }

    /** Reduced motion shows the finished phrase still, for at least this long. */
    private static final long STILL_MIN_MS = 1000;

    /** Raised over the decor's other children (its system-bar backgrounds sit at 0). */
    private static final float SPLASH_Z_DP = 100;

    /**
     * The surface's clock waits for its first frame to be drawn, so the opening isn't played
     * unseen, but no longer than this.
     */
    private static final long CLOCK_START_GRACE_MS = 300;

    /**
     * The fade starts once the window frame without the splash's own copy is out; if that frame
     * doesn't report back by now, it starts anyway.
     */
    private static final long FADE_HANDOFF_FALLBACK_MS = 100;

    /** Never up longer than this, whatever happened to its surface or the app. */
    private static final long MAX_SHOWN_MS = 12000;

    private final Window mWindow;
    private final Listener mListener;
    private final boolean mStill;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final RenderLoop mLoop;

    /** The window's own copy of the animation, on the UI thread's clock. */
    private final UgcSplashRenderer mWindowRenderer;
    private boolean mWindowDrawing = true;
    private boolean mWindowClockStarted;
    private float mWindowClockMs;
    private @Nullable ValueAnimator mWindowClock;
    private @Nullable ValueAnimator mWindowFade;

    /** The browser under the splash, kept from screen readers while it shows. */
    private final @Nullable View mContent;
    private int mContentImportance;
    private boolean mContentHidden;

    private boolean mFinished;
    private boolean mFadeStarted;
    private boolean mFadeHandedOff;
    private long mAttachedAtMs;
    private final Runnable mMaxShown =
            () -> {
                Log.w(TAG, "Still up after %d ms; removed", MAX_SHOWN_MS);
                finish();
            };

    /** Light system-bar icons are held while the splash shows; these are the app's own. */
    private @Nullable WindowInsetsControllerCompat mBars;
    private boolean mAppLightStatusBar;
    private boolean mAppLightNavigationBar;
    private @Nullable ViewTreeObserver mBarsObserver;
    private final ViewTreeObserver.OnPreDrawListener mHoldBarsOnDraw =
            () -> {
                holdLightBars();
                return true;
            };

    UgcSplashView(Activity activity, Listener listener) {
        super(activity);
        mWindow = activity.getWindow();
        mListener = listener;
        mContent = activity.findViewById(android.R.id.content);
        // Reduced motion (animations off): the finished phrase, still.
        mStill = !ValueAnimator.areAnimatorsEnabled();
        mWindowRenderer = new UgcSplashRenderer(activity.getResources());
        mLoop = new RenderLoop(new UgcSplashRenderer(activity.getResources()), mStill);

        setBackgroundColor(UgcSplashRenderer.BACKGROUND);
        setWillNotDraw(false);
        // Above the window's system-bar backgrounds, which are the decor's own later children.
        // No outline, so no shadow.
        setTranslationZ(SPLASH_Z_DP * activity.getResources().getDisplayMetrics().density);
        setOutlineProvider(null);
        setClickable(true);
        setOnClickListener(v -> skip());
        setContentDescription(activity.getString(R.string.browser_express_splash_description));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        // Over the whole window, the system-bar backgrounds included; translucent for the fade.
        SurfaceView surface = new SurfaceView(activity);
        surface.setZOrderOnTop(true);
        surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surface.getHolder().addCallback(mLoop);
        surface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** The app behind is ready: the splash fades as soon as the phrase has been seen. */
    void setReady() {
        if (mFinished) return;
        Log.i(TAG, "App ready after %d ms", sinceAttach());
        mLoop.post(mLoop::setReady);
    }

    private void skip() {
        if (mWindowClock != null) mWindowClock.end();
        mLoop.post(mLoop::skip);
    }

    private int sinceAttach() {
        return mAttachedAtMs == 0 ? 0 : (int) (SystemClock.uptimeMillis() - mAttachedAtMs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttachedAtMs = SystemClock.uptimeMillis();
        mUiHandler.postDelayed(mMaxShown, MAX_SHOWN_MS);
        holdLightBars();
        mBarsObserver = getViewTreeObserver();
        mBarsObserver.addOnPreDrawListener(mHoldBarsOnDraw);
        hideContentFromScreenReaders();
        post(
                () -> {
                    if (isAttachedToWindow()) announceForAccessibility(getContentDescription());
                });
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
        // The window went first (activity destroyed): nothing should keep waiting on the splash.
        if (!mFinished) {
            mFinished = true;
            Log.i(TAG, "Window gone %d ms in, before the splash ended", sinceAttach());
            mListener.onFinished(/* windowGone= */ true);
        }
    }

    // ---- The window's copy (UI thread) ---------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWindowRenderer.layout(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mWindowDrawing) return;
        if (!mWindowClockStarted) {
            mWindowClockStarted = true;
            Log.i(TAG, "First window frame %d ms after attach", sinceAttach());
            post(this::startWindowClock);
        }
        mWindowRenderer.draw(canvas, mWindowClockMs, 0);
    }

    private void startWindowClock() {
        if (!mWindowDrawing || mFinished) return;
        if (mStill) {
            mWindowClockMs = UgcSplashRenderer.T_OUT;
            invalidate();
            return;
        }
        ValueAnimator clock = ValueAnimator.ofFloat(0, UgcSplashRenderer.T_OUT);
        clock.setDuration(UgcSplashRenderer.T_OUT);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(
                animation -> {
                    mWindowClockMs = (float) animation.getAnimatedValue();
                    invalidate();
                });
        mWindowClock = clock;
        clock.start();
    }

    private void stopWindowCopy() {
        mWindowDrawing = false;
        ValueAnimator clock = mWindowClock;
        mWindowClock = null;
        if (clock != null) {
            clock.removeAllUpdateListeners();
            clock.cancel();
        }
    }

    // ---- The end -------------------------------------------------------------------------------

    /**
     * The phrase has held long enough and the app is ready (render thread → UI thread).
     *
     * @param surfaceDrew Whether the surface ever got a frame drawn.
     */
    private void onFadeWanted(boolean surfaceDrew) {
        if (mFinished || mFadeStarted) return;
        mFadeStarted = true;
        Log.i(TAG, "Fading %d ms in (surface drew %b, still %b)", sinceAttach(), surfaceDrew,
                mStill);
        if (mStill) {
            // Animations are off: no fade either.
            finish();
            return;
        }
        if (!surfaceDrew) {
            // The window's copy is what's on screen: it fades itself.
            mLoop.post(mLoop::abandon);
            fadeWindowCopy();
            return;
        }
        // The window's copy and background go first, so the fading surface shows the app. The
        // surface still covers the whole window, so this changes nothing on screen yet.
        stopWindowCopy();
        setBackground(null);
        Runnable handOff =
                () -> {
                    if (mFadeHandedOff || mFinished) return;
                    mFadeHandedOff = true;
                    mLoop.post(mLoop::startFade);
                };
        getViewTreeObserver().registerFrameCommitCallback(() -> mUiHandler.post(handOff));
        mUiHandler.postDelayed(handOff, FADE_HANDOFF_FALLBACK_MS);
        invalidate();
    }

    private void fadeWindowCopy() {
        if (mWindowClock != null) mWindowClock.end();
        mWindowClockMs = UgcSplashRenderer.T_OUT;
        ValueAnimator fade = ValueAnimator.ofFloat(0, 1);
        fade.setDuration(UgcSplashRenderer.T_OUT_DUR);
        fade.setInterpolator(new LinearInterpolator());
        fade.addUpdateListener(
                animation -> {
                    float p = (float) animation.getAnimatedValue();
                    mWindowClockMs = UgcSplashRenderer.T_OUT + p * UgcSplashRenderer.T_OUT_DUR;
                    setAlpha(1 - UgcSplashRenderer.EASE_FADE.getInterpolation(p));
                    invalidate();
                });
        fade.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finish();
                    }
                });
        mWindowFade = fade;
        fade.start();
    }

    private void finish() {
        if (mFinished) return;
        mFinished = true;
        Log.i(TAG, "Done after %d ms", sinceAttach());
        stop();
        mListener.onFinished(/* windowGone= */ false);
    }

    private void stop() {
        mUiHandler.removeCallbacks(mMaxShown);
        stopWindowCopy();
        ValueAnimator fade = mWindowFade;
        mWindowFade = null;
        if (fade != null) {
            fade.removeAllListeners();
            fade.removeAllUpdateListeners();
            fade.cancel();
        }
        mLoop.quit();
        ViewTreeObserver observer = mBarsObserver;
        mBarsObserver = null;
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(mHoldBarsOnDraw);
        }
        releaseBars();
        releaseContent();
    }

    // ---- Screen readers ----------------------------------------------------------------------

    /** Screen readers stay on the splash, not on the browser starting up under it. */
    private void hideContentFromScreenReaders() {
        if (mContent == null || mContentHidden || mFinished) return;
        mContentImportance = mContent.getImportantForAccessibility();
        mContent.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        mContentHidden = true;
    }

    private void releaseContent() {
        if (mContent == null || !mContentHidden) return;
        mContentHidden = false;
        mContent.setImportantForAccessibility(mContentImportance);
    }

    // ---- System bars -----------------------------------------------------------------------

    /**
     * Light status- and navigation-bar icons over the dark splash. The app may set its own while
     * it starts up underneath (checked before every UI frame); the latest it asked for is kept and
     * put back at the end.
     */
    private void holdLightBars() {
        if (mFinished) return;
        if (mBars == null) {
            mBars = WindowCompat.getInsetsController(mWindow, mWindow.getDecorView());
            mAppLightStatusBar = mBars.isAppearanceLightStatusBars();
            mAppLightNavigationBar = mBars.isAppearanceLightNavigationBars();
        }
        if (mBars.isAppearanceLightStatusBars()) {
            mAppLightStatusBar = true;
            mBars.setAppearanceLightStatusBars(false);
        }
        if (mBars.isAppearanceLightNavigationBars()) {
            mAppLightNavigationBar = true;
            mBars.setAppearanceLightNavigationBars(false);
        }
    }

    private void releaseBars() {
        WindowInsetsControllerCompat bars = mBars;
        if (bars == null) return;
        mBars = null;
        bars.setAppearanceLightStatusBars(mAppLightStatusBar);
        bars.setAppearanceLightNavigationBars(mAppLightNavigationBar);
    }

    // ---- The render thread -------------------------------------------------------------------

    /**
     * Runs the timeline on the splash's own thread and draws each frame into the surface on that
     * thread's vsync. Everything but the surface callbacks and {@link #quit} runs on that thread.
     */
    private final class RenderLoop implements SurfaceHolder.Callback, Choreographer.FrameCallback {
        private final UgcSplashRenderer mRenderer;
        private final boolean mStill;
        private final HandlerThread mThread;
        private final Handler mHandler;

        private final Object mSurfaceLock = new Object();

        /** Set from the UI thread's surface callbacks; drawn into only while it holds. */
        private @Nullable SurfaceHolder mHolder;

        // Render thread only.
        private @Nullable Choreographer mChoreographer;
        private boolean mFramePosted;
        private int mWidth;
        private int mHeight;
        private long mFirstFrameMs = -1;
        private long mStartMs = -1;
        private long mPhraseAtMs = -1;
        private long mFadeStartMs = -1;
        private boolean mReady;
        private boolean mFadeAsked;
        private boolean mDone;
        private boolean mDrewFrame;
        private int mFailedLocks;
        private final Runnable mCheckFade = this::maybeAskForFade;

        RenderLoop(UgcSplashRenderer renderer, boolean still) {
            mRenderer = renderer;
            mStill = still;
            mThread = new HandlerThread("UgcSplash", Process.THREAD_PRIORITY_DISPLAY);
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
        }

        /** Runs {@code task} on the render thread; dropped once the loop has quit. */
        void post(Runnable task) {
            mHandler.post(task);
        }

        /** UI thread: no more drawing, and the thread ends. */
        void quit() {
            synchronized (mSurfaceLock) {
                mHolder = null;
            }
            mThread.quitSafely();
        }

        // ---- Surface callbacks (UI thread) ---------------------------------------------------

        @Override
        public void surfaceCreated(SurfaceHolder holder) {}

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mFinished) return;
            Log.i(TAG, "Surface %dx%d, %d ms after attach", width, height, sinceAttach());
            synchronized (mSurfaceLock) {
                mHolder = holder;
            }
            post(
                    () -> {
                        mWidth = width;
                        mHeight = height;
                        mRenderer.layout(width, height);
                        requestFrame();
                    });
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (!mFinished) Log.i(TAG, "Surface destroyed %d ms after attach", sinceAttach());
            // Returns only once no frame is being drawn into it.
            synchronized (mSurfaceLock) {
                mHolder = null;
            }
        }

        // ---- Render thread ------------------------------------------------------------------

        void setReady() {
            if (mReady) return;
            mReady = true;
            requestFrame();
            maybeAskForFade();
        }

        /** A tap jumps to the finished phrase, which then fades once the app is ready. */
        void skip() {
            if (mPhraseAtMs >= 0 || mDone) return;
            long now = SystemClock.uptimeMillis();
            Log.i(TAG, "Skipped at %d ms", mStartMs < 0 ? 0 : (int) (now - mStartMs));
            if (mStartMs < 0) mStartMs = now;
            mPhraseAtMs = now;
            requestFrame();
            maybeAskForFade();
        }

        void startFade() {
            if (mFadeStartMs >= 0 || mDone) return;
            mFadeStartMs = SystemClock.uptimeMillis();
            requestFrame();
        }

        /** The window's copy is fading instead: the surface goes blank and stops. */
        void abandon() {
            if (mDone) return;
            mDone = true;
            drawClear();
        }

        private void requestFrame() {
            if (mFramePosted || mDone) return;
            if (mChoreographer == null) mChoreographer = Choreographer.getInstance();
            mFramePosted = true;
            mChoreographer.postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            mFramePosted = false;
            if (mDone) return;
            // The same monotonic clock as SystemClock.uptimeMillis().
            long now = frameTimeNanos / 1_000_000L;

            if (mStartMs < 0) {
                // The clock starts on the first frame that reaches the surface.
                if (mFirstFrameMs < 0) mFirstFrameMs = now;
                boolean drew = draw(mStill ? UgcSplashRenderer.T_OUT : 0, 0, 1);
                if (drew || now - mFirstFrameMs >= CLOCK_START_GRACE_MS) {
                    mStartMs = now;
                    if (mStill) mPhraseAtMs = now;
                }
                requestFrame();
                return;
            }

            float t;
            float alpha = 1;
            if (mFadeStartMs >= 0) {
                float p =
                        UgcSplashRenderer.clamp01(
                                (now - mFadeStartMs) / (float) UgcSplashRenderer.T_OUT_DUR);
                if (p >= 1) {
                    mDone = true;
                    drawClear();
                    mUiHandler.post(UgcSplashView.this::finish);
                    return;
                }
                // The burst's last pixels keep going under the fade.
                t = UgcSplashRenderer.T_OUT + p * UgcSplashRenderer.T_OUT_DUR;
                alpha = 1 - UgcSplashRenderer.EASE_FADE.getInterpolation(p);
            } else if (mPhraseAtMs >= 0) {
                t = UgcSplashRenderer.T_OUT;
            } else {
                t = now - mStartMs;
                if (t >= UgcSplashRenderer.T_OUT) {
                    t = UgcSplashRenderer.T_OUT;
                    mPhraseAtMs = now;
                    Log.i(TAG, "Phrase up (surface drew %b, %d failed locks)", mDrewFrame,
                            mFailedLocks);
                }
            }

            // Every frame is drawn, the still one too: one that doesn't reach the screen is
            // replaced by the next.
            draw(t, mStill ? 0 : now - mStartMs, alpha);
            maybeAskForFade();
            requestFrame();
        }

        private void maybeAskForFade() {
            mHandler.removeCallbacks(mCheckFade);
            if (mPhraseAtMs < 0 || !mReady || mFadeAsked || mDone) return;
            if (mStill) {
                long wait = STILL_MIN_MS - (SystemClock.uptimeMillis() - mPhraseAtMs);
                if (wait > 0) {
                    mHandler.postDelayed(mCheckFade, wait);
                    return;
                }
            }
            mFadeAsked = true;
            boolean drew = mDrewFrame;
            mUiHandler.post(() -> onFadeWanted(drew));
        }

        /** Draws one frame into the surface. False if it couldn't be drawn. */
        private boolean draw(float t, long grainMs, float alpha) {
            synchronized (mSurfaceLock) {
                SurfaceHolder holder = mHolder;
                if (holder == null) return false;
                Canvas canvas = holder.lockHardwareCanvas();
                if (canvas == null) {
                    if (mFailedLocks++ == 0) Log.i(TAG, "Surface not drawable yet");
                    return false;
                }
                try {
                    if (alpha >= 1) {
                        mRenderer.draw(canvas, t, grainMs);
                    } else {
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        int save =
                                canvas.saveLayerAlpha(
                                        0, 0, mWidth, mHeight, Math.round(alpha * 255));
                        mRenderer.draw(canvas, t, grainMs);
                        canvas.restoreToCount(save);
                    }
                } finally {
                    holder.unlockCanvasAndPost(canvas);
                }
                if (!mDrewFrame) {
                    mDrewFrame = true;
                    Log.i(TAG, "First surface frame drawn (after %d failed locks)", mFailedLocks);
                }
                return true;
            }
        }

        /** The last frame: nothing, so the app shows through until the view is removed. */
        private void drawClear() {
            synchronized (mSurfaceLock) {
                SurfaceHolder holder = mHolder;
                if (holder == null) return;
                Canvas canvas = holder.lockHardwareCanvas();
                if (canvas == null) return;
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                } finally {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }
    }
}
