/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.FileProviderUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;
import org.chromium.ui.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF search for a comment, through the API's {@code /v1/gif/search} proxy: what is trending
 * until the reader types, then results as they do, more as they scroll. The picked GIF is
 * downloaded into the app's cache and handed to the parent fragment as a content URI, so it goes
 * through exactly the path a GIF from the gallery takes.
 */
public class GifPickerSheet extends BottomSheetDialogFragment {
    public static final String TAG = "GifPickerSheet";

    private static final String SEARCH_URL = "https://api.browser.express/v1/gif/search";
    private static final long SEARCH_DEBOUNCE_MS = 350;
    private static final int TIMEOUT_MS = 15000;
    /** Largest GIF worth attaching; the providers' "downsized" renditions stay well under it. */
    private static final long MAX_GIF_BYTES = 15L * 1024 * 1024;
    /** Where downloaded GIFs wait; exposed through the FileProvider's "comment-media" path. */
    static final String MEDIA_CACHE_DIR = "comment-media";

    /** Implemented by the fragment that shows the sheet. */
    public interface Host {
        void onGifPicked(Uri gifUri);
    }

    /** One result from the API. */
    private static class Gif {
        final String mId;
        final String mPreviewUrl;
        final String mUrl;
        final int mWidth;
        final int mHeight;

        Gif(JSONObject json) {
            mId = json.optString("id");
            mPreviewUrl = json.optString("previewUrl");
            mUrl = json.optString("url");
            mWidth = json.optInt("width", 0);
            mHeight = json.optInt("height", 0);
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Gif> mGifs = new ArrayList<>();
    private final GifAdapter mAdapter = new GifAdapter();

    private EditText mSearch;
    private ProgressBar mLoading;
    private TextView mMessage;
    private TextView mAttribution;

    private String mQuery = "";
    private @Nullable String mNextPos;
    /** Bumped on every new search so a slower, older response can't land over a newer one. */
    private int mGeneration;
    private boolean mLoadingPage;
    private boolean mDownloading;

    private final Runnable mSearchRunnable = () -> startSearch(mSearch.getText().toString());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.BrowserExpressCommentSheetTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.browser_express_comment_gif_sheet, container, false);
        view.findViewById(R.id.sheet_close).setOnClickListener(v -> dismiss());
        mSearch = view.findViewById(R.id.gif_search);
        mLoading = view.findViewById(R.id.gif_loading);
        mMessage = view.findViewById(R.id.gif_message);
        mAttribution = view.findViewById(R.id.gif_attribution);

        RecyclerView grid = view.findViewById(R.id.gif_grid);
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        grid.setLayoutManager(layoutManager);
        grid.setAdapter(mAdapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || mLoadingPage || mNextPos == null) return;
                int[] last = layoutManager.findLastVisibleItemPositions(null);
                int furthest = 0;
                for (int position : last) furthest = Math.max(furthest, position);
                if (furthest >= mGifs.size() - 6) loadPage(mQuery, mNextPos, mGeneration);
            }
        });

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mSearchRunnable);
                mHandler.postDelayed(mSearchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        mSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            mHandler.removeCallbacks(mSearchRunnable);
            startSearch(mSearch.getText().toString());
            return true;
        });

        startSearch("");
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            View sheet = dialog.findViewById(R.id.design_bottom_sheet);
            if (sheet != null) {
                // A fixed share of the screen: the grid scrolls inside it and the search field
                // stays put while results come and go.
                ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.75f);
                sheet.setLayoutParams(params);
            }
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
        // Any response still in flight belongs to a view that is gone.
        mGeneration++;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void startSearch(String rawQuery) {
        String query = rawQuery.trim();
        if (query.equals(mQuery) && !mGifs.isEmpty()) return;
        mQuery = query;
        mNextPos = null;
        mGeneration++;
        mGifs.clear();
        mAdapter.notifyDataSetChanged();
        loadPage(query, null, mGeneration);
    }

    private void loadPage(String query, @Nullable String pos, int generation) {
        mLoadingPage = true;
        if (mGifs.isEmpty()) {
            mLoading.setVisibility(View.VISIBLE);
            mMessage.setVisibility(View.GONE);
        }
        new SearchTask(query, pos, (page, provider, next, unavailable) -> {
            if (generation != mGeneration || !isAdded()) return;
            mLoadingPage = false;
            mLoading.setVisibility(View.GONE);
            if (page == null) {
                if (mGifs.isEmpty()) {
                    showMessage(unavailable ? R.string.browser_express_gif_unavailable
                                            : R.string.browser_express_gif_failed);
                }
                return;
            }
            int start = mGifs.size();
            mGifs.addAll(page);
            mAdapter.notifyItemRangeInserted(start, page.size());
            mNextPos = next;
            if (mGifs.isEmpty()) showMessage(R.string.browser_express_gif_empty);
            if (provider != null && !provider.isEmpty()) {
                // The providers' terms ask for attribution wherever their results show.
                String name = "giphy".equals(provider) ? "GIPHY" : "Tenor";
                mAttribution.setText(getString(R.string.browser_express_gif_attribution, name));
                mAttribution.setVisibility(View.VISIBLE);
            }
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showMessage(int stringId) {
        mMessage.setText(stringId);
        mMessage.setVisibility(View.VISIBLE);
    }

    private void onGifClicked(Gif gif) {
        if (mDownloading) return;
        mDownloading = true;
        mLoading.setVisibility(View.VISIBLE);
        File dir = new File(requireContext().getCacheDir(), MEDIA_CACHE_DIR);
        File target = new File(dir, "gif_" + gif.mId.replaceAll("[^A-Za-z0-9_-]", "") + ".gif");
        new DownloadTask(gif.mUrl, target, file -> {
            mDownloading = false;
            if (!isAdded()) return;
            mLoading.setVisibility(View.GONE);
            Uri uri = file != null ? FileProviderUtils.getContentUriFromFile(file) : null;
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.browser_express_gif_failed,
                             Toast.LENGTH_SHORT)
                        .show();
                return;
            }
            if (getParentFragment() instanceof Host) {
                ((Host) getParentFragment()).onGifPicked(uri);
            }
            dismiss();
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class GifAdapter extends RecyclerView.Adapter<GifAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final ImageView mImage;

            Holder(ImageView image) {
                super(image);
                mImage = image;
                image.setClipToOutline(true);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder((ImageView) LayoutInflater.from(parent.getContext())
                                      .inflate(R.layout.browser_express_comment_gif_item, parent,
                                              false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Gif gif = mGifs.get(position);
            // Sized from the GIF's own shape so the staggered columns don't jump as they load.
            int columnWidth = holder.itemView.getResources().getDisplayMetrics().widthPixels / 2;
            ViewGroup.LayoutParams params = holder.mImage.getLayoutParams();
            params.height = gif.mWidth > 0 && gif.mHeight > 0
                    ? Math.max(columnWidth / 3, columnWidth * gif.mHeight / gif.mWidth)
                    : columnWidth * 3 / 4;
            holder.mImage.setLayoutParams(params);
            Glide.with(holder.mImage).load(gif.mPreviewUrl).into(holder.mImage);
            holder.mImage.setOnClickListener(v -> onGifClicked(gif));
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            Glide.with(holder.mImage).clear(holder.mImage);
        }

        @Override
        public int getItemCount() {
            return mGifs.size();
        }
    }

    private interface SearchCallback {
        void onPage(@Nullable List<Gif> page, @Nullable String provider, @Nullable String next,
                boolean unavailable);
    }

    private static class SearchTask extends AsyncTask<Void> {
        private final String mQuery;
        private final @Nullable String mPos;
        private final SearchCallback mCallback;
        private List<Gif> mPage;
        private String mProvider;
        private String mNext;
        private boolean mUnavailable;

        SearchTask(String query, @Nullable String pos, SearchCallback callback) {
            mQuery = query;
            mPos = pos;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            HttpURLConnection connection = null;
            try {
                StringBuilder url = new StringBuilder(SEARCH_URL)
                        .append("?q=").append(URLEncoder.encode(mQuery, "UTF-8"));
                if (mPos != null) url.append("&pos=").append(URLEncoder.encode(mPos, "UTF-8"));
                connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                        new URL(url.toString()),
                        NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_UNAVAILABLE) {
                    mUnavailable = true;
                    return null;
                }
                if (code != HttpURLConnection.HTTP_OK) return null;
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                             connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                }
                JSONObject response = new JSONObject(text.toString());
                JSONArray results = response.optJSONArray("results");
                List<Gif> page = new ArrayList<>();
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    Gif gif = new Gif(item);
                    if (!gif.mPreviewUrl.isEmpty() && !gif.mUrl.isEmpty()) page.add(gif);
                }
                mPage = page;
                mProvider = response.optString("provider", null);
                mNext = response.isNull("next") ? null : response.optString("next", null);
            } catch (Exception e) {
                Log.e(TAG, "GIF search failed: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mCallback.onPage(mPage, mProvider, mNext, mUnavailable);
        }
    }

    private interface DownloadCallback {
        void onDownloaded(@Nullable File file);
    }

    private static class DownloadTask extends AsyncTask<Void> {
        private final String mUrl;
        private final File mTarget;
        private final DownloadCallback mCallback;
        private File mResult;

        DownloadTask(String url, File target, DownloadCallback callback) {
            mUrl = url;
            mTarget = target;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground() {
            if (mTarget.exists() && mTarget.length() > 0) {
                mResult = mTarget;
                return null;
            }
            HttpURLConnection connection = null;
            File partial = new File(mTarget.getPath() + ".part");
            try {
                File dir = mTarget.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) return null;
                connection = (HttpURLConnection) ChromiumNetworkAdapter.openConnection(
                        new URL(mUrl), NetworkTrafficAnnotationTag.MISSING_TRAFFIC_ANNOTATION);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                long total = 0;
                try (InputStream in = connection.getInputStream();
                        OutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_GIF_BYTES) return null;
                        out.write(buffer, 0, read);
                    }
                }
                if (total > 0 && partial.renameTo(mTarget)) mResult = mTarget;
            } catch (Exception e) {
                Log.e(TAG, "GIF download failed: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                if (partial.exists() && !partial.delete()) {
                    Log.w(TAG, "Could not remove partial GIF " + partial);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mCallback.onDownloaded(mResult);
        }
    }
}
