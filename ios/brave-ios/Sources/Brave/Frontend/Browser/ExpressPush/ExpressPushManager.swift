// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import Foundation
import UIKit
import UserNotifications
import os.log

/// Like and reply notifications on iPhone, after the Android app's
/// `BrowserExpressPushTokenManager`, `BrowserExpressPushReceiver` and
/// `BrowserExpressPushOpenHandler`.
///
/// - Off unless the server's `enablePushNotifications` switch is on (or a Debug build is
///   launched with `-ExpressPushForce YES`) and the reader's own switch is on (default on).
/// - Permission is asked once, right after the reader's first posted comment, when "someone
///   replied to you" makes sense; and, as on Android, once from the new tab page after 10
///   comment scrolls even while the server's switch is off.
/// - The token is sent when it, or the account, changes, and at least once a day; it's removed
///   from the account before signing out, and when the server's switch goes off.
/// - Tapping a notification opens the page (or the post) and its comments at that comment.
@MainActor
public final class ExpressPushManager: NSObject {
  public static let shared = ExpressPushManager()

  private let defaults = UserDefaults.standard
  private let userEnabledKey = "express.push.user_enabled"
  private let promptedKey = "express.push.permission_prompted"
  private let ntpPromptedKey = "express.push.ntp_permission_prompted"
  private let tokenKey = "express.push.token"
  private let syncedKey = "express.push.synced"  // "<token>|<userID>"
  private let syncedAtKey = "express.push.synced_at"

  /// Opens a notification's target; set by the browser.
  var openTarget: ((ExpressPushPayload) -> Void)?

  // MARK: Switches

  var isFeatureEnabled: Bool {
    if ExpressRemoteConfigStore.current.enablePushNotifications { return true }
    #if DEBUG
    return defaults.bool(forKey: "ExpressPushForce")
    #else
    return false
    #endif
  }

  /// "Notify me about likes and replies".
  var isUserEnabled: Bool {
    get { defaults.object(forKey: userEnabledKey) as? Bool ?? true }
    set {
      defaults.set(newValue, forKey: userEnabledKey)
      if newValue {
        syncIfNeeded(reason: "user_enabled")
      } else {
        Task { await unregister() }
      }
    }
  }

  private var isActive: Bool { isFeatureEnabled && isUserEnabled }

  // MARK: Registration

  /// Cheap to call often: registers with Apple when allowed; the token goes to the server only
  /// when something changed or a day has passed.
  func syncIfNeeded(reason: String) {
    observeServerSwitch()
    // A device the server switched off but that couldn't be removed then (signed out, offline).
    unregisterIfSwitchedOff()
    guard isActive, ExpressAccount.shared.validAccessToken != nil else { return }
    lastReason = reason
    Task {
      let settings = await UNUserNotificationCenter.current().notificationSettings()
      guard settings.authorizationStatus == .authorized
        || settings.authorizationStatus == .provisional
        || settings.authorizationStatus == .ephemeral
      else { return }
      UIApplication.shared.registerForRemoteNotifications()
    }
  }

  /// Apple gave the app its token.
  public func didRegister(deviceToken: Data) {
    let token = deviceToken.map { String(format: "%02x", $0) }.joined()
    defaults.set(token, forKey: tokenKey)
    Task { await sendToken(token) }
  }

  public func didFailToRegister(_ error: Error) {
    Logger.module.error("Express push registration failed: \(error.localizedDescription, privacy: .public)")
    ExpressAnalytics.shared.capture(
      "Push Token Sync Failed",
      ["reason": lastReason, "error": String(describing: type(of: error))]
    )
  }

  /// Why the last sync was asked for (Android's `reason`).
  private var lastReason = "app_start"

  // MARK: Server switch

  private var configObserver: NSObjectProtocol?

  /// From the first sync on (the browser asks for one at launch), config refreshes are watched.
  private func observeServerSwitch() {
    guard configObserver == nil else { return }
    configObserver = NotificationCenter.default.addObserver(
      forName: .expressRemoteConfigDidChange,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      MainActor.assumeIsolated { self?.unregisterIfSwitchedOff() }
    }
  }

  /// Android's `BrowserExpressPushReceiver` drops every push while the server's
  /// `enablePushNotifications` is off. iOS shows alert pushes itself, so once the switch goes off,
  /// a device whose token was sent comes off the account instead.
  private func unregisterIfSwitchedOff() {
    guard !isFeatureEnabled, defaults.string(forKey: syncedKey) != nil,
      ExpressAccount.shared.validAccessToken != nil
    else { return }
    Task {
      // Asked twice before the first ran: `unregister()` clears the marker before it sends.
      guard defaults.string(forKey: syncedKey) != nil else { return }
      Logger.module.info("Express push: switched off by the server, removing this device's token")
      await unregister()
    }
  }

  /// A notification of ours arrived while the app was open (Android's `Push Received`).
  func noteReceived(_ notification: UNNotification) {
    guard let payload = ExpressPushPayload(userInfo: notification.request.content.userInfo) else { return }
    ExpressAnalytics.shared.capture("Push Received", ["type": payload.type])
  }

  private func sendToken(_ token: String) async {
    guard isActive, let access = ExpressAccount.shared.validAccessToken,
      let userID = ExpressAccount.shared.claims?.userID
    else { return }
    let marker = "\(token)|\(userID)"
    let lastSync = defaults.double(forKey: syncedAtKey)
    if defaults.string(forKey: syncedKey) == marker,
      Date().timeIntervalSince1970 - lastSync < 24 * 60 * 60
    {
      return
    }
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    let ok = await ExpressAPIClient.shared.registerPushToken(
      token,
      accessToken: access,
      refreshToken: ExpressAccount.shared.refreshToken,
      appVersion: version,
      language: Locale.current.language.languageCode?.identifier ?? "en"
    )
    let changed = defaults.string(forKey: syncedKey) != marker
    if ok {
      defaults.set(marker, forKey: syncedKey)
      defaults.set(Date().timeIntervalSince1970, forKey: syncedAtKey)
      ExpressAnalytics.shared.capture(
        "Push Token Registered",
        ["reason": lastReason, "changed": changed ? "true" : "false"]
      )
    } else {
      ExpressAnalytics.shared.capture("Push Token Sync Failed", ["reason": lastReason, "error": "http"])
    }
  }

  /// Takes this device off the account (before signing out, or when switched off).
  func unregister() async {
    guard let token = defaults.string(forKey: tokenKey),
      let access = ExpressAccount.shared.validAccessToken
    else { return }
    defaults.removeObject(forKey: syncedKey)
    defaults.removeObject(forKey: syncedAtKey)
    await ExpressAPIClient.shared.deletePushToken(
      token,
      accessToken: access,
      refreshToken: ExpressAccount.shared.refreshToken
    )
  }

  /// Once, after the reader's first posted comment (or 10 comment scrolls).
  func promptAfterFirstComment(reason: String = "first_comment") {
    guard isActive, !defaults.bool(forKey: promptedKey) else { return }
    askForPermission(reason: reason, markingAsked: promptedKey)
  }

  /// Android's new tab page ask (`CommentScrollPrompts.maybeShowOnNtp`): once, after enough
  /// comment scrolls, whatever the server's push switch says. It has its own "asked" flag, as
  /// on Android. The token still only goes up while push is switched on (`syncIfNeeded`).
  func promptFromNewTabPage() {
    guard !defaults.bool(forKey: ntpPromptedKey) else { return }
    askForPermission(reason: "ntp_comment_scrolls", markingAsked: ntpPromptedKey)
  }

  private func askForPermission(reason: String, markingAsked key: String) {
    Task {
      let center = UNUserNotificationCenter.current()
      // Express no longer asks at launch, but earlier builds asked for Brave's quiet
      // (provisional) notifications, which land silently in Notification Center, so a reply
      // would go unnoticed. Ask those installs for the real thing too.
      let status = await center.notificationSettings().authorizationStatus
      guard status == .notDetermined || status == .provisional else { return }
      defaults.set(true, forKey: key)
      ExpressAnalytics.shared.capture("Push Permission Prompted", ["reason": reason])
      let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
      ExpressAnalytics.shared.capture(granted ? "Notifications Permission Granted" : "Notifications Permission Denied")
      if granted { syncIfNeeded(reason: "permission_granted") }
    }
  }

  // MARK: Notifications

  /// Ours, shown while the app is open, appear as banners like elsewhere.
  func isExpressNotification(_ notification: UNNotification) -> Bool {
    ExpressPushPayload(userInfo: notification.request.content.userInfo) != nil
  }

  /// The reader tapped one of ours.
  func handleTap(_ response: UNNotificationResponse) -> Bool {
    guard let payload = ExpressPushPayload(userInfo: response.notification.request.content.userInfo)
    else { return false }
    ExpressAnalytics.shared.capture("Push Opened", ["type": payload.type])
    if let id = payload.notificationID, let access = ExpressAccount.shared.validAccessToken {
      Task {
        await ExpressAPIClient.shared.markNotificationRead(
          id,
          accessToken: access,
          refreshToken: ExpressAccount.shared.refreshToken
        )
      }
    }
    openTarget?(payload)
    return true
  }
}
