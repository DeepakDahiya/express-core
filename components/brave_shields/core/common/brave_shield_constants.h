// Copyright (c) 2019 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_COMMON_BRAVE_SHIELD_CONSTANTS_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_COMMON_BRAVE_SHIELD_CONSTANTS_H_

#include "base/files/file_path.h"

namespace brave_shields {

inline constexpr char kAds[] = "shieldsAds";
inline constexpr char kCosmeticFiltering[] = "cosmeticFiltering";
inline constexpr char kTrackers[] = "trackers";
inline constexpr char kHTTPUpgradableResources[] = "httpUpgradableResources";
inline constexpr char kHTTPSUpgrades[] = "httpsUpgrades";
inline constexpr char kJavaScript[] = "javascript";
inline constexpr char kFingerprintingV2[] = "fingerprintingV2";
inline constexpr char kBraveShields[] = "braveShields";
inline constexpr char kBraveShieldsMetadata[] = "braveShieldsMetadata";
inline constexpr char kReferrers[] = "referrers";
inline constexpr char kCookies[] = "shieldsCookiesV3";
inline constexpr char kFacebookEmbeds[] = "fb-embeds";
inline constexpr char kTwitterEmbeds[] = "twitter-embeds";
inline constexpr char kLinkedInEmbeds[] = "linked-in-embeds";

// Values used before the migration away from ResourceIdentifier, kept around
// for migration purposes only.
inline constexpr char kObsoleteAds[] = "ads";
inline constexpr char kObsoleteCookies[] = "cookies";
inline constexpr char kObsoleteShieldsCookies[] = "shieldsCookies";

// Some users were not properly migrated from fingerprinting V1.
inline constexpr char kObsoleteFingerprinting[] = "fingerprinting";

// Key for procedural and action filters in the UrlCosmeticResources struct from
// adblock-rust
inline constexpr char kCosmeticResourcesProceduralActions[] =
    "procedural_actions";

// Filename for cached text from a custom filter list subscription
const base::FilePath::CharType kCustomSubscriptionListText[] =
    FILE_PATH_LITERAL("list_text.txt");

inline constexpr char kCookieListUuid[] =
    "AC023D22-AE88-4060-A978-4FEEEC4221693";
inline constexpr char kMobileNotificationsListUuid[] =
    "2F3DCE16-A19A-493C-A88F-2E110FBD37D6";
inline constexpr char kExperimentalListUuid[] =
    "564C3B75-8731-404C-AD7C-5683258BA0B0";

inline constexpr char kAdBlockResourceComponentName[] =
    "Brave Ad Block Resources Library";
inline constexpr char kAdBlockResourceComponentId[] =
    "dlibbfbdhhamaleoofdjnejelcgldodb";
inline constexpr char kAdBlockResourceComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlYa/Beo8ErS7GB31gsjH"
    "QOerBEGD58eXXf6GLPxp8cyJ/DFF06Svo8uXEZMuFK6pT6Qi1byX5UVjNmeB5Xwb"
    "6TndxJGQAYPL2YA8R60OpKDL8fKRmikI6vBleV1Fw56qYi/SoT47xqxf/F7uOFms"
    "W6768ImB9lyF6YWW5ZpUDaHj1H5XemUWSF3JzY6uBYEkjdn1KmbE+zz+tNh1UXrd"
    "AkWSXr3opGDFNWKEg9kOBa2gEZh42mwNJDS8Olo8RgrKQbSHr5zxMCuxsntdua22"
    "mH4vBzHoauLRwQqYpLW64Kkl5xx0cDizUF7EGbqhB+Xcm4yD5kvNzmDzQ6WdNMRL"
    "kwIDAQAB";

inline constexpr char kAdBlockFilterListCatalogComponentName[] =
    "Brave Ad Block List Catalog";
inline constexpr char kAdBlockFilterListCatalogComponentId[] =
    "bbhodghhfoljambigkiibfnmgkobming";
inline constexpr char kAdBlockFilterListCatalogComponentBase64PublicKey[] =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAteLpEXUrGmrqoP0zVdrr"
    "G6ryZX4zuM6uKMSFjTFwGYD3BmqKPZis65HABWe1dSVE96YojLBG+uGK9McNwHb7"
    "pC+Ht3L9TRF4qY/vLLHFSb1lBzgNo+IVOC+s7cj0tQ5CquKd4I14xJTAlHovsI6y"
    "tki4N89is2wFGuJCv+zY6Y8fBmseykhlcp3EHHNX3iKaQMX5B66omEw57iAHMXcB"
    "xNzQEcssBcPnej3FlaPojgdtg+Fzb55OpRgYCq/N5VP8oKGG2LZAxW8rjyab4wVp"
    "I5cNRpVJZzdDhRdkIU05TpIiC0drDprKvNdUpumTVxTtzeOfWjXQPqW7iTSX6HJP"
    "UwIDAQAB";

inline constexpr char kCookieListEnabledHistogram[] =
    "Brave.Shields.CookieListEnabled";
inline constexpr char kCookieListPromptHistogram[] =
    "Brave.Shields.CookieListPrompt";

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_COMMON_BRAVE_SHIELD_CONSTANTS_H_
