/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.browser_express_update_apk;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import org.chromium.ui.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import android.content.Intent;

public class BrowserExpressUpdateApkBottomSheetFragment extends BottomSheetDialogFragment {
    private static final String IS_FROM_MENU = "is_from_menu";

    private boolean isFromMenu;
    private Button nextButton;

    public static BrowserExpressUpdateApkBottomSheetFragment newInstance(boolean isFromMenu) {
        final BrowserExpressUpdateApkBottomSheetFragment fragment =
                new BrowserExpressUpdateApkBottomSheetFragment();
        final Bundle args = new Bundle();
        args.putBoolean(IS_FROM_MENU, isFromMenu);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.AppSetDefaultBottomSheetDialogTheme);

        if (getArguments() != null) {
            isFromMenu = getArguments().getBoolean(IS_FROM_MENU);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_browser_express_update_apk_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        ((BottomSheetDialog) getDialog())
                .getBehavior()
                .setState(BottomSheetBehavior.STATE_EXPANDED);
        nextButton = view.findViewById(R.id.btn_next);
        nextButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    nextButton.setClickable(false);
                    nextButton.setText(R.string.browser_express_loading_title);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String url = "https://apk.browser.express"
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        activity.startActivity(webIntent);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e(TAG, "BookmarkButton click " + e);
                    } catch (Exception ex) {

                    }
                }
                dismiss();
            }
        }));

        int braveDefaultModalCount = SharedPreferencesManager.getInstance().readInt(
                BravePreferenceKeys.BRAVE_SET_DEFAULT_BOTTOM_SHEET_COUNT);

        if (braveDefaultModalCount > 2 && !isFromMenu) {
        } else {
        }

        Button cancelButton = view.findViewById(R.id.btn_cancel);
        cancelButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        }));
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // BraveSetDefaultBrowserUtils.isBottomSheetVisible = false;
    }
}
