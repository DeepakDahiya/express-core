/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "base/immediate_crash.h"
#include "chrome/android/chrome_jni_headers/BrowserExpressCrashVerifier_jni.h"
#include "components/crash/core/common/crash_key.h"

namespace {

crash_reporter::CrashKeyString<64> g_browser_express_crash_verifier(
    "browser-express-crash-verifier");

}  // namespace

namespace chrome {
namespace android {

static void JNI_BrowserExpressCrashVerifier_ForceNativeCrash(JNIEnv* env) {
  g_browser_express_crash_verifier.Set("qa-native-crash");
  base::ImmediateCrash();
}

}  // namespace android
}  // namespace chrome
