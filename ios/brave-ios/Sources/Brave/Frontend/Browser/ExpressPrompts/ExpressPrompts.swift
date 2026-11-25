// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import Foundation
import SwiftUI
import UIKit
import os.log

// MARK: - Comment scrolls

/// The new tab page's default-browser and notification prompts wait until the reader has
/// scrolled through comments enough times in total, across launches (Android's
/// `CommentScrollPrompts`): one scroll is one drag the reader starts on a comment list or reel.
enum ExpressCommentScrolls {
  static let defaultBrowserPromptScrolls = 5
  static let notificationPromptScrolls = 10
  private static let key = "express.comments.scroll_count"

  static var count: Int { UserDefaults.standard.integer(forKey: key) }

  static func record() {
    UserDefaults.standard.set(count + 1, forKey: key)
  }
}

/// Counts each drag the reader starts on a comments scroll view (iOS 18 and later; earlier
/// systems don't report scroll phases, so the prompts simply wait longer there).
struct ExpressCountsCommentScrolls: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 18.0, *) {
      content.onScrollPhaseChange { oldPhase, newPhase in
        if newPhase == .interacting, oldPhase != .interacting {
          ExpressCommentScrolls.record()
        }
      }
    } else {
      content
    }
  }
}

extension View {
  func expressCountsCommentScrolls() -> some View {
    modifier(ExpressCountsCommentScrolls())
  }
}

// MARK: - Default browser

/// "Set Express Browser as your default browser" (Android's `SetDefaultBrowserBottomSheet`).
/// iPhone apps can't make themselves the default; "Next" opens the app's page in Settings,
/// where "Default Browser App" is.
enum ExpressDefaultBrowserPrompt {
  /// After this many "Next" taps it stops asking (Android's forced-prompt cap).
  static let maxNextTaps = 2
  private static let nextTapsKey = "express.default_browser.next_taps"
  /// At most once per launch.
  @MainActor static var shownThisLaunch = false
  /// The sheet is on screen (Android's `BraveSetDefaultBrowserUtils.isBottomSheetVisible`); the
  /// background-play rating waits while it is.
  @MainActor static var isShowing = false

  static var nextTaps: Int { UserDefaults.standard.integer(forKey: nextTapsKey) }

  static func noteNextTap() {
    UserDefaults.standard.set(nextTaps + 1, forKey: nextTapsKey)
  }

  /// Android's welcome flow opens with the system "set as default browser" dialog
  /// (`WelcomeOnboardingActivity.handleSetAsDefaultStep`), so new installs are asked once before
  /// any comment scrolls; iOS asks once per install, the first time the new tab page is free.
  private static let firstAskKey = "express.default_browser.first_asked"
  static var firstAskDone: Bool { UserDefaults.standard.bool(forKey: firstAskKey) }
  static func noteShown() { UserDefaults.standard.set(true, forKey: firstAskKey) }

  @MainActor static var isDue: Bool {
    #if DEBUG
    // `-ExpressForceDefaultBrowserPrompt YES`: show it on the new tab page, for testing.
    if UserDefaults.standard.bool(forKey: "ExpressForceDefaultBrowserPrompt"), !shownThisLaunch {
      return true
    }
    #endif
    // Android's new tab page path (`CommentScrollPrompts.maybeShowOnNtp`) doesn't look at the
    // server's `enableDefaultBrowser`; only its launch-count prompt does, which iOS doesn't have.
    return !shownThisLaunch
      && nextTaps < maxNextTaps
      && (!firstAskDone
        || ExpressCommentScrolls.count >= ExpressCommentScrolls.defaultBrowserPromptScrolls)
      && !DefaultBrowserHelper.isBraveLikelyDefaultBrowser()
  }
}

struct ExpressDefaultBrowserSheet: View {
  var onNext: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      Image(systemName: "safari")
        .font(.system(size: 44, weight: .regular))
        .foregroundColor(Color(expressARGB: 0xFF57_7DFF))
        .padding(.top, 28)
        .accessibilityHidden(true)
      Text("To improve your privacy, set Express Browser as your default browser")
        .font(.system(size: 20, weight: .semibold))
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, 16)
      Text("On the next screen, tap Default Browser App and choose Express Browser.")
        .font(.system(size: 15))
        .foregroundColor(.secondary)
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, 10)
      Button(action: onNext) {
        Text("Next")
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(.white)
          .frame(maxWidth: .infinity, minHeight: 50)
          .background(RoundedRectangle(cornerRadius: 25).fill(Color(expressARGB: 0xFF57_7DFF)))
      }
      .buttonStyle(.plain)
      .padding(.top, 24)
    }
    .padding(.horizontal, 24)
    .padding(.bottom, 20)
  }
}

// MARK: - Back to the new tab page

/// After the app has been away longer than the server's `ntpLaunchDelay`, coming back lands on
/// the new tab page (Android's dormant-cooldown redirect in `BraveActivity`). Opening the app
/// through a link or a notification, or returning from picture in picture, keeps the reader
/// where they were.
@MainActor
final class ExpressNewTabReturn {
  private static let backgroundedAtKey = "express.app.backgrounded_at"
  /// Links and notifications that arrive around the return win over it.
  private var suppressedUntil = Date.distantPast
  private var observers: [NSObjectProtocol] = []

  /// Called once the return is due and nothing has claimed the screen.
  var perform: (() -> Void)?

  func start() {
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(
        forName: UIApplication.didEnterBackgroundNotification,
        object: nil,
        queue: .main
      ) { _ in
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.backgroundedAtKey)
      }
    )
    observers.append(
      center.addObserver(
        forName: UIApplication.didBecomeActiveNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.becameActive() }
      }
    )
  }

  /// A link or notification brought the app forward: stay with it.
  func suppress() {
    suppressedUntil = Date().addingTimeInterval(3)
  }

  private func becameActive() {
    let defaults = UserDefaults.standard
    let backgroundedAt = defaults.double(forKey: Self.backgroundedAtKey)
    defaults.removeObject(forKey: Self.backgroundedAtKey)
    let awayMs = (Date().timeIntervalSince1970 - backgroundedAt) * 1000
    var limit = Double(ExpressRemoteConfigStore.current.ntpLaunchDelayMs)
    #if DEBUG
    // `-ExpressNTPLaunchDelayMs 5000`: a short wait, for testing.
    let override = defaults.double(forKey: "ExpressNTPLaunchDelayMs")
    if override > 0 { limit = override }
    #endif
    Logger.module.info(
      "Express NTP return: away \(Int(awayMs), privacy: .public) ms, limit \(Int(limit), privacy: .public) ms"
    )
    guard backgroundedAt > 0, awayMs > limit else { return }
    // Give link and notification handlers a moment to claim the screen first.
    Task { @MainActor [weak self] in
      try? await Task.sleep(for: .milliseconds(700))
      guard let self, Date() >= self.suppressedUntil else { return }
      self.perform?()
    }
  }
}

extension Notification.Name {
  /// The new tab page came on screen (its prompts may be due).
  static let expressNewTabPageDidAppear = Notification.Name("ExpressNewTabPageDidAppear")
}

// MARK: - App Store update

/// "A new version is available" (Android's update check, on the App Store's terms): at most
/// once a day, the App Store's listing for this app is compared with the running version.
/// Nothing is shown until the app is listed, or for a version the reader already passed over.
enum ExpressAppStoreUpdate {
  struct Release: Equatable {
    let version: String
    let storeURL: URL
  }

  private static let checkedAtKey = "express.update.checked_at"
  private static let skippedVersionKey = "express.update.skipped_version"

  static var currentVersion: String {
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
  }

  /// A newer release the reader hasn't passed over, or nil.
  static func checkIfDue() async -> Release? {
    let defaults = UserDefaults.standard
    #if DEBUG
    // `-ExpressFakeStoreVersion 99.0`: pretend the App Store has this version, to see the alert.
    if let fake = defaults.string(forKey: "ExpressFakeStoreVersion"), isNewer(fake, than: currentVersion) {
      return Release(version: fake, storeURL: URL(string: "https://apps.apple.com/app/id0")!)
    }
    #endif
    let last = defaults.double(forKey: checkedAtKey)
    guard Date().timeIntervalSince1970 - last > 24 * 60 * 60,
      let bundleID = Bundle.main.bundleIdentifier
    else { return nil }
    defaults.set(Date().timeIntervalSince1970, forKey: checkedAtKey)

    var components = URLComponents(string: "https://itunes.apple.com/lookup")!
    components.queryItems = [
      URLQueryItem(name: "bundleId", value: bundleID),
      URLQueryItem(name: "country", value: Locale.current.region?.identifier.lowercased() ?? "us"),
    ]
    guard let url = components.url,
      let (data, response) = try? await URLSession.shared.data(from: url),
      (response as? HTTPURLResponse)?.statusCode == 200,
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
      let first = (json["results"] as? [[String: Any]])?.first,
      let version = first["version"] as? String,
      let store = (first["trackViewUrl"] as? String).flatMap(URL.init(string:))
    else { return nil }
    guard isNewer(version, than: currentVersion),
      defaults.string(forKey: skippedVersionKey) != version
    else { return nil }
    return Release(version: version, storeURL: store)
  }

  static func skip(_ release: Release) {
    UserDefaults.standard.set(release.version, forKey: skippedVersionKey)
  }

  /// Dotted numeric comparison: "1.10" is newer than "1.9".
  static func isNewer(_ candidate: String, than current: String) -> Bool {
    let a = candidate.split(separator: ".").map { Int($0) ?? 0 }
    let b = current.split(separator: ".").map { Int($0) ?? 0 }
    for i in 0..<max(a.count, b.count) {
      let x = i < a.count ? a[i] : 0
      let y = i < b.count ? b[i] : 0
      if x != y { return x > y }
    }
    return false
  }
}
