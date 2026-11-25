// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import ExpressCore
import SwiftUI

// "Karma earned", ported from the Android app's `KarmaEarned` (design handoff
// design_handoff_karma_earned): every reaction the reader adds earns them +1 Karma, and once the
// emoji burst has peaked their own picture confirms it with one light sweep, a Karma-pink ring
// that breathes in and out, and a "+1" drifting off it ("+1 Karma" the first time, so they learn
// what it means).
//
// On a text page or the list, the picture is the comment bar's avatar and the label rises. A
// video page has no picture on screen, so a 30 pt copy slides out from behind the folded "Add
// your take" pill, shimmers and tucks back, the label drifting down. Times are ms from the tap.
// A new reaction restarts the timeline, so there is only ever one label. With Reduce Motion only
// the label fades in and out in place.

/// Starts "Karma earned" runs for one comments screen.
@MainActor
final class ExpressKarmaModel: ObservableObject {
  struct Run: Equatable {
    let id = UUID()
    let start = Date()
    /// The video page it plays on, or nil for the comment bar's avatar.
    let reelCommentID: String?
    let label: String
    let pictureURL: URL?
  }

  @Published private(set) var run: Run?

  func play(onReel reelCommentID: String?) {
    let userID = ExpressAccount.shared.claims?.userID
    run = Run(
      reelCommentID: reelCommentID,
      label: Self.label(for: userID),
      // The reader's own picture, as on the comment bar (Android's `ProfileAvatar.selfUrl`).
      pictureURL: ExpressProfileModel.shared.avatarURL ?? ExpressSelfAvatar.url(for: userID)
    )
  }

  /// Where the device remembers that `userID` has seen "+1 Karma"; the Profile syncs it with
  /// the server's `hasSeenKarmaLabel`.
  static func labelSeenKey(for userID: String) -> String {
    "ExpressKarmaSeenLabel.\(userID)"
  }

  /// "+1 Karma" on this account's first Karma, "+1" after that. Opening the Profile tells the
  /// server, as Android's `/user/me` sync does.
  private static func label(for userID: String?) -> String {
    guard let userID else { return "+1" }
    let key = labelSeenKey(for: userID)
    if UserDefaults.standard.bool(forKey: key) { return "+1" }
    UserDefaults.standard.set(true, forKey: key)
    return "+1 Karma"
  }
}

// MARK: - Timeline (ms)

private enum KarmaTime {
  static let shimmerStart: Double = 800
  static let shimmerLength: Double = 700
  static let labelIn: Double = 200
  static let labelScale: Double = 250
  static let labelDrift: Double = 1000
  /// From the shimmer's start: the label holds until 1500 ms, then fades by 1800 ms.
  static let labelHold: Double = 700
  static let labelOut: Double = 300
  static let pictureInStart: Double = 600
  static let pictureIn: Double = 250
  static let pictureOutStart: Double = 2100
  static let pictureOut: Double = 300
  static let textEnd: Double = 1800
  static let videoEnd: Double = 2400

  /// Where `t` is in the `length` after `start`, clamped to 0...1.
  static func ramp(_ t: Double, _ start: Double, _ length: Double) -> CGFloat {
    CGFloat(max(0, min(1, (t - start) / length)))
  }
}

private enum KarmaColors {
  static let ring = UIColor(expressLight: 0xFF5C7A, expressDark: 0xFF7A92)
  static let label = UIColor(expressLight: 0xD93A5A, expressDark: 0xFF7A92)
  static let glow = UIColor(expressLight: 0xFF5C7A, expressDark: 0xFF7A92)
  /// Reel chrome sits on the video, so it's the same in both themes.
  static let onVideo = Color(expressARGB: 0xFFFF_7A92)
  static let onVideoEdge = Color(expressARGB: 0xE6FF_FFFF)
  static let onVideoShadow = Color(expressARGB: 0x5900_0000)
  static let onVideoLabelShadow = Color(expressARGB: 0x8000_0000)
}

// MARK: - On the comment bar's avatar

/// Overlay this on the comment bar's avatar (`size` across).
struct ExpressKarmaAvatarEffect: View {
  @ObservedObject var karma: ExpressKarmaModel
  let size: CGFloat

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.colorScheme) private var colorScheme
  @State private var finished: UUID?

  var body: some View {
    if let run = karma.run, run.reelCommentID == nil, run.id != finished {
      TimelineView(.animation) { timeline in
        let t = timeline.date.timeIntervalSince(run.start) * 1000
        ZStack {
          if !reduceMotion {
            KarmaShimmer(t: t, size: size, ringColor: Color(KarmaColors.ring), glowColor: Color(KarmaColors.glow), glowOpacity: colorScheme == .dark ? 0.55 : 0.45)
          }
        }
        .frame(width: size, height: size)
        .overlay(alignment: .topLeading) {
          KarmaLabel(t: t, text: run.label, direction: -1, reduceMotion: reduceMotion, color: Color(KarmaColors.label), shadow: nil)
            .offset(x: 14, y: -10)
        }
      }
      .allowsHitTesting(false)
      .accessibilityHidden(true)
      .task(id: run.id) {
        try? await Task.sleep(nanoseconds: UInt64(KarmaTime.textEnd * 1_000_000))
        if !Task.isCancelled { finished = run.id }
      }
    }
  }
}

// MARK: - On a video page

/// The reader's picture sliding out from behind the "Add your take" pill. Place it as a 30 pt
/// box 29 pt from the page's top and 60 pt from its right edge, under the pill.
struct ExpressKarmaReelEffect: View {
  @ObservedObject var karma: ExpressKarmaModel
  let commentID: String

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var finished: UUID?

  private let size: CGFloat = 30

  var body: some View {
    ZStack {
      if let run = karma.run, run.reelCommentID == commentID, run.id != finished {
        TimelineView(.animation) { timeline in
          let t = timeline.date.timeIntervalSince(run.start) * 1000
          picture(run, t: t)
        }
        .task(id: run.id) {
          try? await Task.sleep(nanoseconds: UInt64(KarmaTime.videoEnd * 1_000_000))
          if !Task.isCancelled { finished = run.id }
        }
      }
    }
    .frame(width: size, height: size)
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }

  @ViewBuilder private func picture(_ run: ExpressKarmaModel.Run, t: Double) -> some View {
    let into = expressEaseOut(KarmaTime.ramp(t, KarmaTime.pictureInStart, KarmaTime.pictureIn))
    let out = expressEaseOut(KarmaTime.ramp(t, KarmaTime.pictureOutStart, KarmaTime.pictureOut))
    let show = min(into, 1 - out)
    if show > 0 {
      ZStack {
        Circle()
          .fill(KarmaColors.onVideoShadow)
          .blur(radius: 4)
          .offset(y: 2)
        ExpressAvatarView(url: run.pictureURL, size: size)
        Circle()
          .stroke(KarmaColors.onVideoEdge, lineWidth: 1.5)
          .frame(width: size + 1.5, height: size + 1.5)
        if !reduceMotion {
          KarmaShimmer(t: t, size: size, ringColor: KarmaColors.onVideo, glowColor: KarmaColors.onVideo, glowOpacity: 0.55)
        }
      }
      .frame(width: size, height: size)
      .overlay(alignment: .topLeading) {
        KarmaLabel(t: t, text: run.label, direction: 1, reduceMotion: reduceMotion, color: KarmaColors.onVideo, shadow: KarmaColors.onVideoLabelShadow)
          .offset(x: 7, y: 34)
      }
      .scaleEffect(reduceMotion ? 1 : 0.85 + 0.15 * show)
      .offset(x: reduceMotion ? 0 : (1 - show) * 24)
      .opacity(Double(show))
    }
  }
}

// MARK: - Pieces

/// 800–1500 ms: one sweep across the picture, and the ring breathing 0 → 0.6 → 0.
private struct KarmaShimmer: View {
  let t: Double
  let size: CGFloat
  let ringColor: Color
  let glowColor: Color
  let glowOpacity: Double

  var body: some View {
    let p = KarmaTime.ramp(t, KarmaTime.shimmerStart, KarmaTime.shimmerLength)
    if p > 0 && p < 1 {
      let ring = 0.6 * sin(.pi * Double(p))
      let ringDiameter = size + 2 * 3 + 1.5
      ZStack {
        Circle()
          .stroke(glowColor.opacity(glowOpacity * ring), lineWidth: 4)
          .frame(width: ringDiameter, height: ringDiameter)
          .blur(radius: 3)
        Circle()
          .stroke(ringColor.opacity(ring), lineWidth: 1.5)
          .frame(width: ringDiameter, height: ringDiameter)
        // A band of white at 60%, its gradient at 105°, crossing the picture once.
        LinearGradient(
          stops: [
            .init(color: .clear, location: 0),
            .init(color: .clear, location: 0.3),
            .init(color: .white.opacity(0.6), location: 0.5),
            .init(color: .clear, location: 0.7),
            .init(color: .clear, location: 1),
          ],
          startPoint: UnitPoint(x: 0.017, y: 0.371),
          endPoint: UnitPoint(x: 0.983, y: 0.629)
        )
        .frame(width: size, height: size)
        .offset(x: (-1 + 2 * expressEaseOut(p)) * size)
        .frame(width: size, height: size)
        .clipShape(Circle())
      }
      .frame(width: size, height: size)
    }
  }
}

/// The label from 800 ms: in over 200 ms and up from 0.8× over 250 ms, drifting 14 pt over
/// 1000 ms (`direction` -1 up, 1 down), out from 1500 to 1800 ms.
private struct KarmaLabel: View {
  let t: Double
  let text: String
  let direction: CGFloat
  let reduceMotion: Bool
  let color: Color
  let shadow: Color?

  var body: some View {
    let k = t - KarmaTime.shimmerStart
    let alpha =
      k <= 0
      ? 0
      : 0.9 * KarmaTime.ramp(k, 0, KarmaTime.labelIn)
        * (1 - KarmaTime.ramp(k, KarmaTime.labelHold, KarmaTime.labelOut))
    let drift = reduceMotion ? 0 : direction * 14 * expressEaseOut(KarmaTime.ramp(k, 0, KarmaTime.labelDrift))
    let scale = reduceMotion ? 1 : 0.8 + 0.2 * expressEaseOut(KarmaTime.ramp(k, 0, KarmaTime.labelScale))
    Text(text)
      .font(.system(size: 12, weight: .bold).monospacedDigit())
      .foregroundColor(color)
      .shadow(color: shadow ?? .clear, radius: 2, x: 0, y: 1)
      .lineLimit(1)
      .fixedSize()
      .frame(height: 14)
      .scaleEffect(scale)
      .offset(y: drift)
      .opacity(Double(alpha))
  }
}
