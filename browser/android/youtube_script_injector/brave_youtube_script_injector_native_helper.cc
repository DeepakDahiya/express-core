/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/brave_youtube_script_injector_native_helper.h"

#include <memory>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/logging.h"
#include "base/unguessable_token.h"
#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"
#include "brave/browser/android/youtube_script_injector/jni_headers/BraveYouTubeScriptInjectorNativeHelper_jni.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "ui/android/window_android.h"
#include "ui/gl/android/scoped_java_surface.h"
#include "gpu/ipc/common/surface_handle.h"

// Include the generated Mojo interface
#include "brave/browser/android/youtube_script_injector/mojom/video_surface_streamer.mojom.h"

#include "services/service_manager/public/cpp/interface_provider.h"

namespace youtube_script_injector {

namespace {

// Store the active video streamers
std::map<content::WebContents*, mojo::Remote<brave::mojom::VideoSurfaceStreamer>> g_active_streamers;

}  // namespace

void SetFullscreen(JNIEnv* env, 
                  const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return;
  }

  YouTubeScriptInjectorTabHelper* tab_helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  if (tab_helper) {
    tab_helper->MaybeSetFullscreen();
  }
}

jboolean HasFullscreenBeenRequested(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return false;
  }

  YouTubeScriptInjectorTabHelper* tab_helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  return tab_helper && tab_helper->HasFullscreenBeenRequested();
}

jboolean IsPictureInPictureAvailable(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return false;
  }

  YouTubeScriptInjectorTabHelper* tab_helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  return tab_helper && tab_helper->IsPictureInPictureAvailable();
}

void StartGlobalPip(JNIEnv* env,
                   const base::android::JavaParamRef<jobject>& j_web_contents,
                   const base::android::JavaParamRef<jobject>& j_surface) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    LOG(ERROR) << "StartGlobalPip: Invalid WebContents";
    return;
  }

  // Create ScopedJavaSurface with correct constructor
  gl::ScopedJavaSurface scoped_surface(j_surface, true /* auto_release */);
  
  // Use the correct validation method
  if (scoped_surface.IsEmpty() || !scoped_surface.IsValid()) {
    LOG(ERROR) << "StartGlobalPip: Invalid surface";
    return;
  }

  // Use the native SurfaceHandle type directly (int32_t on Android)
  gpu::SurfaceHandle native_surface_handle = 
      static_cast<gpu::SurfaceHandle>(
          reinterpret_cast<uintptr_t>(scoped_surface.j_surface().obj()));

  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (!rfh) {
    LOG(ERROR) << "StartGlobalPip: No primary main frame";
    return;
  }

  mojo::Remote<brave::mojom::VideoSurfaceStreamer> streamer;
  rfh->GetRemoteInterfaces()->GetInterface(streamer.BindNewPipeAndPassReceiver());
  
  if (!streamer.is_bound()) {
    LOG(ERROR) << "StartGlobalPip: Failed to bind VideoSurfaceStreamer";
    return;
  }

  g_active_streamers[web_contents] = std::move(streamer);

  // Pass the native surface handle directly
  g_active_streamers[web_contents]->StartStreaming(
      native_surface_handle,
      base::BindOnce([](bool success) {
        if (success) {
          LOG(INFO) << "Successfully started video streaming to surface";
        } else {
          LOG(ERROR) << "Failed to start video streaming to surface";
        }
      }));
}

void StopGlobalPip(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return;
  }

  auto it = g_active_streamers.find(web_contents);
  if (it != g_active_streamers.end()) {
    if (it->second.is_bound()) {
      it->second->StopStreaming();
    }
    g_active_streamers.erase(it);
  }

  LOG(INFO) << "Stopped global PiP for WebContents";
}

void TogglePipPlayback(JNIEnv* env,
                       const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return;
  }

  // Find the active streamer and toggle playback
  auto it = g_active_streamers.find(web_contents);
  if (it != g_active_streamers.end() && it->second.is_bound()) {
    it->second->TogglePlayback();
    LOG(INFO) << "Toggled PiP playback";
  }
}

void EnterPictureInPicture(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_BraveYouTubeScriptInjectorNativeHelper_enterPictureInPicture(
      env, web_contents->GetJavaWebContents());
}

}  // namespace youtube_script_injector