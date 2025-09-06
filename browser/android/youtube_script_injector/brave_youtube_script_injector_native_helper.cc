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
  std::string script = R"(
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
  
  rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());
  
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
    std::string script = R"(
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
    rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());
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
    std::string script = R"(
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
    rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());
    LOG(INFO) << "Toggled PiP playback via JavaScript";
  }
}

void EnterPictureInPicture(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_BraveYouTubeScriptInjectorNativeHelper_enterPictureInPicture(
      env, web_contents->GetJavaWebContents());
}

}  // namespace youtube_script_injector

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

  // Clean up any existing session
  g_streaming_sessions.erase(web_contents);

  // Create a new streaming session
  auto session = std::make_unique<StreamingSession>();
  
  // Create a ScopedJavaSurface from the Java Surface object
  session->surface = gl::ScopedJavaSurface(j_surface, /*auto_release=*/false);
  if (!session->surface.IsValid()) {
    LOG(ERROR) << "StartGlobalPip: Invalid surface";
    return;
  }

  // Generate a unique token for this surface
  session->surface_token = base::UnguessableToken::Create();

  // Get the render frame host
  content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
  if (!rfh) {
    LOG(ERROR) << "StartGlobalPip: No primary main frame";
    return;
  }

  // Alternative approach: Pass the surface directly to the renderer
  // The renderer will handle the surface setup through the compositor
  
  // For now, we'll use JavaScript injection as a fallback
  // This approach directly manipulates the video element from JavaScript
  std::string script = R"(
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
        video.parentNode.insertBefore(placeholder, video);
        
        // Move video to an off-screen position but keep it playing
        video.style.position = 'fixed';
        video.style.left = '-10000px';
        video.style.top = '-10000px';
        video.style.width = '320px';
        video.style.height = '180px';
        document.body.appendChild(video);
        
        // Ensure video continues playing
        if (video.paused) {
          video.play();
        }
        
        console.log('Video prepared for PiP streaming');
        return true;
      }
      return false;
    })();
  )";
  
  rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());

  // Try to bind the Mojo interface (this may not work without proper registration)
  mojo::Remote<brave::mojom::VideoSurfaceStreamer> streamer;
  rfh->GetRemoteInterfaces()->GetInterface(streamer.BindNewPipeAndPassReceiver());
  
  if (streamer.is_bound()) {
    LOG(INFO) << "Successfully bound VideoSurfaceStreamer interface";
    session->streamer = std::move(streamer);
    
    // Start streaming to the surface
    session->streamer->StartStreaming(
        session->surface_token,
        base::BindOnce([](bool success) {
          LOG(INFO) << "Streaming started: " << (success ? "success" : "failed");
        }));
  } else {
    LOG(WARNING) << "Could not bind VideoSurfaceStreamer, using fallback method";
    // Fallback: Use existing Android PiP API
    // This will use the standard Android PiP but without the custom surface
  }

  // Store the session
  g_streaming_sessions[web_contents] = std::move(session);
  
  LOG(INFO) << "Global PiP session created for WebContents";
}

void StopGlobalPip(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& j_web_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(j_web_contents);
  if (!web_contents) {
    return;
  }

  // Find and stop the active session
  auto it = g_streaming_sessions.find(web_contents);
  if (it != g_streaming_sessions.end()) {
    if (it->second->streamer.is_bound()) {
      it->second->streamer->StopStreaming();
    }
    
    // Restore video position via JavaScript
    content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
    if (rfh) {
      std::string script = R"(
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
      rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());
    }
    
    g_streaming_sessions.erase(it);
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

  // Find the active session
  auto it = g_streaming_sessions.find(web_contents);
  if (it != g_streaming_sessions.end() && it->second->streamer.is_bound()) {
    it->second->streamer->TogglePlayback();
    LOG(INFO) << "Toggled PiP playback via Mojo";
  } else {
    // Fallback: Toggle via JavaScript
    content::RenderFrameHost* rfh = web_contents->GetPrimaryMainFrame();
    if (rfh) {
      std::string script = R"(
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
      rfh->ExecuteJavaScript(base::UTF8ToUTF16(script), base::NullCallback());
      LOG(INFO) << "Toggled PiP playback via JavaScript";
    }
  }
}

void EnterPictureInPicture(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_BraveYouTubeScriptInjectorNativeHelper_enterPictureInPicture(
      env, web_contents->GetJavaWebContents());
}

}  // namespace youtube_script_injector