/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/brave_youtube_script_injector_native_helper.h"

#include "base/android/jni_android.h"
#include "base/logging.h"
#include "brave/browser/android/youtube_script_injector/jni_headers/BraveYouTubeScriptInjectorNativeHelper_jni.h"
#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"
#include "content/public/browser/media_session.h"
#include "content/public/browser/web_contents.h"
#include "media_session/public/mojom/media_session.mojom.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"
#include "ui/gl/android/scoped_java_surface.h"

// Required for ANativeWindow_fromSurface
#include <android/native_window_jni.h>

namespace youtube_script_injector {

// static
void JNI_BraveYouTubeScriptInjectorNativeHelper_SetFullscreen(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  YouTubeScriptInjectorTabHelper* helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  if (!helper) {
    return;
  }

  helper->MaybeSetFullscreen();
}

// static
jboolean JNI_BraveYouTubeScriptInjectorNativeHelper_HasFullscreenBeenRequested(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);

  YouTubeScriptInjectorTabHelper* helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  if (!helper) {
    return false;
  }

  return helper->HasFullscreenBeenRequested();
}

// static
jboolean JNI_BraveYouTubeScriptInjectorNativeHelper_IsPictureInPictureAvailable(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);

  YouTubeScriptInjectorTabHelper* helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  if (helper) {
    return helper->IsPictureInPictureAvailable();
  }

  return false;
}

// static
void EnterPictureInPicture(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_BraveYouTubeScriptInjectorNativeHelper_enterPictureInPicture(
      env, web_contents->GetJavaWebContents());
}

// --- Implementation of New JNI Methods for Surface-based PiP ---

// static
void JNI_BraveYouTubeScriptInjectorNativeHelper_StartGlobalPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents,
    const base::android::JavaParamRef<jobject>& jsurface) {
  // ARCHITECTURAL NOTE:
  // A direct redirection of a video stream from a <video> element to a custom
  // native Surface is not possible through public Chromium APIs. The rendering
  // is handled in a separate, sandboxed process for security and stability.
  //
  // A full implementation would require significant changes to the Chromium
  // content/ and media/ layers to expose the underlying media player's surface
  // target.
  //
  // This function will log that the process has started and ensure the media
  // is playing, but it cannot perform the actual stream redirection. The visual
  // video will still appear in the main WebContents. The Android PiP window
  // will show a snapshot of the main activity, which includes the mini-player
  // UI with the SurfaceView, but the video frames won't be on that SurfaceView.
  LOG(WARNING) << "StartGlobalPip: Surface redirection is not implemented due "
                  "to Chromium architectural constraints. Video will continue "
                  "playing in the main tab.";

  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  if (!web_contents) {
    return;
  }

  // We can still control the media session to ensure it's playing.
  content::MediaSession* media_session =
      content::MediaSession::Get(web_contents);
  if (media_session) {
    media_session->Resume(content::MediaSession::SuspendType::kUI);
  }

  // The following is placeholder code for what a full implementation would need.
  // ANativeWindow* window = ANativeWindow_fromSurface(env, jsurface);
  // FindMediaPlayerAndRedirect(web_contents, window);
  // ANativeWindow_release(window);
}

// static
void JNI_BraveYouTubeScriptInjectorNativeHelper_StopGlobalPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  // As with StartGlobalPip, this is a placeholder. A full implementation
  // would restore the video stream to its original target within the WebView.
  LOG(WARNING) << "StopGlobalPip: No-op, as surface redirection is not implemented.";

  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  if (!web_contents) {
    return;
  }

  // We can suspend the media session as a proxy for stopping the PiP player.
  content::MediaSession* media_session =
      content::MediaSession::Get(web_contents);
  if (media_session) {
    media_session->Suspend(content::MediaSession::SuspendType::kUI);
  }
}

// static
void JNI_BraveYouTubeScriptInjectorNativeHelper_TogglePipPlayback(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  // This function IS fully implementable using the MediaSession API.
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  if (!web_contents) {
    return;
  }

  content::MediaSession* media_session =
      content::MediaSession::Get(web_contents);
  if (!media_session) {
    return;
  }

  // Get the current playback state and toggle it.
  media_session->GetMediaSessionInfo(base::BindOnce(
      [](content::MediaSession* session,
         media_session::mojom::MediaSessionInfoPtr info) {
        if (info->playback_state ==
            media_session::mojom::MediaPlaybackState::kPlaying) {
          session->Suspend(content::MediaSession::SuspendType::kUI);
        } else {
          session->Resume(content::MediaSession::SuspendType::kUI);
        }
      },
      media_session));
}

}  // namespace youtube_script_injector