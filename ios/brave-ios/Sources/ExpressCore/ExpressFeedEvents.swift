// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import UIKit
import os.log

/// New-tab-page feed events (Android's `ntp/FeedEvents`): impressions, video watching, opens,
/// caption expands, shares and hides, batched to `POST /v1/post/feed/events` with a Bearer token.
///
/// Waiting events with the same `type|postId|session|surface|target` merge (counts and times add
/// up). A batch keeps its `batchId` across retries so the server can drop repeats.
@MainActor
public final class ExpressFeedEvents {
  public static let shared = ExpressFeedEvents()

  public enum Kind: String, Codable, Sendable {
    case impression, video, open, expand, share, hide, unhide
  }

  public struct Event: Codable, Sendable {
    public var type: Kind
    public var postId: String
    public var session: String?
    public var at: Int64
    public var lastAt: Int64
    public var count: Int = 1
    public var position: Int?
    public var visibleMs: Int64?
    public var surface: String?
    public var watchMs: Int64?
    public var audibleMs: Int64?
    public var durationMs: Int64?
    public var target: String?

    var key: String {
      [type.rawValue, postId, session ?? "", surface ?? "", target ?? ""].joined(separator: "|")
    }

    mutating func merge(_ other: Event) {
      count += other.count
      visibleMs = Self.sum(visibleMs, other.visibleMs)
      watchMs = Self.sum(watchMs, other.watchMs)
      audibleMs = Self.sum(audibleMs, other.audibleMs)
      lastAt = max(lastAt, other.lastAt)
      if let other = other.durationMs { durationMs = max(durationMs ?? 0, other) }
    }

    private static func sum(_ a: Int64?, _ b: Int64?) -> Int64? {
      guard a != nil || b != nil else { return nil }
      return (a ?? 0) + (b ?? 0)
    }
  }

  private struct Batch: Codable {
    let batchId: String
    let events: [Event]
    var attempts: Int
  }

  private static let flushAtEvents = 50
  private static let flushInterval: TimeInterval = 5 * 60
  private static let maxPendingEvents = 300
  private static let maxBatchAttempts = 40
  private static let maxBackoff: TimeInterval = 6 * 60 * 60

  private enum Keys {
    static let pending = "express.feed_events.pending"
    static let inflight = "express.feed_events.inflight"
    static let failures = "express.feed_events.failures"
    static let nextRetry = "express.feed_events.next_retry"
  }

  private var pending: [Event] = []
  private var lastAttempt = Date.distantPast
  private var isSending = false
  private var observesBackground = false
  private let defaults = UserDefaults.standard

  private init() {
    if let data = defaults.data(forKey: Keys.pending),
      let saved = try? JSONDecoder().decode([Event].self, from: data)
    {
      pending = saved
    }
  }

  // MARK: Recording

  public static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

  /// Queues an event, merging it with a waiting one of the same key.
  public func record(_ event: Event) {
    guard !event.postId.isEmpty else { return }
    if event.type == .video, (event.watchMs ?? 0) <= 0 { return }
    startObservingBackground()
    if let index = pending.firstIndex(where: { $0.key == event.key }) {
      pending[index].merge(event)
    } else {
      pending.append(event)
      if pending.count > Self.maxPendingEvents {
        pending.removeFirst(pending.count - Self.maxPendingEvents)
      }
    }
    if event.type == .hide || event.type == .unhide
      || pending.count >= Self.flushAtEvents
      || Date().timeIntervalSince(lastAttempt) >= Self.flushInterval
    {
      flush(reason: event.type.rawValue)
    }
  }

  public func record(
    _ type: Kind,
    postID: String,
    session: String?,
    position: Int? = nil,
    visibleMs: Int64? = nil,
    surface: String? = nil,
    watchMs: Int64? = nil,
    audibleMs: Int64? = nil,
    durationMs: Int64? = nil,
    target: String? = nil
  ) {
    let now = Self.now
    record(
      Event(
        type: type,
        postId: postID,
        session: session,
        at: now - (visibleMs ?? watchMs ?? 0),
        lastAt: now,
        position: position,
        visibleMs: visibleMs,
        surface: surface,
        watchMs: watchMs,
        audibleMs: audibleMs,
        durationMs: (durationMs ?? 0) > 0 ? durationMs : nil,
        target: target
      )
    )
  }

  // MARK: Sending

  /// Saves what's waiting and sends it (after any backoff), one send at a time.
  public func flush(reason: String) {
    savePending()
    guard !isSending else { return }
    let nextRetry = defaults.double(forKey: Keys.nextRetry)
    guard Date().timeIntervalSince1970 >= nextRetry else { return }
    lastAttempt = Date()
    isSending = true
    Task { @MainActor in
      // At most the leftover in-flight batch, then one new one.
      for _ in 0..<2 {
        guard await sendNextBatch() else { break }
      }
      isSending = false
    }
  }

  /// Sends the in-flight batch, or makes one from what's waiting. True to go on to the next.
  private func sendNextBatch() async -> Bool {
    var batch: Batch
    if let data = defaults.data(forKey: Keys.inflight),
      let saved = try? JSONDecoder().decode(Batch.self, from: data)
    {
      batch = saved
    } else {
      guard !pending.isEmpty else { return false }
      batch = Batch(batchId: UUID().uuidString.lowercased(), events: pending, attempts: 0)
      pending = []
      savePending()
      saveInflight(batch)
    }

    if ExpressDebug.isDryRun {
      let kinds = Dictionary(grouping: batch.events, by: \.type.rawValue).mapValues(\.count)
      Logger.module.info(
        "Express dry run: skipped POST post/feed/events (\(batch.events.count, privacy: .public) events: \(kinds.description, privacy: .public))"
      )
      saveInflight(nil)
      return true
    }
    guard let token = ExpressAccount.shared.validAccessToken else {
      // Signed out: keep everything for later.
      return false
    }

    var body: [String: Any] = [
      "batchId": batch.batchId,
      "deviceId": ExpressAccount.deviceID,
      "sentAt": Self.now,
    ]
    if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
      body["appVersion"] = version
    }
    let encoded = (try? JSONEncoder().encode(batch.events)).flatMap {
      try? JSONSerialization.jsonObject(with: $0)
    }
    body["events"] = encoded ?? []

    let client = ExpressAPIClient.shared
    let request = client.makeRequest(
      path: "post/feed/events",
      method: "POST",
      authorization: .bearer(token),
      body: try? JSONSerialization.data(withJSONObject: body),
      timeout: 10
    )
    let status: Int
    do {
      let (_, response) = try await client.session.data(for: request)
      status = (response as? HTTPURLResponse)?.statusCode ?? 0
    } catch {
      status = 0
    }

    switch status {
    case 200..<300:
      saveInflight(nil)
      defaults.removeObject(forKey: Keys.failures)
      defaults.removeObject(forKey: Keys.nextRetry)
      return true
    case 400, 413:
      Logger.module.error("Express feed events dropped (HTTP \(status, privacy: .public))")
      saveInflight(nil)
      return true
    case 401, 403:
      // Keep the batch for the next session.
      return false
    default:
      batch.attempts += 1
      if batch.attempts >= Self.maxBatchAttempts {
        saveInflight(nil)
      } else {
        saveInflight(batch)
      }
      let failures = defaults.integer(forKey: Keys.failures) + 1
      defaults.set(failures, forKey: Keys.failures)
      let backoff = min(Self.maxBackoff, 5 * 60 * pow(2, Double(min(failures - 1, 6))))
      defaults.set(Date().timeIntervalSince1970 + backoff, forKey: Keys.nextRetry)
      return false
    }
  }

  private func savePending() {
    if pending.isEmpty {
      defaults.removeObject(forKey: Keys.pending)
    } else if let data = try? JSONEncoder().encode(pending) {
      defaults.set(data, forKey: Keys.pending)
    }
  }

  private func saveInflight(_ batch: Batch?) {
    if let batch, let data = try? JSONEncoder().encode(batch) {
      defaults.set(data, forKey: Keys.inflight)
    } else {
      defaults.removeObject(forKey: Keys.inflight)
    }
  }

  private func startObservingBackground() {
    guard !observesBackground else { return }
    observesBackground = true
    NotificationCenter.default.addObserver(
      forName: UIApplication.didEnterBackgroundNotification,
      object: nil,
      queue: .main
    ) { _ in
      MainActor.assumeIsolated { ExpressFeedEvents.shared.flush(reason: "background") }
    }
  }
}
