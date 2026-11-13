/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.youtube_premium;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.fragment.app.FragmentManager;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.BraveDialogFragment;
import org.chromium.chrome.browser.rate.RateUtils;

/** Center modal that asks background playback users for a Play Store rating. */
public class GoldenYouTubeBackgroundPlaybackRatingDialogFragment extends BraveDialogFragment {
    public static final String TAG = "GoldenYouTubeBackgroundPlaybackRatingDialog";
    private static final int PLAY_STORE_RATING = 5;
    private AppCompatImageButton[] mStarButtons;

    public static boolean showIfNeeded(FragmentManager fragmentManager, Activity activity) {
        if (!GoldenYouTubeHomeScreenShortcutUtil.shouldShowBackgroundPlaybackRatingPrompt(activity)) {
            return false;
        }

        try {
            if (fragmentManager.isStateSaved()) return false;
            if (fragmentManager.findFragmentByTag(TAG) != null) return true;

            GoldenYouTubeBackgroundPlaybackRatingDialogFragment fragment =
                    new GoldenYouTubeBackgroundPlaybackRatingDialogFragment();
            fragment.show(fragmentManager, TAG);
            GoldenYouTubeHomeScreenShortcutUtil.recordBackgroundPlaybackRatingPromptShown();
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot show background playback rating prompt: " + e.getMessage());
            return false;
        }
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_golden_youtube_background_play_rating_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View closeButton = view.findViewById(R.id.btn_background_play_rating_close);
        closeButton.setOnClickListener(v -> dismiss());

        mStarButtons =
                new AppCompatImageButton[] {
                    bindStar(view, R.id.background_play_rating_star_1, 1),
                    bindStar(view, R.id.background_play_rating_star_2, 2),
                    bindStar(view, R.id.background_play_rating_star_3, 3),
                    bindStar(view, R.id.background_play_rating_star_4, 4),
                    bindStar(view, R.id.background_play_rating_star_5, 5)
                };
        updateStarSelection(0);
    }

    @Override
    public void onResume() {
        super.onResume();

        Dialog dialog = getDialog();
        if (dialog == null) return;

        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private AppCompatImageButton bindStar(View rootView, int starViewId, int rating) {
        AppCompatImageButton starView = rootView.findViewById(starViewId);
        if (starView == null) return null;
        starView.setOnClickListener(v -> onRatingSelected(rating));
        return starView;
    }

    private void updateStarSelection(int rating) {
        if (mStarButtons == null) return;
        for (int i = 0; i < mStarButtons.length; i++) {
            AppCompatImageButton starButton = mStarButtons[i];
            if (starButton == null) continue;
            starButton.setImageResource(
                    i < rating
                            ? R.drawable.ic_background_play_rating_star
                            : R.drawable.ic_background_play_rating_star_outline);
        }
    }

    private void onRatingSelected(int rating) {
        updateStarSelection(rating);
        if (rating == PLAY_STORE_RATING && getContext() != null) {
            RateUtils.getInstance().openPlaystore(getContext());
            GoldenYouTubeHomeScreenShortcutUtil.recordBackgroundPlaybackRatingPromptHandled();
        }
        dismiss();
    }
}
