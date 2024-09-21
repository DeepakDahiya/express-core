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

public class BrowserExpressCommentsBottomSheetFragment extends BottomSheetDialogFragment {
    public static final String IS_FROM_MENU = "is_from_menu";
    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private ProgressBar mCommentProgress;

    private boolean isFromMenu;
    // private ImageButton mSendButton;
    // private ImageButton mCancelReplyButton;
    // private EditText mMessageEditText;
    // private TextView mReplyToText;
    // private TextView mCommentsText;

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
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_browser_express_comments_bottom_sheet, container, false);
        loadFragment(CommentListFragment.newInstance(mPostId, mCommentsFor));
        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        ((BottomSheetDialog) getDialog())
                .getBehavior()
                .setState(BottomSheetBehavior.STATE_EXPANDED);

        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // mAvatarImage = (ImageView) view.findViewById(R.id.avatar_image);
        // mSendButton = view.findViewById(R.id.button_send);
        // mMessageEditText = view.findViewById(R.id.comment_content);
        // mReplyToText = view.findViewById(R.id.reply_to);
        // mCancelReplyButton = view.findViewById(R.id.cancel_btn);

        DisplayMetrics displaymetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

        int a =  (displaymetrics.heightPixels*70)/100;

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            // bottom bar comment count
            // mCommentsText = activity.getCommentCountText();
            String accessToken = activity.getAccessToken();
            // if(accessToken != null){
            //     JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
            //     ImageLoader.downloadImage("https://api.multiavatar.com/" + decodedAccessTokenObj.getString("_id") + ".png?apikey=ewsXMRIAbcdY5F", Glide.with(activity), false, 5, mAvatarImage, null);
            // }
            
            // SharedPreferences sharedPref = activity.getSharedPreferencesForReplyTo();
            // SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            //     @Override
            //     public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            //         if(key.equals(BraveActivity.BROWSER_EXPRESS_REPLY_TO)){
            //             if(activity.getReplyTo() != null && !activity.getReplyTo().equals("")){
            //                 try{
            //                     JSONObject jsonObj = new JSONObject(activity.getReplyTo().toString());
            //                     String username = jsonObj.getString("name");
            //                     String replyToString = "replying to " + username;
            //                     mReplyToText.setText(replyToString);
            //                     mCancelReplyButton.setVisibility(View.VISIBLE);
            //                     mMessageEditText.requestFocus();
            //                     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            //                     imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,InputMethodManager.HIDE_IMPLICIT_ONLY);
            //                 } catch (JSONException e) {
            //                     Log.e("BROWSER_EXPRESS_REPLY_TO_EXTRACT", e.getMessage());
            //                 }
            //             }else{
            //                 mReplyToText.setText(R.string.browser_express_empty_text);
            //                 mCancelReplyButton.setVisibility(View.INVISIBLE);
            //             }
            //         }
            //     }
            // };

            // sharedPref.registerOnSharedPreferenceChangeListener(listener);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch (JSONException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            Log.e("Express Browser Access Token", ex.getMessage());
        }

        // mSendButton.setOnClickListener((new View.OnClickListener() {
        //     @Override
        //     public void onClick(View v) {
        //         if (getActivity() != null) {
        //             try {
        //                 mSendButton.setClickable(false);
        //                 BraveActivity activity = BraveActivity.getBraveActivity();
        //                 String accessToken = activity.getAccessToken();
        //                 if (accessToken == null) {
        //                     InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        //                     imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
        //                     activity.showGenerateUsernameBottomSheet();
        //                     dismiss();
        //                 } else {
        //                     String content = mMessageEditText.getText().toString().trim();
        //                     if(content.length() > 0){
        //                         if(activity.getReplyTo() != null && !activity.getReplyTo().equals("")){
        //                             try{
        //                                 JSONObject jsonObj = new JSONObject(activity.getReplyTo().toString());
        //                                 String commentId = jsonObj.getString("commentId");
        //                                 BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
        //                                     new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
        //                                             content, "comment", mUrl, commentId, accessToken, addCommentCallback);
        //                                 workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        //                             } catch (JSONException e) {
        //                                 Log.e("BROWSER_EXPRESS_REPLY_TO_EXTRACT", e.getMessage());
        //                             }
        //                         }else{
        //                             String pType = "page";
        //                             String pId = null;
        //                             if(mCommentsFor.equals("post")){
        //                                 pType = "post";
        //                                 pId = mPostId;
        //                             }
        //                             BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
        //                                 new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
        //                                         content, pType, mUrl, pId, accessToken, addCommentCallback);
        //                             workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        //                         }
        //                         mMessageEditText.setText(R.string.browser_express_empty_text);
        //                     }
        //                 }
        //             } catch (BraveActivity.BraveActivityNotFoundException e) {
        //                 Log.e("Express Browser Access Token", e.getMessage());
        //             }finally{
        //                 mSendButton.setClickable(true);
        //             }
        //         }
        //     }
        // }));

        int braveDefaultModalCount = SharedPreferencesManager.getInstance().readInt(
                BravePreferenceKeys.BRAVE_SET_DEFAULT_BOTTOM_SHEET_COUNT);

        if (braveDefaultModalCount > 2 && !isFromMenu) {
        } else {
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // BraveSetDefaultBrowserUtils.isBottomSheetVisible = false;
    }

    private JSONObject getDecodedToken(String accessToken){
        try{
            String[] split_string = accessToken.split("\\.");
            String base64EncodedHeader = split_string[0];
            String base64EncodedBody = split_string[1];
            String base64EncodedSignature = split_string[2];

            byte[] data = Base64.decode(base64EncodedBody, Base64.DEFAULT);
            String decodedString = new String(data, "UTF-8");
            JSONObject jsonObj = new JSONObject(decodedString.toString());
            return jsonObj;
        }catch(JSONException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }catch(UnsupportedEncodingException e){
            Log.e("Express Browser Access Token", e.getMessage());
            return null;
        }
        
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

    public void dismissBottomsheet() {
        dismiss();
    }

    public void openComments() {
        // if (getFragmentManager() != null) {
        //     getFragmentManager().popBackStack();
        // }
        // FragmentManager fragmentManager = getParentFragmentManager();
        // fragmentManager.popBackStack();
    }

    public EditText getMessageEditText() {
        return mMessageEditText;
    }

    public TextView getReplyToText() {
        return mReplyToText;
    }

    public ImageButton getCancelReplyButton() {
        return mCancelReplyButton;
    }
}
