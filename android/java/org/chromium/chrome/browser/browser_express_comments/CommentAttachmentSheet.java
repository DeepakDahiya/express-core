/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.chrome.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * "Add to comment" (Figma "Attachment picker sheet"): photo, camera, video, voice note, GIF or
 * document. The sheet only reports the choice; the parent fragment runs it.
 */
public class CommentAttachmentSheet extends BottomSheetDialogFragment {
    public static final String TAG = "CommentAttachmentSheet";

    @IntDef({Kind.PHOTO, Kind.CAMERA, Kind.VIDEO, Kind.VOICE, Kind.GIF, Kind.DOCUMENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Kind {
        int PHOTO = 0;
        int CAMERA = 1;
        int VIDEO = 2;
        int VOICE = 3;
        int GIF = 4;
        int DOCUMENT = 5;
    }

    /** Implemented by the fragment that shows the sheet. */
    public interface Host {
        void onAttachmentKindChosen(@Kind int kind);
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
        View view = inflater.inflate(R.layout.browser_express_comment_attach_sheet, container,
                false);
        view.findViewById(R.id.sheet_close).setOnClickListener(v -> dismiss());
        bind(view, R.id.attach_photo, Kind.PHOTO);
        bind(view, R.id.attach_camera, Kind.CAMERA);
        bind(view, R.id.attach_video, Kind.VIDEO);
        bind(view, R.id.attach_voice, Kind.VOICE);
        bind(view, R.id.attach_gif, Kind.GIF);
        bind(view, R.id.attach_document, Kind.DOCUMENT);
        return view;
    }

    /** The kind's name for analytics: photo, camera, video, voice, gif or document. */
    public static String kindName(@Kind int kind) {
        switch (kind) {
            case Kind.PHOTO:
                return "photo";
            case Kind.CAMERA:
                return "camera";
            case Kind.VIDEO:
                return "video";
            case Kind.VOICE:
                return "voice";
            case Kind.GIF:
                return "gif";
            case Kind.DOCUMENT:
                return "document";
            default:
                return "unknown";
        }
    }

    private void bind(View root, int id, @Kind int kind) {
        root.findViewById(id).setOnClickListener(v -> {
            // Dismissed first: most choices open something of their own (a picker, the camera,
            // another sheet) that should not appear behind this one.
            dismiss();
            if (getParentFragment() instanceof Host) {
                ((Host) getParentFragment()).onAttachmentKindChosen(kind);
            }
        });
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
