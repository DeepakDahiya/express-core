// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Server switches from `GET /v1/public/config`. Missing fields keep Android's defaults.
public struct ExpressRemoteConfig: Codable, Equatable {
  public var enableDefaultBrowser = true
  public var enableYouTubePremium = true
  /// After this long in the background, resuming opens the new tab page.
  public var ntpLaunchDelayMs = 30_000
  public var youtubePremiumCooldownMs = 30_000
  public var enablePushNotifications = false

  enum CodingKeys: String, CodingKey {
    case enableDefaultBrowser, enableYouTubePremium, enablePushNotifications
    case ntpLaunchDelayMs = "ntpLaunchDelay"
    case youtubePremiumCooldownMs = "youtubePremiumCooldown"
  }

  public init() {}

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    let defaults = ExpressRemoteConfig()
    enableDefaultBrowser = c.lenientBool(.enableDefaultBrowser) ?? defaults.enableDefaultBrowser
    enableYouTubePremium = c.lenientBool(.enableYouTubePremium) ?? defaults.enableYouTubePremium
    ntpLaunchDelayMs = c.lenientInt(.ntpLaunchDelayMs) ?? defaults.ntpLaunchDelayMs
    youtubePremiumCooldownMs =
      c.lenientInt(.youtubePremiumCooldownMs) ?? defaults.youtubePremiumCooldownMs
    enablePushNotifications =
      c.lenientBool(.enablePushNotifications) ?? defaults.enablePushNotifications
  }
}

/// Caches the remote config and refreshes it at most every 6 hours, like Android.
@MainActor
public enum ExpressRemoteConfigStore {
  private static let configKey = "express.remote-config"
  private static let lastCheckKey = "express.remote-config.last-check"
  private static let refreshInterval: TimeInterval = 6 * 60 * 60

  /// The last config fetched, or the defaults.
  public static var current: ExpressRemoteConfig {
    guard let data = UserDefaults.standard.data(forKey: configKey),
      let config = try? JSONDecoder().decode(ExpressRemoteConfig.self, from: data)
    else { return ExpressRemoteConfig() }
    return config
  }

  /// Fetches the config when the cached copy is older than 6 hours. The timestamp is only
  /// written after a successful fetch, so failures retry on the next launch. Posts
  /// `expressRemoteConfigDidChange` when the switches differ from the cached ones.
  public static func refreshIfNeeded(client: ExpressAPIClient = .shared) async {
    let lastCheck = UserDefaults.standard.double(forKey: lastCheckKey)
    if lastCheck > 0, Date().timeIntervalSince1970 - lastCheck < refreshInterval { return }
    do {
      let config = try await client.fetchRemoteConfig()
      let previous = current
      if let data = try? JSONEncoder().encode(config) {
        UserDefaults.standard.set(data, forKey: configKey)
        if config != previous {
          NotificationCenter.default.post(name: .expressRemoteConfigDidChange, object: nil)
        }
      }
      UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: lastCheckKey)
    } catch {
      Logger.module.error("Express config fetch failed: \(error.localizedDescription, privacy: .public)")
    }
  }
}

extension Notification.Name {
  /// A refresh brought server switches that differ from the cached ones (posted on the main
  /// thread); `ExpressRemoteConfigStore.current` already holds the new ones.
  public static let expressRemoteConfigDidChange = Notification.Name("ExpressRemoteConfigDidChange")
}

/// `GET /v1/public/tutorial-video`. The card shows only when enabled with a video or image.
public struct ExpressTutorialVideo: Decodable, Equatable {
  public let enabled: Bool
  public let videoURL: URL?
  public let imageURL: URL?

  enum CodingKeys: String, CodingKey { case enabled, videoUrl, imageUrl }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    enabled = c.lenientBool(.enabled) ?? false
    videoURL = c.lenientURL(.videoUrl)
    imageURL = c.lenientURL(.imageUrl)
  }

  public var isShown: Bool { enabled && (videoURL != nil || imageURL != nil) }
}
