/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_BROWSER_ANDROID_YOUTUBE_SCRIPT_INJECTOR_BRAVE_YOUTUBE_SCRIPT_INJECTOR_NATIVE_HELPER_H_
#define BRAVE_BROWSER_ANDROID_YOUTUBE_SCRIPT_INJECTOR_BRAVE_YOUTUBE_SCRIPT_INJECTOR_NATIVE_HELPER_H_

#include <jni.h>

#include "base/android/scoped_java_ref.h"
#include "content/public/browser/web_contents.h"

namespace youtube_script_injector {

// Enters Picture-in-Picture mode for the given WebContents
void EnterPictureInPicture(content::WebContents* web_contents);

// JNI methods called from Java

// Sets fullscreen for the given WebContents
void SetFullscreen(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& j_web_contents);

// Checks if fullscreen has been requested
jboolean HasFullscreenBeenRequested(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents);

// Checks if Picture-in-Picture is available
jboolean IsPictureInPictureAvailable(
    JNIEnv* env,
    const base::android::JavaParamRef<jobject>& j_web_contents);

// Starts global PiP with the provided surface
void StartGlobalPip(JNIEnv* env,
                   const base::android::JavaParamRef<jobject>& j_web_contents,
                   const base::android::JavaParamRef<jobject>& j_surface);

// Stops global PiP
void StopGlobalPip(JNIEnv* env,
                  const base::android::JavaParamRef<jobject>& j_web_contents);

// Toggles PiP playback (play/pause)
void TogglePipPlayback(JNIEnv* env,
                       const base::android::JavaParamRef<jobject>& j_web_contents);

}  // namespace youtube_script_injector

#endif  // BRAVE_BROWSER_ANDROID_YOUTUBE_SCRIPT_INJECTOR_BRAVE_YOUTUBE_SCRIPT_INJECTOR_NATIVE_HELPER_H_