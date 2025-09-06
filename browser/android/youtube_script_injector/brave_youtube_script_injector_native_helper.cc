/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/brave_youtube_script_injector_native_helper.h"

#include "base/android/jni_android.h"
#include "brave/browser/android/youtube_script_injector/jni_headers/BraveYouTubeScriptInjectorNativeHelper_jni.h"
#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"
#include "content/public/browser/web_contents.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"

#include "base/android/scoped_java_ref.h"
#include "content/public/browser/media_player_host.h"
#include "content/public/browser/render_frame_host.h"
#include "media/mojo/mojom/media_player.mojom.h"
#include "services/media_session/public/mojom/media_session.mojom.h"
#include "ui/gl/android/scoped_java_surface.h"

namespace {
// Helper function to find the primary, active video player for a WebContents.
media::mojom::MediaPlayer* GetActiveMediaPlayer(content::WebContents* web_contents) {
  if (!web_contents) {
    return nullptr;
  }
  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (!rfh) {
    return nullptr;
  }
  content::MediaPlayerHost* host = content::MediaPlayerHost::Get(rfh->GetProcess()->GetID(), rfh->GetRoutingID());
  if (!host) {
    return nullptr;
  }

  // Find the first player that is currently playing. This is a heuristic that
  // works well for pages like YouTube with one primary video.
  for (auto& player_ptr : host->GetMediaPlayers()) {
    media::mojom::MediaPlayer* player = player_ptr.get();
    bool is_playing = false;
    // We need to synchronously check if the player is playing.
    if (player->IsPlaying(&is_playing) && is_playing) {
      return player;
    }
  }
  return nullptr; // No active player found
}
} // namespace

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

void JNI_BraveYouTubeScriptInjectorNativeHelper_StartGlobalPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents,
    const base::android::JavaParamRef<jobject>& jsurface) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  
  media::mojom::MediaPlayer* player = GetActiveMediaPlayer(web_contents);
  if (!player) {
    LOG(ERROR) << "StartGlobalPip: Could not find an active media player.";
    return;
  }

  // This is the critical redirection call.
  // We create a ScopedJavaSurface, which is a C++ wrapper around the Java Surface
  // object, and pass it to the media player. The media pipeline will then
  // redirect its output to this surface.
  player->SetSurface(gl::ScopedJavaSurface(jsurface));
  LOG(INFO) << "StartGlobalPip: Successfully redirected video stream to new surface.";
}

void JNI_BraveYouTubeScriptInjectorNativeHelper_StopGlobalPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);

  media::mojom::MediaPlayer* player = GetActiveMediaPlayer(web_contents);
  if (!player) {
    LOG(ERROR) << "StopGlobalPip: Could not find an active media player.";
    return;
  }

  // To restore the video to the webpage, we pass an empty/invalid surface.
  // The media pipeline interprets this as a command to revert to its default
  // rendering target, which is the original web view.
  player->SetSurface(gl::ScopedJavaSurface());
  LOG(INFO) << "StopGlobalPip: Successfully restored video stream to web page.";
}

void JNI_BraveYouTubeScriptInjectorNativeHelper_TogglePipPlayback(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);

  media::mojom::MediaPlayer* player = GetActiveMediaPlayer(web_contents);
  if (!player) {
    LOG(ERROR) << "TogglePipPlayback: Could not find an active media player.";
    return;
  }

  // Check the player's state and issue the opposite command.
  bool is_playing = false;
  if (player->IsPlaying(&is_playing)) {
    if (is_playing) {
      player->Pause(false); // `false` for "not triggered by media session"
      LOG(INFO) << "TogglePipPlayback: Paused video.";
    } else {
      player->Start();
      LOG(INFO) << "TogglePipPlayback: Started video.";
    }
  }
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

}  // namespace youtube_script_injector
