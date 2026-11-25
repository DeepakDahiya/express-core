// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// The YouTube home tutorial (the spotlight on a video in `ExpressYouTubeScript.js`) shows once
/// per install, and only on the m.youtube.com home, like Android's `kYoutubeHomeTutorialShown`
/// profile pref. The flag lives in the app rather than in the page's localStorage, so www and m
/// share it and clearing website data (Clear on launch) doesn't bring the tutorial back.
///
/// The page asks with `homeTutorialState` before it shows anything and reports
/// `homeTutorialShown` once the overlay is on screen (`ExpressYouTubeScriptHandler`).
@MainActor
enum ExpressYouTubeHomeTutorial {
  private static let shownKey = "express.youtube.home_tutorial_shown"

  static var isShown: Bool {
    applyDebugReset()
    return UserDefaults.standard.bool(forKey: shownKey)
  }

  /// Whether a page on `host` may show the tutorial now.
  static func mayShow(onHost host: String) -> Bool {
    host == "m.youtube.com" && !isShown
  }

  static func markShown() {
    UserDefaults.standard.set(true, forKey: shownKey)
    Logger.module.info("Express YouTube home tutorial shown; it won't show again")
  }

  #if DEBUG
  private static var didApplyDebugReset = false
  #endif

  /// `-ExpressResetYouTubeHomeTutorial YES` (Debug): forgets the flag once per launch, so the
  /// next m.youtube.com home load shows the tutorial again.
  private static func applyDebugReset() {
    #if DEBUG
    guard !didApplyDebugReset else { return }
    didApplyDebugReset = true
    if UserDefaults.standard.bool(forKey: "ExpressResetYouTubeHomeTutorial") {
      UserDefaults.standard.removeObject(forKey: shownKey)
    }
    #endif
  }
}
