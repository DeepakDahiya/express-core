// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import UIKit
import os.log

// YouTube access and referrals, after the Android app's `YouTubePremiumAccessUtil`,
// `ReferralCodeUtil`, `ReferralHelper` and `ReferralUsageTracker`.
//
// Free YouTube access runs out over time; each friend who installs through the reader's link
// and then uses picture in picture or background play adds days. The server decides all of it:
// the app shows the state, shares the link, attributes installs and reports usage.

/// `GET /v1/youtube/access`.
public struct ExpressYouTubeAccess: Equatable, Sendable {
  public let referralCount: Int
  /// Seconds of free access left when this was fetched.
  public let secondsRemaining: Int
  public let isBlocked: Bool
  public let referralCode: String?
  /// Friends joined since the last look: the sheet celebrates.
  public let newReferrals: Bool
  public let rewardDaysPerReferral: Int
  public let daysEarned: Int

  public init(
    referralCount: Int,
    secondsRemaining: Int,
    isBlocked: Bool,
    referralCode: String?,
    newReferrals: Bool,
    rewardDaysPerReferral: Int,
    daysEarned: Int
  ) {
    self.referralCount = referralCount
    self.secondsRemaining = secondsRemaining
    self.isBlocked = isBlocked
    self.referralCode = referralCode
    self.newReferrals = newReferrals
    self.rewardDaysPerReferral = rewardDaysPerReferral
    self.daysEarned = daysEarned
  }
}

extension ExpressAPIClient {
  public func fetchYouTubeAccess(accessToken: String) async throws -> ExpressYouTubeAccess {
    let json = try await sendForJSON(
      makeRequest(path: "youtube/access", authorization: .bearer(accessToken), timeout: 10)
    )
    func int(_ key: String) -> Int? { (json[key] as? NSNumber)?.intValue }
    var seconds = int("accessRemainingInSeconds") ?? 0
    if seconds <= 0, let days = int("accessDaysRemaining"), days > 0 {
      seconds = days * 86_400
    }
    let referrals = max(0, int("referralCount") ?? 0)
    let reward = int("rewardDaysPerReferral") ?? 30
    return ExpressYouTubeAccess(
      referralCount: referrals,
      secondsRemaining: max(0, seconds),
      isBlocked: json["isBlocked"] as? Bool ?? false,
      referralCode: (json["referralCode"] as? String).nonEmptyValue,
      newReferrals: json["newReferrals"] as? Bool ?? false,
      rewardDaysPerReferral: reward,
      daysEarned: int("daysEarned") ?? referrals * reward
    )
  }

  /// `GET /v1/referral/code`.
  public func fetchReferralCode(accessToken: String) async -> String? {
    guard
      let json = try? await sendForJSON(
        makeRequest(path: "referral/code", authorization: .bearer(accessToken), timeout: 10)
      ),
      json["success"] as? Bool == true
    else { return nil }
    return (json["referralCode"] as? String).nonEmptyValue
  }

  /// Posts JSON for the referral endpoints; returns the HTTP status (0 when it never arrived).
  fileprivate func referralPost(
    _ path: String,
    body: [String: Any],
    accessToken: String?
  ) async -> (status: Int, json: [String: Any]) {
    let request = makeRequest(
      path: path,
      method: "POST",
      authorization: accessToken.map { .bearer($0) } ?? .none,
      body: try? JSONSerialization.data(withJSONObject: body),
      timeout: 15
    )
    guard let (data, response) = try? await session.data(for: request) else { return (0, [:]) }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    return ((response as? HTTPURLResponse)?.statusCode ?? 0, json)
  }
}

/// The reader's share link, and the text Android shares with it.
public enum ExpressReferralLink {
  /// `www` on purpose: the bare domain redirects, which breaks app-link checks.
  public static func url(code: String) -> URL? {
    var components = URLComponents(string: "https://www.browser.express/refer")
    components?.queryItems = [URLQueryItem(name: "code", value: code)]
    return components?.url
  }

  public static func shareText(code: String) -> String? {
    url(code: code).map {
      "Check out this browser with premium YouTube features! Download here: \($0.absoluteString)"
    }
  }

  /// The code in an incoming `browser.express/refer?code=…` link.
  public static func code(in url: URL) -> String? {
    guard let host = url.host?.lowercased(), host == "browser.express" || host == "www.browser.express",
      url.path.lowercased().hasPrefix("/refer")
    else { return nil }
    return URLComponents(url: url, resolvingAgainstBaseURL: false)?
      .queryItems?.first { $0.name == "code" }?.value.nonEmptyValue
  }
}

// MARK: - Install attribution

/// Who referred this install (Android's `ReferralHelper`, from a link only: iOS has no install
/// referrer, and Apple's rules forbid fingerprint matching).
@MainActor
public final class ExpressReferralTracker {
  public static let shared = ExpressReferralTracker()

  private let defaults = UserDefaults.standard
  private let pendingCodeKey = "express.referral.pending_code"
  private let pendingSourceKey = "express.referral.pending_source"
  private let clipboardTriedKey = "express.referral.clipboard_tried"
  private let clipboardAttemptsKey = "express.referral.clipboard_attempts"
  private let firstSeenKey = "express.referral.first_seen"
  private let attributedKey = "express.referral.attributed"
  private let linkedKey = "express.referral.linked_attempts"
  private var isSending = false

  /// A `browser.express/refer?code=…` link opened the app.
  public func handleIncoming(_ url: URL) {
    guard let code = ExpressReferralLink.code(in: url) else { return }
    guard !defaults.bool(forKey: attributedKey), defaults.string(forKey: pendingCodeKey) == nil
    else { return }
    // The reader's own link doesn't count.
    if code.caseInsensitiveCompare(ExpressAccount.shared.claims?.referralCode ?? "") == .orderedSame {
      return
    }
    defaults.set(code, forKey: pendingCodeKey)
    defaults.set("deep_link", forKey: pendingSourceKey)
    Task { await sendPending() }
  }

  /// Android's clipboard fallback: in the first 7 days, a referral page may have copied
  /// `express-ref:CODE`. Reading the clipboard shows iOS's paste prompt, so it's read at most
  /// once per install, and only when it holds text (checking that doesn't prompt).
  public func checkClipboard() {
    let now = Date().timeIntervalSince1970
    if defaults.double(forKey: firstSeenKey) == 0 { defaults.set(now, forKey: firstSeenKey) }
    guard !defaults.bool(forKey: clipboardTriedKey), !defaults.bool(forKey: attributedKey),
      defaults.string(forKey: pendingCodeKey) == nil
    else { return }
    guard now - defaults.double(forKey: firstSeenKey) < 7 * 24 * 60 * 60 else {
      defaults.set(true, forKey: clipboardTriedKey)
      return
    }
    let pasteboard = UIPasteboard.general
    guard pasteboard.hasStrings else {
      // Empty for now: look again on the next few returns (Android allows 3 tries).
      let attempts = defaults.integer(forKey: clipboardAttemptsKey) + 1
      defaults.set(attempts, forKey: clipboardAttemptsKey)
      if attempts >= 3 { defaults.set(true, forKey: clipboardTriedKey) }
      ExpressAnalytics.shared.capture("referral_app_clipboard_result", ["result": "empty"])
      return
    }
    defaults.set(true, forKey: clipboardTriedKey)
    let text = pasteboard.string ?? ""
    guard let match = text.range(of: #"express-ref:[A-Za-z0-9_-]{4,64}"#, options: .regularExpression)
    else {
      ExpressAnalytics.shared.capture("referral_app_clipboard_result", ["result": text.isEmpty ? "no_text" : "no_match"])
      return
    }
    let code = String(text[match].dropFirst("express-ref:".count))
    ExpressAnalytics.shared.capture("referral_app_clipboard_result", ["result": "matched"])
    if code.caseInsensitiveCompare(ExpressAccount.shared.claims?.referralCode ?? "") == .orderedSame {
      ExpressAnalytics.shared.capture("referral_app_self_referral_skipped", ["code": code, "source": "clipboard"])
      return
    }
    defaults.set(code, forKey: pendingCodeKey)
    defaults.set("clipboard", forKey: pendingSourceKey)
    Task { await sendPending() }
  }

  /// `POST /v1/referral/track {referralCode, deviceId, source}`: 200/409 done, 422 self-referral,
  /// 5xx kept for later, anything else dropped.
  public func sendPending() async {
    guard !isSending, let code = defaults.string(forKey: pendingCodeKey) else { return }
    let source = defaults.string(forKey: pendingSourceKey) ?? "deep_link"
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST referral/track")
      return
    }
    isSending = true
    defer { isSending = false }
    let (status, _) = await ExpressAPIClient.shared.referralPost(
      "referral/track",
      body: ["referralCode": code, "deviceId": ExpressAccount.deviceID, "source": source],
      accessToken: ExpressAccount.shared.validAccessToken
    )
    switch status {
    case 200, 409:
      defaults.set(true, forKey: attributedKey)
      defaults.removeObject(forKey: pendingCodeKey)
      ExpressAnalytics.shared.capture(
        "referral_referred_device_tracked",
        ["code": code, "source": source, "device_id": ExpressAccount.deviceID]
      )
    case 422:
      defaults.removeObject(forKey: pendingCodeKey)
      ExpressAnalytics.shared.capture(
        "referral_app_self_referral_skipped",
        ["code": code, "source": source, "rejected_by": "backend"]
      )
    case 500...599, 0:
      break  // Try again on the next launch or sign-in.
    default:
      defaults.removeObject(forKey: pendingCodeKey)
    }
  }

  /// `POST /v1/referral/link {deviceId}` after each sign-in, until the server says done (at most
  /// 25 tries, as on Android), so a referral made before signing in counts for the account.
  public func linkDeviceIfNeeded() async {
    guard let token = ExpressAccount.shared.validAccessToken else { return }
    let attempts = defaults.integer(forKey: linkedKey)
    guard attempts >= 0, attempts < 25 else { return }
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST referral/link")
      return
    }
    let (status, json) = await ExpressAPIClient.shared.referralPost(
      "referral/link",
      body: ["deviceId": ExpressAccount.deviceID],
      accessToken: token
    )
    if (200..<300).contains(status), json["done"] as? Bool == true {
      defaults.set(-1, forKey: linkedKey)
    } else {
      defaults.set(attempts + 1, forKey: linkedKey)
    }
  }
}

// MARK: - Usage

/// What a referred install has done on YouTube (Android's `ReferralUsageTracker`). The server
/// activates the referral, and rewards the friend who shared it, on the first picture-in-picture
/// or background-play use.
@MainActor
public final class ExpressYouTubeUsage {
  public static let shared = ExpressYouTubeUsage()

  public enum Counter: String, CaseIterable {
    case youtubeVisits, youtubePageViews, pipUses, bgPlayUses
  }

  private struct Tally: Codable {
    var count = 0
    var firstAt: Double = 0
    var lastAt: Double = 0
  }

  private let defaults = UserDefaults.standard
  private let pendingKey = "express.youtube_usage.pending"
  private let totalVisitsKey = "express.youtube_usage.total_visits"
  private let lastActivityKey = "express.youtube_usage.last_activity"
  private var lastFlush = Date.distantPast
  private var isFlushing = false
  /// Asked to send while a send was in flight.
  private var flushAgain = false

  /// All YouTube visits on this device, for the access sheet's countdown card.
  public var totalVisits: Int { defaults.integer(forKey: totalVisitsKey) }

  /// A YouTube page was shown. A new visit starts after 30 minutes away.
  public func notePageView() {
    let now = Date().timeIntervalSince1970
    let last = defaults.double(forKey: lastActivityKey)
    if now - last > 30 * 60 {
      record(.youtubeVisits, flushNow: false)
      defaults.set(totalVisits + 1, forKey: totalVisitsKey)
    }
    defaults.set(now, forKey: lastActivityKey)
    record(.youtubePageViews, flushNow: false)
    // Visits and views go every 15 minutes; PiP and background play go at once.
    if Date().timeIntervalSince(lastFlush) > 15 * 60 { Task { await flush() } }
  }

  public func record(_ counter: Counter, flushNow: Bool = true) {
    var pending = loadPending()
    let now = Date().timeIntervalSince1970 * 1000
    var tally = pending[counter.rawValue] ?? Tally()
    if tally.count == 0 { tally.firstAt = now }
    tally.count += 1
    tally.lastAt = now
    pending[counter.rawValue] = tally
    savePending(pending)
    if flushNow { Task { await flush() } }
  }

  /// `POST /v1/referral/usage`; what was sent is cleared once the server has it. Sent when the
  /// app launches and goes to the background, at sign-in, on picture in picture and background
  /// play, and every 15 minutes of YouTube page views (Android's `ReferralUsageTracker`).
  public func flush() async {
    // Android's flush also sends what was recorded during its send (a second batch): background
    // play is noticed just after the app's own send on going to the background.
    if isFlushing {
      flushAgain = true
      return
    }
    guard let token = ExpressAccount.shared.validAccessToken else { return }
    let pending = loadPending()
    guard !pending.isEmpty else { return }
    lastFlush = Date()
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST referral/usage")
      return
    }
    isFlushing = true
    var usage: [String: Any] = [:]
    for (key, tally) in pending {
      usage[key] = ["count": tally.count, "firstAt": tally.firstAt, "lastAt": tally.lastAt]
    }
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    let (status, _) = await ExpressAPIClient.shared.referralPost(
      "referral/usage",
      body: [
        "reportId": UUID().uuidString.lowercased(),
        "deviceId": ExpressAccount.deviceID,
        "appVersion": version,
        "usage": usage,
      ],
      accessToken: token
    )
    isFlushing = false
    let delivered = (200..<300).contains(status)
    if delivered {
      // Keep anything recorded while this was in flight.
      var latest = loadPending()
      for (key, sent) in pending {
        guard var current = latest[key] else { continue }
        current.count -= sent.count
        if current.count <= 0 { latest[key] = nil } else { latest[key] = current }
      }
      savePending(latest)
    }
    if flushAgain {
      flushAgain = false
      // After a failure, what's left waits for the next send, as before.
      if delivered { await flush() }
    }
  }

  private func loadPending() -> [String: Tally] {
    guard let data = defaults.data(forKey: pendingKey) else { return [:] }
    return (try? JSONDecoder().decode([String: Tally].self, from: data)) ?? [:]
  }

  private func savePending(_ pending: [String: Tally]) {
    defaults.set(try? JSONEncoder().encode(pending), forKey: pendingKey)
  }
}
