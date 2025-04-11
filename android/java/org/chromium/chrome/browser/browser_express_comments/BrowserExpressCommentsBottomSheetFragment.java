/**
 * Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.CheckBox;
import org.chromium.ui.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import android.util.DisplayMetrics;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import android.view.WindowManager;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ProgressBar;
import android.util.Base64;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.EditText;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.FragmentActivity;
import android.view.KeyEvent;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.browser.app.BraveActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

public class BrowserExpressCommentsBottomSheetFragment extends BottomSheetDialogFragment implements BottomSheetInputCallback {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String OPEN_KEYBOARD = "open_keyboard";
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private Boolean mOpenKeyboard = false;
    private ProgressBar mCommentProgress;

    private ImageButton mSendButton;
    private EditText mMessageEditText;

    private Button mLolButton;
    private Button mHeartButton;
    private Button mCryButton;
    private Button mFireButton;
    private Button mLoveButton;
    private Button mClapButton;

    private boolean isFromMenu;

    private ImageView mAvatarImage;

    public static BrowserExpressCommentsBottomSheetFragment newInstance(boolean isFromMenu) {
        final BrowserExpressCommentsBottomSheetFragment fragment =
                new BrowserExpressCommentsBottomSheetFragment();
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
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
            String tempOpenKeyboard = getArguments().getString(OPEN_KEYBOARD);
            if (tempOpenKeyboard != null && tempOpenKeyboard.equals("true")) {
                mOpenKeyboard = true;
            } else {
                mOpenKeyboard = false;
            }
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_browser_express_comments_bottom_sheet, container, false);
        loadFragment(CommentListFragment.newInstance(mPostId, mCommentsFor, mOpenKeyboard));

        mMessageEditText = view.findViewById(R.id.comment_content_input);
        mSendButton = view.findViewById(R.id.button_send);
        mAvatarImage = view.findViewById(R.id.avatar_image);

        mLolButton = view.findViewById(R.id.lol_button);
        mHeartButton = view.findViewById(R.id.heart_button);
        mCryButton = view.findViewById(R.id.cry_button);
        mFireButton = view.findViewById(R.id.fire_button);
        mLoveButton = view.findViewById(R.id.love_button);
        mClapButton = view.findViewById(R.id.clap_button);

        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        int defaultHeight = (int) (screenHeight * 0.8);

        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        BottomSheetBehavior behavior = dialog.getBehavior();

        behavior.setMaxHeight(defaultHeight);

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                Log.e("ROOT_VIEW_KEY", "keyCode: " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                        return true;
                    }
                }
                return false;
            }
        });

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                Log.e("BACK BUTTON PRESSED", "keyCode: " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    FragmentManager fragmentManager = getChildFragmentManager();
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                        return true;
                    }
                }
                return false;
            }
        });

        ((FragmentActivity) requireActivity()).getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), 
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Log.e("BACK BUTTON PRESSED 2", "START");
                    FragmentManager fragmentManager = getChildFragmentManager();
                    Log.e("BACK BUTTON PRESSED 2", fragmentManager.getBackStackEntryCount() + "");
                    if (fragmentManager.getBackStackEntryCount() > 1) {
                        openComments();
                    } else {
                        this.remove();
                        dismissBottomsheet();
                    }
                }
            });
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // BraveSetDefaultBrowserUtils.isBottomSheetVisible = false;
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getChildFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();

        transaction.setCustomAnimations(
            R.anim.slide_in_right,  // enter
            R.anim.slide_out_left,  // exit
            R.anim.slide_in_left,   // popEnter
            R.anim.slide_out_right  // popExit
        );

        transaction.replace(R.id.bottom_sheet_container, fragment).addToBackStack(null).commit();
    }

    public void openReplies(String commentId) {
        ReplyListFragment replyFragment = new ReplyListFragment();
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
    }

    public void openRepliesToReply(String commentId) {
        Log.e("OpenRepliesToReply", "commentId: " + commentId);
        ReplyListFragment2 replyFragment = new ReplyListFragment2();
        Log.e("OpenRepliesToReply", "After replyFragment");
        Bundle args = new Bundle();
        args.putString("comment_id", commentId);
        Log.e("OpenRepliesToReply", "After args");
        replyFragment.setArguments(args);
        loadFragment(replyFragment);
    }

    public void dismissBottomsheet() {
        dismiss();
    }

    public void openComments() {
        FragmentManager fragmentManager = getChildFragmentManager();
        fragmentManager.popBackStack();
    }

    @Override
    public EditText getInputEditText() {
        return mMessageEditText;
    }

    @Override
    public ImageButton getSendButton() {
        return mSendButton;
    }

    @Override
    public void setInputEnabled(boolean enabled) {
        if (mMessageEditText != null) mMessageEditText.setEnabled(enabled);
        if (mSendButton != null) mSendButton.setEnabled(enabled);
    }

    @Override
    public Button getEmojiButton(String type) {
        switch(type) {
            case "lol": return mLolButton;
            case "heart": return mHeartButton;
            case "cry": return mCryButton;
            case "fire": return mFireButton;
            case "love": return mLoveButton;
            case "clap": return mClapButton;
            default: return null;
        }
    }

    @Override
    public void updateAvatar(String avatarUrl, BraveActivity activity) {
        if (mAvatarImage != null && getContext() != null) {
            ImageLoader.downloadImage(
                avatarUrl,
                Glide.with(activity),
                false,
                5,
                mAvatarImage,
                null
            );
        }
    }

}
