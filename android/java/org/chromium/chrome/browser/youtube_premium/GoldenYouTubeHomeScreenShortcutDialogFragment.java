/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.ui.widget.Toast;

/** Bottom sheet that nudges users to pin the Golden YouTube shortcut. */
public class GoldenYouTubeHomeScreenShortcutDialogFragment extends BottomSheetDialogFragment {
    public static final String TAG = "GoldenYouTubeHomeScreenShortcutDialog";
    private static final float COMPACT_BODY_TEXT_SIZE_SP = 9f;
    private static final float COMPACT_SMALL_TEXT_SIZE_SP = 10f;

    public static boolean showIfNeeded(FragmentManager fragmentManager, Activity activity) {
        if (!GoldenYouTubeHomeScreenShortcutUtil.shouldShowPrompt(activity)) return false;

        try {
            if (fragmentManager.isStateSaved()) return false;
            if (fragmentManager.findFragmentByTag(TAG) != null) return true;

            GoldenYouTubeHomeScreenShortcutDialogFragment fragment =
                    new GoldenYouTubeHomeScreenShortcutDialogFragment();
            fragment.show(fragmentManager, TAG);
            GoldenYouTubeHomeScreenShortcutUtil.recordPromptShown();
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot show shortcut prompt: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.AppSetDefaultBottomSheetDialogTheme);
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_golden_youtube_home_screen_shortcut_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog != null) {
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        applyCompactTextSizes(view);

        View addShortcutButton = view.findViewById(R.id.btn_add_golden_youtube_shortcut);
        addShortcutButton.setOnClickListener(
                v -> {
                    boolean requested =
                            GoldenYouTubeHomeScreenShortcutUtil.requestPinShortcut(
                                    requireContext());
                    if (!requested) {
                        Toast.makeText(
                                        requireContext(),
                                        R.string.golden_youtube_shortcut_not_supported,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                    dismiss();
                });

        TextView laterButton = view.findViewById(R.id.btn_golden_youtube_shortcut_later);
        laterButton.setOnClickListener(v -> dismiss());
    }

    private void applyCompactTextSizes(View view) {
        setTextSizeSp(view, R.id.golden_youtube_shortcut_body, COMPACT_BODY_TEXT_SIZE_SP);
        setTextSizeSp(view, R.id.golden_youtube_shortcut_ads_icon_text, COMPACT_SMALL_TEXT_SIZE_SP);
        setTextSizeSp(view, R.id.golden_youtube_shortcut_ad_free_title, COMPACT_SMALL_TEXT_SIZE_SP);
        setTextSizeSp(view, R.id.golden_youtube_shortcut_ad_free_body, COMPACT_BODY_TEXT_SIZE_SP);
        setTextSizeSp(
                view, R.id.golden_youtube_shortcut_background_play_title,
                COMPACT_SMALL_TEXT_SIZE_SP);
        setTextSizeSp(
                view, R.id.golden_youtube_shortcut_background_play_body,
                COMPACT_BODY_TEXT_SIZE_SP);
        setTextSizeSp(view, R.id.btn_golden_youtube_shortcut_later, COMPACT_SMALL_TEXT_SIZE_SP);
    }

    private void setTextSizeSp(View rootView, int viewId, float sizeSp) {
        TextView textView = rootView.findViewById(viewId);
        if (textView == null) return;

        float sizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        sizeSp,
                        rootView.getResources().getDisplayMetrics());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
    }
}
