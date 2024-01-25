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
import android.widget.CheckBox;
import org.chromium.ui.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import android.util.DisplayMetrics;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

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
    private int mPerPage = 30;
    private String mUrl;

    private boolean isFromMenu;
    // private Button nextButton;
    private ImageButton mSendButton;
    private EditText mMessageEditText;

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

        mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_gchat);
        mCommentRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        mCommentAdapter = new CommentListAdapter(requireContext(), mComments);
        mCommentRecycler.setAdapter(mCommentAdapter);

        // ViewGroup.LayoutParams params=mCommentRecycler.getLayoutParams();
        // params.height=a;
        // mCommentRecycler.setLayoutParams(params);

        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
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
                        comments.add(new Comment(
                            comment.getString("_id"), 
                            comment.getString("content"),
                            comment.getInt("upvoteCount"),
                            comment.getInt("downvoteCount"),
                            comment.getInt("commentCount"),
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
        

            mUrl = activity.getActivityTab().getUrl().getSpec();

            BrowserExpressGetCommentsUtil.GetCommentsWorkerTask workerTask =
                new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                        mUrl, mPage, mPerPage, getCommentsCallback);
            workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e("Browser Express Access Token", e.getMessage());
        }

        mSendButton = view.findViewById(R.id.button_gchat_send);
        mMessageEditText = view.findViewById(R.id.edit_gchat_message);
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
                                BrowserExpressAddCommentUtil.AddCommentWorkerTask workerTask =
                                    new BrowserExpressAddCommentUtil.AddCommentWorkerTask(
                                            content, "page", mUrl, null, accessToken, addCommentCallback);
                                workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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

                    // mCommentRecycler = (RecyclerView) view.findViewById(R.id.recycler_gchat);
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
                    mComments.add(0, comment);
                    mCommentAdapter.notifyItemInserted(0);
                    mMessageEditText.setText(R.string.browser_express_empty_text);
                }

                @Override
                public void addCommentFailed(String error) {
                    Log.e("BROWSER EXPRESS LOGIN", "INSIDE LOGIN FAILED");
                }
            };
}
