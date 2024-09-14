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
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mComments;
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;
    private String mCommentsFor;
    private String mPostId;
    private ProgressBar mCommentProgress;

    private boolean isFromMenu;
    // private Button nextButton;
    private ImageButton mSendButton;
    private ImageButton mCancelReplyButton;
    private EditText mMessageEditText;
    private TextView mReplyToText;
    private TextView mCommentsText;

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

        mAvatarImage = (ImageView) view.findViewById(R.id.avatar_image);
        mSendButton = view.findViewById(R.id.button_send);
        mMessageEditText = view.findViewById(R.id.comment_content);
        mReplyToText = view.findViewById(R.id.reply_to);
        mCancelReplyButton = view.findViewById(R.id.cancel_btn);

        DisplayMetrics displaymetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

        int a =  (displaymetrics.heightPixels*70)/100;

        // mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
        // mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        // mCommentAdapter = new CommentListAdapter(requireContext(), mComments, mReplyToText, mCancelReplyButton, mMessageEditText, mCommentRecycler);
        // mCommentRecycler.setAdapter(mCommentAdapter);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            // bottom bar comment count
            mCommentsText = activity.getCommentCountText();
            String accessToken = activity.getAccessToken();
            if(accessToken != null){
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);
                ImageLoader.downloadImage("https://api.multiavatar.com/" + decodedAccessTokenObj.getString("_id") + ".png?apikey=ewsXMRIAbcdY5F", Glide.with(activity), false, 5, mAvatarImage, null);
            }
            
            SharedPreferences sharedPref = activity.getSharedPreferencesForReplyTo();
            SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if(key.equals(BraveActivity.BROWSER_EXPRESS_REPLY_TO)){
                        if(activity.getReplyTo() != null && !activity.getReplyTo().equals("")){
                            try{
                                JSONObject jsonObj = new JSONObject(activity.getReplyTo().toString());
                                String username = jsonObj.getString("name");
                                String replyToString = "replying to " + username;
                                mReplyToText.setText(replyToString);
                                mCancelReplyButton.setVisibility(View.VISIBLE);
                                mMessageEditText.requestFocus();
                                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,InputMethodManager.HIDE_IMPLICIT_ONLY);
                            } catch (JSONException e) {
                                Log.e("BROWSER_EXPRESS_REPLY_TO_EXTRACT", e.getMessage());
                            }
                        }else{
                            mReplyToText.setText(R.string.browser_express_empty_text);
                            mCancelReplyButton.setVisibility(View.INVISIBLE);
                        }
                    }
                }
            };

            sharedPref.registerOnSharedPreferenceChangeListener(listener);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch (JSONException e) {
            Log.e("Express Browser Access Token", e.getMessage());
        }catch(Exception ex){
            Log.e("Express Browser Access Token", ex.getMessage());
        }

        mSendButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    try {
                        mSendButton.setClickable(false);
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String accessToken = activity.getAccessToken();
                        if (accessToken == null) {
                            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
                            activity.showGenerateUsernameBottomSheet();
                            dismiss();
                        } else {
                            String content = mMessageEditText.getText().toString().trim();
                            if(content.length() > 0){
                                if(activity.getReplyTo() != null && !activity.getReplyTo().equals("")){
                                    try{
                                        JSONObject jsonObj = new JSONObject(activity.getReplyTo().toString());
                                        String commentId = jsonObj.getString("commentId");
                                        BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                            new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                    content, "comment", mUrl, commentId, accessToken, addCommentCallback);
                                        workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                    } catch (JSONException e) {
                                        Log.e("BROWSER_EXPRESS_REPLY_TO_EXTRACT", e.getMessage());
                                    }
                                }else{
                                    String pType = "page";
                                    String pId = null;
                                    if(mCommentsFor.equals("post")){
                                        pType = "post";
                                        pId = mPostId;
                                    }
                                    BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                        new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                content, pType, mUrl, pId, accessToken, addCommentCallback);
                                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                }
                                mMessageEditText.setText(R.string.browser_express_empty_text);
                            }
                        }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e("Express Browser Access Token", e.getMessage());
                    }finally{
                        mSendButton.setClickable(true);
                    }
                }
            }
        }));

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

    private BrowserExpressAddCommentUtil.AddCommentCallback addCommentCallback=
            new BrowserExpressAddCommentUtil.AddCommentCallback() {
                @Override
                public void addCommentSuccessful(Comment comment) {
                    if(comment.getPageParent() == null){
                        try {
                            BraveActivity activity = BraveActivity.getBraveActivity();

                            JSONObject commentJson = new JSONObject();
                            commentJson.put("_id", comment.getId());
                            commentJson.put("content", comment.getContent());
                            commentJson.put("upvoteCount", comment.getUpvoteCount());
                            commentJson.put("downvoteCount", comment.getDownvoteCount());
                            commentJson.put("commentCount", comment.getCommentCount());
                            commentJson.put("commentParent", comment.getCommentParent());
                            commentJson.put("pageParent", comment.getPageParent());
                            commentJson.put("didVote", null);

                            User u = comment.getUser();
                            JSONObject userJson = new JSONObject();
                            userJson.put("_id", u.getId());
                            userJson.put("username", u.getUsername());
                            commentJson.put("user", userJson);

                            activity.setReplyComment(commentJson.toString());
                            activity.setReplyTo(null);
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e("Express Browser Access Token", e.getMessage());
                        } catch (JSONException e) {
                            Log.e("BROWSER_EXPRESS_REPLY_COMMENT_EXTRACT", e.getMessage());
                        }
                    }else{
                        // mComments.add(0, comment);
                        // mCommentAdapter.notifyItemInserted(0);

                        // LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
                        // layoutManager.scrollToPositionWithOffset(0, 0);

                        try{
                            BraveActivity activity = BraveActivity.getBraveActivity();
                            // Updating comment count for bottom toolbar
                            mCommentsText = activity.getCommentCountText();

                            String currentText = mCommentsText.getText().toString();
                            int commentCount = 0;
                            try {
                                String[] parts = currentText.split(" ");
                                if (parts.length > 0) {
                                    commentCount = Integer.parseInt(parts[0]);
                                }
                            } catch (NumberFormatException e) {
                            }

                            // Increment the comment count
                            commentCount++;

                            mCommentsText.setText(String.format(Locale.getDefault(), "%d comments", commentCount));
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e("Express Browser Access Token", e.getMessage());
                        }
                    }
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("Express Browser LOGIN", "INSIDE LOGIN FAILED");
                }
            };
    
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
        transaction.replace(R.id.bottom_sheet_container, fragment);
        transaction.commit();
    }

    public EditText getMessageEditText() {
        return mMessageEditText;
    }

    public TextView getReplyToText() {
        return mReplyToText;
    }

    public TextView getCancelReplyButton() {
        return mCancelReplyButton;
    }
}
