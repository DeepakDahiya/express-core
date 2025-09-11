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

#include "content/browser/web_contents/web_contents_impl.h"
#include "content/public/browser/web_contents.h"
#include "content/browser/renderer_host/render_frame_host_impl.h"
#include "third_party/blink/public/mojom/frame/fullscreen.mojom.h"

namespace youtube_script_injector {

void JNI_BraveYouTubeScriptInjectorNativeHelper_EnterFullscreenForPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents) {
  
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) return;

  // Cast to WebContentsImpl to access the internal EnterFullscreenMode method.
  auto* web_contents_impl = static_cast<content::WebContentsImpl*>(web_contents);

  // The target for fullscreen is the primary main frame.
  content::RenderFrameHost* target_frame = web_contents->GetPrimaryMainFrame();
  if (!target_frame) return;

  // DEBUGGING: This is the correct way to iterate through all frames.
  LOG(INFO) << "Available frames in WebContents:";
  web_contents->ForEachRenderFrameHost([](content::RenderFrameHost* rfh) {
      LOG(INFO) << "  Frame URL: " << rfh->GetLastCommittedURL();
  });

  // [!! FIX !!] Create a default FullscreenOptions object.
  // The .mojom file shows this is a simple struct with default values.
  blink::mojom::FullscreenOptions options;

  // This is the direct, low-level call to initiate fullscreen for the frame.
  // It is the correct replacement for the desktop-only FullscreenController.
  web_contents_impl->EnterFullscreenMode(target_frame, options);

  LOG(INFO) << "EnterFullscreenMode called successfully for the primary main frame.";
}

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

}  // namespace youtube_script_injector
