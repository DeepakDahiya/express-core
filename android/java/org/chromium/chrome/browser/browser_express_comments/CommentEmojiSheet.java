/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.chrome.R;

/**
 * Emoji for the message box (Figma "Emoji sheet"): six popular ones, then eighteen more. The
 * pick goes to the parent fragment, which puts it where the cursor is.
 */
public class CommentEmojiSheet extends BottomSheetDialogFragment {
    public static final String TAG = "CommentEmojiSheet";

    private static final int COLUMNS = 6;

    private static final String[] POPULAR = {
        "👍", "❤️", "😂", "😮", "😢",
        "😡",
    };

    private static final String[] MORE = {
        "😊", "🥰", "😍", "🤔", "😭",
        "😎", "🙌", "💯", "🚀", "✨",
        "💡", "🙏", "🤝", "💪", "👀",
        "🥳", "🤩", "🙃",
    };

    /** Implemented by the fragment that shows the sheet. */
    public interface Host {
        void onEmojiPicked(String emoji);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.BrowserExpressCommentSheetTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.browser_express_comment_emoji_sheet, container, false);
        view.findViewById(R.id.sheet_close).setOnClickListener(v -> dismiss());
        fillGrid(view.findViewById(R.id.emoji_popular), POPULAR);
        fillGrid(view.findViewById(R.id.emoji_more), MORE);
        return view;
    }

    private void fillGrid(LinearLayout grid, String[] emoji) {
        int cellHeight = Math.round(
                48 * getResources().getDisplayMetrics().density);
        LinearLayout row = null;
        for (int i = 0; i < emoji.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellHeight));
            }
            TextView cell = new TextView(requireContext());
            cell.setText(emoji[i]);
            cell.setGravity(Gravity.CENTER);
            // In dp so large font settings can't push a glyph out of its cell.
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            cell.setBackgroundResource(R.drawable.browser_express_comment_emoji_cell_bg);
            cell.setContentDescription(emoji[i]);
            final String pick = emoji[i];
            cell.setOnClickListener(v -> {
                if (getParentFragment() instanceof Host) {
                    ((Host) getParentFragment()).onEmojiPicked(pick);
                }
                dismiss();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, cellHeight, 1f));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }
}
