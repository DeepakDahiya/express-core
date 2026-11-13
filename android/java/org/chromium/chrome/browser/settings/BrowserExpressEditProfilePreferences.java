/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.settings;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;

import com.bumptech.glide.Glide;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.FileProviderUtils;
import org.chromium.base.Log;
import org.chromium.base.supplier.ObservableSupplier;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.helpers.ProfileAvatar;
import org.chromium.ui.UiUtils;
import org.chromium.ui.util.ColorUtils;
import org.chromium.ui.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Edit profile (design handoff "Edit profile"), opened by Edit on the Profile: the photo, the name
 * and the username, the only things the Profile shows. Save is on only once something changed,
 * the name isn't blank and the username is unchanged or known to be free, checked 550ms after
 * typing stops. Cancel (or back) with changes asks first. A save goes back to the Profile, which
 * says "Profile updated"; a failed one stays here with the input kept and an error banner.
 *
 * <p>The photo sheet, the "Move and scale" stage and the discard dialog are full-screen windows of
 * their own, so their scrim covers the status bar as the design's does. With no uploaded photo
 * the account shows its default face, as it does everywhere ({@link ProfileAvatar}), where the
 * design has initials.
 */
public class BrowserExpressEditProfilePreferences extends BravePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = "EditProfile";

    private static final int NAME_MAX = 30;
    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 20;
    private static final Pattern USERNAME_CHARS = Pattern.compile("^[a-z0-9_]*$");
    private static final long USERNAME_CHECK_DELAY_MS = 550;
    private static final long SAVE_SPINNER_MIN_MS = 800;
    private static final long ERROR_BANNER_MS = 4000;

    /** The avatar uploader's own cap: avatars show at 120dp at most. */
    private static final int AVATAR_SIZE_PX = 512;
    private static final int AVATAR_JPEG_QUALITY = 90;
    /** The crop stage's working copy: enough for a 512px avatar at 2× zoom. */
    private static final int CROP_MAX_DIMENSION = 2048;
    /** Camera captures go where the FileProvider shares from (res_template/xml/file_paths.xml). */
    private static final String CAMERA_DIR = "comment-media";
    private static final String STATE_CAMERA_URI = "edit_profile_camera_uri";

    // Motion, as tokens.json has it.
    private static final long SCRIM_IN_MS = 200;
    private static final long SHEET_IN_MS = 280;
    private static final float SHEET_IN_DP = 40f;
    private static final long DIALOG_IN_MS = 220;
    private static final float DIALOG_IN_SCALE = 0.94f;
    private static final long CROP_IN_MS = 200;
    private static final long OVERLAY_OUT_MS = 150;
    private static final Interpolator EASE_OUT_CUBIC =
            new PathInterpolator(0.215f, 0.61f, 0.355f, 1f);

    /** Where the username stands; it decides the helper line, the icon and Save. */
    private enum Check {
        /** As saved: nothing to check. */
        IDLE,
        BAD_CHARS,
        SHORT,
        CHECKING,
        AVAILABLE,
        TAKEN,
        /** The server could not say; the save checks again. */
        UNKNOWN
    }

    private final ObservableSupplierImpl<String> mPageTitle = new ObservableSupplierImpl<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheckUsername = this::checkUsername;
    private final Runnable mHideError = this::hideError;

    private ActivityResultLauncher<PickVisualMediaRequest> mPickPhoto;
    private ActivityResultLauncher<Uri> mTakePhoto;
    private ActivityResultLauncher<String> mCameraPermission;
    private @Nullable Uri mPendingCameraUri;

    private ImageView mAvatar;
    private TextView mPhotoCta;
    private EditText mName;
    private TextView mNameCount;
    private EditText mUsername;
    private View mUsernameChecking;
    private View mUsernameAvailable;
    private View mUsernameBad;
    private TextView mHelper;
    private TextView mSave;
    private View mSavingSpinner;
    private View mError;
    private TextView mErrorDetail;

    private String mSavedName = "";
    private String mSavedUsername = "";
    /** The account's picture, resolved: its upload, else its default face. */
    private @Nullable String mSavedPhotoUrl;
    private boolean mSavedHasPhoto;
    /** A new photo, cropped, until it is saved. */
    private @Nullable Bitmap mDraftPhoto;
    private boolean mDraftPhotoRemoved;

    private Check mCheck = Check.IDLE;
    /** Bumped on every username edit, so an answer about an older one is dropped. */
    private int mCheckGeneration;
    private boolean mCleaningUsername;
    private boolean mSaving;
    private long mSaveStartedMs;

    /** The sheet, crop stage or dialog on screen, if any. */
    private @Nullable Dialog mOverlay;
    private @Nullable View mSheetCameraSettings;

    @Override
    public ObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // The camera app may outlive this page's process; the photo's destination has to too.
            mPendingCameraUri = savedInstanceState.getParcelable(STATE_CAMERA_URI);
        }
        mPickPhoto = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (uri != null) openCrop(uri);
                });
        mTakePhoto = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), taken -> {
                    Uri photo = mPendingCameraUri;
                    mPendingCameraUri = null;
                    if (Boolean.TRUE.equals(taken) && photo != null) openCrop(photo);
                });
        mCameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        closeOverlay(false);
                        launchCamera();
                    } else if (mSheetCameraSettings != null) {
                        // The sheet stays up, pointing to where the permission can be turned on.
                        mSheetCameraSettings.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_CAMERA_URI, mPendingCameraUri);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.browser_express_edit_profile_title));
        return inflater.inflate(R.layout.browser_express_edit_profile_settings, container, false);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {}

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object o) {
        return true;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAvatar = view.findViewById(R.id.edit_profile_avatar);
        mPhotoCta = view.findViewById(R.id.edit_profile_photo_cta);
        mName = view.findViewById(R.id.edit_profile_name);
        mNameCount = view.findViewById(R.id.edit_profile_name_count);
        mUsername = view.findViewById(R.id.edit_profile_username);
        mUsernameChecking = view.findViewById(R.id.edit_profile_username_checking);
        mUsernameAvailable = view.findViewById(R.id.edit_profile_username_ok);
        mUsernameBad = view.findViewById(R.id.edit_profile_username_bad);
        mHelper = view.findViewById(R.id.edit_profile_helper);
        mSave = view.findViewById(R.id.edit_profile_save);
        mSavingSpinner = view.findViewById(R.id.edit_profile_saving);
        mError = view.findViewById(R.id.edit_profile_error);
        mErrorDetail = view.findViewById(R.id.edit_profile_error_detail);
        ((EditProfileSpinner) mUsernameChecking).setArcColor(ContextCompat.getColor(
                requireContext(), R.color.browser_express_profile_text_meta));

        view.findViewById(R.id.edit_profile_cancel).setOnClickListener(v -> cancel());
        mSave.setOnClickListener(v -> save());
        view.findViewById(R.id.edit_profile_photo).setOnClickListener(v -> openPhotoSheet());
        mPhotoCta.setOnClickListener(v -> openPhotoSheet());
        mError.setOnClickListener(v -> hideError());
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        cancel();
                    }
                });

        // Who they are, from the session at once; /v1/user/me corrects it below.
        String accessToken = accessToken();
        JSONObject claims = claimsOf(accessToken);
        mSavedName = textOf(claims, "name");
        mSavedUsername = textOf(claims, "username");
        mSavedPhotoUrl = ProfileAvatar.selfUrl(accessToken);
        mSavedHasPhoto = hasUpload(mSavedPhotoUrl);
        mName.setText(mSavedName);
        mUsername.setText(mSavedUsername);
        mName.addTextChangedListener(new AfterTextChanged(this::onNameEdited));
        mUsername.addTextChangedListener(new AfterTextChanged(this::onUsernameEdited));
        showPhoto();
        render();

        if (accessToken == null) return;
        BrowserExpressProfileApi.fetch(accessToken, profile -> {
            // Only onto an untouched page: the reader's edits win.
            if (profile == null || !isAdded() || getView() == null || hasChanges()) return;
            mSavedName = profile.name != null ? profile.name : "";
            mSavedUsername = profile.username != null ? profile.username : "";
            mSavedPhotoUrl = profile.avatarUrl;
            mSavedHasPhoto = hasUpload(mSavedPhotoUrl);
            mName.setText(mSavedName);
            mUsername.setText(mSavedUsername);
            showPhoto();
            render();
        });
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        closeOverlay(false);
        super.onDestroyView();
    }

    // ---------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------

    private String draftName() {
        return mName.getText().toString();
    }

    private String draftUsername() {
        return mUsername.getText().toString();
    }

    private boolean draftHasPhoto() {
        return mDraftPhoto != null || (mSavedHasPhoto && !mDraftPhotoRemoved);
    }

    private boolean photoChanged() {
        return mDraftPhoto != null || mDraftPhotoRemoved;
    }

    private boolean hasChanges() {
        return !draftName().equals(mSavedName) || !draftUsername().equals(mSavedUsername)
                || photoChanged();
    }

    private boolean canSave() {
        return hasChanges() && !draftName().trim().isEmpty() && !mSaving
                && (mCheck == Check.IDLE || mCheck == Check.AVAILABLE || mCheck == Check.UNKNOWN);
    }

    /**
     * Holds the name to 30 characters. Not with android:maxLength, which would also cut a longer
     * name saved before the limit the moment the page shows it.
     */
    private void onNameEdited(Editable text) {
        if (text.length() > NAME_MAX && !text.toString().equals(mSavedName)) {
            text.delete(NAME_MAX, text.length());
            return; // The deletion comes back through here.
        }
        render();
    }

    /**
     * Lowercases the username and strips spaces as it is typed, holds it to 20 characters, then
     * works out where it stands. The saved one is left as it is: generated usernames can be
     * longer than 20.
     */
    private void onUsernameEdited(Editable text) {
        if (mCleaningUsername) return;
        String typed = text.toString();
        String clean = typed;
        if (!typed.equals(mSavedUsername)) {
            clean = typed.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            if (clean.length() > USERNAME_MAX) clean = clean.substring(0, USERNAME_MAX);
        }
        if (!clean.equals(typed)) {
            int cursor = Math.max(0, Math.min(clean.length(),
                    mUsername.getSelectionEnd() - (typed.length() - clean.length())));
            mCleaningUsername = true;
            text.replace(0, text.length(), clean);
            mUsername.setSelection(cursor);
            mCleaningUsername = false;
        }

        mHandler.removeCallbacks(mCheckUsername);
        mCheckGeneration++;
        if (clean.equals(mSavedUsername)) {
            mCheck = Check.IDLE;
        } else if (!USERNAME_CHARS.matcher(clean).matches()) {
            mCheck = Check.BAD_CHARS;
        } else if (clean.length() < USERNAME_MIN) {
            mCheck = Check.SHORT;
        } else {
            mCheck = Check.CHECKING;
            mHandler.postDelayed(mCheckUsername, USERNAME_CHECK_DELAY_MS);
        }
        render();
    }

    private void checkUsername() {
        String accessToken = accessToken();
        if (accessToken == null) {
            mCheck = Check.UNKNOWN;
            render();
            return;
        }
        int generation = mCheckGeneration;
        BrowserExpressProfileApi.checkUsername(accessToken, draftUsername(),
                (username, available) -> {
                    if (generation != mCheckGeneration || !isAdded() || getView() == null) return;
                    mCheck = available == null ? Check.UNKNOWN
                            : available ? Check.AVAILABLE : Check.TAKEN;
                    render();
                });
    }

    /** Brings the counter, photo link, username status, helper line and Save up to date. */
    private void render() {
        mNameCount.setText(getString(
                R.string.browser_express_edit_profile_name_count, draftName().length(), NAME_MAX));
        mPhotoCta.setText(draftHasPhoto() ? R.string.browser_express_edit_profile_change_photo
                                          : R.string.browser_express_edit_profile_add_photo);

        mUsernameChecking.setVisibility(mCheck == Check.CHECKING ? View.VISIBLE : View.GONE);
        mUsernameAvailable.setVisibility(mCheck == Check.AVAILABLE ? View.VISIBLE : View.GONE);
        boolean bad = mCheck == Check.BAD_CHARS || mCheck == Check.SHORT || mCheck == Check.TAKEN;
        mUsernameBad.setVisibility(bad ? View.VISIBLE : View.GONE);

        String username = draftUsername();
        int helperColor;
        switch (mCheck) {
            case BAD_CHARS:
                mHelper.setText(R.string.browser_express_edit_profile_username_badchars);
                helperColor = R.color.browser_express_profile_danger;
                break;
            case SHORT:
                mHelper.setText(R.string.browser_express_edit_profile_username_short);
                helperColor = R.color.browser_express_profile_danger;
                break;
            case CHECKING:
                mHelper.setText(R.string.browser_express_edit_profile_username_checking);
                helperColor = R.color.browser_express_profile_text_meta;
                break;
            case AVAILABLE:
                mHelper.setText(getString(
                        R.string.browser_express_edit_profile_username_ok, username));
                helperColor = R.color.browser_express_edit_profile_success;
                break;
            case TAKEN:
                mHelper.setText(getString(
                        R.string.browser_express_edit_profile_username_taken, username));
                helperColor = R.color.browser_express_profile_danger;
                break;
            default:
                mHelper.setText(R.string.browser_express_edit_profile_username_idle);
                helperColor = R.color.browser_express_profile_text_secondary;
                break;
        }
        mHelper.setTextColor(ContextCompat.getColor(requireContext(), helperColor));

        mSave.setEnabled(canSave());
        mSave.setVisibility(mSaving ? View.INVISIBLE : View.VISIBLE);
        mSavingSpinner.setVisibility(mSaving ? View.VISIBLE : View.GONE);
    }

    private void showPhoto() {
        if (mDraftPhoto != null) {
            Glide.with(mAvatar).clear(mAvatar);
            RoundedBitmapDrawable round =
                    RoundedBitmapDrawableFactory.create(getResources(), mDraftPhoto);
            round.setCircular(true);
            mAvatar.setImageDrawable(round);
        } else if (mDraftPhotoRemoved) {
            ProfileAvatar.load(mAvatar, null, ProfileAvatar.selfId(accessToken()));
        } else {
            ProfileAvatar.load(mAvatar, mSavedPhotoUrl, ProfileAvatar.selfId(accessToken()));
        }
    }

    private void cancel() {
        if (mSaving) return;
        hideKeyboard();
        if (hasChanges()) {
            openDiscardDialog();
        } else {
            requireActivity().finish();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    private void save() {
        if (!canSave()) return;
        String accessToken = accessToken();
        if (accessToken == null) {
            showError(null);
            return;
        }
        hideKeyboard();
        hideError();
        mHandler.removeCallbacks(mCheckUsername);
        mSaving = true;
        mSaveStartedMs = SystemClock.elapsedRealtime();
        render();

        String name = draftName().trim();
        String username = draftUsername();
        if (name.equals(mSavedName) && username.equals(mSavedUsername)) {
            savePhoto(accessToken);
            return;
        }
        BrowserExpressProfileApi.saveIdentity(accessToken, name, username, result -> {
            if (!isAdded() || getView() == null) return;
            if (!result.saved) {
                if ("username_taken".equals(result.code)) {
                    // Taken since the check: the helper line says so.
                    mCheck = Check.TAKEN;
                    failSave(null, false);
                } else {
                    failSave(result.error, true);
                }
                return;
            }
            String nextToken = storeTokens(result);
            mSavedName = name;
            mSavedUsername = username;
            if (!draftName().equals(name)) mName.setText(name); // Trimmed.
            mCheck = Check.IDLE;
            savePhoto(nextToken != null ? nextToken : accessToken);
        });
    }

    /** Uploads or removes the photo if that changed, then finishes the save. */
    private void savePhoto(String accessToken) {
        if (mDraftPhoto != null) {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            mDraftPhoto.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, jpeg);
            new BrowserExpressEditAvatarPreferencesUtil.EditAvatarWorkerTask(
                    new ByteArrayInputStream(jpeg.toByteArray()), accessToken,
                    new BrowserExpressEditAvatarPreferencesUtil.EditAvatarCallback() {
                        @Override
                        public void editAvatarSuccessful() {
                            if (!isAdded() || getView() == null) return;
                            mSavedHasPhoto = true;
                            finishSave(accessToken);
                        }

                        @Override
                        public void editAvatarFailed(String error) {
                            if (!isAdded() || getView() == null) return;
                            failSave(error, true);
                        }
                    })
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else if (mDraftPhotoRemoved) {
            BrowserExpressProfileApi.removeAvatar(accessToken, done -> {
                if (!isAdded() || getView() == null) return;
                if (!done) {
                    failSave(null, true);
                    return;
                }
                mSavedHasPhoto = false;
                mDraftPhotoRemoved = false;
                finishSave(accessToken);
            });
        } else {
            finishSave(accessToken);
        }
    }

    /** Back to the Profile, which shows "Profile updated", once the spinner has had its 800ms. */
    private void finishSave(String accessToken) {
        // Remembers the new picture (or the default face) for the toolbar, comments and Profile.
        BrowserExpressProfileApi.fetch(accessToken, profile -> {});
        trackProfileUpdated(accessToken);
        long shown = SystemClock.elapsedRealtime() - mSaveStartedMs;
        mHandler.postDelayed(() -> {
            if (!isAdded()) return;
            BrowserExpressProfilePreferences.notifyProfileUpdated();
            requireActivity().finish();
        }, Math.max(0, SAVE_SPINNER_MIN_MS - shown));
    }

    private void failSave(@Nullable String reason, boolean banner) {
        mSaving = false;
        render();
        if (banner) showError(reason);
    }

    /** Keeps the fresh session tokens the save returned; returns the access token, if any. */
    private @Nullable String storeTokens(BrowserExpressProfileApi.SaveResult result) {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            if (!TextUtils.isEmpty(result.accessToken)) activity.setAccessToken(result.accessToken);
            if (!TextUtils.isEmpty(result.refreshToken)) {
                activity.setRefreshToken(result.refreshToken);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "New session not stored: " + e.getMessage());
        }
        return result.accessToken;
    }

    private void trackProfileUpdated(String accessToken) {
        JSONObject claims = claimsOf(accessToken);
        String userId = textOf(claims, "_id");
        if (userId.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("app_version", BraveActivity.getBraveActivity().getCurrentAppVersion());
            new PostHogUtil.PostHogWorkerTask(PostHogEventKeys.PROFILE_UPDATED, userId, payload)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (JSONException | BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "Profile-updated event not sent: " + e.getMessage());
        }
    }

    /** The app's error banner, with the server's reason when it gave one. */
    private void showError(@Nullable String reason) {
        mErrorDetail.setText(TextUtils.isEmpty(reason)
                        ? getString(R.string.browser_express_edit_profile_save_failed_retry)
                        : reason);
        mError.setVisibility(View.VISIBLE);
        mError.setAlpha(0f);
        mError.animate().alpha(1f).setDuration(SCRIM_IN_MS).start();
        mHandler.removeCallbacks(mHideError);
        mHandler.postDelayed(mHideError, ERROR_BANNER_MS);
    }

    private void hideError() {
        mHandler.removeCallbacks(mHideError);
        mError.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------------
    // Photo options, camera and library
    // ---------------------------------------------------------------------------------------

    @SuppressLint("InflateParams") // A window of its own: there is no parent to inflate into.
    private void openPhotoSheet() {
        if (mSaving) return;
        hideKeyboard();
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.browser_express_edit_profile_photo_sheet, null);
        mSheetCameraSettings = content.findViewById(R.id.edit_profile_sheet_camera_settings);
        mSheetCameraSettings.setOnClickListener(v -> openAppSettings());
        content.findViewById(R.id.edit_profile_sheet_remove_group)
                .setVisibility(draftHasPhoto() ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.edit_profile_scrim).setOnClickListener(v -> closeOverlay(true));
        content.findViewById(R.id.edit_profile_sheet_cancel)
                .setOnClickListener(v -> closeOverlay(true));
        content.findViewById(R.id.edit_profile_sheet_take_photo)
                .setOnClickListener(v -> takePhoto());
        content.findViewById(R.id.edit_profile_sheet_library).setOnClickListener(v -> {
            closeOverlay(false);
            mPickPhoto.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
        content.findViewById(R.id.edit_profile_sheet_remove).setOnClickListener(v -> {
            closeOverlay(true);
            mDraftPhoto = null;
            mDraftPhotoRemoved = mSavedHasPhoto;
            showPhoto();
            render();
        });
        Dialog sheet = openOverlay(content, false);
        sheet.setOnDismissListener(d -> {
            if (mOverlay == d) mOverlay = null;
            mSheetCameraSettings = null;
        });

        View scrim = content.findViewById(R.id.edit_profile_scrim);
        View panel = content.findViewById(R.id.edit_profile_overlay_insets);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SCRIM_IN_MS).start();
        panel.setAlpha(0f);
        panel.setTranslationY(SHEET_IN_DP * getResources().getDisplayMetrics().density);
        panel.animate().alpha(1f).translationY(0f).setDuration(SHEET_IN_MS)
                .setInterpolator(EASE_OUT_CUBIC).start();
    }

    /**
     * The camera needs the runtime permission because the app declares CAMERA (for the web):
     * with it declared, the capture intent refuses to start without it.
     */
    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            mCameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        closeOverlay(false);
        launchCamera();
    }

    private void launchCamera() {
        File dir = new File(requireContext().getCacheDir(), CAMERA_DIR);
        File photo = new File(dir, "profile_" + System.currentTimeMillis() + ".jpg");
        Uri uri = (dir.exists() || dir.mkdirs())
                ? FileProviderUtils.getContentUriFromFile(photo)
                : null;
        if (uri == null) {
            Toast.makeText(requireContext(), R.string.browser_express_attach_camera_unavailable,
                         Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        mPendingCameraUri = uri;
        try {
            mTakePhoto.launch(uri);
        } catch (ActivityNotFoundException e) {
            mPendingCameraUri = null;
            Toast.makeText(requireContext(), R.string.browser_express_attach_camera_unavailable,
                         Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app settings screen: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Move and scale
    // ---------------------------------------------------------------------------------------

    @SuppressLint("InflateParams") // A window of its own: there is no parent to inflate into.
    private void openCrop(Uri photo) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.browser_express_edit_profile_crop, null);
        EditProfileCropView cropView = content.findViewById(R.id.edit_profile_crop_view);
        SeekBar zoom = content.findViewById(R.id.edit_profile_crop_zoom);
        TextView choose = content.findViewById(R.id.edit_profile_crop_choose);
        content.findViewById(R.id.edit_profile_crop_cancel)
                .setOnClickListener(v -> closeOverlay(true));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                cropView.setZoom(EditProfileCropView.MIN_ZOOM
                        + (EditProfileCropView.MAX_ZOOM - EditProfileCropView.MIN_ZOOM)
                                * progress / seekBar.getMax());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cropView.setOnZoomChangedListener(scale -> zoom.setProgress(Math.round(
                (scale - EditProfileCropView.MIN_ZOOM)
                / (EditProfileCropView.MAX_ZOOM - EditProfileCropView.MIN_ZOOM)
                * zoom.getMax())));
        choose.setOnClickListener(v -> {
            Bitmap cropped = cropView.crop(AVATAR_SIZE_PX);
            if (cropped != null) {
                mDraftPhoto = cropped;
                mDraftPhotoRemoved = false;
                showPhoto();
                render();
            }
            closeOverlay(true);
        });

        Dialog stage = openOverlay(content, true);
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(CROP_IN_MS).start();

        ContentResolver resolver = requireContext().getContentResolver();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Bitmap bitmap = decodeForCrop(resolver, photo);
            PostTask.postTask(TaskTraits.UI_DEFAULT, () -> {
                if (mOverlay != stage || !isAdded()) return;
                if (bitmap == null) {
                    closeOverlay(false);
                    Toast.makeText(requireContext(),
                                 R.string.browser_express_edit_profile_photo_failed,
                                 Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                cropView.setPhoto(bitmap);
                choose.setEnabled(true);
            });
        });
    }

    /**
     * The photo upright (ImageDecoder applies its EXIF orientation), at most {@link
     * #CROP_MAX_DIMENSION} on its long side, in memory a software Canvas can draw from; null when
     * it can't be read.
     */
    private static @Nullable Bitmap decodeForCrop(ContentResolver resolver, Uri uri) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        Size size = info.getSize();
                        int longSide = Math.max(size.getWidth(), size.getHeight());
                        int sample = 1;
                        while (longSide / (sample * 2) >= CROP_MAX_DIMENSION) sample *= 2;
                        decoder.setTargetSampleSize(sample);
                    });
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Photo not readable: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Discard
    // ---------------------------------------------------------------------------------------

    @SuppressLint("InflateParams") // A window of its own: there is no parent to inflate into.
    private void openDiscardDialog() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.browser_express_edit_profile_discard, null);
        content.findViewById(R.id.edit_profile_keep_editing)
                .setOnClickListener(v -> closeOverlay(true));
        content.findViewById(R.id.edit_profile_discard).setOnClickListener(v -> {
            closeOverlay(false);
            requireActivity().finish();
        });
        openOverlay(content, false);

        View scrim = content.findViewById(R.id.edit_profile_scrim);
        View panel = content.findViewById(R.id.edit_profile_overlay_panel);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SCRIM_IN_MS).start();
        panel.setAlpha(0f);
        panel.setScaleX(DIALOG_IN_SCALE);
        panel.setScaleY(DIALOG_IN_SCALE);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(DIALOG_IN_MS)
                .setInterpolator(EASE_OUT_CUBIC).start();
    }

    // ---------------------------------------------------------------------------------------
    // Overlays
    // ---------------------------------------------------------------------------------------

    /**
     * Shows {@code content} in a window of its own over the whole screen, status bar included,
     * with its {@code edit_profile_overlay_insets} view, if it has one, padded clear of the system
     * bars.
     *
     * @param darkBars light status and navigation bar icons whatever the theme (the crop stage).
     */
    private Dialog openOverlay(View content, boolean darkBars) {
        closeOverlay(false);
        Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setWindowAnimations(0);
            WindowCompat.setDecorFitsSystemWindows(window, false);
            UiUtils.setStatusBarColor(window, Color.TRANSPARENT);
        }
        padBySystemBars(content.findViewById(R.id.edit_profile_overlay_insets));
        dialog.setOnDismissListener(d -> {
            if (mOverlay == d) mOverlay = null;
        });
        mOverlay = dialog;
        dialog.show();
        if (window != null) {
            boolean lightBars = !darkBars
                    && !ColorUtils.shouldUseLightForegroundOnBackground(ContextCompat.getColor(
                            requireContext(), R.color.browser_express_profile_page_bg));
            View decor = window.getDecorView();
            UiUtils.setStatusBarIconColor(decor, lightBars);
            UiUtils.setNavigationBarIconColor(decor, lightBars);
        }
        return dialog;
    }

    /** Puts away the sheet, crop stage or dialog, fading it out when {@code animate}. */
    private void closeOverlay(boolean animate) {
        Dialog overlay = mOverlay;
        if (overlay == null) return;
        mOverlay = null;
        Window window = overlay.getWindow();
        View content = window != null ? window.getDecorView() : null;
        if (!animate || content == null || !overlay.isShowing()) {
            overlay.dismiss();
            return;
        }
        content.animate().alpha(0f).setDuration(OVERLAY_OUT_MS)
                .withEndAction(overlay::dismiss).start();
    }

    private static void padBySystemBars(@Nullable View view) {
        if (view == null) return;
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void hideKeyboard() {
        View view = getView();
        InputMethodManager imm = view != null
                ? (InputMethodManager) requireContext().getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE)
                : null;
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private static boolean hasUpload(@Nullable String photoUrl) {
        return photoUrl != null && !ProfileAvatar.isDefaultFace(photoUrl);
    }

    private static @Nullable String accessToken() {
        try {
            return BraveActivity.getBraveActivity().getAccessToken();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            return null;
        }
    }

    /** The session token's claims (name, username, _id), or null. */
    private static @Nullable JSONObject claimsOf(@Nullable String accessToken) {
        if (TextUtils.isEmpty(accessToken)) return null;
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] payload =
                    Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }

    private static String textOf(@Nullable JSONObject json, String key) {
        return json == null || json.isNull(key) ? "" : json.optString(key, "");
    }

    /** A TextWatcher that only cares about the text after a change. */
    private static final class AfterTextChanged implements TextWatcher {
        interface Listener {
            void afterTextChanged(Editable text);
        }

        private final Listener mListener;

        AfterTextChanged(Listener listener) {
            mListener = listener;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            mListener.afterTextChanged(s);
        }
    }
}
