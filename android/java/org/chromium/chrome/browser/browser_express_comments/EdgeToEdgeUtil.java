/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Keeps bottom-anchored controls visible on devices where the window extends under the system
 * bars. Android 15 forces edge-to-edge on apps targeting SDK 35+, so on such devices (e.g. a
 * Galaxy S24 on One UI 7) a sheet's input bar or a full-screen dialog's bottom buttons end up
 * behind the navigation bar — worst with 3-button navigation, whose bar is ~48dp tall, not just
 * a gesture strip. Padding by the *reported* insets fixes exactly the affected devices: windows
 * that do not extend under the bars report zero, so nothing changes there.
 *
 * <p>The helpers keep whatever padding the view already carries and only add the inset on top,
 * recomputing from that baseline every time so re-applying never compounds. All of it no-ops
 * below API 30, where the affected windows do not occur and the typed inset API does not exist.
 */
public final class EdgeToEdgeUtil {
    private EdgeToEdgeUtil() {}

    /** For sheets: only the bottom edge can be covered (the sheet never reaches the top). */
    public static void padBottomBySystemBars(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        final int basePaddingBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePaddingBottom + bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    /**
     * Pins a full-screen dialog's window to one inset model instead of leaving the choice to the
     * theme. Chromium's base theme opts the app out of Android 15+ edge-to-edge enforcement, so
     * the decor insets content by itself; a dialog that overrides the window theme with a
     * platform one (Theme.Black.NoTitleBar.Fullscreen) does not reliably inherit that opt-out, so
     * its window can land in either model. That is the trap: in the legacy model the insets are
     * consumed on the way down and reach the content as zero, so a listener pads by nothing while
     * the window is still drawing under the navigation bar — the Next/Reset row ends up behind
     * the S24's navigation buttons.
     *
     * <p>Turning decor fitting off means the decor never insets anything, which makes
     * {@link #padAllBySystemBars} the single source of truth on every device. Call this once the
     * dialog's window exists (onStart), and pair it with {@code padAllBySystemBars} on the
     * content.
     */
    public static void makeWindowEdgeToEdge(@Nullable Window window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setDecorFitsSystemWindows(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            // The bars now sit over the dialog's own black backdrop, so their icons have to be
            // light; a light-themed device would otherwise draw them black on black.
            controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    /**
     * For full-screen dialogs: every edge can be covered — the navigation bar sits at a side in
     * landscape 3-button mode, and the status bar covers the top once the window is edge-to-edge.
     */
    public static void padAllBySystemBars(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            applyPadding(v, insets, baseLeft, baseTop, baseRight, baseBottom);
            return insets;
        });
        // A dispatch is not guaranteed to arrive before the dialog is on screen, so re-read the
        // window directly on attach as well; applyPadding works off the captured baseline, so
        // running twice lands on the same padding.
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                applyPadding(v, null, baseLeft, baseTop, baseRight, baseBottom);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });
        if (view.isAttachedToWindow()) {
            applyPadding(view, null, baseLeft, baseTop, baseRight, baseBottom);
        }
        view.requestApplyInsets();
    }

    // Split out of the guarded caller, so it carries the version requirement itself.
    @RequiresApi(Build.VERSION_CODES.R)
    private static void applyPadding(View view, @Nullable WindowInsets dispatched, int baseLeft,
            int baseTop, int baseRight, int baseBottom) {
        final int mask = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
        Insets bars = dispatched != null ? dispatched.getInsets(mask) : Insets.NONE;
        // The dispatched insets can arrive already consumed — a decor that insets its own content
        // zeroes them on the way down — while the window's own insets always report the real
        // overlap. Both measure the same gap, so the larger one is the honest answer and taking
        // it can never double count.
        WindowInsets fromWindow = view.getRootWindowInsets();
        if (fromWindow != null) bars = Insets.max(bars, fromWindow.getInsets(mask));
        view.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right,
                baseBottom + bars.bottom);
    }
}
