// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SwiftUI
import UIKit
import os.log

/// State for the floating comments pill: whether it shows, and the comment count for the
/// current page from `GET /v1/comment/first_load` (no auth), as in the Android app.
@MainActor
final class ExpressCommentsPillModel: ObservableObject {
  /// What the badge shows; on YouTube it counts up to `targetCount`.
  @Published private(set) var count = 0
  /// The real number, for VoiceOver while the badge counts up.
  @Published private(set) var targetCount = 0
  @Published private(set) var isVisible = false
  /// YouTube watch pages: up to two top comments that rise above the pill once (Android's
  /// `showYouTubeCommentsPreview`). A new batch replaces the old one.
  @Published private(set) var previews: ExpressPillPreviews?

  /// True while the YouTube background-play tip is up (`ExpressYouTubeCoordinator`): preview
  /// cards on screen go, and ones that load meanwhile are dropped, as Android drops them while
  /// its tutorial is active on the page (`mPipIntroActiveOnCurrentPage`).
  var holdsPreviews = false {
    didSet {
      guard holdsPreviews, previews != nil else { return }
      withAnimation(.easeOut(duration: 0.2)) { previews = nil }
    }
  }

  private var currentURL: URL?
  private var countTask: Task<Void, Never>?
  private var animationTask: Task<Void, Never>?
  private var previewTask: Task<Void, Never>?
  private var lastPreviewVideoID: String?

  /// Shows the pill for a web page and refreshes its count when the page changes. YouTube watch
  /// pages show YouTube's own comment count (Android's `YouTubeCommentsUtil`), counting up.
  func show(for url: URL) {
    guard url.scheme == "http" || url.scheme == "https" else {
      hide()
      return
    }
    isVisible = true
    guard url != currentURL else { return }
    currentURL = url
    setShown(0)
    loadPreviews(for: url)
    countTask?.cancel()
    countTask = Task { [weak self] in
      if let videoID = ExpressYouTubeStats.watchVideoID(in: url) {
        do {
          let count = try await ExpressYouTubeStats.commentCount(videoID: videoID)
          guard !Task.isCancelled, let self, self.currentURL == url else { return }
          self.countUp(to: count)
          return
        } catch {
          Logger.module.debug("YouTube comment count unavailable: \(error.localizedDescription, privacy: .public)")
        }
      }
      do {
        let count = try await ExpressAPIClient.shared.fetchCommentCount(for: url)
        guard !Task.isCancelled, let self, self.currentURL == url else { return }
        self.setShown(count)
      } catch {
        Logger.module.debug("Comment count unavailable: \(error.localizedDescription, privacy: .public)")
      }
    }
  }

  func hide() {
    isVisible = false
    previews = nil
  }

  /// Once per video, like Android (`mLastFetchedPreviewVideoId`).
  private func loadPreviews(for url: URL) {
    guard let videoID = ExpressYouTubeStats.watchVideoID(in: url) else {
      lastPreviewVideoID = nil
      previews = nil
      return
    }
    guard videoID != lastPreviewVideoID else { return }
    lastPreviewVideoID = videoID
    previewTask?.cancel()
    previewTask = Task { [weak self] in
      let comments = await ExpressYouTubeComments.preview(videoID: videoID)
      guard !Task.isCancelled, let self, self.lastPreviewVideoID == videoID, !comments.isEmpty,
        !self.holdsPreviews
      else { return }
      self.previews = ExpressPillPreviews(comments: Array(comments.prefix(2)))
    }
  }

  /// Updates the badge after the comments sheet loads or posts (keeps it in step with the sheet).
  /// YouTube's own count stays on watch pages.
  func setCount(_ count: Int) {
    if let currentURL, ExpressYouTubeStats.watchVideoID(in: currentURL) != nil { return }
    setShown(count)
  }

  private func setShown(_ value: Int) {
    animationTask?.cancel()
    targetCount = value
    count = value
  }

  /// 0 to the number over 7 s, slowing down at the end (`DecelerateInterpolator(1.5)`).
  private func countUp(to target: Int) {
    animationTask?.cancel()
    targetCount = target
    guard target > 0, !UIAccessibility.isReduceMotionEnabled else {
      count = target
      return
    }
    animationTask = Task { [weak self] in
      let duration: Double = 7
      let start = Date()
      while !Task.isCancelled {
        let t = min(1, Date().timeIntervalSince(start) / duration)
        let eased = 1 - pow(1 - t, 3)
        self?.count = Int((Double(target) * eased).rounded())
        if t >= 1 { break }
        try? await Task.sleep(nanoseconds: 50_000_000)
      }
    }
  }
}

/// Android's bottom-bar pill: Home, a divider, and the comments button with its badge.
struct ExpressCommentsPillView: View {
  @ObservedObject var model: ExpressCommentsPillModel
  var onHome: () -> Void
  var onComments: () -> Void

  private static let accent = Color(red: 0x57 / 255, green: 0x7D / 255, blue: 0xFF / 255)

  var body: some View {
    HStack(spacing: 0) {
      Button(action: onHome) {
        Image(systemName: "house.fill")
          .font(.system(size: 22, weight: .semibold))
          .foregroundColor(.white)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
      }
      .accessibilityLabel("Home")

      Rectangle()
        .fill(Color.white.opacity(0.2))
        .frame(width: 1, height: 32)
        .padding(.horizontal, 18)

      Button(action: onComments) {
        ZStack(alignment: .topTrailing) {
          RoundedRectangle(cornerRadius: 8)
            .fill(Color.white)
            .frame(width: 32, height: 32)
            .overlay(
              ExpressPillIcon(isWiggling: model.targetCount > 0, accent: Self.accent)
            )
          badge
            .offset(x: 8, y: -8)
        }
        .frame(width: 48, height: 48)
        .contentShape(Rectangle())
      }
      .accessibilityLabel(
        model.targetCount > 0 ? "\(model.targetCount) comments" : "Start the conversation"
      )
    }
    .padding(.horizontal, 18.5)
    .frame(height: 60)
    .background(RoundedRectangle(cornerRadius: 20).fill(Color(white: 0.4, opacity: 0.8)))
    .opacity(model.isVisible ? 1 : 0)
    .allowsHitTesting(model.isVisible)
    .animation(.easeInOut(duration: 0.2), value: model.isVisible)
  }

  @ViewBuilder private var badge: some View {
    if model.count > 0 {
      Text(ExpressNumberFormat.compact(model.count))
        .font(.system(size: 9, weight: .semibold))
        .foregroundColor(.white)
        .padding(.horizontal, 3)
        .frame(minWidth: 16, minHeight: 16)
        .background(Capsule().fill(Color(red: 1, green: 0x3B / 255, blue: 0x3B / 255)))
        .overlay(Capsule().stroke(Color(white: 0x85 / 255), lineWidth: 1))
    } else {
      Image(systemName: "plus")
        .font(.system(size: 9, weight: .bold))
        .foregroundColor(Self.accent)
        .frame(width: 16, height: 16)
        .background(Circle().fill(Color.white))
        .overlay(Circle().stroke(Color(white: 0x79 / 255), lineWidth: 1))
    }
  }
}

/// A batch of preview comments; a new id replays the animation.
struct ExpressPillPreviews: Identifiable, Equatable {
  let id = UUID()
  let comments: [ExpressComment]
}

/// The comments icon, with Android's `CommentsBarIconMotion` while there are comments: every
/// 2 s, an 860 ms wobble (tilt and scale), then rest. Still with Reduce Motion.
private struct ExpressPillIcon: View {
  let isWiggling: Bool
  let accent: Color
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private static let times: [Double] = [0, 0.12, 0.21, 0.31, 0.43]
  private static let degrees: [Double] = [0, 2.177, -1.089, 1.203, 0]
  private static let scales: [Double] = [1, 1.07, 0.985, 1.045, 1]

  var body: some View {
    if isWiggling && !reduceMotion {
      TimelineView(.animation) { context in
        let phase = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2) / 2
        icon
          .rotationEffect(.degrees(Self.value(Self.degrees, at: phase)))
          .scaleEffect(Self.value(Self.scales, at: phase))
      }
    } else {
      icon
    }
  }

  private var icon: some View {
    Image(systemName: "play.rectangle.on.rectangle.fill")
      .font(.system(size: 14, weight: .semibold))
      .foregroundColor(accent)
  }

  private static func value(_ values: [Double], at phase: Double) -> Double {
    guard phase < times.last! else { return values.last! }
    for i in 1..<times.count where phase <= times[i] {
      let t = (phase - times[i - 1]) / (times[i] - times[i - 1])
      return values[i - 1] + (values[i] - values[i - 1]) * t
    }
    return values.last!
  }
}

/// Android's YouTube preview cards: they rise from below one after another (400 ms apart, a
/// 500 ms overshoot), stay 3 s, then drop away bottom card first. Touches pass through.
struct ExpressPillPreviewCards: View {
  @ObservedObject var model: ExpressCommentsPillModel

  var body: some View {
    ZStack {
      if let previews = model.previews {
        ExpressPillPreviewStack(comments: previews.comments)
          .id(previews.id)
          .transition(.opacity)
      }
    }
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }
}

private struct ExpressPillPreviewStack: View {
  let comments: [ExpressComment]
  @State private var shown: Set<Int> = []
  @State private var finished = false
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    VStack(spacing: 8) {
      ForEach(Array(comments.enumerated()), id: \.offset) { index, comment in
        card(comment)
          .offset(y: shown.contains(index) ? 0 : UIScreen.main.bounds.height * 0.4)
          .opacity(shown.contains(index) ? 1 : 0)
      }
    }
    .padding(.horizontal, 12)
    .opacity(finished ? 0 : 1)
    .task { await run() }
  }

  private func card(_ comment: ExpressComment) -> some View {
    HStack(alignment: .top, spacing: 4) {
      ExpressAvatarView(url: comment.avatarURL, size: 24)
      VStack(alignment: .leading, spacing: 4) {
        Text(comment.authorName)
          .font(.system(size: 13, weight: .bold))
          .foregroundColor(.white)
          .lineLimit(1)
        Text(comment.content)
          .font(.system(size: 14))
          .foregroundColor(.white.opacity(0xF0 / 255.0))
          .lineLimit(2)
          .fixedSize(horizontal: false, vertical: true)
      }
      .padding(4)
      Spacer(minLength: 0)
    }
    .padding(10)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(
      RoundedRectangle(cornerRadius: 12)
        .fill(Color(UIColor(expressRGB: 0x39B1F6)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0x55 / 255.0), lineWidth: 1))
    )
  }

  private func run() async {
    let count = comments.count
    guard count > 0 else { return }
    let sleep: (Double) async -> Bool = { seconds in
      try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
      return !Task.isCancelled
    }
    for index in 0..<count {
      if index > 0 { guard await sleep(0.4) else { return } }
      withAnimation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.5, dampingFraction: 0.72)) {
        _ = shown.insert(index)
      }
    }
    guard await sleep(0.5 + 3) else { return }
    for index in stride(from: count - 1, through: 0, by: -1) {
      withAnimation(.easeIn(duration: 0.35)) { _ = shown.remove(index) }
      guard await sleep(0.4) else { return }
    }
    finished = true
  }
}
