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

#include "chrome/browser/ui/exclusive_access/fullscreen_controller.h"
#include "chrome/browser/ui/exclusive_access/exclusive_access_manager.h"
#include "chrome/browser/ui/browser_finder.h"
#include "chrome/browser/ui/browser.h"

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

void JNI_BraveYouTubeScriptInjectorNativeHelper_EnterFullscreenForPip(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents) {
  
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) return;

  // 1. Set the "request" flag so our interceptor knows this is a special call.
  YouTubeScriptInjectorTabHelper* helper =
      YouTubeScriptInjectorTabHelper::FromWebContents(web_contents);
  if (helper) {
    helper->SetFullscreenRequested(true);
  }

  // 2. Find the browser's FullscreenController.
  Browser* browser = chrome::FindBrowserWithWebContents(web_contents);
  if (!browser) return;

  FullscreenController* fullscreen_controller =
      browser->exclusive_access_manager()->fullscreen_controller();
  
  content::RenderFrameHost* main_frame = web_contents->GetPrimaryMainFrame();
  
  if (fullscreen_controller && main_frame) {
    // 3. Directly invoke the browser's internal fullscreen logic.
    fullscreen_controller->EnterFullscreenModeForTab(main_frame, FullscreenTabParams());
  }
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
