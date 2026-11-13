/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.DialogFragment;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.ui.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * Full-screen crop stage between picking an image and composing the comment around it: pan the
 * selection, drag its corners, rotate in quarter turns, then hit Next. Next ALWAYS delivers a
 * result — a freshly written cache JPEG when the user edited, or the untouched source URI when
 * they didn't — so the host can treat it as "this is the image, carry on". Backing out instead
 * fires {@link OnImageCroppedListener#onCropCancelled()} and nothing is attached.
 */
public class ImageCropDialogFragment extends DialogFragment {
    private static final String TAG = "ImageCropDialog";
    private static final String ARG_SOURCE_URI = "source_uri";

    /**
     * Cropping runs on the image straight out of the picker, ahead of ImageProcessor, so this
     * screen does its own downsampling. The cap stays above ImageProcessor's 1920 output so the
     * downstream resize is what decides the final resolution, not this one.
     */
    private static final int MAX_WORKING_DIMENSION = 2048;
    private static final int OUTPUT_JPEG_QUALITY = 90;

    public interface OnImageCroppedListener {
        void onImageCropped(Uri croppedImageUri);

        /** The user left the crop stage without confirming; the post should not proceed. */
        default void onCropCancelled() {}
    }

    private Uri mSourceUri;
    @Nullable private OnImageCroppedListener mListener;
    /** Set once Next has delivered a URI, so dismissal stops counting as a cancel. */
    private boolean mResultDelivered;

    private ImageCropView mCropView;
    private ImageButton mRotateButton;
    private Button mResetButton;
    private Button mDoneButton;
    private ProgressBar mProgressBar;

    /** As loaded from disk; kept unrotated so Reset can return to it without re-decoding. */
    @Nullable private Bitmap mOriginalBitmap;
    @Nullable private Bitmap mCurrentBitmap;
    private int mQuarterTurns;
    private boolean mSaving;

    public static ImageCropDialogFragment newInstance(Uri sourceUri) {
        ImageCropDialogFragment fragment = new ImageCropDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_SOURCE_URI, sourceUri);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof OnImageCroppedListener) {
            mListener = (OnImageCroppedListener) getParentFragment();
        } else if (context instanceof OnImageCroppedListener) {
            mListener = (OnImageCroppedListener) context;
        } else {
            Log.w(TAG, "Host does not implement OnImageCroppedListener; crops will be dropped");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (getArguments() != null) {
            mSourceUri = getArguments().getParcelable(ARG_SOURCE_URI);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_image_crop, container, false);

        // This dialog is full screen, so every system bar can cover it — most visibly a
        // 3-button navigation bar sitting on top of the Next/Reset row. The padding here is the
        // only thing holding the content clear of the bars; onStart() puts the window in the
        // matching mode. See EdgeToEdgeUtil.
        EdgeToEdgeUtil.padAllBySystemBars(view);

        mCropView = view.findViewById(R.id.crop_view);
        mRotateButton = view.findViewById(R.id.crop_rotate_button);
        mResetButton = view.findViewById(R.id.crop_reset_button);
        mDoneButton = view.findViewById(R.id.crop_done_button);
        mProgressBar = view.findViewById(R.id.crop_progress);
        ImageButton closeButton = view.findViewById(R.id.crop_close_button);

        setEditingEnabled(false);

        closeButton.setOnClickListener(v -> dismiss());
        mRotateButton.setOnClickListener(v -> rotateByQuarterTurn());
        mResetButton.setOnClickListener(v -> resetEdits());
        mDoneButton.setOnClickListener(v -> confirmCrop());

        if (mSourceUri != null) {
            new LoadBitmapTask(this, mSourceUri).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            dismiss();
        }
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // The window only exists from here on, and it decides whether the decor insets the
        // content or leaves that to padAllBySystemBars; pinning it settles which one applies.
        if (getDialog() != null) EdgeToEdgeUtil.makeWindowEdgeToEdge(getDialog().getWindow());
    }

    private void setEditingEnabled(boolean enabled) {
        mRotateButton.setEnabled(enabled);
        mResetButton.setEnabled(enabled);
        mDoneButton.setEnabled(enabled);
    }

    private void onBitmapLoaded(@Nullable Bitmap bitmap) {
        if (!isAdded() || mCropView == null) return;
        mProgressBar.setVisibility(View.GONE);
        if (bitmap == null) {
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.be_crop_failed, Toast.LENGTH_SHORT).show();
            }
            dismiss();
            return;
        }
        mOriginalBitmap = bitmap;
        mCurrentBitmap = bitmap;
        mCropView.setBitmap(bitmap);
        setEditingEnabled(true);
    }

    private void rotateByQuarterTurn() {
        if (mCurrentBitmap == null || mSaving) return;
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        Bitmap rotated;
        try {
            rotated = Bitmap.createBitmap(mCurrentBitmap, 0, 0, mCurrentBitmap.getWidth(),
                    mCurrentBitmap.getHeight(), matrix, true);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Not enough memory to rotate", e);
            return;
        }
        Bitmap previous = mCurrentBitmap;
        mCurrentBitmap = rotated;
        mQuarterTurns = (mQuarterTurns + 1) % 4;
        mCropView.setBitmap(rotated);
        if (previous != mOriginalBitmap) previous.recycle();
    }

    private void resetEdits() {
        if (mOriginalBitmap == null || mSaving) return;
        Bitmap previous = mCurrentBitmap;
        mCurrentBitmap = mOriginalBitmap;
        mQuarterTurns = 0;
        mCropView.setBitmap(mOriginalBitmap);
        if (previous != null && previous != mOriginalBitmap) previous.recycle();
    }

    private void confirmCrop() {
        if (mCurrentBitmap == null || mSaving) return;

        // Untouched selection on an unrotated image: nothing to re-encode, the
        // original file continues to the upload as-is.
        if (mQuarterTurns == 0 && !mCropView.hasSelection()) {
            deliverResult(mSourceUri);
            return;
        }

        Rect cropRect = mCropView.getCropRectInBitmap();
        if (cropRect == null) {
            deliverResult(mSourceUri);
            return;
        }

        mSaving = true;
        setEditingEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        new SaveCropTask(this, mCurrentBitmap, cropRect)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void onCropSaved(@Nullable Uri croppedUri) {
        mSaving = false;
        if (!isAdded()) return;
        mProgressBar.setVisibility(View.GONE);
        if (croppedUri == null) {
            setEditingEnabled(true);
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.be_crop_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        deliverResult(croppedUri);
    }

    private void deliverResult(Uri uri) {
        mResultDelivered = true;
        if (mListener != null) mListener.onImageCropped(uri);
        dismiss();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        // Covers every way out that isn't Next: the X, the back gesture, or the
        // system tearing the dialog down.
        if (!mResultDelivered && mListener != null) {
            mListener.onCropCancelled();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // A save still running holds mCurrentBitmap on a worker thread; recycling under it would
        // crash the compress. Those bitmaps are left to the GC instead.
        if (!mSaving) {
            if (mCurrentBitmap != null && mCurrentBitmap != mOriginalBitmap) {
                mCurrentBitmap.recycle();
            }
            if (mOriginalBitmap != null) mOriginalBitmap.recycle();
        }
        mCurrentBitmap = null;
        mOriginalBitmap = null;
    }

    private static class LoadBitmapTask extends AsyncTask<Bitmap> {
        private final WeakReference<ImageCropDialogFragment> mFragmentRef;
        private final WeakReference<Context> mContextRef;
        private final Uri mUri;

        LoadBitmapTask(ImageCropDialogFragment fragment, Uri uri) {
            mFragmentRef = new WeakReference<>(fragment);
            mContextRef = new WeakReference<>(fragment.requireContext().getApplicationContext());
            mUri = uri;
        }

        @Override
        protected Bitmap doInBackground() {
            Context context = mContextRef.get();
            if (context == null) return null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream in = context.getContentResolver().openInputStream(mUri)) {
                    if (in == null) return null;
                    BitmapFactory.decodeStream(in, null, options);
                }
                if (options.outWidth <= 0 || options.outHeight <= 0) return null;

                options.inSampleSize = 1;
                while (options.outWidth / (options.inSampleSize * 2) >= MAX_WORKING_DIMENSION / 2
                        && options.outHeight / (options.inSampleSize * 2)
                                >= MAX_WORKING_DIMENSION / 2) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;

                Bitmap bitmap;
                try (InputStream in = context.getContentResolver().openInputStream(mUri)) {
                    if (in == null) return null;
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
                if (bitmap == null) return null;

                // Straight from the picker, so nothing has normalized EXIF rotation yet.
                try (InputStream in = context.getContentResolver().openInputStream(mUri)) {
                    if (in != null) {
                        int orientation = new ExifInterface(in).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                        int degrees = 0;
                        switch (orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
                            case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                            case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                        }
                        if (degrees != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(degrees);
                            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                                    bitmap.getHeight(), matrix, true);
                            if (upright != bitmap) bitmap.recycle();
                            bitmap = upright;
                        }
                    }
                }
                return bitmap;
            } catch (IOException | OutOfMemoryError e) {
                Log.e(TAG, "Failed to load bitmap for cropping", e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            ImageCropDialogFragment fragment = mFragmentRef.get();
            if (fragment != null) {
                fragment.onBitmapLoaded(result);
            } else if (result != null) {
                result.recycle();
            }
        }
    }

    private static class SaveCropTask extends AsyncTask<Uri> {
        private final WeakReference<ImageCropDialogFragment> mFragmentRef;
        private final WeakReference<Context> mContextRef;
        private final Bitmap mBitmap;
        private final Rect mCropRect;

        SaveCropTask(ImageCropDialogFragment fragment, Bitmap bitmap, Rect cropRect) {
            mFragmentRef = new WeakReference<>(fragment);
            mContextRef = new WeakReference<>(fragment.requireContext().getApplicationContext());
            mBitmap = bitmap;
            mCropRect = cropRect;
        }

        @Override
        protected Uri doInBackground() {
            Context context = mContextRef.get();
            if (context == null || mBitmap.isRecycled()) return null;
            File outputFile = null;
            try {
                Bitmap cropped = Bitmap.createBitmap(
                        mBitmap, mCropRect.left, mCropRect.top, mCropRect.width(),
                        mCropRect.height());
                outputFile = File.createTempFile("cropped_img_", ".jpg", context.getCacheDir());
                try (FileOutputStream out = new FileOutputStream(outputFile)) {
                    cropped.compress(Bitmap.CompressFormat.JPEG, OUTPUT_JPEG_QUALITY, out);
                }
                // createBitmap returns the source itself for a full-frame crop of a rotated
                // image; that bitmap is still on screen, so only recycle real copies.
                if (cropped != mBitmap) cropped.recycle();
                return Uri.fromFile(outputFile);
            } catch (IOException | IllegalArgumentException | OutOfMemoryError e) {
                Log.e(TAG, "Failed to save cropped image", e);
                if (outputFile != null && outputFile.exists()) outputFile.delete();
                return null;
            }
        }

        @Override
        protected void onPostExecute(Uri result) {
            ImageCropDialogFragment fragment = mFragmentRef.get();
            if (fragment != null) fragment.onCropSaved(result);
        }
    }
}
