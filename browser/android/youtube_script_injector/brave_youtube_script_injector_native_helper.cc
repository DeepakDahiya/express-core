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
#include "content/public/android/java/jni_helper.h"
#include "content/public/browser/web_contents.h"
#include "content/browser/renderer_host/render_frame_host_impl.h"
#include "third_party/blink/public/mojom/fullscreen/fullscreen.mojom.h"

namespace youtube_script_injector {

void JNI_BraveYouTubeScriptInjectorNativeHelper_EnterFullscreenForPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents) {
  
  // 1. Convert the Java WebContents reference to native WebContents
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents)
    return;

  // Cast to WebContentsImpl so we can call internal methods
  auto* web_contents_impl = static_cast<content::WebContentsImpl*>(web_contents);

  // 2. Get the correct RenderFrameHostImpl
  // YouTube videos are usually in an iframe, but start with main frame.
  content::RenderFrameHostImpl* target_frame =
      static_cast<content::RenderFrameHostImpl*>(web_contents->GetPrimaryMainFrame());

  if (!target_frame)
    return;

  // DEBUG: Log all frames to ensure we target the correct one.
  for (auto* frame : web_contents->GetAllFrames()) {
    auto* rfh = static_cast<content::RenderFrameHostImpl*>(frame);
    LOG(INFO) << "Frame URL: " << rfh->GetLastCommittedURL();
    // If needed, you can match against a YouTube embed URL here
  }

  // 3. Build fullscreen options
  blink::mojom::FullscreenOptions options;
  options.has_toolbar = false;
  options.prefers_video_only = true;  // Video-only fullscreen mode
  options.display_id = 0;             // Default display

  // 4. Call the internal Chromium method
  web_contents_impl->EnterFullscreenMode(target_frame, options);

  LOG(INFO) << "EnterFullscreenMode called successfully for YouTube video.";
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
