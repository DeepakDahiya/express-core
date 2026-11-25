// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import UIKit
import os.log

/// Product analytics through the same PostHog proxy and event names as the Android app
/// (`PostHogUtil`): one event per request, a 50-event disk queue sent in order, a session id
/// that rolls over after 30 minutes idle, and `$identify` once the install has an account.
///
/// Debug builds only log events, unless launched with `-ExpressSendAnalytics YES` (and never in a
/// dry run), so testing doesn't land in the real dashboards.
@MainActor
public final class ExpressAnalytics {
  public static let shared = ExpressAnalytics()

  private static let endpoint = URL(string: "https://posthog.dd-fb2.workers.dev/i/v0/e/")!
  /// The same public project key the Android app ships (`PostHogUtil.POST_HOG_API_KEY`).
  private static let apiKey = "phc_Nn8HVrhAeFqo35ymuvUKOKe59RhXnShdushDuyDn3vL"
  private static let sessionIdle: TimeInterval = 30 * 60
  private static let maxQueued = 50

  private enum Keys {
    static let queue = "express.analytics.queue"
    static let identified = "express.analytics.identified"
    static let installed = "express.analytics.installed"
    static let firstVersion = "express.analytics.first_version"
    static let firstLaunch = "express.analytics.first_launch_at"
  }

  private struct Queued: Codable {
    let id: String
    let event: String
    let distinctID: String
    let properties: Data
  }

  private var sessionID = ExpressAnalytics.uuidV7()
  private var lastEventAt = Date()
  private var isFlushing = false
  private var backgroundedAt: Date?
  private var foregroundedAt = Date()
  private var observers: [NSObjectProtocol] = []
  private let defaults = UserDefaults.standard

  private var sends: Bool {
    #if DEBUG
    return !ExpressDebug.isDryRun && defaults.bool(forKey: "ExpressSendAnalytics")
    #else
    return true
    #endif
  }

  // MARK: Identity

  public static var anonymousID: String { "anon-\(ExpressAccount.deviceID)" }

  private var distinctID: String {
    ExpressAccount.shared.claims?.userID ?? Self.anonymousID
  }

  // MARK: Capture

  /// Sends an event with the common properties; `nil` values go as JSON null, as on Android.
  public func capture(_ event: String, _ properties: [String: Any?] = [:]) {
    let now = Date()
    if now.timeIntervalSince(lastEventAt) > Self.sessionIdle { sessionID = Self.uuidV7() }
    lastEventAt = now
    var props: [String: Any] = [:]
    for (key, value) in properties { props[key] = value ?? NSNull() }
    if props["app_version"] == nil { props["app_version"] = Self.appVersion }
    props["$os"] = "iOS"
    props["$os_version"] = UIDevice.current.systemVersion
    props["$device_manufacturer"] = "Apple"
    props["$device_model"] = Self.deviceModel
    props["$lib"] = "browser-express-ios"
    props["$session_id"] = sessionID
    props["signed_in"] = ExpressAccount.shared.validAccessToken != nil
    props["locale"] = Locale.current.identifier(.bcp47)
    enqueue(event, distinctID: distinctID, properties: props)
    identifyIfNeeded()
  }

  /// `$identify` once per install, the first time there's an account: joins the anonymous
  /// history to the account.
  public func identifyIfNeeded() {
    guard !defaults.bool(forKey: Keys.identified), let userID = ExpressAccount.shared.claims?.userID
    else { return }
    defaults.set(true, forKey: Keys.identified)
    enqueue("$identify", distinctID: userID, properties: ["$anon_distinct_id": Self.anonymousID])
  }

  // MARK: App lifecycle

  /// `App Launched` (with person properties) on each start, `App Installed` the first time, and
  /// foreground/background with how long the app was away or used.
  public func start(personProperties: [String: Any]) {
    guard observers.isEmpty else { return }
    if defaults.string(forKey: Keys.firstVersion) == nil {
      defaults.set(Self.appVersion, forKey: Keys.firstVersion)
      defaults.set(Date().timeIntervalSince1970 * 1000, forKey: Keys.firstLaunch)
    }
    var set = personProperties
    set["app_version"] = Self.appVersion
    capture(
      "App Launched",
      [
        "$set": set,
        "$set_once": [
          "first_app_version": defaults.string(forKey: Keys.firstVersion) ?? Self.appVersion,
          "first_launch_at": Int64(defaults.double(forKey: Keys.firstLaunch)),
        ],
      ]
    )
    if !defaults.bool(forKey: Keys.installed) {
      defaults.set(true, forKey: Keys.installed)
      capture("App Installed")
    }
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) {
        _ in
        MainActor.assumeIsolated {
          let analytics = ExpressAnalytics.shared
          analytics.backgroundedAt = Date()
          analytics.capture("App Backgrounded", ["foreground_ms": Self.ms(since: analytics.foregroundedAt)])
        }
      }
    )
    observers.append(
      center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) {
        _ in
        MainActor.assumeIsolated {
          let analytics = ExpressAnalytics.shared
          analytics.foregroundedAt = Date()
          if let away = analytics.backgroundedAt {
            analytics.capture("App Foregrounded", ["background_ms": Self.ms(since: away)])
          }
        }
      }
    )
    flush()
  }

  // MARK: Page properties

  /// Android's `pageProperties`: domain, site, YouTube page kind, and search terms. Never the
  /// full URL.
  public static func pageProperties(_ url: URL, searchQuery: String? = nil) -> [String: Any?] {
    guard let host = url.host?.lowercased() else { return [:] }
    var props: [String: Any?] = [:]
    let domain = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    props["domain"] = domain
    let labels = host.split(separator: ".")
    let site = labels.count >= 2 ? labels.suffix(2).joined(separator: ".") : nil
    if let site { props["site"] = site }
    let isYouTube = site == "youtube.com" || site == "youtu.be"
    props["is_youtube"] = isYouTube
    var query = searchQuery
    if isYouTube {
      let path = url.path
      let page: String
      if path.isEmpty || path == "/" {
        page = "home"
      } else if path.hasPrefix("/watch") {
        page = "watch"
      } else if path.hasPrefix("/shorts") {
        page = "shorts"
      } else if path.hasPrefix("/results") {
        page = "search"
        query = URLComponents(url: url, resolvingAgainstBaseURL: false)?
          .queryItems?.first(where: { $0.name == "search_query" })?.value
      } else if path.hasPrefix("/@") || path.hasPrefix("/channel") || path.hasPrefix("/c/") {
        page = "channel"
      } else {
        page = "other"
      }
      props["youtube_page"] = page
    }
    let trimmed = query?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    props["is_search"] = !trimmed.isEmpty
    if !trimmed.isEmpty {
      props["search_engine"] = domain
      props["search_query"] = String(trimmed.prefix(200))
    }
    return props
  }

  // MARK: Queue

  private func enqueue(_ event: String, distinctID: String, properties: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: properties) else { return }
    guard sends else {
      Logger.module.info("Express analytics (not sent): \(event, privacy: .public)")
      return
    }
    var queue = loadQueue()
    queue.append(Queued(id: UUID().uuidString, event: event, distinctID: distinctID, properties: data))
    if queue.count > Self.maxQueued { queue.removeFirst(queue.count - Self.maxQueued) }
    saveQueue(queue)
    flush()
  }

  /// Sends what's queued, in order, one at a time; stops at the first failure to retry later.
  /// 2xx and 4xx are done (4xx won't get better); 5xx and network errors stay.
  public func flush() {
    guard sends, !isFlushing else { return }
    isFlushing = true
    Task { @MainActor in
      defer { isFlushing = false }
      while let next = loadQueue().first {
        guard let properties = try? JSONSerialization.jsonObject(with: next.properties) else {
          drop(next.id)
          continue
        }
        let body: [String: Any] = [
          "api_key": Self.apiKey, "event": next.event, "distinct_id": next.distinctID,
          "properties": properties,
        ]
        var request = URLRequest(url: Self.endpoint, timeoutInterval: 8)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(Self.apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<500).contains(status) else { return }
        drop(next.id)
      }
    }
  }

  private func loadQueue() -> [Queued] {
    guard let data = defaults.data(forKey: Keys.queue),
      let queue = try? JSONDecoder().decode([Queued].self, from: data)
    else { return [] }
    return queue
  }

  private func saveQueue(_ queue: [Queued]) {
    if let data = try? JSONEncoder().encode(queue) { defaults.set(data, forKey: Keys.queue) }
  }

  private func drop(_ id: String) {
    saveQueue(loadQueue().filter { $0.id != id })
  }

  // MARK: Helpers

  static var appVersion: String {
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
  }

  static var deviceModel: String {
    var info = utsname()
    uname(&info)
    let machine = withUnsafeBytes(of: &info.machine) { raw in
      String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
    }
    return ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
  }

  static func ms(since date: Date) -> Int64 {
    Int64(max(0, Date().timeIntervalSince(date)) * 1000)
  }

  /// UUIDv7: milliseconds in the top 48 bits, version 7, the rest random.
  static func uuidV7() -> String {
    var bytes = [UInt8](repeating: 0, count: 16)
    _ = SecRandomCopyBytes(kSecRandomDefault, 16, &bytes)
    let ms = UInt64(Date().timeIntervalSince1970 * 1000)
    for i in 0..<6 { bytes[i] = UInt8((ms >> (8 * (5 - i))) & 0xFF) }
    bytes[6] = (bytes[6] & 0x0F) | 0x70
    bytes[8] = (bytes[8] & 0x3F) | 0x80
    let hex = bytes.map { String(format: "%02x", $0) }.joined()
    let parts = [hex.prefix(8), hex.dropFirst(8).prefix(4), hex.dropFirst(12).prefix(4), hex.dropFirst(16).prefix(4), hex.dropFirst(20)]
    return parts.map(String.init).joined(separator: "-")
  }
}
