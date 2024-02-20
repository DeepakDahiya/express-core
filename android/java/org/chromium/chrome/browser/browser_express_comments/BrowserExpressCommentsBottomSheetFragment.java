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

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.EditText;

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

public class BrowserExpressCommentsBottomSheetFragment extends BottomSheetDialogFragment {
    private static final String IS_FROM_MENU = "is_from_menu";
    private RecyclerView mCommentRecycler;
    private CommentListAdapter mCommentAdapter;
    private List<Comment> mComments;
    private int mPage = 1;
    private int mPerPage = 100;
    private String mUrl;

    private boolean isFromMenu;
    // private Button nextButton;
    private ImageButton mSendButton;
    private ImageButton mCanceReplyButton;
    private EditText mMessageEditText;
    private TextView mReplyToText;

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
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(
                R.layout.fragment_browser_express_comments_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        ((BottomSheetDialog) getDialog())
                .getBehavior()
                .setState(BottomSheetBehavior.STATE_EXPANDED);

        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        mComments = new ArrayList<Comment>();

        DisplayMetrics displaymetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

        int a =  (displaymetrics.heightPixels*70)/100;

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        mCommentAdapter = new CommentListAdapter(requireContext(), mComments);
        mCommentRecycler.setAdapter(mCommentAdapter);

        // ViewGroup.LayoutParams params=mCommentRecycler.getLayoutParams();
        // params.height=a;
        // mCommentRecycler.setLayoutParams(params);

        mSendButton = view.findViewById(R.id.button_send);
        mMessageEditText = view.findViewById(R.id.comment_content);
        mReplyToText = view.findViewById(R.id.reply_to);
        mCanceReplyButton = view.findViewById(R.id.cancel_btn);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();

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
                                mCanceReplyButton.setVisibility(View.VISIBLE);
                                mMessageEditText.requestFocus();
                                InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,InputMethodManager.HIDE_IMPLICIT_ONLY);
                            } catch (JSONException e) {
                                Log.e("BROWSER_EXPRESS_REPLY_TO_EXTRACT", e.getMessage());
                            }
                        }else{
                            mReplyToText.setText(R.string.browser_express_empty_text);
                            mCanceReplyButton.setVisibility(View.INVISIBLE);
                        }
                    }
                }
            };

            sharedPref.registerOnSharedPreferenceChangeListener(listener);

            String commentsString = activity.getFirstComments();
            if(commentsString != null){
                try{
                    JSONArray commentsArray = new JSONArray(commentsString);
                    List<Comment> comments = new ArrayList<Comment>();
                    for (int i = 0; i < commentsArray.length(); i++) {
                        JSONObject comment = commentsArray.getJSONObject(i);
                        JSONObject user = comment.getJSONObject("user");
                        JSONObject didVote = comment.optJSONObject("didVote");
                        Vote v = null;
                        if(didVote != null){
                            v = new Vote(didVote.getString("_id"), didVote.getString("type"));
                        }
                        User u = new User(user.getString("_id"), user.getString("username"));
                        String pageParent = null;
                        String commentParent = null;
                        if(comment.has("pageParent")){
                            pageParent = comment.getString("pageParent");
                        }

                        if(comment.has("commentParent")){
                            commentParent = comment.getString("commentParent");
                        }
                        comments.add(new Comment(
                            comment.getString("_id"), 
                            comment.getString("content"),
                            comment.getInt("upvoteCount"),
                            comment.getInt("downvoteCount"),
                            comment.getInt("commentCount"),
                            pageParent,
                            commentParent,
                            u, 
                            v));
                    }

                    int len = mComments.size();
                    mComments.clear();
                    mCommentAdapter.notifyItemRangeRemoved(0, len);
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(0, comments.size());

                    mPage = 2;
                } catch (JSONException e) {
                    Log.e("Comments_Bottom_Sheet", e.getMessage());
                }
            }
        
            String accessToken = activity.getAccessToken();
            mUrl = activity.getActivityTab().getUrl().getSpec();

            BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                        mUrl, null, mPage, mPerPage, accessToken, getCommentsCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Browser Express Access Token", e.getMessage());
        }

        mCanceReplyButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setReplyTo(null);
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e("Browser Express Access Token", e.getMessage());
                    }
                }
            }
        }));

        mSendButton.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        String accessToken = activity.getAccessToken();
                        if (accessToken == null) {
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
                                    BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                        new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                                content, "page", mUrl, null, accessToken, addCommentCallback);
                                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                }
                            }
                        }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                        Log.e("Browser Express Access Token", e.getMessage());
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

    private BrowserExpressGetCommentsUtil.GetCommentsCallback getCommentsCallback=
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsSuccessful(List<Comment> comments) {
                    int len = mComments.size();
                    // mComments.clear();
                    // mCommentAdapter.notifyItemRangeRemoved(0, len);
                    mComments.addAll(comments);
                    mCommentAdapter.notifyItemRangeInserted(len-1, comments.size());

                    // data.addAll(insertIndex, items);
                    // mCommentAdapter.notifyItemRangeInserted(insertIndex, items.size());

                    // DisplayMetrics displaymetrics = new DisplayMetrics();
                    // getActivity().getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

                    // int a =  (displaymetrics.heightPixels*70)/100;

                    // mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_comments);
                    // mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

                    // mCommentAdapter = new CommentListAdapter(requireContext(), mComments);
                    // mCommentRecycler.setAdapter(mCommentAdapter);

                    // ViewGroup.LayoutParams params=mCommentRecycler.getLayoutParams();
                    // params.height=a;
                    // mCommentRecycler.setLayoutParams(params);

                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e("BROWSER EXPRESS LOGIN", "INSIDE LOGIN FAILED");
                }
            };

    private BrowserExpressAddCommentUtil.AddCommentCallback addCommentCallback=
            new BrowserExpressAddCommentUtil.AddCommentCallback() {
                @Override
                public void addCommentSuccessful(Comment comment) {
                    if(comment.getCommentParent() == null){
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
                        } catch (BraveActivity.BraveActivityNotFoundException e) {
                            Log.e("Browser Express Access Token", e.getMessage());
                        } catch (JSONException e) {
                            Log.e("BROWSER_EXPRESS_REPLY_COMMENT_EXTRACT", e.getMessage());
                        }
                    }else{
                        mComments.add(0, comment);
                        mCommentAdapter.notifyItemInserted(0);
                        mMessageEditText.setText(R.string.browser_express_empty_text);

                        LinearLayoutManager layoutManager = (LinearLayoutManager) mCommentRecycler.getLayoutManager();
                        layoutManager.scrollToPositionWithOffset(0, 0);
                    }
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("BROWSER EXPRESS LOGIN", "INSIDE LOGIN FAILED");
                }
            };
}
