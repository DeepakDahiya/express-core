// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import Foundation

/// Turns what the feed shows into `ExpressFeedEvents` (Android's `PostListAdapter` rules):
/// - a session per showing of the new tab page;
/// - a card is on screen at 50% visible (of the card, or of the screen for tall cards); its time
///   counts only while the page is showing and nothing covers it, and nothing is reported until
///   a post has 1 s in the session, after which every stretch is;
/// - a feed video's playing stretches, ended by pausing, release or a sound change.
@MainActor
final class ExpressFeedTelemetry {
  private(set) var session = UUID().uuidString.lowercased()
  private(set) var isActive = false

  private var onScreen: [String: (since: Date, position: Int)] = [:]
  private var unreportedMs: [String: Int64] = [:]
  private var passedThreshold: Set<String> = []

  private struct Stretch {
    let postID: String
    let since: Date
    let audible: Bool
    let durationMs: Int64
  }

  private var stretch: Stretch?
  private let events = ExpressFeedEvents.shared

  /// Android's `Feed Session Ended` totals for this showing of the feed.
  private struct SessionTotals {
    var seen: Set<String> = []
    var deepest = -1
    var seenMs: Int64 = 0
    var opened = 0
    var hidden = 0
    var videoMs: Int64 = 0
  }
  private var totals = SessionTotals()
  private var newTakesShown: Set<String> = []

  /// Posts whose "N new takes" callout is showing, with the count (for `New Takes Shown`).
  var newTakesCounts: [String: Int] = [:]

  // MARK: Session and activity

  /// The new tab page came on screen: a new session.
  func pageAppeared() {
    closeAllImpressions()
    endSession()
    newTakesShown = []
    session = UUID().uuidString.lowercased()
    unreportedMs = [:]
    passedThreshold = []
  }

  /// Asked for what's on screen when the feed becomes active again (no scroll needed).
  var onBecameActive: (() -> Void)?

  /// Showing and uncovered (and the app in front).
  func setActive(_ active: Bool) {
    guard active != isActive else { return }
    isActive = active
    if active {
      onBecameActive?()
    } else {
      closeAllImpressions()
      videoStopped()
      events.flush(reason: "feed_left")
    }
  }

  /// `Feed Session Ended`, unless nothing was seen or opened.
  func endSession() {
    defer { totals = SessionTotals() }
    guard !totals.seen.isEmpty || totals.opened > 0 else { return }
    ExpressAnalytics.shared.capture(
      "Feed Session Ended",
      [
        "posts_seen": totals.seen.count, "deepest_position": totals.deepest, "seen_ms": totals.seenMs,
        "posts_opened": totals.opened, "posts_hidden": max(0, totals.hidden), "video_watch_ms": totals.videoMs,
      ]
    )
  }

  // MARK: Impressions

  /// Posts at least half on screen now, with their feed positions.
  func updateOnScreen(_ visible: [String: Int]) {
    guard isActive else { return }
    let now = Date()
    for (id, position) in visible where onScreen[id] == nil {
      onScreen[id] = (now, position)
      if let count = newTakesCounts[id], !newTakesShown.contains(id) {
        newTakesShown.insert(id)
        ExpressAnalytics.shared.capture("New Takes Shown", ["post_id": id, "count": count])
      }
    }
    for id in Array(onScreen.keys) where visible[id] == nil {
      close(id, at: now)
    }
  }

  private func closeAllImpressions() {
    let now = Date()
    for id in Array(onScreen.keys) { close(id, at: now) }
  }

  private func close(_ id: String, at now: Date) {
    guard let entry = onScreen.removeValue(forKey: id) else { return }
    let ms = Int64(now.timeIntervalSince(entry.since) * 1000)
    guard ms > 0 else { return }
    totals.seenMs += ms
    if passedThreshold.contains(id) || (unreportedMs[id] ?? 0) + ms >= 1000 {
      totals.seen.insert(id)
      totals.deepest = max(totals.deepest, entry.position)
    }
    if passedThreshold.contains(id) {
      events.record(.impression, postID: id, session: session, position: entry.position, visibleMs: ms)
      return
    }
    let total = (unreportedMs[id] ?? 0) + ms
    if total >= 1000 {
      passedThreshold.insert(id)
      unreportedMs[id] = nil
      events.record(.impression, postID: id, session: session, position: entry.position, visibleMs: total)
    } else {
      unreportedMs[id] = total
    }
  }

  // MARK: Video

  func videoStarted(postID: String, audible: Bool, durationMs: Int64) {
    if stretch?.postID == postID, stretch?.audible == audible { return }
    videoStopped()
    stretch = Stretch(postID: postID, since: Date(), audible: audible, durationMs: durationMs)
  }

  func videoStopped() {
    guard let stretch else { return }
    self.stretch = nil
    let watched = Int64(Date().timeIntervalSince(stretch.since) * 1000)
    guard watched > 0 else { return }
    totals.videoMs += watched
    events.record(
      .video,
      postID: stretch.postID,
      session: session,
      surface: "feed",
      watchMs: watched,
      audibleMs: stretch.audible ? watched : 0,
      durationMs: stretch.durationMs
    )
  }

  // MARK: Taps

  /// "comments", "video" or "article".
  func opened(_ postID: String, target: String, position: Int? = nil) {
    totals.opened += 1
    events.record(.open, postID: postID, session: session, target: target)
    ExpressAnalytics.shared.capture(
      "Post Clicked On",
      ["post_id": postID, "target": target, "position": position]
    )
  }

  func expanded(_ postID: String) {
    events.record(.expand, postID: postID, session: session)
  }

  func hid(_ postID: String, undo: Bool, position: Int? = nil) {
    totals.hidden += undo ? -1 : 1
    events.record(undo ? .unhide : .hide, postID: postID, session: session)
    ExpressAnalytics.shared.capture(undo ? "Post Unhidden" : "Post Hidden", ["post_id": postID, "position": position])
  }
}
