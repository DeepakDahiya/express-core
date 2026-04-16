/* Copyright (c) 2023 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/components/constants/pref_names.h"
#include "chrome/browser/profiles/profile.h"
// Pull in the PasswordManagerSetting enum before the kOfferToSavePasswords
// macro below, so the enumerator declaration isn't rewritten by the macro.
#include "components/password_manager/core/browser/password_manager_setting.h"

#define IsGuestSession                                                   \
  IsGuestSession() ||                                                    \
      (!profile->GetPrefs()->GetBoolean(kBraveAutofillPrivateWindows) && \
       (IsOffTheRecord() || profile->IsTor())) ||                        \
      profile->IsGuestSession

// Suppress the "Save password" prompt on Google and YouTube domains.
// The save bubble is noisy on these high-traffic signin flows and users
// overwhelmingly already have accounts saved elsewhere.
#define kOfferToSavePasswords                                    \
  kOfferToSavePasswords) &&                                      \
         !url.DomainIs("google.com") &&                          \
         !url.DomainIs("youtube.com") &&                         \
         settings_service->IsSettingEnabled(                     \
             PasswordManagerSetting::kOfferToSavePasswords

#include <chrome/browser/password_manager/chrome_password_manager_client.cc>
#undef kOfferToSavePasswords
#undef IsGuestSession
