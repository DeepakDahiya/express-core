/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/youtube_script_injector/brave_youtube_script_injector_native_helper.h"

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/logging.h"
#include "base/strings/utf_string_conversions.h"
#include "brave/browser/android/youtube_script_injector/youtube_script_injector_tab_helper.h"
#include "brave/browser/android/youtube_script_injector/jni_headers/BraveYouTubeScriptInjectorNativeHelper_jni.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/window_android.h"

namespace youtube_script_injector {

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

  LOG(INFO) << "StartGlobalPip: Starting PiP mode";

  // Get the render frame host
  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (!rfh) {
    LOG(ERROR) << "StartGlobalPip: No primary main frame";
    return;
  }

  // Use JavaScript to manipulate the video element
  // This keeps the video playing while moving it off-screen
  const char* script = R"(
    (function() {
      const video = document.querySelector('video');
      if (video) {
        // Store original video state
        window._pipOriginalVideo = {
          parent: video.parentNode,
          nextSibling: video.nextSibling,
          style: video.style.cssText
        };
        
        // Create a placeholder
        const placeholder = document.createElement('div');
        placeholder.id = 'pip-placeholder';
        placeholder.style.width = video.offsetWidth + 'px';
        placeholder.style.height = video.offsetHeight + 'px';
        placeholder.style.background = '#000';
        placeholder.style.display = 'flex';
        placeholder.style.alignItems = 'center';
        placeholder.style.justifyContent = 'center';
        placeholder.innerHTML = '<span style="color: white;">Playing in mini-player</span>';
        video.parentNode.insertBefore(placeholder, video);
        
        // Move video to an off-screen position but keep it playing
        video.style.position = 'fixed';
        video.style.left = '-10000px';
        video.style.top = '-10000px';
        video.style.width = '320px';
        video.style.height = '180px';
        video.style.pointerEvents = 'none';
        document.body.appendChild(video);
        
        // Ensure video continues playing
        if (video.paused) {
          video.play();
        }
        
        // Prevent pause on visibility change
        document.addEventListener('visibilitychange', function(e) {
          e.stopImmediatePropagation();
        }, true);
        
        console.log('Video prepared for PiP streaming');
        return true;
      }
      return false;
    })();
  )";
  
  rfh->ExecuteJavaScript(base::ASCIIToUTF16(script), base::NullCallback());
  
  LOG(INFO) << "Global PiP JavaScript executed";
}

void StopGlobalPip(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return;
  }

  // Restore video position via JavaScript
  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (rfh) {
    const char* script = R"(
      (function() {
        const video = document.querySelector('video');
        const placeholder = document.getElementById('pip-placeholder');
        if (video && window._pipOriginalVideo && placeholder) {
          // Restore video to original position
          video.style.cssText = window._pipOriginalVideo.style;
          if (window._pipOriginalVideo.nextSibling) {
            window._pipOriginalVideo.parent.insertBefore(
              video, window._pipOriginalVideo.nextSibling);
          } else {
            window._pipOriginalVideo.parent.appendChild(video);
          }
          // Remove placeholder
          placeholder.remove();
          delete window._pipOriginalVideo;
          console.log('Video restored from PiP');
        }
      })();
    )";
    rfh->ExecuteJavaScript(base::ASCIIToUTF16(script), base::NullCallback());
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

  // Toggle via JavaScript
  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (rfh) {
    const char* script = R"(
      (function() {
        const video = document.querySelector('video');
        if (video) {
          if (video.paused) {
            video.play();
            return 'playing';
          } else {
            video.pause();
            return 'paused';
          }
        }
        return 'no_video';
      })();
    )";
    rfh->ExecuteJavaScript(base::ASCIIToUTF16(script), base::NullCallback());
    LOG(INFO) << "Toggled PiP playback via JavaScript";
  }
}

void EnterPictureInPicture(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_BraveYouTubeScriptInjectorNativeHelper_enterPictureInPicture(
      env, web_contents->GetJavaWebContents());
}

}  // namespace youtube_script_injector