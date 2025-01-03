package org.chromium.chrome.browser.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import android.widget.EditText;
import java.io.UnsupportedEncodingException;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Base64;
import android.net.Uri;
import android.app.Activity;
import android.provider.MediaStore;
import java.io.FileNotFoundException;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;
import java.io.InputStream;

import org.chromium.ui.widget.Toast;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_news.mojom.BraveNewsController;
import org.chromium.brave_news.mojom.Channel;
import org.chromium.brave_news.mojom.Publisher;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_news.BraveNewsControllerFactory;
import org.chromium.chrome.browser.brave_news.BraveNewsUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.crypto_wallet.util.Utils;
import org.chromium.chrome.browser.customtabs.CustomTabActivity;
import org.chromium.chrome.browser.night_mode.GlobalNightModeStateProviderHolder;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.util.BraveConstants;
import org.chromium.chrome.browser.util.BraveTouchUtils;
import org.chromium.components.browser_ui.settings.FragmentSettingsLauncher;
import org.chromium.components.browser_ui.settings.SettingsLauncher;
import org.chromium.mojo.bindings.ConnectionErrorHandler;
import org.chromium.mojo.system.MojoException;

import com.bumptech.glide.Glide;
import android.widget.ImageView;
import org.chromium.chrome.browser.app.helpers.ImageLoader;

import java.util.List;

public class BrowserExpressEditProfilePreferences extends BravePreferenceFragment
        implements BraveNewsPreferencesDataListener, ConnectionErrorHandler,
                   FragmentSettingsLauncher {
    public static final String PREF_SHOW_OPTIN = "show_optin";
    private static final int REQUEST_IMAGE_PICK = 1;

    private LinearLayout mParentLayout;
    private ImageView mAvatarImage;
    private ImageView mEditImage;
    private Button mBtnEdit;
    private EditText mNameEditText;
    private EditText mEmailEditText;
    private EditText mUsernameEditText;
    private TextView mErrorTextView;

    private boolean mIsSuggestionAvailable;
    private boolean mIsChannelAvailable;
    private boolean mIsPublisherAvailable;
    private BraveNewsController mBraveNewsController;

    // SettingsLauncher injected from main Settings Activity.
    private SettingsLauncher mSettingsLauncher;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.browser_express_edit_profile_settings, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (getActivity() != null) {
            getActivity().setTitle("");
        }

        super.onActivityCreated(savedInstanceState);

        initBraveNewsController();

        View view = getView();
        if (view != null) {
            mParentLayout = (LinearLayout) view.findViewById(R.id.layout_parent);
            
            mNameEditText = (EditText) view.findViewById(R.id.be_name);
            mUsernameEditText = (EditText) view.findViewById(R.id.be_username);
            mEmailEditText = (EditText) view.findViewById(R.id.be_email);

            mBtnEdit = (Button) view.findViewById(R.id.btn_edit_1);
            mErrorTextView = (TextView) view.findViewById(R.id.error_message);

            mAvatarImage = (ImageView) view.findViewById(R.id.avatar_image_1);
            mEditImage = (ImageView) view.findViewById(R.id.edit_icon);

            try {
                BraveActivity activity = BraveActivity.getBraveActivity();

                String accessToken = activity.getAccessToken();
                JSONObject decodedAccessTokenObj = this.getDecodedToken(accessToken);

                if (decodedAccessTokenObj.has("email") && !decodedAccessTokenObj.isNull("email")) {
                    String email = decodedAccessTokenObj.getString("email");
                    mEmailEditText.setText(email);

                    if (email.length() > 0) {
                        mEmailEditText.setEnabled(false);
                    }
                } else {
                    mEmailEditText.setText(""); // Set default or placeholder
                    Log.e("Express Browser", "Email not found in token.");
                }

                if (decodedAccessTokenObj.has("name") && !decodedAccessTokenObj.isNull("name")) {
                    mNameEditText.setText(decodedAccessTokenObj.getString("name"));
                } else {
                    mNameEditText.setText(""); // Set default or placeholder
                    Log.e("Express Browser", "Name not found in token.");
                }

                if (decodedAccessTokenObj.has("username") && !decodedAccessTokenObj.isNull("username")) {
                    mUsernameEditText.setText(decodedAccessTokenObj.getString("username"));
                } else {
                    mUsernameEditText.setText(""); // Set default or placeholder
                    Log.e("Express Browser", "Username not found in token.");
                }

                ImageLoader.downloadImage("https://api.dicebear.com/9.x/fun-emoji/png?seed=" + decodedAccessTokenObj.getString("_id") + "&radius=50&backgroundColor=059ff2,71cf62,d84be5,d9915b,f6d594,fcbc34,ffd5dc,ffdfbf,b6e3f4,c0aede,d1d4f9&backgroundType=gradientLinear&mouth=cute,faceMask,kissHeart,lilSmile,smileLol,smileTeeth,tongueOut,wideSmile", Glide.with(activity), false, 5, mAvatarImage, null);
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e("Express Browser Access Token", e.getMessage());
            } catch (JSONException e) {
                Log.e("Express Browser Access Token", e.getMessage());
            }catch(Exception ex){
                Log.e("Express Browser Access Token", ex.getMessage());
            }

            mAvatarImage.setOnClickListener(view1 -> openImagePicker());
            mEditImage.setOnClickListener(view1 -> openImagePicker());

            mBtnEdit.setText(R.string.browser_express_edit_profile_button_title);

            mBtnEdit.setOnClickListener(view1 -> {
                try {
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    String accessToken1 = activity.getAccessToken();

                    String email = mEmailEditText.getText().toString();
                    String name = mNameEditText.getText().toString();
                    String username = mUsernameEditText.getText().toString();

                    mErrorTextView.setText(R.string.browser_express_empty_text);
                    mErrorTextView.setVisibility(View.INVISIBLE);

                    String emptyString = "";

                    if(username.equals(emptyString)){
                        mErrorTextView.setText(R.string.browser_express_fill_all_fields_text);
                        mErrorTextView.setVisibility(View.VISIBLE);
                        return;
                    }

                    mBtnEdit.setClickable(false);
                    mBtnEdit.setText(R.string.browser_express_loading_title);

                    Utils.hideKeyboard(getActivity());

                    BrowserExpressEditProfilePreferencesUtil.EditProfileWorkerTask workerTask =
                            new BrowserExpressEditProfilePreferencesUtil.EditProfileWorkerTask(
                                    email, username, name, accessToken1, editProfileCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                    Log.e("Express Browser Access Token", e.getMessage());
                }catch(Exception ex){
                    Log.e("Express Browser Access Token", ex.getMessage());
                }
            });

            setData();
            onClickViews();
        }
    }

    private void setData() {
        if (!GlobalNightModeStateProviderHolder.getInstance().isInNightMode()
                && getView() != null) {
            LottieAnimationView lottieAnimationVIew =
                    (LottieAnimationView) getView().findViewById(R.id.animation_view);

            try {
                lottieAnimationVIew.addValueCallback(new KeyPath("newspaper", "**"),
                        LottieProperty.COLOR_FILTER,
                        frameInfo
                        -> new PorterDuffColorFilter(ContextCompat.getColor(getActivity(),
                                                             R.color.news_settings_optin_color),
                                PorterDuff.Mode.SRC_ATOP));
            } catch (Exception exception) {
                // if newspaper keypath changed in animation json
            }
        }

        if (BraveNewsUtils.getLocale() != null
                && BraveNewsUtils.getSuggestionsPublisherList().size() > 0) {
            mIsSuggestionAvailable = true;
        }

        boolean isNewsEnable = BraveNewsUtils.shouldDisplayNewsFeed();
        onShowNewsToggle(isNewsEnable);
    }

    private void onClickViews() {
    }

    private void onShowNewsToggle(boolean isEnable) {
        BravePrefServiceBridge.getInstance().setShowNews(isEnable);

        SharedPreferencesManager.getInstance().writeBoolean(
                BravePreferenceKeys.BRAVE_NEWS_PREF_SHOW_NEWS, isEnable);

        FrameLayout.LayoutParams parentLayoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);

        parentLayoutParams.gravity = Gravity.NO_GRAVITY;
        mParentLayout.setLayoutParams(parentLayoutParams);
    }

    private void openBraveNewsPreferencesDetails(
            BraveNewsPreferencesType braveNewsPreferencesType) {
        Bundle fragmentArgs = new Bundle();
        fragmentArgs.putString(
                BraveConstants.BRAVE_NEWS_PREFERENCES_TYPE, braveNewsPreferencesType.toString());
        mSettingsLauncher.launchSettingsActivity(
                getActivity(), BraveNewsPreferencesDetails.class, fragmentArgs);
    }

    private void initBraveNewsController() {
        if (mBraveNewsController != null) {
            return;
        }

        mBraveNewsController =
                BraveNewsControllerFactory.getInstance().getBraveNewsController(this);
    }

    @Override
    public void onChannelReceived() {
    }

    @Override
    public void onPublisherReceived() {
    }

    @Override
    public void onSuggestionsReceived() {
    }

    @Override
    public void setSettingsLauncher(SettingsLauncher settingsLauncher) {
        mSettingsLauncher = settingsLauncher;
    }

    @Override
    public void onConnectionError(MojoException e) {
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
        mBraveNewsController = null;
        initBraveNewsController();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBraveNewsController != null) {
            mBraveNewsController.close();
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImage = data.getData();
            Log.e("ImagePicker", "Selected image URI: " + selectedImage.toString());
            if (selectedImage != null) {
                try {
                    Log.e("ImagePicker", "Selected image URI 2: " + selectedImage.toString());
                    BraveActivity activity = BraveActivity.getBraveActivity();
                    Glide.with(getActivity())
                        .load(selectedImage)
                        .circleCrop()
                        .into(mAvatarImage);
                    
                    Log.e("ImagePicker", "Sending image to backend");
                    String accessToken = activity.getAccessToken();

                    InputStream imageStream = getActivity().getContentResolver().openInputStream(selectedImage);

                    BrowserExpressEditAvatarPreferencesUtil.EditAvatarWorkerTask workerTask =
                            new BrowserExpressEditAvatarPreferencesUtil.EditAvatarWorkerTask(imageStream, accessToken, editAvatarCallback);
                    workerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    
                } catch (BraveActivity.BraveActivityNotFoundException e) {
                } catch(FileNotFoundException ex){}
                
            }
        }
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

    private BrowserExpressEditProfilePreferencesUtil.EditProfileCallback editProfileCallback =
            new BrowserExpressEditProfilePreferencesUtil.EditProfileCallback() {
                @Override
                public void editProfileSuccessful(String accessToken, String refreshToken) {
                    mBtnEdit.setClickable(true);
                    mBtnEdit.setText(R.string.browser_express_edit_profile_button_title);

                    try {
                        BraveActivity activity = BraveActivity.getBraveActivity();
                        activity.setAccessToken(accessToken);
                        Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        intent.setAction(Intent.ACTION_VIEW);
                        Toast.makeText(activity, "Profile Updated", Toast.LENGTH_SHORT).show();
                        startActivity(intent);
                        // if (getFragmentManager() != null) {
                        //     getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                        // }
                    } catch (BraveActivity.BraveActivityNotFoundException e) {
                    }
                }

                @Override
                public void editProfileFailed(String error) {
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnEdit.setClickable(true);
                    mBtnEdit.setText(R.string.browser_express_edit_profile_button_title);
                }
            };

    private BrowserExpressEditAvatarPreferencesUtil.EditAvatarCallback editAvatarCallback =
            new BrowserExpressEditAvatarPreferencesUtil.EditAvatarCallback() {
                @Override
                public void editAvatarSuccessful() {
                    mBtnEdit.setClickable(true);
                    mBtnEdit.setText(R.string.browser_express_edit_profile_button_title);

                    // try {
                    //     BraveActivity activity = BraveActivity.getBraveActivity();
                    //     activity.setAccessToken(accessToken);
                    //     Intent intent = new Intent(getActivity(), ChromeTabbedActivity.class);
                    //     intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    //     intent.setAction(Intent.ACTION_VIEW);
                    //     Toast.makeText(activity, "Profile Updated", Toast.LENGTH_SHORT).show();
                    //     startActivity(intent);
                    //     // if (getFragmentManager() != null) {
                    //     //     getFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                    //     // }
                    // } catch (BraveActivity.BraveActivityNotFoundException e) {
                    // }
                }

                @Override
                public void editAvatarFailed(String error) {
                    mErrorTextView.setText(error);
                    mErrorTextView.setVisibility(View.VISIBLE);

                    mBtnEdit.setClickable(true);
                    mBtnEdit.setText(R.string.browser_express_edit_profile_button_title);
                }
            };
}
